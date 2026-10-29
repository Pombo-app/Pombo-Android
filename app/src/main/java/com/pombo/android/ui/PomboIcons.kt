package com.pombo.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
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

    // The web message menu's icons (index.html #message-context-menu), from
    // the same SVG path data so both clients draw the same glyph. Arc flags
    // must be separate tokens: Compose misreads the minified `a2 2 0 01-2-2`.
    val CopyText by lazy { stroked("CopyText", "M8 16 H6 a2 2 0 0 1 -2 -2 V6 a2 2 0 0 1 2 -2 h8 a2 2 0 0 1 2 2 v2 m-6 12 h8 a2 2 0 0 0 2 -2 v-8 a2 2 0 0 0 -2 -2 h-8 a2 2 0 0 0 -2 2 v8 a2 2 0 0 0 2 2 z") }
    val Clipboard by lazy { stroked("Clipboard", "M15.666 3.888 A2.25 2.25 0 0 0 13.5 2.25 h-3 c-1.03 0 -1.9 .693 -2.166 1.638 m7.332 0 c.055 .194 .084 .4 .084 .612 v0 a.75 .75 0 0 1 -.75 .75 H9 a.75 .75 0 0 1 -.75 -.75 v0 c0 -.212 .03 -.418 .084 -.612 m7.332 0 c.646 .049 1.288 .11 1.927 .184 1.1 .128 1.907 1.077 1.907 2.185 V19.5 a2.25 2.25 0 0 1 -2.25 2.25 H6.75 A2.25 2.25 0 0 1 4.5 19.5 V6.257 c0 -1.108 .806 -2.057 1.907 -2.185 a48.208 48.208 0 0 1 1.927 -.184") }
    val Reply by lazy { stroked("Reply", "M9 17 l-5 -5 5 -5", "M4 12 h11 a4 4 0 0 1 4 4 v4") }
    val Envelope by lazy { stroked("Envelope", "M21.75 6.75 v10.5 a2.25 2.25 0 0 1 -2.25 2.25 h-15 a2.25 2.25 0 0 1 -2.25 -2.25 V6.75 m19.5 0 A2.25 2.25 0 0 0 19.5 4.5 h-15 a2.25 2.25 0 0 0 -2.25 2.25 m19.5 0 v.243 a2.25 2.25 0 0 1 -1.07 1.916 l-7.5 4.615 a2.25 2.25 0 0 1 -2.36 0 L3.32 8.91 a2.25 2.25 0 0 1 -1.07 -1.916 V6.75") }
    val UserPlus by lazy { stroked("UserPlus", "M19 7.5 v3 m0 0 v3 m0 -3 h3 m-3 0 h-3 m-2.25 -4.125 a3.375 3.375 0 1 1 -6.75 0 3.375 3.375 0 0 1 6.75 0 z M4 19.235 v-.11 a6.375 6.375 0 0 1 12.75 0 v.109 A12.318 12.318 0 0 1 10.374 21 c-2.331 0 -4.512 -.645 -6.374 -1.766 z") }
    val UserMinus by lazy { stroked("UserMinus", "M22 10.5 h-6 m-2.25 -4.125 a3.375 3.375 0 1 1 -6.75 0 3.375 3.375 0 0 1 6.75 0 z M4 19.235 v-.11 a6.375 6.375 0 0 1 12.75 0 v.109 A12.318 12.318 0 0 1 10.374 21 c-2.331 0 -4.512 -.645 -6.374 -1.766 z") }
    val Edit by lazy { stroked("Edit", "M11 4 H4 a2 2 0 0 0 -2 2 v14 a2 2 0 0 0 2 2 h14 a2 2 0 0 0 2 -2 v-7", "M18.5 2.5 a2.121 2.121 0 0 1 3 3 L12 15 l-4 1 1 -4 9.5 -9.5 z") }
    val Trash by lazy { stroked("Trash", "M14.74 9 l-.346 9 m-4.788 0 L9.26 9 m9.968 -3.21 c.342 .052 .682 .107 1.022 .166 m-1.022 -.165 L18.16 19.673 a2.25 2.25 0 0 1 -2.244 2.077 H8.084 a2.25 2.25 0 0 1 -2.244 -2.077 L4.772 5.79 m14.456 0 a48.108 48.108 0 0 0 -3.478 -.397 m-12 .562 c.34 -.059 .68 -.114 1.022 -.165 m0 0 a48.11 48.11 0 0 1 3.478 -.397 m7.5 0 v-.916 c0 -1.18 -.91 -2.164 -2.09 -2.201 a51.964 51.964 0 0 0 -3.32 0 c-1.18 .037 -2.09 1.022 -2.09 2.201 v.916 m7.5 0 a48.667 48.667 0 0 0 -7.5 0") }
    val Erase by lazy { stroked("Erase", "M4 7 h16 M10 11 v6 m4 -6 v6 M5 7 l1 12 a2 2 0 0 0 2 2 h8 a2 2 0 0 0 2 -2 l1 -12 M9 7 V4 h6 v3", width = 2f) }
    val Pin by lazy { filled("Pin", "M16 12 V4 h1 V2 H7 v2 h1 v8 l-2 2 v2 h5 v6 l1 1 1 -1 v-6 h5 v-2 l-2 -2 z") }
    val Close by lazy { stroked("Close", "M6 18 L18 6 M6 6 l12 12") }
    val Eye by lazy { stroked("Eye", "M15 12 a3 3 0 1 1 -6 0 3 3 0 0 1 6 0 z", "M2.458 12 C3.732 7.943 7.523 5 12 5 c4.478 0 8.268 2.943 9.542 7 -1.274 4.057 -5.064 7 -9.542 7 -4.477 0 -8.268 -2.943 -9.542 -7 z", width = 2f) }
    val StarOutline by lazy { stroked("StarOutline", "M12 3.2 l2.6 5.3 5.8 .8 -4.2 4.1 1 5.8 L12 16.5 l-5.2 2.7 1 -5.8 -4.2 -4.1 5.8 -.8 z", width = 2f) }
    val Ban by lazy { stroked("Ban", "M18.364 18.364 A9 9 0 0 0 5.636 5.636 m12.728 12.728 A9 9 0 0 1 5.636 5.636 m12.728 12.728 L5.636 5.636") }

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    )

    private fun stroked(name: String, vararg d: String, width: Float = 1.5f): ImageVector =
        builder(name).apply {
            d.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                )
            }
        }.build()

    private fun filled(name: String, d: String): ImageVector =
        builder(name).apply {
            addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        }.build()
}
