package com.cardprice.app.data.vault

/**
 * What the TCG Vault server sends back, as plain Kotlin. Prices are always USD (see [Money] for
 * conversion). Shapes follow `server/index.js` and `docs/app-api.md` in the server repository.
 */

/** A catalog product as listed by `/api/search`, `/api/home`, `/api/watchlist` and `/api/app/resolve`. */
data class ProductItem(
    val id: Long,
    val name: String,
    val imageUrl: String?,
    val number: String?,
    val rarity: String?,
    val isSealed: Boolean,
    val price: Double?,
    val price7d: Double?,
    val groupId: Long,
    val setName: String,
    val categoryId: Long,
    val categoryName: String?,
    val subType: String?,
    /** Price move over [changeWindow] (home rows and movers), as a fraction (0.05 = +5%). */
    val changePct: Double? = null,
    val changeAbs: Double? = null,
    val changeWindow: String? = null,
    /** Copies held, on the personal home rows. */
    val qty: Int? = null,
) {
    /** The change shown on a tile: the server's window, else last 7 days when known. */
    val shownChange: Pair<Double, String?>?
        get() = changePct?.let { it to changeWindow }
            ?: if (price != null && price7d != null && price7d > 0) ((price - price7d) / price7d) to "7D" else null
}

data class HomeSection(
    val id: String,
    /** "products" or "sets". */
    val kind: String,
    val title: String,
    val subtitle: String?,
    /** Web UI hash link ("#/set/24722", "#/?sort=gain&minPrice=5"); mapped to app screens by [VaultLinks]. */
    val link: String?,
    val products: List<ProductItem> = emptyList(),
    val sets: List<VaultSet> = emptyList(),
)

data class HomeFeed(val asOf: String?, val historyDays: Int, val window: String?, val sections: List<HomeSection>)

data class VaultSet(
    val id: Long,
    val name: String,
    val abbreviation: String?,
    val publishedOn: String?,
    val imageUrl: String?,
    val logoUrl: String?,
    val cardCount: Int,
    val sealedCount: Int,
    val categoryId: Long? = null,
    val topCard: Double? = null,
)

data class SetStats(val cardsValue: Double?, val cardsValue7d: Double?, val topCard: Double?)

data class SetDetail(val set: VaultSet, val categoryName: String?, val stats: SetStats)

data class SearchPage(val items: List<ProductItem>, val total: Int, val window: String?)

data class Variant(
    val id: Long,
    val subType: String,
    val market: Double?,
    val low: Double? = null,
    val mid: Double? = null,
    val high: Double? = null,
    val directLow: Double? = null,
)

data class Cardmarket(
    val trend: Double?,
    val avg: Double?,
    val low: Double?,
    val avg1: Double?,
    val avg7: Double?,
    val avg30: Double?,
    val trendHolo: Double?,
    val day: String?,
    val matchStatus: String?,
)

data class GradedPrice(
    val grader: String,
    val grade: String,
    val gradeKey: String,
    val price: Double?,
    val sales: Int,
    val trend: String?,
    val confidence: String?,
) {
    val label: String get() = if (gradeKey == "ungraded") "Ungraded (eBay)" else "$grader $grade"
}

data class EbaySale(
    val id: String,
    val soldDate: String,
    val price: Double,
    val priceIsRange: Boolean,
    val title: String,
    val kind: String?,
    val grader: String?,
    val grade: String?,
    val quantity: Int,
) {
    /** "Raw", "Sealed", "PSA 10"…, as the web UI groups comps. */
    val label: String
        get() = when (kind) {
            "graded" -> listOfNotNull(grader ?: "Graded", grade).joinToString(" ")
            "sealed" -> "Sealed"
            else -> "Raw"
        }

    val unitPrice: Double get() = if (quantity > 1) price / quantity else price
}

data class ExtField(val name: String, val value: String)

data class ProductDetail(
    val product: ProductItem,
    val url: String?,
    val setAbbr: String?,
    val releasedOn: String?,
    val primaryVariantId: Long?,
    val variants: List<Variant>,
    val watched: Boolean,
    val holdings: List<Holding>,
    val cardmarket: Cardmarket?,
    val graded: List<GradedPrice>,
    /** Non-null once a graded lookup ran (any status). */
    val gradedFetchedAt: Long?,
    val creditsRemaining: Int?,
    val ebaySales: List<EbaySale>,
    /** Japanese edition of a Chinese card, if the server knows one. */
    val jpTwin: ProductItem?,
    val ext: List<ExtField>,
)

data class PricePoint(val day: String, val value: Double)

data class PriceHistory(val series: List<PricePoint>, val cardmarket: List<PricePoint>)

data class Portfolio(val id: Long, val name: String, val items: Int)

data class Holding(
    val id: Long,
    val portfolioId: Long,
    val productId: Long,
    val variantId: Long?,
    val qty: Int,
    val condition: String?,
    val grade: String?,
    val purchasePrice: Double?,
    val customValue: Double?,
    val addedOn: String?,
    val notes: String?,
    val name: String?,
    val imageUrl: String?,
    val number: String?,
    val isSealed: Boolean,
    val setName: String?,
    val subType: String?,
    val portfolioName: String?,
    /** "cards", "sealed" or "graded" (only from `/api/portfolio`). */
    val kind: String?,
    val unitValue: Double?,
    val value: Double?,
    val cost: Double?,
)

data class Bucket(val count: Int, val value: Double)

data class PortfolioSummary(val value: Double, val cost: Double, val costCoveredValue: Double, val items: Int) {
    /** Unrealized gain on holdings that have a purchase price; null when none do. */
    val gain: Double? get() = if (cost > 0) costCoveredValue - cost else null
}

data class PortfolioView(
    val holdings: List<Holding>,
    val summary: PortfolioSummary,
    val cards: Bucket,
    val sealed: Bucket,
    val graded: Bucket,
)

/** One tracked game/language: `{ id, name, lang, code }` (code "JP", "CN"…, null for English). */
data class TrackedCategory(val id: Long, val name: String, val lang: String?, val code: String?) {
    /** Tab label: "English", "Japanese", "Chinese". */
    val tab: String get() = lang ?: "English"
}

data class ServerStatus(val lastDay: String?, val tracked: List<TrackedCategory>)

data class Me(
    val deviceId: Long,
    val deviceName: String?,
    val serverName: String?,
    val serverVersion: String?,
    val lastDay: String?,
    val tracked: List<TrackedCategory>,
)

data class PairResult(val token: String, val deviceId: Long, val serverName: String?)

/** `/api/app/resolve` body. [lang] is "en", "ja", "zh-tw", "zh-cn" or null. */
data class ResolveRequest(
    val lang: String?,
    val setCode: String?,
    val setName: String?,
    val number: String,
    val name: String?,
    val limit: Int = 5,
)

data class ResolveMatch(val product: ProductItem, val variants: List<Variant>, val score: Double, val how: String?)

/** One local item for `/api/app/import`. */
data class ImportItem(
    val ref: String,
    val lang: String?,
    val setCode: String?,
    val setName: String?,
    val number: String?,
    val name: String?,
    val variant: String?,
    val qty: Int,
    val condition: String?,
    val grade: String?,
    val purchasePrice: Double?,
    val addedOn: String?,
    val notes: String?,
    val sealed: Boolean,
    val productName: String?,
)

data class Unmatched(val ref: String, val reason: String)

data class ImportMatch(val ref: String, val productId: Long, val score: Double, val how: String?)

/**
 * `/api/app/import` reply. [imported] counts every item that became (or updated) a holding, [updated] those
 * that already existed from an earlier import. A dry run fills [matched] and writes nothing.
 */
data class ImportResult(
    val imported: Int,
    val unmatched: List<Unmatched>,
    val portfolioId: Long?,
    val updated: Int = 0,
    val dryRun: Boolean = false,
    val matched: List<ImportMatch> = emptyList(),
)

/** `POST /api/holdings` / `PUT /api/holdings/:id` body. Money is USD. */
data class HoldingInput(
    val portfolioId: Long,
    val productId: Long,
    val variantId: Long?,
    val qty: Int,
    val condition: String? = null,
    val grade: String? = null,
    val purchasePrice: Double? = null,
    val customValue: Double? = null,
    val addedOn: String? = null,
    val notes: String? = null,
)

/** History ranges the server understands, with the web UI's labels. */
enum class PriceRange(val key: String, val label: String) {
    D7("7d", "7D"),
    W2("14d", "2W"),
    M1("1m", "1M"),
    M3("3m", "3M"),
    M6("6m", "6M"),
    Y1("1y", "1Y"),
    MAX("max", "MAX"),
}

/** Search sort orders (`/api/search?sort=`). */
enum class SearchSort(val key: String, val label: String) {
    BEST("best", "Best match"),
    PRICE_DESC("price_desc", "Price: high to low"),
    PRICE_ASC("price_asc", "Price: low to high"),
    GAIN("gain", "Top gainers"),
    LOSS("loss", "Top losers"),
    NEWEST("newest", "Newest sets"),
    NAME("name", "Name A–Z"),
    NUMBER("number", "Card number"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: BEST
    }
}

const val CONDITION_NM = "Near Mint"
val CONDITIONS = listOf("Near Mint", "Lightly Played", "Moderately Played", "Heavily Played", "Damaged")
val SEALED_CONDITIONS = listOf("Sealed", "Opened")
