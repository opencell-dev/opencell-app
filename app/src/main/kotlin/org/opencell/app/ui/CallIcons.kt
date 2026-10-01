package org.opencell.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few Material icons the keypad and Recents need that aren't in
 * material-icons-core (the extended set is several MB in an unminified APK).
 * Path data from Material Icons (Apache-2.0), 24 x 24.
 */
object CallIcons {
    val Outgoing = icon("CallMade", "M9,5v2h6.59L4,18.59 5.41,20 17,8.41V15h2V5z")
    val Incoming = icon("CallReceived", "M20,5.41L18.59,4 7,15.59V9H5v10h10v-2H8.41z")
    val Missed = icon("CallMissed", "M19.59,7L12,14.59 6.41,9H11V7H3v8h2v-4.59l7,7 9,-9z")
    val Rejected = icon(
        "CallEnd",
        "M12,9c-1.6,0 -3.15,0.25 -4.6,0.72v3.1c0,0.39 -0.23,0.74 -0.56,0.9 -0.98,0.49 -1.87,1.12 -2.66,1.85 " +
            "-0.18,0.18 -0.43,0.28 -0.7,0.28 -0.28,0 -0.53,-0.11 -0.71,-0.29L0.29,13.08c-0.18,-0.17 -0.29,-0.42 " +
            "-0.29,-0.7 0,-0.28 0.11,-0.53 0.29,-0.71C3.34,8.78 7.46,7 12,7s8.66,1.78 11.71,4.67c0.18,0.18 0.29,0.43 " +
            "0.29,0.71 0,0.28 -0.11,0.53 -0.29,0.71l-2.48,2.48c-0.18,0.18 -0.43,0.29 -0.71,0.29 -0.27,0 -0.52,-0.11 " +
            "-0.7,-0.28 -0.79,-0.74 -1.69,-1.36 -2.67,-1.85 -0.33,-0.16 -0.56,-0.5 -0.56,-0.9v-3.1C15.15,9.25 13.6,9 12,9z",
    )
    val Backspace = icon(
        "Backspace",
        "M22,3H7c-0.69,0 -1.23,0.35 -1.59,0.88L0,12l5.41,8.11c0.36,0.53 0.9,0.89 1.59,0.89h15c1.1,0 2,-0.9 2,-2V5" +
            "c0,-1.1 -0.9,-2 -2,-2zM19,15.59L17.59,17 14,13.41 10.41,17 9,15.59 12.59,12 9,8.41 10.41,7 14,10.59 " +
            "17.59,7 19,8.41 15.41,12 19,15.59z",
        autoMirror = true,
    )

    private fun icon(name: String, path: String, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
