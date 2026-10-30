package dev.andsmb.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Feather Icons (MIT) の線画。線幅2・丸端、24 グリッド。円・矩形・線はパスに直してある。 */
object Feather {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val checkCircle = icon("check-circle", "M22 11.08V12a10 10 0 1 1-5.93-9.14", "M22 4L12 14.01l-3-3")
    val square = icon("square", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z")
    val play = icon("play", "M5 3l14 9-14 9V3z")
    val copy = icon("copy", "M11 9h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z", "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1")
    val wifiOff = icon(
        "wifi-off", "M1 1l22 22", "M16.72 11.06A10.94 10.94 0 0 1 19 12.55", "M5 12.55a10.94 10.94 0 0 1 5.17-2.39",
        "M10.71 5.05A16 16 0 0 1 22.58 9", "M1.42 9a15.91 15.91 0 0 1 4.7-2.88", "M8.53 16.11a6 6 0 0 1 6.95 0", "M12 20h.01",
    )
    val alertTriangle = icon("alert-triangle", "M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z", "M12 9v4", "M12 17h.01")
    val share2 = icon(
        "share-2", "M15 5a3 3 0 1 0 6 0a3 3 0 1 0-6 0z", "M3 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0z", "M15 19a3 3 0 1 0 6 0a3 3 0 1 0-6 0z",
        "M8.59 13.51l6.83 3.98", "M15.41 6.51l-6.82 3.98",
    )
    val folder = icon("folder", "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z")
    val user = icon("user", "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2", "M8 7a4 4 0 1 0 8 0a4 4 0 1 0-8 0z")
    val users = icon(
        "users", "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2", "M5 7a4 4 0 1 0 8 0a4 4 0 1 0-8 0z",
        "M23 21v-2a4 4 0 0 0-3-3.87", "M16 3.13a4 4 0 0 1 0 7.75",
    )
    val lock = icon("lock", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4")
    val hash = icon("hash", "M4 9h16", "M4 15h16", "M10 3L8 21", "M16 3l-2 18")
    val eye = icon("eye", "M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0z")
    val terminal = icon("terminal", "M4 17l6-6-6-6", "M12 19h8")
    val activity = icon("activity", "M22 12h-4l-3 9L9 3l-3 9H2")
    val barChart = icon("bar-chart-2", "M18 20V10", "M12 20V4", "M6 20v-6")
    val arrowDown = icon("arrow-down", "M12 5v14", "M19 12l-7 7-7-7")
    val arrowUp = icon("arrow-up", "M12 19V5", "M5 12l7-7 7 7")
    val server = icon(
        "server", "M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z",
        "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z", "M6 6h.01", "M6 18h.01",
    )
}

@Composable
fun FIcon(icon: ImageVector, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}
