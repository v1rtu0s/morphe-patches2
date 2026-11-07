package org.ungoogled.patches.maps.ui.speed

import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.maps.ui.speed.SpeedCamerasPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/** Adds a refresh button to the main map view for manually reloading live Blitzer.de speed cameras. */
internal val speedCameraRefreshButtonPatch = bytecodePatch(
    name = "Speed camera refresh button",
    description = "Adds a floating action button on the main map view to manually refresh live Blitzer.de speed cameras in the current area. Shows a loading animation while fetching.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch, SpeedCamerasPatch)

    execute {
        markPatched("speedCameraRefreshButtonPatched")
    }
}
