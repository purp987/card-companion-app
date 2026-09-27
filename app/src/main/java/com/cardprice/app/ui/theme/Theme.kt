package com.cardprice.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.cardprice.app.data.Series
import com.cardprice.app.data.market.Deal

private val LightColors = lightColorScheme(
    primary = Color(0xFFC62828),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410001),
    secondary = Color(0xFF3B5BA9),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2FF),
    onSecondaryContainer = Color(0xFF001848),
    tertiary = Color(0xFFB8860B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A9),
    onPrimary = Color(0xFF690002),
    primaryContainer = Color(0xFF930006),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFB1C5FF),
    onSecondary = Color(0xFF002C71),
    secondaryContainer = Color(0xFF1F428F),
    onSecondaryContainer = Color(0xFFDAE2FF),
    tertiary = Color(0xFFFFD54F),
)

/** Gold used for the favorite star. */
val FavoriteGold = Color(0xFFFFB300)

fun seriesColor(series: Series): Color = when (series) {
    Series.MEGA_EVOLUTION -> Color(0xFF6A1B9A)
    Series.SCARLET_VIOLET -> Color(0xFFD84315)
    Series.GEM_PACK -> Color(0xFF00897B)
    Series.SWORD_SHIELD -> Color(0xFF1565C0)
    Series.SUN_MOON -> Color(0xFFF9A825)
    Series.XY -> Color(0xFF00838F)
    Series.CUSTOM -> Color(0xFF546E7A)
}

/** Background and text colors for the result card, by deal quality. */
data class DealColors(val container: Color, val content: Color)

@Composable
fun dealColors(deal: Deal?): DealColors {
    val dark = isSystemInDarkTheme()
    return when (deal) {
        Deal.GOOD -> if (dark) DealColors(Color(0xFF1E5B2A), Color(0xFFC8F2CC)) else DealColors(Color(0xFFC8EBCB), Color(0xFF0B3D14))
        Deal.FAIR -> if (dark) DealColors(Color(0xFF5A4300), Color(0xFFFFE08A)) else DealColors(Color(0xFFFFE9A8), Color(0xFF3E2E00))
        Deal.OVERPAID -> if (dark) DealColors(Color(0xFF8C1D18), Color(0xFFFFDAD6)) else DealColors(Color(0xFFFFDAD6), Color(0xFF410002))
        // Nothing to compare against yet: stay neutral so red only ever means "overpaid".
        null -> DealColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun CardPricerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
