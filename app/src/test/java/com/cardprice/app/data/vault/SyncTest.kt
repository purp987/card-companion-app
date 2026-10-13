package com.cardprice.app.data.vault

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTest {
    private var state = SyncState(10, 2, 5, "2026-10-12")
    private var error: VaultException? = null
    private var calls = 0
    private val monitor = SyncMonitor {
        calls++
        error?.let { throw it }
        state
    }

    @Test
    fun firstAnswerIsABaselineThenNothingChanged() {
        assertEquals(PollResult.Baseline, monitor.poll(1))
        assertEquals(PollResult.Unchanged, monitor.poll(2))
        assertEquals(2L, monitor.lastOkAt)
    }

    @Test
    fun eachRevMapsToItsSignal() {
        monitor.poll()
        state = state.copy(holdingsRev = 11)
        assertEquals(PollResult.Changed(SyncSignals(holdings = true)), monitor.poll())
        state = state.copy(portfoliosRev = 3, watchlistRev = 6)
        assertEquals(PollResult.Changed(SyncSignals(portfolios = true, watchlist = true)), monitor.poll())
        state = state.copy(pricesDay = "2026-10-13")
        assertEquals(PollResult.Changed(SyncSignals(prices = true)), monitor.poll())
        assertEquals(PollResult.Unchanged, monitor.poll())
    }

    @Test
    fun offlineBacksOffAndRecoversWithoutLosingTheBaseline() {
        monitor.poll()
        assertEquals(SyncMonitor.INTERVAL_MS, monitor.nextDelayMs())
        error = VaultException.Offline("down")
        assertTrue(monitor.poll() is PollResult.Offline)
        assertEquals(40_000L, monitor.nextDelayMs())
        monitor.poll(); monitor.poll()
        assertEquals(160_000L, monitor.nextDelayMs())
        repeat(5) { monitor.poll() }
        assertEquals(SyncMonitor.MAX_BACKOFF_MS, monitor.nextDelayMs())
        // Back online, and holdings changed while away: one signal, normal interval.
        error = null
        state = state.copy(holdingsRev = 12)
        assertEquals(PollResult.Changed(SyncSignals(holdings = true)), monitor.poll())
        assertEquals(SyncMonitor.INTERVAL_MS, monitor.nextDelayMs())
    }

    @Test
    fun olderServerStopsPollingQuietly() {
        error = VaultException.NotFound("Cannot GET /api/app/sync", "/api/app/sync")
        assertEquals(PollResult.Unsupported, monitor.poll())
        assertTrue(monitor.unsupported)
        error = null
        assertEquals(PollResult.Unsupported, monitor.poll())
        assertEquals(1, calls) // never asked again
        monitor.reset()
        assertEquals(PollResult.Baseline, monitor.poll())
    }

    @Test
    fun revokedTokenReportsUnauthorized() {
        error = VaultException.Unauthorized("revoked")
        assertEquals(PollResult.Unauthorized, monitor.poll())
        assertFalse(monitor.unsupported)
    }

    @Test
    fun syncEndpointIsParsed() {
        val server = FakeServer().json("GET", "/api/app/sync") {
            """{"holdingsRev":42,"portfoliosRev":3,"watchlistRev":7,"pricesDay":"2026-10-12","lastSync":"2026-10-12T20:05:00Z","serverTime":"2026-10-13T08:00:00Z"}"""
        }
        val s = server.api().sync()
        assertEquals(SyncState(42, 3, 7, "2026-10-12", "2026-10-12T20:05:00Z", "2026-10-13T08:00:00Z"), s)
        assertEquals("Bearer tok", server.calls.single().headers["Authorization"])
    }

    // ---- scanner counts follow the server

    private val resolveReq = ResolveRequest("en", "sv03.5", "151", "199", "Charizard ex")
    private val target = ScanTarget("ENGLISH|sv03.5|sv03.5-199", resolveReq, "Charizard ex")
    private val resolveReply =
        """{"matches":[{"id":517045,"name":"Charizard ex","is_sealed":0,"group_id":1,"set_name":"151","category_id":3,"variants":[{"id":2,"sub_type":"Holofoil","market":300}],"score":0.97}]}"""

    private fun portfolio(qty: Int) =
        if (qty == 0) """{"holdings":[],"summary":{},"breakdown":{}}"""
        else """{"holdings":[{"id":9,"portfolio_id":1,"product_id":517045,"variant_id":2,"qty":$qty}],"summary":{},"breakdown":{}}"""

    @Test
    fun countsAreReloadedWhenHoldingsChangeElsewhere() = runBlocking {
        var serverQty = 3
        val server = FakeServer()
            .json("POST", "/api/app/resolve") { resolveReply }
            .on("GET", "/api/portfolio") { VaultResponse(200, portfolio(serverQty)) }
        val sync = ScanSync(server.api(), PendingAdds(MemoryPendingStore()), { 1 }, { "d" })
        sync.prefetch(target, listOf("holo"))
        assertEquals(3, sync.count(target, "holo"))
        // Two duplicates removed on the server; without a refresh the cached portfolio would still say 3.
        serverQty = 1
        sync.refreshFromServer()
        assertEquals(1, sync.count(target, "holo"))
        val portfolioLoads = server.calls.count { it.path.startsWith("/api/portfolio") }
        assertEquals(2, portfolioLoads) // cache dropped and reloaded once
    }

    @Test
    fun refreshKeepsScansWaitingOfflineWithoutDoubleCounting() = runBlocking {
        var serverQty = 1
        val server = FakeServer()
            .json("POST", "/api/app/resolve") { resolveReply }
            .on("GET", "/api/portfolio") { VaultResponse(200, portfolio(serverQty)) }
            .json("POST", "/api/holdings") { """{"id":70}""" }
        val queue = PendingAdds(MemoryPendingStore())
        val sync = ScanSync(server.api(), queue, { 1 }, { "d" })
        sync.prefetch(target, listOf("holo"))
        // One copy saved on the server (it now has 2), then the server goes away and one is queued.
        sync.change(target, "holo", 1)
        serverQty = 2
        server.offline = true
        sync.change(target, "holo", 1)
        assertEquals(1, queue.size)
        assertEquals(3, sync.count(target, "holo"))
        // Back online, before the queue is sent: server 2 + 1 waiting = 3, not 4.
        server.offline = false
        sync.refreshFromServer()
        assertEquals(3, sync.count(target, "holo"))
        // The queue is sent; the server now has all 3 and nothing is waiting.
        queue.flush(server.api(), { 1 }, { "d" })
        serverQty = 3
        sync.refreshFromServer()
        assertEquals(3, sync.count(target, "holo"))
    }
}
