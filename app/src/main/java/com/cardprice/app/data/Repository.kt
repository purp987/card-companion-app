package com.cardprice.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists favorites, user-added sets and saved purchases in SharedPreferences. */
class Repository(context: Context) {
    private val prefs = context.getSharedPreferences("card_pricer", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    init {
        // Older versions kept the PriceCharting token in plain text; move it to encrypted storage.
        prefs.getString(KEY_PC_TOKEN, null)?.let { plain ->
            runCatching { secrets.put(KEY_PC_TOKEN, plain.trim().ifBlank { null }) }
            prefs.edit().remove(KEY_PC_TOKEN).apply()
        }
    }

    fun loadFavorites(): Set<String> = prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toSet()

    fun saveFavorites(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_FAVORITES, ids).apply()
    }

    fun loadPriceChartingToken(): String? = runCatching { secrets.get(KEY_PC_TOKEN) }.getOrNull()?.ifBlank { null }

    fun savePriceChartingToken(token: String?) {
        secrets.put(KEY_PC_TOKEN, token?.trim()?.ifBlank { null })
    }

    fun loadLanguage(): Language =
        Language.entries.firstOrNull { it.name == prefs.getString(KEY_LANGUAGE, null) } ?: Language.ENGLISH

    fun saveLanguage(language: Language) {
        prefs.edit().putString(KEY_LANGUAGE, language.name).apply()
    }

    fun loadCustomSets(): List<PokemonSet> = readArray(KEY_CUSTOM_SETS) { o ->
        PokemonSet(
            id = o.getString("id"),
            name = o.getString("name"),
            series = Series.CUSTOM,
            year = o.getInt("year"),
            cardsPerPack = o.getInt("cardsPerPack"),
        ).withLanguage(Language.entries.firstOrNull { it.name == o.optString("language") } ?: Language.ENGLISH)
    }

    fun saveCustomSets(sets: List<PokemonSet>) = writeArray(KEY_CUSTOM_SETS, sets) { s ->
        JSONObject()
            .put("id", s.id)
            .put("name", s.name)
            .put("year", s.year)
            .put("cardsPerPack", s.cardsPerPack)
            .put("language", s.language.name)
    }

    fun loadPurchases(): List<Purchase> = readArray(KEY_PURCHASES) { o ->
        Purchase(
            id = o.getLong("id"),
            setId = o.getString("setId"),
            product = o.getString("product"),
            quantity = o.getInt("quantity"),
            packsPerProduct = o.getInt("packsPerProduct"),
            cardsPerPack = o.getInt("cardsPerPack"),
            totalPrice = o.getDouble("totalPrice"),
            timestamp = o.getLong("timestamp"),
        )
    }

    fun savePurchases(purchases: List<Purchase>) = writeArray(KEY_PURCHASES, purchases) { p ->
        JSONObject()
            .put("id", p.id)
            .put("setId", p.setId)
            .put("product", p.product)
            .put("quantity", p.quantity)
            .put("packsPerProduct", p.packsPerProduct)
            .put("cardsPerPack", p.cardsPerPack)
            .put("totalPrice", p.totalPrice)
            .put("timestamp", p.timestamp)
    }

    private fun <T> readArray(key: String, parse: (JSONObject) -> T): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { parse(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun <T> writeArray(key: String, items: List<T>, toJson: (T) -> JSONObject) {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_CUSTOM_SETS = "custom_sets"
        const val KEY_PURCHASES = "purchases"
        const val KEY_PC_TOKEN = "pricecharting_token"
        const val KEY_LANGUAGE = "language"
    }
}

/** A user-added set in [language], sold in that language's usual products. */
fun PokemonSet.withLanguage(language: Language) = copy(language = language, products = customSetProducts(language))
