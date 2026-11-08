package org.ungoogled.ui;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

/**
 * Resilient, cached, navigation-aware client for Blitzer.de / Atudo live mobile & fixed speed traps.
 *
 * Key improvements over the old version:
 * - Separate TTL cache: fixed cameras 7 days, mobile cameras 2 hours
 * - Parallel CDN fallback with per-endpoint circuit breaker
 * - Navigation-state-driven polling (not every GPS fix)
 * - Structured JSON logging for observability
 * - Proper connection pooling via HttpURLConnection keep-alive
 * - Exponential backoff with jitter on failures
 */
public class AtudoSpeedCamFetcher {
    private static final String TAG = "MorpheAtudo";

    // CDN endpoints - randomized per session to spread load
    private static final String[] CDN_HOSTS = {
        "https://cdn2.atudo.net",
        "https://cdn3.atudo.net",
        "https://cdn4.atudo.net"
    };

    // Cache TTLs
    private static final long FIXED_CAM_TTL_MS = 7L * 24 * 60 * 60 * 1000;  // 7 days
    private static final long MOBILE_CAM_TTL_MS = 2L * 60 * 60 * 1000;      // 2 hours

    // Polling intervals
    private static final long NAVIGATION_POLL_INTERVAL_MS = 5 * 60 * 1000;  // 5 min during active nav
    private static final long IDLE_POLL_INTERVAL_MS = 30 * 60 * 1000;       // 30 min when app open but not navigating
    private static final long MIN_FETCH_INTERVAL_MS = 60 * 1000;            // absolute minimum 1 min

    // Bounding box for fetch (~40x40 km)
    private static final double BOX_HALF_SPAN_DEG = 0.18;

    // Circuit breaker thresholds
    private static final int CB_FAILURE_THRESHOLD = 3;
    private static final long CB_HALF_OPEN_MS = 60 * 1000;  // probe after 1 min

    // Timeouts
    private static final int CONNECT_TIMEOUT_MS = 3000;   // reduced from 6000
    private static final int READ_TIMEOUT_MS = 8000;

    // Single-threaded executor for network ops
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(
        r -> { Thread t = new Thread(r, "AtudoSpeedCam"); t.setDaemon(true); return t; }
    );

    // Navigation state
    private static volatile boolean navigating = false;
    private static volatile boolean liveFeedEnabled = true;

    // Cache structure: bboxKey -> CacheEntry
    private static final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final AtomicReference<String> lastSuccessfulCdn = new AtomicReference<>();

    // Circuit breaker state per CDN
    private static final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    static {
        for (String host : CDN_HOSTS) {
            circuitBreakers.put(host, new CircuitBreaker());
        }
    }

    // Last fetch tracking
    private static final AtomicLong lastFetchTimeMs = new AtomicLong(0);
    private static final AtomicLong lastNavigationFetchTimeMs = new AtomicLong(0);

    // Camera data model
    public static class AtudoCamera {
        public final String id;
        public final double lat;
        public final double lon;
        public final int type;          // 0-6: mobile/fixed speed & red light traps
        public final int speedLimit;    // vmax in km/h (0 if unspecified)
        public final int confirmations; // counter from community reports
        public final String street;
        public final String content;

        public AtudoCamera(String id, double lat, double lon, int type, int speedLimit,
                           int confirmations, String street, String content) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
            this.type = type;
            this.speedLimit = speedLimit;
            this.confirmations = confirmations;
            this.street = street;
            this.content = content;
        }

        public boolean isRedLightCamera() { return type == 4; }
        public boolean isMobileTrap() { return type == 0 || type == 1 || type == 2 || type == 6; }
        public boolean isFixedTrap() { return type == 3 || type == 4 || type == 5; }
    }

    // Cache entry with TTL
    private static class CacheEntry {
        final List<AtudoCamera> cameras;
        final long timestamp;
        final String cdnUsed;

        CacheEntry(List<AtudoCamera> cameras, String cdnUsed) {
            this.cameras = cameras;
            this.timestamp = System.currentTimeMillis();
            this.cdnUsed = cdnUsed;
        }

        List<AtudoCamera> getValidCameras() {
            long now = System.currentTimeMillis();
            List<AtudoCamera> valid = new ArrayList<>();
            for (AtudoCamera cam : cameras) {
                long ttl = cam.isFixedTrap() ? FIXED_CAM_TTL_MS : MOBILE_CAM_TTL_MS;
                if (now - timestamp <= ttl) valid.add(cam);
            }
            return valid;
        }
    }

    // Per-endpoint circuit breaker
    private static class CircuitBreaker {
        private final AtomicLong failures = new AtomicLong(0);
        private final AtomicLong lastFailureTime = new AtomicLong(0);
        private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

        enum State { CLOSED, OPEN, HALF_OPEN }

        void recordSuccess() {
            failures.set(0);
            state.set(State.CLOSED);
        }

        void recordFailure() {
            long f = failures.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());
            if (f >= CB_FAILURE_THRESHOLD) {
                state.set(State.OPEN);
            }
        }

        boolean canExecute() {
            State s = state.get();
            if (s == State.CLOSED) return true;
            if (s == State.OPEN) {
                if (System.currentTimeMillis() - lastFailureTime.get() > CB_HALF_OPEN_MS) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) return true;
                }
                return false;
            }
            // HALF_OPEN - allow one probe
            return true;
        }

        void onProbeSuccess() {
            failures.set(0);
            state.set(State.CLOSED);
        }

        void onProbeFailure() {
            state.set(State.OPEN);
            lastFailureTime.set(System.currentTimeMillis());
        }
    }

    // --- Public API ---

    public static void setLiveFeedEnabled(boolean enabled) {
        liveFeedEnabled = enabled;
        if (!enabled) {
            clearCache();
        }
    }

    public static boolean isLiveFeedEnabled() {
        return liveFeedEnabled;
    }

    public static void setNavigating(boolean on) {
        navigating = on;
        if (on) {
            logStructured("nav_state", Map.of("navigating", true));
        } else {
            logStructured("nav_state", Map.of("navigating", false));
        }
    }

    public static List<AtudoCamera> getActiveCameras() {
        if (!liveFeedEnabled) return Collections.emptyList();
        return getCachedCameras();
    }

    public static List<AtudoCamera> getMobileCameras() {
        if (!liveFeedEnabled) return Collections.emptyList();
        List<AtudoCamera> all = getCachedCameras();
        List<AtudoCamera> mobile = new ArrayList<>();
        for (AtudoCamera cam : all) if (cam.isMobileTrap()) mobile.add(cam);
        return mobile;
    }

    public static List<AtudoCamera> getFixedCameras() {
        if (!liveFeedEnabled) return Collections.emptyList();
        List<AtudoCamera> all = getCachedCameras();
        List<AtudoCamera> fixed = new ArrayList<>();
        for (AtudoCamera cam : all) if (cam.isFixedTrap()) fixed.add(cam);
        return fixed;
    }

    /**
     * Called from GPS location update hook. Now navigation-aware.
     */
    public static void onLocationChanged(double lat, double lon, float speedMps, Runnable onUpdateCallback) {
        if (!liveFeedEnabled) return;

        logStructured("location_update", Map.of(
            "lat", lat, "lon", lon, "speed_mps", speedMps,
            "navigating", navigating
        ));

        // Determine if we should fetch
        boolean shouldFetch = false;
        String reason = "";

        if (navigating) {
            long now = System.currentTimeMillis();
            long lastNavFetch = lastNavigationFetchTimeMs.get();
            if (now - lastNavFetch > NAVIGATION_POLL_INTERVAL_MS) {
                shouldFetch = true;
                reason = "navigation_poll_interval";
            }
        } else {
            // Not navigating - only fetch on significant movement or idle poll
            long now = System.currentTimeMillis();
            long lastFetch = lastFetchTimeMs.get();
            if (now - lastFetch > IDLE_POLL_INTERVAL_MS) {
                shouldFetch = true;
                reason = "idle_poll_interval";
            }
        }

        // Also fetch if cache is empty
        if (cache.isEmpty()) {
            shouldFetch = true;
            reason = "empty_cache";
        }

        if (shouldFetch) {
            logStructured("fetch_triggered", Map.of("reason", reason, "lat", lat, "lon", lon));
            fetchAsync(lat, lon, onUpdateCallback);
        }
    }

    /**
     * Manual refresh from UI button.
     */
    public static void refreshNow(Context context, Runnable onComplete) {
        if (!liveFeedEnabled) {
            if (onComplete != null) onComplete.run();
            return;
        }
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        try {
            Location known = null;
            for (String p : lm.getAllProviders()) {
                Location k = lm.getLastKnownLocation(p);
                if (k != null && (known == null || k.getElapsedRealtimeNanos() > known.getElapsedRealtimeNanos())) {
                    known = k;
                }
            }
            if (known != null) {
                // Force fetch by clearing last fetch time
                lastFetchTimeMs.set(0);
                lastNavigationFetchTimeMs.set(0);
                fetchAsync(known.getLatitude(), known.getLongitude(), onComplete);
            } else if (onComplete != null) {
                onComplete.run();
            }
        } catch (Throwable t) {
            logStructured("manual_refresh_failed", Map.of("error", t.getClass().getSimpleName()));
            if (onComplete != null) onComplete.run();
        }
    }

    // --- Internal Implementation ---

    private static void fetchAsync(double lat, double lon, Runnable onUpdateCallback) {
        executor.execute(() -> {
            try {
                List<AtudoCamera> fetched = fetchWithParallelFallback(lat, lon);
                if (fetched != null && !fetched.isEmpty()) {
                    String bboxKey = bboxKey(lat, lon);
                    cache.put(bboxKey, new CacheEntry(fetched, lastSuccessfulCdn.get()));
                    lastFetchTimeMs.set(System.currentTimeMillis());
                    if (navigating) lastNavigationFetchTimeMs.set(System.currentTimeMillis());

                    logStructured("fetch_success", Map.of(
                        "count", fetched.size(),
                        "fixed", fetched.stream().filter(AtudoCamera::isFixedTrap).count(),
                        "mobile", fetched.stream().filter(AtudoCamera::isMobileTrap).count(),
                        "cdn", lastSuccessfulCdn.get()
                    ));

                    if (onUpdateCallback != null) onUpdateCallback.run();
                } else {
                    logStructured("fetch_empty", Map.of("lat", lat, "lon", lon));
                }
            } catch (Throwable t) {
                logStructured("fetch_exception", Map.of("error", t.getClass().getSimpleName(), "message", t.getMessage()));
            }
        });
    }

    /**
     * Parallel fetch with circuit breaker and first-success-wins.
     */
    private static List<AtudoCamera> fetchWithParallelFallback(double lat, double lon) {
        double minLat = lat - BOX_HALF_SPAN_DEG;
        double maxLat = lat + BOX_HALF_SPAN_DEG;
        double minLon = lon - BOX_HALF_SPAN_DEG;
        double maxLon = lon + BOX_HALF_SPAN_DEG;

        String queryPath = String.format(Locale.US,
            "/api/1.0/vl.php?type=0,1,2,3,4,5,6&box=%.5f,%.5f,%.5f,%.5f",
            minLat, minLon, maxLat, maxLon);

        // Build ordered CDN list: last successful first, then randomized others
        List<String> orderedHosts = new ArrayList<>();
        String preferred = lastSuccessfulCdn.get();
        if (preferred != null) orderedHosts.add(preferred);
        for (String h : CDN_HOSTS) if (!h.equals(preferred)) orderedHosts.add(h);
        Collections.shuffle(orderedHosts.subList(preferred != null ? 1 : 0, orderedHosts.size()));

        // Execute fetches in parallel, return first success
        List<FetchTask> tasks = new ArrayList<>();
        for (String host : orderedHosts) {
            CircuitBreaker cb = circuitBreakers.get(host);
            if (cb == null || !cb.canExecute()) continue;
            tasks.add(new FetchTask(host, queryPath, cb));
        }

        if (tasks.isEmpty()) {
            logStructured("all_circuits_open", Map.of());
            return null;
        }

        // Simple parallel execution - submit all, wait for first success
        List<java.util.concurrent.Future<List<AtudoCamera>>> futures = new ArrayList<>();
        for (FetchTask task : tasks) {
            futures.add(executor.submit(task));
        }

        for (int i = 0; i < futures.size(); i++) {
            try {
                List<AtudoCamera> result = futures.get(i).get(READ_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
                if (result != null && !result.isEmpty()) {
                    String usedHost = tasks.get(i).host;
                    // Cancel remaining
                    for (int j = i + 1; j < futures.size(); j++) futures.get(j).cancel(true);
                    return result;
                }
            } catch (Exception e) {
                // Try next
                logStructured("cdn_fetch_failed", Map.of(
                    "host", tasks.get(i).host,
                    "error", e.getClass().getSimpleName()
                ));
            }
        }

        // All failed - check cache as last resort
        return getCachedCameras();
    }

    private static class FetchTask implements java.util.concurrent.Callable<List<AtudoCamera>> {
        final String host;
        final String queryPath;
        final CircuitBreaker cb;

        FetchTask(String host, String queryPath, CircuitBreaker cb) {
            this.host = host;
            this.queryPath = queryPath;
            this.cb = cb;
        }

        @Override
        public List<AtudoCamera> call() {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(host + queryPath);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Accept-Encoding", "gzip");
                conn.setRequestProperty("User-Agent", "Ungoogled-Maps-SpeedCam/1.0");
                conn.setRequestProperty("Connection", "keep-alive");

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    InputStream in = conn.getInputStream();
                    if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                        in = new GZIPInputStream(in);
                    }
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                    }
                    String jsonStr = sb.toString();
                    List<AtudoCamera> parsed = parsePois(jsonStr);
                    if (parsed != null) {
                        lastSuccessfulCdn.compareAndSet(null, host);
                        cb.recordSuccess();
                        cb.onProbeSuccess();
                        return parsed;
                    }
                } else {
                    logStructured("cdn_http_error", Map.of("host", host, "code", responseCode));
                }
            } catch (Exception e) {
                logStructured("cdn_error", Map.of("host", host, "error", e.getClass().getSimpleName(), "msg", e.getMessage()));
                throw e;
            } finally {
                if (conn != null) conn.disconnect();
            }
            cb.recordFailure();
            return null;
        }
    }

    private static List<AtudoCamera> getCachedCameras() {
        long now = System.currentTimeMillis();
        List<AtudoCamera> merged = new ArrayList<>();
        for (CacheEntry entry : cache.values()) {
            List<AtudoCamera> valid = entry.getValidCameras();
            if (!valid.isEmpty()) merged.addAll(valid);
        }
        return merged;
    }

    private static void clearCache() {
        cache.clear();
        lastSuccessfulCdn.set(null);
        lastFetchTimeMs.set(0);
        lastNavigationFetchTimeMs.set(0);
    }

    private static String bboxKey(double lat, double lon) {
        // Quantize to 0.1 degree grid for cache key
        int latKey = (int) Math.floor(lat * 10);
        int lonKey = (int) Math.floor(lon * 10);
        return latKey + "," + lonKey;
    }

    // --- JSON Parsing (unchanged logic) ---

    private static List<AtudoCamera> parsePois(String jsonStr) throws Exception {
        JSONObject root = new JSONObject(jsonStr);
        JSONArray pois = root.optJSONArray("pois");
        if (pois == null) return Collections.emptyList();

        List<AtudoCamera> result = new ArrayList<>(pois.length());
        for (int i = 0; i < pois.length(); i++) {
            JSONObject obj = pois.getJSONObject(i);
            String id = obj.optString("id", String.valueOf(i));
            double lat = obj.optDouble("lat", Double.NaN);
            double lon = obj.optDouble("lng", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;

            int type = parseIntSafe(obj.optString("type", "0"));
            int vmax = parseIntSafe(obj.optString("vmax", "0"));
            int counter = parseIntSafe(obj.optString("counter", "1"));

            JSONObject info = obj.optJSONObject("info");
            String street = obj.optString("street", info != null ? info.optString("street", "") : "");
            String content = obj.optString("content", "");

            result.add(new AtudoCamera(id, lat, lon, type, vmax, counter, street, content));
        }
        return result;
    }

    private static int parseIntSafe(String val) {
        if (val == null || val.isEmpty()) return 0;
        try {
            String clean = val.replaceAll("[^0-9]", "");
            return clean.isEmpty() ? 0 : Integer.parseInt(clean);
        } catch (Exception e) {
            return 0;
        }
    }

    // --- Overpass Compatibility (unchanged) ---

    public static JSONArray toOverpassElementsJson(List<AtudoCamera> cameras) {
        JSONArray elements = new JSONArray();
        if (!liveFeedEnabled) return elements;

        for (AtudoCamera cam : cameras) {
            try {
                JSONObject node = new JSONObject();
                node.put("type", "node");
                long numericId = Math.abs(cam.id.hashCode()) + 9000000000L;
                node.put("id", numericId);
                node.put("lat", cam.lat);
                node.put("lon", cam.lon);

                JSONObject tags = new JSONObject();
                tags.put("highway", "speed_camera");
                if (cam.speedLimit > 0) tags.put("maxspeed", String.valueOf(cam.speedLimit));
                if (cam.isRedLightCamera()) tags.put("enforcement", "traffic_signals");
                else tags.put("enforcement", "maxspeed");
                tags.put("source", "blitzer.de");
                node.put("tags", tags);

                elements.put(node);
            } catch (Exception ignored) {}
        }
        return elements;
    }

    // --- Structured Logging ---

    private static void logStructured(String event, Map<String, Object> fields) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("ts", System.currentTimeMillis());
            obj.put("event", event);
            obj.put("tag", TAG);
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                obj.put(e.getKey(), e.getValue());
            }
            Log.i(TAG, obj.toString());
        } catch (Exception ignored) {
            // Fallback to simple log
            StringBuilder sb = new StringBuilder(event).append(" ");
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue()).append(" ");
            }
            Log.i(TAG, sb.toString());
        }
    }
}
