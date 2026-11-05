package org.ungoogled.patches.maps.ui.speed

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions

/** Adds a refresh button to the main map view for manually reloading live Blitzer.de speed cameras. */
internal val speedCameraRefreshButtonPatch = bytecodePatch(
    name = "Speed camera refresh button",
    description = "Adds a floating action button on the main map view to manually refresh live Blitzer.de speed cameras in the current area. Shows a loading animation while fetching.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch, speedCamerasPatch)

    execute {
        markPatched("speedCameraRefreshButtonPatched")

        // Find any method that calls getMapAsync - this is the Google Maps API to get the map
        // The map fragment's onViewCreated or onMapReady will call this
        // Use anonymous object to avoid "cannot be local" error
        Fingerprint(
            filters = listOf(
                methodCall(name = "getMapAsync")
            )
        ).let { fp ->
            // Add at the end of the method that calls getMapAsync
            val endOfMethod = fp.method.implementation!!.instructions.lastIndex
            fp.method.addInstructions(endOfMethod, """
                // Get the map fragment's view (first parameter is the fragment/view)
                invoke-static { p0 }, $SHAPES->addSpeedCameraRefreshButton(Landroid/view/View;)V
            """)
        }
    }
}

