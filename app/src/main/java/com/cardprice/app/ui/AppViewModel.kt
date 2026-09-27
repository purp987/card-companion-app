package com.cardprice.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.cardprice.app.data.ChineseSets
import com.cardprice.app.data.JapaneseSets
import com.cardprice.app.data.Language
import com.cardprice.app.data.PokemonSet
import com.cardprice.app.data.Purchase
import com.cardprice.app.data.Repository
import com.cardprice.app.data.Series
import com.cardprice.app.data.SetCatalog
import com.cardprice.app.data.withLanguage
import com.cardprice.app.data.market.MarketRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Calendar

data class AppState(
    val favorites: Set<String> = emptySet(),
    val customSets: List<PokemonSet> = emptyList(),
    val purchases: List<Purchase> = emptyList(),
    val priceChartingToken: String? = null,
    val language: Language = Language.ENGLISH,
) {
    val allSets: List<PokemonSet> get() = customSets + SetCatalog.sets + JapaneseSets.sets + ChineseSets.sets

    fun setById(id: String): PokemonSet? = allSets.firstOrNull { it.id == id }

    fun purchasesFor(setId: String): List<Purchase> =
        purchases.filter { it.setId == setId }.sortedByDescending { it.timestamp }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = Repository(application)

    private val _state = MutableStateFlow(
        AppState(
            favorites = repo.loadFavorites(),
            customSets = repo.loadCustomSets(),
            purchases = repo.loadPurchases(),
            priceChartingToken = repo.loadPriceChartingToken(),
            language = repo.loadLanguage(),
        )
    )
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun setPriceChartingToken(token: String?) {
        repo.savePriceChartingToken(token)
        MarketRepository.clearPriceCharting()
        _state.update { it.copy(priceChartingToken = repo.loadPriceChartingToken()) }
    }

    fun selectLanguage(language: Language) {
        repo.saveLanguage(language)
        _state.update { it.copy(language = language) }
    }

    fun toggleFavorite(setId: String) {
        _state.update { s ->
            val favorites = if (setId in s.favorites) s.favorites - setId else s.favorites + setId
            repo.saveFavorites(favorites)
            s.copy(favorites = favorites)
        }
    }

    fun addCustomSet(name: String, cardsPerPack: Int) {
        val set = PokemonSet(
            id = "custom_${System.currentTimeMillis()}",
            name = name.trim(),
            series = Series.CUSTOM,
            year = Calendar.getInstance().get(Calendar.YEAR),
            cardsPerPack = cardsPerPack,
        ).withLanguage(_state.value.language)
        _state.update { s ->
            val sets = listOf(set) + s.customSets
            repo.saveCustomSets(sets)
            s.copy(customSets = sets)
        }
    }

    fun deleteCustomSet(setId: String) {
        _state.update { s ->
            val sets = s.customSets.filterNot { it.id == setId }
            val purchases = s.purchases.filterNot { it.setId == setId }
            repo.saveCustomSets(sets)
            repo.savePurchases(purchases)
            repo.saveFavorites(s.favorites - setId)
            s.copy(customSets = sets, purchases = purchases, favorites = s.favorites - setId)
        }
    }

    fun addPurchase(purchase: Purchase) {
        _state.update { s ->
            val purchases = s.purchases + purchase
            repo.savePurchases(purchases)
            s.copy(purchases = purchases)
        }
    }

    fun deletePurchase(id: Long) {
        _state.update { s ->
            val purchases = s.purchases.filterNot { it.id == id }
            repo.savePurchases(purchases)
            s.copy(purchases = purchases)
        }
    }
}
