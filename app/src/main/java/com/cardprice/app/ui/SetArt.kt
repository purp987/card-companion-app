package com.cardprice.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cardprice.app.data.Language
import com.cardprice.app.data.PokemonSet
import com.cardprice.app.data.SetArtwork

/**
 * A set's picture for pickers: tries each image in [urls] in order (e.g. pack art, then logo), and
 * shows a colored badge with [label] if none load.
 */
@Composable
fun SetArt(urls: List<String>, label: String, color: Color, modifier: Modifier = Modifier.size(56.dp)) {
    var index by remember(urls) { mutableIntStateOf(0) }
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        val url = urls.getOrNull(index)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onError = { index++ },
            )
        } else {
            Box(Modifier.fillMaxSize().background(color, shape), contentAlignment = Alignment.Center) {
                Text(
                    label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}

/** Pictures for a calculator set, matching what the collection shows for the same set. */
fun calculatorSetArt(set: PokemonSet): List<String> = when (set.language) {
    Language.ENGLISH -> {
        val tcgdex = SetArtwork.tcgdexArt(set.id)
        listOfNotNull(
            tcgdex?.logo?.let { "$it.webp" },
            tcgdex?.let { SetArtwork.collectionImage(it.setId) },
            SetArtwork.packImage(set.tcgSlug),
            tcgdex?.symbol?.let { "$it.webp" },
        )
    }
    Language.JAPANESE -> listOfNotNull(SetArtwork.packImage(set.tcgSlug))
    Language.CHINESE_SIMPLIFIED -> emptyList()
}

fun formatCurrency(amount: Double, currency: String): String =
    java.text.NumberFormat.getCurrencyInstance().apply { this.currency = java.util.Currency.getInstance(currency) }.format(amount)
