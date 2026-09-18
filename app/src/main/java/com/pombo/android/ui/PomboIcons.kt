package com.pombo.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icons Material does not ship, drawn to match the outlined set. */
object PomboIcons {

    val Ghost: ImageVector by lazy {
        ImageVector.Builder(
            name = "Ghost",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 2.5f)
                curveTo(7.86f, 2.5f, 4.5f, 5.86f, 4.5f, 10f)
                lineTo(4.5f, 21f)
                lineTo(7f, 19f)
                lineTo(9.5f, 21f)
                lineTo(12f, 19f)
                lineTo(14.5f, 21f)
                lineTo(17f, 19f)
                lineTo(19.5f, 21f)
                lineTo(19.5f, 10f)
                curveTo(19.5f, 5.86f, 16.14f, 2.5f, 12f, 2.5f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(8.4f, 10.2f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, 2.2f, 0f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, -2.2f, 0f)
                close()
                moveTo(13.4f, 10.2f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, 2.2f, 0f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, -2.2f, 0f)
                close()
            }
        }.build()
    }
}
