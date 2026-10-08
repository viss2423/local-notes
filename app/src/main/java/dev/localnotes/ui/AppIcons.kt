package dev.localnotes.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A small hand-drawn icon set: thin round-capped lines for navigation, solid shapes for transport. */
object AppIcons {
    private fun line(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(addPathNodes(it), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()
    private fun solid(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
    }.build()

    val Mic = line("mic", "M12,3.2a3,3 0,0 1 3,3v5a3,3 0,0 1 -6,0v-5a3,3 0,0 1 3,-3z", "M6.2,11a5.8,5.8 0,0 0 11.6,0", "M12,16.8v4")
    val Play = solid("play", "M8,5.2L19,12L8,18.8z")
    val Pause = solid("pause", "M7,5h3.4v14H7z", "M13.6,5H17v14h-3.6z")
    val Stop = solid("stop", "M6.5,6.5h11v11h-11z")
    val Tune = line("tune", "M4,8h9", "M19,8h1", "M4,16h1", "M11,16h9", "M16,5.8a2.2,2.2 0,1 1 0,4.4a2.2,2.2 0,1 1 0,-4.4z", "M8,13.8a2.2,2.2 0,1 1 0,4.4a2.2,2.2 0,1 1 0,-4.4z")
    val Download = line("download", "M12,4v11", "M7.5,10.8L12,15.2l4.5,-4.4", "M5,19.5h14")
    val Back = line("back", "M15,5L8,12l7,7")
    val Copy = line("copy", "M9,9h10.5v11H9z", "M5,15V4h10.5")
    val Share = line("share", "M12,15.5V4", "M7.8,8.2L12,4l4.2,4.2", "M5,12.5v7h14v-7")
    val Edit = line("edit", "M5,19.2l0.9,-4L16.4,4.7l3,3L8.9,18.3z", "M14.2,6.9l3,3")
    val Check = line("check", "M5,12.6L9.8,17.4L19,7.2")
    val Search = line("search", "M10.5,4.2a6.3,6.3 0,1 1 0,12.6a6.3,6.3 0,1 1 0,-12.6z", "M15.3,15.3L20,20")
    val Close = line("close", "M6,6L18,18", "M18,6L6,18")
    val Down = line("down", "M6.2,9.2L12,15l5.8,-5.8")
    val Flag = line("flag", "M6,20.5V3.8", "M6,4.8h11.2l-2.6,3.6l2.6,3.6H6")
    val Refresh = line("refresh", "M19,12a7,7 0,1 1 -2.2,-5.1", "M19,4.5V9h-4.5")
}
