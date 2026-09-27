package com.cardprice.app.data.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class MarketTest {
    private fun product(name: String, price: Double? = 10.0) = MarketProduct(name.hashCode().toLong(), name, price, null)

    // Real TCGplayer names for Surging Sparks and Evolving Skies, in TCGplayer's (sales-ranked) order.
    private val surgingSparks = listOf(
        "Surging Sparks Booster Pack", "Surging Sparks Elite Trainer Box", "Surging Sparks Booster Box",
        "Surging Sparks Booster Bundle (LGS)", "Surging Sparks Sleeved Booster Pack",
        "Surging Sparks Pokemon Center Elite Trainer Box (Exclusive)", "Surging Sparks Booster Pack Art Bundle [Set of 4]",
        "Surging Sparks 3 Pack Blisters [Quagsire]", "Surging Sparks Half Booster Box", "Surging Sparks Booster Box Case",
    ).map { product(it) }

    @Test
    fun picksMainstreamProductForEachKind() {
        assertEquals("Surging Sparks Booster Pack", ProductKind.BOOSTER_PACK.pick(surgingSparks)?.name)
        assertEquals("Surging Sparks Elite Trainer Box", ProductKind.ETB.pick(surgingSparks)?.name)
        assertEquals("Surging Sparks Booster Box", ProductKind.BOOSTER_BOX.pick(surgingSparks)?.name)
        assertEquals("Surging Sparks Booster Bundle (LGS)", ProductKind.BUNDLE.pick(surgingSparks)?.name)
        assertEquals("Surging Sparks 3 Pack Blisters [Quagsire]", ProductKind.BLISTER.pick(surgingSparks)?.name)
    }

    @Test
    fun skipsCasesExclusivesAndLots() {
        val names = listOf(
            "Prismatic Evolutions Pokemon Center Elite Trainer Box (Exclusive)",
            "Prismatic Evolutions Booster Bundle + Surprise Box Bundle (Sam's Club)",
            "Prismatic Evolutions Booster Bundle Display",
            "Evolving Skies Elite Trainer Box [Set of 2]",
            "151 Elite Trainer Box Case",
        )
        names.forEach { name -> ProductKind.entries.forEach { assert(!it.matches(name)) { "$it matched $name" } } }
    }

    @Test
    fun handlesXyStyleNames() {
        assertEquals(true, ProductKind.BLISTER.matches("XY - Evolutions 3 Pack Blister [Braixen]"))
        assertEquals(true, ProductKind.BOOSTER_BOX.matches("XY Evolutions Booster Box"))
    }

    @Test
    fun prefersMatchWithAPrice() {
        val list = listOf(product("Evolving Skies Elite Trainer Box [A]", null), product("Evolving Skies Elite Trainer Box [B]", 500.0))
        assertEquals("Evolving Skies Elite Trainer Box [B]", ProductKind.ETB.pick(list)?.name)
    }

    @Test
    fun parsesTcgPlayerSearch() {
        val json = """{"errors":[],"results":[{"aggregations":{},"results":[
            {"productId":503313.0,"productName":"151 Elite Trainer Box","setName":"SV: Scarlet & Violet 151","marketPrice":470.95,"lowestPrice":455.0},
            {"productId":506640.0,"productName":"Promo Thing","setName":"Other","marketPrice":null,"lowestPrice":0}]}]}"""
        val all = TcgPlayerApi.parseProducts(json)
        assertEquals(2, all.size)
        assertEquals(503313L, all[0].id)
        assertEquals(470.95, all[0].marketPrice!!, 0.001)
        assertNull(all[1].marketPrice)
        assertNull(all[1].lowestPrice)
        assertEquals(listOf("151 Elite Trainer Box"), TcgPlayerApi.parseProducts(json, setNameFilter = "151").map { it.name })
    }

    @Test
    fun parsesTcgPlayerHistoryOldestFirst() {
        val json = """{"count":3,"result":[
            {"date":"2026-09-24","variants":[{"averageSalesPrice":"462.3","marketPrice":"470.95","quantity":"3","variant":"Normal"}]},
            {"date":"2026-09-23","variants":[{"averageSalesPrice":"0","marketPrice":"475.23","quantity":"0","variant":"Normal"}]},
            {"date":"2026-09-22","variants":[]}]}"""
        val points = TcgPlayerApi.parseHistory(json)
        assertEquals(listOf(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 24)), points.map { it.date })
        assertNull(points[0].avgSalePrice)
        assertEquals(475.23, points[0].marketPrice!!, 0.001)
        assertEquals(3, points[1].quantitySold)
        assertEquals(462.3, points[1].avgSalePrice!!, 0.001)
    }

    @Test
    fun dropsTodayEntryAlreadyCountedInWeeklyBucket() {
        fun day(date: String, qty: Int) =
            """{"date":"$date","variants":[{"averageSalesPrice":"10","marketPrice":"10","quantity":"$qty","variant":"Normal"}]}"""
        val weekly = """{"result":[${day("2026-09-24", 3)},${day("2026-09-21", 16)},${day("2026-09-14", 24)},${day("2026-09-07", 7)}]}"""
        assertEquals(listOf(7, 24, 16), TcgPlayerApi.parseHistory(weekly).map { it.quantitySold })

        val daily = """{"result":[${day("2026-09-24", 3)},${day("2026-09-23", 1)},${day("2026-09-22", 2)}]}"""
        assertEquals(3, TcgPlayerApi.parseHistory(daily).size)
    }

    @Test
    fun historyStatsWeightByVolume() {
        val d = LocalDate.of(2026, 9, 1)
        val points = listOf(
            PricePoint(d, 100.0, 110.0, 1),
            PricePoint(d.plusDays(1), null, 108.0, 0),
            PricePoint(d.plusDays(2), 90.0, 105.0, 3),
        )
        val stats = HistoryStats.of(points)
        assertEquals(1, stats.bucketDays)
        assertEquals(4, stats.totalSold)
        assertEquals(92.5, stats.avgSalePrice!!, 0.001) // (100*1 + 90*3) / 4
        assertEquals(4.0 / 3, stats.avgSold, 0.001)
        assertEquals(105.0, stats.latestMarket!!, 0.001)
        assertEquals(90.0, stats.low!!, 0.001)
        assertEquals(100.0, stats.high!!, 0.001)
    }

    @Test
    fun historyStatsDetectWeeklyBuckets() {
        val d = LocalDate.of(2026, 1, 1)
        val stats = HistoryStats.of(listOf(PricePoint(d, 10.0, 10.0, 7), PricePoint(d.plusDays(7), 10.0, 10.0, 7)))
        assertEquals(7, stats.bucketDays)
        assertEquals(1.0, stats.avgSold, 0.001)
    }

    @Test
    fun parsesPriceChartingPenniesAndMatchesSet() {
        val json = """{"status":"success","products":[
            {"id":"1","product-name":"Booster Box Case","console-name":"Pokemon Surging Sparks","loose-price":180000},
            {"id":"2","product-name":"Booster Box","console-name":"Pokemon Stellar Crown","loose-price":20000},
            {"id":"3","product-name":"Booster Box","console-name":"Pokemon Surging Sparks","loose-price":30400}]}"""
        val match = PriceChartingApi.parseBestMatch(json, "Surging Sparks", ProductKind.BOOSTER_BOX)!!
        assertEquals(304.0, match.price!!, 0.001)
        assertEquals("Pokemon Surging Sparks", match.consoleName)
    }

    @Test
    fun priceChartingMatchesAmpersandSetNames() {
        val json = """{"status":"success","products":[
            {"product-name":"Elite Trainer Box","console-name":"Pokemon Scarlet and Violet 151","loose-price":47000}]}"""
        assertEquals(470.0, PriceChartingApi.parseBestMatch(json, "151", ProductKind.ETB)!!.price!!, 0.001)
    }

    @Test
    fun priceChartingNoMatchIsNull() {
        val json = """{"status":"success","products":[{"product-name":"Booster Box","console-name":"Pokemon Other","loose-price":1}]}"""
        assertNull(PriceChartingApi.parseBestMatch(json, "Surging Sparks", ProductKind.BOOSTER_BOX))
    }

    @Test
    fun priceChartingErrorsSurfaceMessage() {
        val json = """{"status":"error","error-message":"Invalid access token"}"""
        val e = assertThrows(IllegalStateException::class.java) {
            PriceChartingApi.parseBestMatch(json, "Surging Sparks", ProductKind.BOOSTER_BOX)
        }
        assertEquals("Invalid access token", e.message)
        assertEquals("Invalid access token", PriceChartingApi.errorMessage(json))
    }
}
