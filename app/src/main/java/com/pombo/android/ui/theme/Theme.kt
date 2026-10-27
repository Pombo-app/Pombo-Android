package com.pombo.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.pombo.android.R

/**
 * Pombo web palette (single dark theme), taken from the MOBILE viewport.
 *
 * The desktop stylesheet uses a zinc palette, but the `@media (max-width:767px)`
 * block overrides every full-screen surface to true black — the CSS comment says
 * why: "Force true black on mobile for Samsung/Android nav bar"
 * (components.css:1895). This app replicates only the mobile viewport, so black
 * is the value used here. Using zinc made the chat header and composer read as
 * visibly lighter bands framing the message list, where the web is one
 * seamless black field.
 */
object PomboColors {
    // components.css:1897,1934,1974,2005,2009,2013 — all `#000000 !important`
    val Background = Color(0xFF000000)
    /** Chat header and composer: also true black on mobile, NOT a raised surface. */
    val Surface = Color(0xFF000000)
    /** Raised fills (menus, fields) that the mobile block leaves on the dark scale. */
    val SurfaceHigh = Color(0xFF18181B)
    // components.css:1978 — mobile overrides structural borders to white 8%.
    val Border = Color(0xFFFFFFFF).copy(alpha = 0.08f)
    // Same value as res/values/colors.xml `pombo_accent` and the web's --pombo-accent.
    val Accent = Color(0xFFF6851B)
    val AccentHover = Color(0xFFE5780F)
    val AccentDim = Color(0xFFAC5D13)    // darker tone of the same hue
    val Text = Color(0xFFE4E4E7)
    /** Web uses `text-white/40` for every secondary label — zinc-400 was too bright. */
    val TextDim = Color(0xFFFFFFFF).copy(alpha = 0.40f)
    val Danger = Color(0xFFEF4444)
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFFBBF24)
    val Info = Color(0xFF60A5FA)
    val BgDesktop = Color(0xFF09090B)
    val SurfaceRaised = Color(0xFF16161B)
    val Scrim = Color.Black.copy(alpha = 0.80f)

    // Surface ramp, same names and values as the web's surface-N.
    val Surface1 = Color(0xFF09090B)
    val Surface2 = Color(0xFF0A0A0A)
    val Surface3 = Color(0xFF0B0B0E)
    val Surface4 = Color(0xFF0E0E10)
    val Surface5 = Color(0xFF0F0F12)
    val Surface6 = Color(0xFF111113)
    val Surface7 = Color(0xFF16161B)
    val Surface8 = Color(0xFF18181B)
    val Surface9 = Color(0xFF1A1A1A)
    val Surface10 = Color(0xFF1E1E1E)

    // White at N % (web overlay-N). Built exactly as the literals they replace,
    // so swapping one for the other cannot move a pixel.
    val Overlay1 = Color.White.copy(alpha = 0.01f)
    val Overlay2 = Color.White.copy(alpha = 0.02f)
    val Overlay3 = Color.White.copy(alpha = 0.03f)
    val Overlay4 = Color.White.copy(alpha = 0.04f)
    val Overlay5 = Color.White.copy(alpha = 0.05f)
    val Overlay6 = Color.White.copy(alpha = 0.06f)
    val Overlay8 = Color.White.copy(alpha = 0.08f)
    val Overlay10 = Color.White.copy(alpha = 0.10f)
    val Overlay12 = Color.White.copy(alpha = 0.12f)
    val Overlay14 = Color.White.copy(alpha = 0.14f)
    val Overlay15 = Color.White.copy(alpha = 0.15f)
    val Overlay20 = Color.White.copy(alpha = 0.20f)
    val Overlay25 = Color.White.copy(alpha = 0.25f)
    val Overlay30 = Color.White.copy(alpha = 0.30f)
    val Overlay35 = Color.White.copy(alpha = 0.35f)
    val Overlay40 = Color.White.copy(alpha = 0.40f)
    val Overlay45 = Color.White.copy(alpha = 0.45f)
    val Overlay50 = Color.White.copy(alpha = 0.50f)
    val Overlay55 = Color.White.copy(alpha = 0.55f)
    val Overlay60 = Color.White.copy(alpha = 0.60f)
    val Overlay70 = Color.White.copy(alpha = 0.70f)
    val Overlay75 = Color.White.copy(alpha = 0.75f)
    val Overlay80 = Color.White.copy(alpha = 0.80f)
    val Overlay85 = Color.White.copy(alpha = 0.85f)
    val Overlay87 = Color.White.copy(alpha = 0.87f)
    val Overlay90 = Color.White.copy(alpha = 0.90f)
    // Message bubbles (exact web values: components.css .message-bubble)
    val BubbleOwn = Color(0xFF272A2E)
    val BubbleOther = Color(0xFF1E1E1E)
    val BubbleOwnText = Color(0xFFF0F0F0)
    val BubbleOtherText = Color(0xFFD1D5DB)
}

/** 4 dp spacing scale with the web's Tailwind names (S3 = p-3 = 12). */
object PomboSpacing {
    val S0_5 = 2.dp
    val S1 = 4.dp
    val S1_5 = 6.dp
    val S2 = 8.dp
    val S2_5 = 10.dp
    val S3 = 12.dp
    val S3_5 = 14.dp
    val S4 = 16.dp
    val S5 = 20.dp
    val S6 = 24.dp
    val S8 = 32.dp
}

object PomboShapes {
    val Xs = RoundedCornerShape(4.dp)
    val Sm = RoundedCornerShape(6.dp)
    val Md = RoundedCornerShape(8.dp)
    val Lg = RoundedCornerShape(12.dp)
    val Xl = RoundedCornerShape(16.dp)
    val Xxl = RoundedCornerShape(20.dp)
    val Full = CircleShape
}

object PomboType {
    val Size10 = 10.sp
    val Size11 = 11.sp
    val Size12 = 12.sp
    val Size13 = 13.sp
    val Size14 = 14.sp
    val Size15 = 15.sp
    val Size16 = 16.sp
    val Size18 = 18.sp
    val Size20 = 20.sp
    val Size24 = 24.sp
}

private val DarkScheme = darkColorScheme(
    primary = PomboColors.Accent,
    onPrimary = Color.Black,
    secondary = PomboColors.AccentDim,
    background = PomboColors.Background,
    onBackground = PomboColors.Text,
    surface = PomboColors.Surface,
    onSurface = PomboColors.Text,
    surfaceVariant = PomboColors.SurfaceHigh,
    onSurfaceVariant = PomboColors.TextDim,
    outline = PomboColors.Border,
    error = PomboColors.Danger
)

/**
 * Inter, bundled — the same family the web loads from Google Fonts
 * (index.html:53, weights 300–700).
 *
 * Bundled rather than fetched at runtime: a downloadable font arrives after
 * first paint, so every screen would flash in Roboto and reflow. It also makes
 * the app's text metrics deterministic and offline-proof, which matters here
 * because line breaking depends on glyph widths — with Roboto the same message
 * wrapped at different words than the web.
 */
private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold)
)

/**
 * Every text style uses Inter. `includeFontPadding` is switched off to match
 * CSS box behaviour — with it on, Android adds ascent/descent padding that CSS
 * has no equivalent for, which inflates every line box.
 */
private val PomboTypography = Typography().run {
    val base = TextStyle(
        fontFamily = Inter,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
    copy(
        displayLarge = displayLarge.merge(base), displayMedium = displayMedium.merge(base),
        displaySmall = displaySmall.merge(base), headlineLarge = headlineLarge.merge(base),
        headlineMedium = headlineMedium.merge(base), headlineSmall = headlineSmall.merge(base),
        titleLarge = titleLarge.merge(base), titleMedium = titleMedium.merge(base),
        titleSmall = titleSmall.merge(base), bodyLarge = bodyLarge.merge(base),
        bodyMedium = bodyMedium.merge(base), bodySmall = bodySmall.merge(base),
        labelLarge = labelLarge.merge(base), labelMedium = labelMedium.merge(base),
        labelSmall = labelSmall.merge(base)
    )
}

@Composable
fun PomboTheme(content: @Composable () -> Unit) {
    // The web only has a dark theme — we ignore the system light mode.
    MaterialTheme(colorScheme = DarkScheme, typography = PomboTypography, content = content)
}
