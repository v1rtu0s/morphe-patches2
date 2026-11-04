package org.ungoogled.ui;

import android.location.Location;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * Low-power, low-data client for Blitzer.de / Atudo live mobile & fixed speed traps.
 * Piggybacks on Google Maps' existing location ticks and merges directly into the
 * Morphe Overpass/Waze camera pipeline.
 */
public class AtudoSpeedCamFetcher {
    private static final String TAG = "MorpheAtudo";

    private static final String[] CDN_HOSTS = {
        "https://cdn2.atudo.net",
        "https://cdn3.atudo.net",
        "https://cdn4.atudo.net"
    };

    // Hysteresis parameters to eliminate idle battery & data drain
    private static final long MIN_FETCH_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes
    private static final float MIN_MOVEMENT_METERS = 600.0f;          // 600 meters
    private static final double BOX_HALF_SPAN_DEG = 0.18;             // ~20 km radius (~40x40 km box)
    private static final double EDGE_BUFFER_DEG = 0.03;               // ~3.3 km edge trigger

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static long lastFetchTimeMs = 0;
    private static double lastFetchLat = Double.NaN;
    private static double lastFetchLon = Double.NaN;
    private static double cachedMinLat, cachedMinLon, cachedMaxLat, cachedMaxLon;
    private static volatile boolean isFetching = false;

    public static class AtudoCamera {
        public final String id;
        public final double lat;
        public final double lon;
        public final int type;          // 0-6: mobile/fixed speed & red light traps, 101+: hazards
        public final int speedLimit;    // vmax in km/h (0 if unspecified)
        public final int confirmations; // counter from community reports
        public final String street;
        public final String content;

        public AtudoCamera(String id, double lat, double lon, int type, int speedLimit, int confirmations, String street, String content) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
            this.type = type;
            this.speedLimit = speedLimit;
            this.confirmations = confirmations;
            this.street = street;
            this.content = content;
        }

        public boolean isRedLightCamera() {
            return type == 4;
        }

        public boolean isMobileTrap() {
            return type == 0 || type == 1 || type == 2 || type == 6;
        }
    }

    private static volatile List<AtudoCamera> activeCameras = Collections.emptyList();

    public static List<AtudoCamera> getActiveCameras() {
        return activeCameras;
    }

    /**
     * Triggered from the existing GPS Location update hook in the Maps extension.
     */
    public static void onLocationChanged(double lat, double lon, float speedMetersPerSec, Runnable onUpdateCallback) {
        if (isFetching) return;

        long now = System.currentTimeMillis();

        // 1. Do not poll if stationary (under ~7 km/h), unless we have never fetched before
        if (!Double.isNaN(lastFetchLat) && speedMetersPerSec < 2.0f) {
            return;
        }

        // 2. Check if user is approaching the edge of the cached bounding box
        boolean nearBoxEdge = Double.isNaN(lastFetchLat)
                || lat < (cachedMinLat + EDGE_BUFFER_DEG)
                || lat > (cachedMaxLat - EDGE_BUFFER_DEG)
                || lon < (cachedMinLon + EDGE_BUFFER_DEG)
                || lon > (cachedMaxLon - EDGE_BUFFER_DEG);

        // 3. Time + Distance Hysteresis Gate
        if (!nearBoxEdge) {
            if ((now - lastFetchTimeMs) < MIN_FETCH_INTERVAL_MS) {
                return;
            }
            float[] dist = new float[1];
            Location.distanceBetween(lastFetchLat, lastFetchLon, lat, lon, dist);
            if (dist[0] < MIN_MOVEMENT_METERS) {
                return;
            }
        }

        isFetching = true;
        executor.execute(() -> {
            try {
                double minLat = lat - BOX_HALF_SPAN_DEG;
                double maxLat = lat + BOX_HALF_SPAN_DEG;
                double minLon = lon - BOX_HALF_SPAN_DEG;
                double maxLon = lon + BOX_HALF_SPAN_DEG;

                List<AtudoCamera> fetched = fetchFromCdn(minLat, minLon, maxLat, maxLon);
                if (fetched != null) {
                    activeCameras = fetched;
                    lastFetchTimeMs = System.currentTimeMillis();
                    lastFetchLat = lat;
                    lastFetchLon = lon;
                    cachedMinLat = minLat;
                    cachedMaxLat = maxLat;
                    cachedMinLon = minLon;
                    cachedMaxLon = maxLon;

                    Log.i(TAG, "Fetched " + fetched.size() + " live Blitzer.de POIs");
                    if (onUpdateCallback != null) {
                        onUpdateCallback.run();
                    }
                }
            } finally {
                isFetching = false;
            }
        });
    }

    /**
     * Synchronous fetch for direct injection inside the Overpass download thread.
     */
    public static List<AtudoCamera> fetchBoundingBoxSync(double minLat, double minLon, double maxLat, double maxLon) {
        List<AtudoCamera> fetched = fetchFromCdn(minLat, minLon, maxLat, maxLon);
        if (fetched != null) {
            activeCameras = fetched;
            return fetched;
        }
        return Collections.emptyList();
    }

    /**
     * Converts the Atudo JSON response into an Overpass-compatible JSON elements array string,
     * allowing 1-line drop-in compatibility with bearinmindcat's existing Overpass OSM parser!
     */
    public static JSONArray toOverpassElementsJson(List<AtudoCamera> cameras) {
        JSONArray elements = new JSONArray();
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
                if (cam.speedLimit > 0) {
                    tags.put("maxspeed", String.valueOf(cam.speedLimit));
                }
                if (cam.isRedLightCamera()) {
                    tags.put("enforcement", "traffic_signals");
                } else {
                    tags.put("enforcement", "maxspeed");
                }
                tags.put("source", "blitzer.de");
                node.put("tags", tags);

                elements.put(node);
            } catch (Exception ignored) {}
        }
        return elements;
    }

    private static List<AtudoCamera> fetchFromCdn(double minLat, double minLon, double maxLat, double maxLon) {
        String queryPath = String.format(Locale.US,
                "/api/1.0/vl.php?type=0,1,2,3,4,5,6&box=%.5f,%.5f,%.5f,%.5f",
                minLat, minLon, maxLat, maxLon);

        for (String host : CDN_HOSTS) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(host + queryPath);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("Accept-Encoding", "gzip");
                conn.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14)");

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    InputStream in = conn.getInputStream();
                    if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                        in = new GZIPInputStream(in);
                    }

                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                    }
                    return parsePois(sb.toString());
                }
            } catch (Exception e) {
                Log.w(TAG, "Atudo fetch failed on " + host + ": " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

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
}
