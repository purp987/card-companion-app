package com.cardprice.app.data.market

import org.junit.Assert.assertEquals
import org.junit.Test

class DealTest {
    @Test
    fun ratesAgainstMarket() {
        assertEquals(Deal.GOOD, Deal.rate(paid = 49.99, market = 470.95))
        assertEquals(Deal.FAIR, Deal.rate(paid = 30.0, market = 29.62))
        assertEquals(Deal.OVERPAID, Deal.rate(paid = 40.0, market = 29.62))
    }

    @Test
    fun fairBandIsFivePercentEitherWay() {
        assertEquals(Deal.GOOD, Deal.rate(paid = 95.0, market = 100.0))
        assertEquals(Deal.FAIR, Deal.rate(paid = 95.01, market = 100.0))
        assertEquals(Deal.FAIR, Deal.rate(paid = 105.0, market = 100.0))
        assertEquals(Deal.OVERPAID, Deal.rate(paid = 105.01, market = 100.0))
    }
}
