package com.cardprice.app.data.vault

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class VaultApiTest {
    private val server = FakeServer()
    private val api = server.api()

    // Trimmed real replies from the live server (GET only).
    private val searchJson = """{"items":[{"id":671250,"name":"Mega Charizard Tin (Mega Charizard X)","image_url":"https://tcgplayer-cdn.tcgplayer.com/product/671250_200w.jpg","number":null,"rarity":null,"is_sealed":1,"price":45.67,"price_7d":null,"group_id":2374,"set_name":"Miscellaneous Cards & Products","category_id":3,"category_name":"Pokemon","sub_type":"Normal"}],"total":691}"""

    private val productJson = """{"id":517045,"name":"Charizard ex - 199/165","image_url":"https://tcgplayer-cdn.tcgplayer.com/product/517045_200w.jpg","number":"199/165","rarity":"Special Illustration Rare","is_sealed":0,"price":321.7,"price_7d":null,"group_id":23237,"set_name":"SV: Scarlet & Violet 151","category_id":3,"category_name":"Pokemon","sub_type":"Holofoil","url":"https://www.tcgplayer.com/product/517045","ext":[{"name":"HP","displayName":"HP","value":"330"}],"released_on":"2023-09-22T00:00:00","primary_variant_id":7860,"set_abbr":"MEW","variants":[{"id":7860,"sub_type":"Holofoil","market":321.7,"low":295,"mid":360,"high":10000,"direct_low":326.86}],"watched":false,"holdings":[],"cardmarket":{"product_id":517045,"trend":355.73,"avg":370.41,"low":109.98,"avg1":299.5,"avg7":351.02,"avg30":389.88,"trend_holo":null,"day":"2026-10-11","match_status":"ok"},"graded":[{"grade_key":"psa10","grader":"PSA","grade":"10","price":1310,"confidence":"medium","median":1500,"sales":554,"last_sale":"2026-10-08","trend":"up"}],"graded_fetch":{"fetched_at":1791750926146,"status":"ok","total_sales":2606},"api_credits":{"remaining":72,"limit":100},"ebay_sales":[{"id":"123","sold_date":"2026-10-01","price":300,"price_is_range":0,"title":"Charizard ex 199/165 PSA 10","condition":null,"kind":"graded","grader":"PSA","grade":"10","quantity":1,"confidence":0.9}]}"""

    private val portfolioJson = """{"holdings":[{"id":17,"portfolio_id":1,"product_id":696683,"variant_id":24,"qty":2,"condition":"Near Mint","grade":null,"purchase_price":null,"custom_value":null,"added_on":"2026-10-12","notes":null,"name":"Lapras - 131/128","image_url":"https://tcgplayer-cdn.tcgplayer.com/product/696683_200w.jpg","number":"131/128","rarity":"Illustration Rare","is_sealed":0,"group_id":24722,"set_name":"ME: 30th Celebration","category_name":"Pokemon","sub_type":"Holofoil","market":5.78,"product_price":5.78,"portfolio_name":"Main","kind":"cards","unit_value":5.78,"value_source":"market","value":11.56,"cost":null}],"summary":{"value":11.56,"cost":0,"costCoveredValue":0,"items":2},"breakdown":{"cards":{"count":2,"value":11.56},"sealed":{"count":0,"value":0},"graded":{"count":0,"value":0}}}"""

    @Test
    fun searchBuildsQueryAndParsesItems() {
        server.json("GET", "/api/search") { searchJson }
        val page = api.search(q = "charizard tin", cat = 3, type = "sealed", sort = SearchSort.PRICE_DESC, minPrice = 5.0, limit = 2)
        val call = server.calls.single()
        assertEquals("/api/search?q=charizard+tin&cat=3&type=sealed&sort=price_desc&minPrice=5&limit=2", call.path)
        assertEquals("Bearer tok", call.headers["Authorization"])
        assertEquals(691, page.total)
        val item = page.items.single()
        assertTrue(item.isSealed)
        assertEquals(45.67, item.price!!, 0.001)
        assertNull(item.number)
        assertEquals("Miscellaneous Cards & Products", item.setName)
    }

    @Test
    fun productDetailParsesEverySection() {
        server.json("GET", "/api/products/517045") { productJson }
        val p = api.product(517045)
        assertEquals(7860L, p.primaryVariantId)
        assertEquals("Holofoil", p.variants.single().subType)
        assertEquals(295.0, p.variants.single().low!!, 0.001)
        assertEquals(355.73, p.cardmarket!!.trend!!, 0.001)
        assertEquals("PSA 10", p.graded.single().label)
        assertEquals(72, p.creditsRemaining)
        assertEquals("PSA 10", p.ebaySales.single().label)
        assertEquals("HP", p.ext.single().name)
        assertFalse(p.watched)
        assertNull(p.jpTwin)
    }

    @Test
    fun historyAndPortfolioParse() {
        server.json("GET", "/api/products/1/history") { """{"variant":7860,"range":"1m","series":[{"d":"2026-10-10","v":322.94},{"d":"2026-10-11","v":321.19}],"cardmarket":[{"d":"2026-10-10","v":418.51}]}""" }
        server.json("GET", "/api/portfolio?") { portfolioJson }
        val h = api.history(1, PriceRange.W2, variantId = 7860)
        assertEquals("/api/products/1/history?range=14d&variant=7860", server.calls.last().path)
        assertEquals(2, h.series.size)
        assertEquals(418.51, h.cardmarket.single().value, 0.001)
        val v = api.portfolio(1)
        assertEquals("/api/portfolio?pid=1", server.calls.last().path)
        assertEquals(11.56, v.summary.value, 0.001)
        assertEquals(2, v.cards.count)
        assertNull(v.summary.gain)
        assertEquals("cards", v.holdings.single().kind)
    }

    @Test
    fun homeSplitsProductAndSetRows() {
        server.json("GET", "/api/home") {
            """{"asOf":"2026-10-12","historyDays":2,"window":"1D","sections":[
              {"id":"gainers","kind":"products","title":"Rising Stars","subtitle":"x","link":"#/?sort=gain&minPrice=5","items":[{"id":1,"name":"A","is_sealed":0,"price":5,"group_id":2,"set_name":"S","category_id":3,"change_pct":0.1,"change_window":"1D"}]},
              {"id":"latest-sets","kind":"sets","title":"Fresh Off the Press","link":"#/sets","items":[{"id":24837,"name":"ME: 30th","abbreviation":"30C","card_count":30,"sealed_count":0,"logo_url":"https://x/logo"}]}]}"""
        }
        val feed = api.home()
        assertEquals("1D", feed.window)
        assertEquals(0.1, feed.sections[0].products.single().changePct!!, 1e-9)
        assertEquals(24837L, feed.sections[1].sets.single().id)
        assertTrue(feed.sections[1].products.isEmpty())
    }

    @Test
    fun holdingAndWatchlistWritesSendTheContractBodies() {
        server.json("POST", "/api/holdings") { """{"id":42}""" }
        server.json("PUT", "/api/watchlist/5") { """{"ok":true}""" }
        val id = api.addHolding(HoldingInput(portfolioId = 1, productId = 5, variantId = null, qty = 2, condition = "Near Mint", addedOn = "2026-10-12"))
        assertEquals(42L, id)
        val body = JSONObject(server.calls.last().body!!)
        assertEquals(1, body.getInt("portfolio_id"))
        assertEquals(5, body.getInt("product_id"))
        assertTrue(body.isNull("variant_id"))
        assertEquals(2, body.getInt("qty"))
        assertEquals("2026-10-12", body.getString("added_on"))
        api.setWatched(5, true)
        assertEquals("PUT", server.calls.last().method)
    }

    @Test
    fun pairSendsNoTokenAndParsesReply() {
        server.json("POST", "/api/app/pair") { """{"token":"${"a".repeat(64)}","deviceId":3,"serverName":"TCG Vault"}""" }
        val r = api.pair("123456", "Galaxy S25 Ultra")
        assertNull(server.calls.last().headers["Authorization"])
        assertEquals("123456", JSONObject(server.calls.last().body!!).getString("code"))
        assertEquals(3L, r.deviceId)
        assertEquals(64, r.token.length)
    }

    @Test
    fun resolveAndImportFollowTheContract() {
        server.json("POST", "/api/app/resolve") {
            """{"matches":[{"id":517045,"name":"Charizard ex - 199/165","is_sealed":0,"price":321.7,"group_id":23237,"set_name":"SV: 151","category_id":3,"variants":[{"id":7860,"sub_type":"Holofoil","market":321.7}],"score":0.97,"how":"set+number"}]}"""
        }
        server.json("POST", "/api/app/import") {
            """{"imported":0,"updated":0,"dryRun":true,"matched":[{"ref":"a","productId":517045,"score":0.97,"how":"set+number"}],"unmatched":[{"ref":"b","reason":"no product for set+number"}],"portfolioId":null}"""
        }
        val m = api.resolve(ResolveRequest("en", "sv03.5", "151", "199", "Charizard ex")).single()
        assertEquals(0.97, m.score, 1e-9)
        assertEquals(7860L, m.variants.single().id)
        val req = JSONObject(server.calls.last().body!!)
        assertEquals("sv03.5", req.getString("setCode"))
        assertEquals(5, req.getInt("limit"))

        val item = ImportItem("a", "en", "sv03.5", "151", "199", "Charizard ex", "Holofoil", 1, null, null, null, null, null, false, null)
        val r = api.import("Collection", listOf(item), dryRun = true)
        val body = JSONObject(server.calls.last().body!!)
        assertTrue(body.getBoolean("dryRun"))
        assertEquals("Collection", body.getString("portfolio"))
        assertEquals("Holofoil", body.getJSONArray("items").getJSONObject(0).getString("variant"))
        assertTrue(r.dryRun)
        assertNull(r.portfolioId)
        assertEquals(517045L, r.matched.single().productId)
        assertEquals("no product for set+number", r.unmatched.single().reason)
    }

    @Test
    fun appRoutesNeedAToken() {
        val unpaired = server.api(token = null)
        assertThrows(VaultException.Unauthorized::class.java) { unpaired.me() }
        assertTrue(server.calls.isEmpty()) // never sent
    }

    @Test
    fun errorsMapToTypedExceptions() {
        server.json("GET", "/api/app/me", code = 401) { """{"error":"Device revoked"}""" }
        server.json("POST", "/api/app/pair", code = 429) { """{"error":"Too many attempts"}""" }
        server.json("GET", "/api/products/9", code = 500) { "boom" }
        assertEquals("Device revoked", assertThrows(VaultException.Unauthorized::class.java) { api.me() }.message)
        assertThrows(VaultException.RateLimited::class.java) { api.pair("1", "x") }
        assertEquals(500, assertThrows(VaultException.Http::class.java) { api.product(9) }.code)
        // An older server without the app routes answers 404.
        val e = assertThrows(VaultException.NotFound::class.java) { api.resolve(ResolveRequest("en", null, null, "1", null)) }
        assertTrue(e.message!!.contains("doesn't support the app"))
        server.offline = true
        assertThrows(VaultException.Offline::class.java) { api.home() }
    }

    @Test
    fun baseUrlsAreNormalized() {
        assertEquals("https://desktop.ts.net:8443", VaultApi.normalizeBaseUrl("desktop.ts.net:8443/"))
        assertEquals("http://localhost:3000", VaultApi.normalizeBaseUrl(" http://localhost:3000/api "))
        assertNull(VaultApi.normalizeBaseUrl(""))
        assertThrows(VaultException.NotConfigured::class.java) { VaultApi({ null }, { null }, server).home() }
    }

    /** The real HttpURLConnection transport against a tiny local HTTP server (no internet). */
    @Test
    fun urlConnectionTransportTalksHttp() {
        val socket = ServerSocket(0)
        val seen = mutableListOf<Pair<String, String>>() // request head, body
        val worker = thread {
            repeat(2) {
                socket.accept().use { s ->
                    val input = s.getInputStream().bufferedReader()
                    val head = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val length = head.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val body = CharArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }.concatToString()
                    seen.add(head.joinToString("\n") to body)
                    val (status, reply) = if (head.first().contains("/api/app/me")) "401 Unauthorized" to """{"error":"Unknown device"}""" else "200 OK" to """{"id":7}"""
                    val bytes = reply.toByteArray()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        try {
            val real = VaultApi({ "http://127.0.0.1:${socket.localPort}" }, { "secret" })
            assertEquals(7L, real.addHolding(HoldingInput(1, 2, null, 1)))
            assertEquals("Unknown device", assertThrows(VaultException.Unauthorized::class.java) { real.me() }.message)
            worker.join(5000)
            val (head, body) = seen.first()
            assertTrue(head.startsWith("POST /api/holdings"))
            assertTrue(head.lines().any { it.equals("Authorization: Bearer secret", ignoreCase = true) })
            assertEquals(2, JSONObject(body).getInt("product_id"))
        } finally {
            socket.close()
        }
    }
}
