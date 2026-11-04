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

        // Find the main map fragment's onViewCreated by its unique call to getMapAsync
        // The map fragment is the only fragment that calls getMapAsync in onViewCreated
        object MapFragmentOnViewCreatedFingerprint : Fingerprint(
            returnType = "V",
            parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
            filters = listOf(
                string("onViewCreated"),
                methodCall(name = "getMapAsync")
            )
        )

        MapFragmentOnViewCreatedFingerprint.let { fp ->
            val endOfMethod = fp.method.implementation!!.instructions.lastIndex
            fp.method.addInstructions(endOfMethod, """
                // Get the map fragment's view
                invoke-static { p0 }, $SHAPES->addSpeedCameraRefreshButton(Landroid/view/View;)V
            """)
        }
    }
}

