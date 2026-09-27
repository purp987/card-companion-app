package com.cardprice.app.data.market

import java.time.LocalDate

/** A sealed product listed on TCGplayer. */
data class MarketProduct(
    val id: Long,
    val name: String,
    val marketPrice: Double?,
    val lowestPrice: Double?,
) {
    val url: String get() = "https://www.tcgplayer.com/product/$id"
}

/** One history bucket: a single day for the 1M range, several days for longer ranges. */
data class PricePoint(
    val date: LocalDate,
    val avgSalePrice: Double?,
    val marketPrice: Double?,
    val quantitySold: Int,
)

enum class HistoryRange(val label: String, val apiValue: String) {
    MONTH("1M", "month"),
    QUARTER("3M", "quarter"),
    HALF_YEAR("6M", "semi-annual"),
    YEAR("1Y", "annual"),
}

/** A PriceCharting match. [price] is PriceCharting's ungraded ("loose") value, which is what it shows for sealed items. */
data class PriceChartingPrice(
    val productName: String,
    val consoleName: String,
    val price: Double?,
)

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** Summary numbers for a history range. */
data class HistoryStats(
    val bucketDays: Int,
    val totalSold: Int,
    val avgSold: Double,
    val avgSalePrice: Double?,
    val latestMarket: Double?,
    val low: Double?,
    val high: Double?,
) {
    companion object {
        fun of(points: List<PricePoint>): HistoryStats {
            val bucketDays = if (points.size >= 2) {
                (points[1].date.toEpochDay() - points[0].date.toEpochDay()).toInt().coerceAtLeast(1)
            } else 1
            val totalSold = points.sumOf { it.quantitySold }
            val soldPoints = points.filter { it.quantitySold > 0 && it.avgSalePrice != null }
            // Weight each bucket's average by its volume so busy days count more.
            val avgSale = if (soldPoints.isEmpty()) null
            else soldPoints.sumOf { it.avgSalePrice!! * it.quantitySold } / soldPoints.sumOf { it.quantitySold }
            val days = (points.size * bucketDays).coerceAtLeast(1)
            return HistoryStats(
                bucketDays = bucketDays,
                totalSold = totalSold,
                avgSold = totalSold.toDouble() / days,
                avgSalePrice = avgSale,
                latestMarket = points.lastOrNull { it.marketPrice != null }?.marketPrice,
                low = soldPoints.minOfOrNull { it.avgSalePrice!! },
                high = soldPoints.maxOfOrNull { it.avgSalePrice!! },
            )
        }
    }
}
