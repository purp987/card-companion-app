package com.cardprice.app.data.market

/** How a price paid compares with market value. */
enum class Deal(val label: String) {
    GOOD("Good deal"),
    FAIR("Fair price"),
    OVERPAID("Above market");

    companion object {
        /** Within this percentage of market either way counts as a fair price. */
        const val FAIR_BAND_PERCENT = 5.0

        /** Percent difference from market: negative means you paid less. */
        fun percentVsMarket(paid: Double, market: Double): Double = (paid - market) / market * 100

        fun rate(paid: Double, market: Double): Deal {
            val diff = percentVsMarket(paid, market)
            return when {
                diff <= -FAIR_BAND_PERCENT -> GOOD
                diff > FAIR_BAND_PERCENT -> OVERPAID
                else -> FAIR
            }
        }
    }
}
