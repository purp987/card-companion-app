package com.cardprice.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardprice.app.R
import com.cardprice.app.data.Series
import com.cardprice.app.data.market.Deal

/**
 * TCG Vault's "deep sea" dark theme (server `public/styles.css` :root tokens): blue-black canvas,
 * glassy slate surfaces, teal accent with violet and gold support.
 */
object Palette {
    val bg = Color(0xFF070A0F)
    /** Cards: translucent slate (rgba(17,23,32,.72) over the canvas). */
    val surface = Color(0xB8111720)
    val surfaceSolid = Color(0xFF10151D)
    val surface2 = Color(0xFF18202B)
    val surface3 = Color(0xFF222C39)
    val input = Color(0xA6060910)
    val imgBg = Color(0xFF0B1017)
    val border = Color(0x1C96ACCC)
    val borderStrong = Color(0x3896ACCC)

    val text = Color(0xFFEDF2F7)
    val text2 = Color(0xFFA7B2C1)
    val text3 = Color(0xFF808B9C)

    val accent = Color(0xFF2FD4B5)
    val accent2 = Color(0xFF8B80F9)
    val accent3 = Color(0xFFF2B55E)
    val accentSoft = Color(0x212FD4B5)
    val accentGlow = Color(0x592FD4B5)
    val onAccent = Color(0xFF032620)

    val up = Color(0xFF35D49B)
    val down = Color(0xFFFF6B81)

    val chartGrid = Color(0x1496ACCC)
    val chartSealed = Color(0xFF6F8DFF)
    val chartGraded = accent3

    /** --grad-brand: teal → mint → violet. */
    val brand = Brush.linearGradient(listOf(Color(0xFF2FD4B5), Color(0xFF5FE3CF), Color(0xFF8B80F9)))
    /** --showcase-bg: spotlight behind set logos and cut-out sealed products. */
    val showcase = Brush.radialGradient(listOf(Color(0xFF2A3546), Color(0xFF18202C), Color(0xFF10161F)))
    val cardImageBg = Brush.radialGradient(listOf(Color(0xFF121A24), imgBg))
    /** Section-title accent bar. */
    val bar = Brush.verticalGradient(listOf(accent, accent2))
}

@OptIn(ExperimentalTextApi::class)
private fun grotesk(weight: Int) =
    Font(R.font.space_grotesk, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Headings and prices. Bundled (SIL OFL 1.1, see licenses/). */
val SpaceGrotesk = FontFamily(grotesk(500), grotesk(700))

/** Body text. Bundled (SIL OFL 1.1, see licenses/). */
val Lato = FontFamily(
    Font(R.font.lato_regular, FontWeight.Normal),
    Font(R.font.lato_bold, FontWeight.Bold),
    Font(R.font.lato_black, FontWeight.Black),
)

private val VaultColors = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.onAccent,
    primaryContainer = Color(0xFF0E3B33),
    onPrimaryContainer = Color(0xFFBFF5EA),
    secondary = Palette.accent2,
    onSecondary = Color(0xFF14104A),
    secondaryContainer = Color(0xFF2B2760),
    onSecondaryContainer = Color(0xFFE2DFFF),
    tertiary = Palette.accent3,
    onTertiary = Color(0xFF3A2600),
    background = Palette.bg,
    onBackground = Palette.text,
    surface = Palette.bg,
    onSurface = Palette.text,
    surfaceVariant = Palette.surface2,
    onSurfaceVariant = Palette.text2,
    surfaceContainerLowest = Palette.bg,
    surfaceContainerLow = Palette.surfaceSolid,
    surfaceContainer = Palette.surfaceSolid,
    surfaceContainerHigh = Palette.surface2,
    surfaceContainerHighest = Palette.surface3,
    surfaceBright = Palette.surface3,
    surfaceDim = Palette.bg,
    inverseSurface = Palette.text,
    inverseOnSurface = Palette.bg,
    outline = Palette.borderStrong,
    outlineVariant = Palette.border,
    error = Palette.down,
    onError = Color(0xFF3B0010),
    errorContainer = Color(0xFF5C1622),
    onErrorContainer = Color(0xFFFFD9DE),
    scrim = Color(0xCC000000),
)

private fun display(size: Int, weight: Int = 700) =
    TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight(weight), fontSize = size.sp, letterSpacing = (-size * 0.01).sp)

private fun body(size: Double, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Lato, fontWeight = weight, fontSize = size.sp, lineHeight = (size * 1.4).sp)

private val VaultType = Typography(
    displayLarge = display(44),
    displayMedium = display(38),
    displaySmall = display(32),
    headlineLarge = display(28),
    headlineMedium = display(24),
    headlineSmall = display(20),
    titleLarge = display(20),
    titleMedium = body(16.0, FontWeight.Black),
    titleSmall = body(14.0, FontWeight.Bold),
    bodyLarge = body(16.0),
    bodyMedium = body(14.5),
    bodySmall = body(12.5),
    labelLarge = body(14.0, FontWeight.Bold),
    labelMedium = body(12.5, FontWeight.Bold),
    labelSmall = body(11.0, FontWeight.Bold),
)

private val VaultShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Prices: Space Grotesk with tabular figures. */
val PriceStyle = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

@Composable
fun CardPricerTheme(content: @Composable () -> Unit) {
    // Always dark: the app mirrors the TCG Vault web UI.
    MaterialTheme(colorScheme = VaultColors, typography = VaultType, shapes = VaultShapes, content = content)
}

/** Gold used for the favorite star. */
val FavoriteGold = Palette.accent3

/** Calculator series accents, tuned for the dark canvas. */
fun seriesColor(series: Series): Color = when (series) {
    Series.MEGA_EVOLUTION -> Color(0xFF8B80F9)
    Series.SCARLET_VIOLET -> Color(0xFFFF8A5C)
    Series.GEM_PACK -> Color(0xFF2FD4B5)
    Series.SWORD_SHIELD -> Color(0xFF6F8DFF)
    Series.SUN_MOON -> Color(0xFFF2B55E)
    Series.XY -> Color(0xFF4FC3F7)
    Series.CUSTOM -> Color(0xFF808B9C)
}

/** Background and text colors for the calculator's result card, by deal quality. */
data class DealColors(val container: Color, val content: Color)

@Composable
fun dealColors(deal: Deal?): DealColors = when (deal) {
    Deal.GOOD -> DealColors(Color(0xFF0F3A2C), Color(0xFFB9F5DD))
    Deal.FAIR -> DealColors(Color(0xFF3D2F10), Color(0xFFFFE3B0))
    Deal.OVERPAID -> DealColors(Color(0xFF4A1520), Color(0xFFFFD6DC))
    // Nothing to compare against yet: stay neutral so red only ever means "overpaid".
    null -> DealColors(Palette.surface2, Palette.text)
}
