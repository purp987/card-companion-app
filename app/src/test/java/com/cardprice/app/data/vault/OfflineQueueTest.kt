package com.cardprice.app.data.vault

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeServer()
    private val api = server.api()
    private val resolveReq = ResolveRequest("en", "sv03.5", "151", "199", "Charizard ex")

    private fun resolveReply(score: Double = 0.97) =
        """{"matches":[{"id":517045,"name":"Charizard ex","is_sealed":0,"group_id":1,"set_name":"151","category_id":3,"variants":[{"id":1,"sub_type":"Normal","market":1},{"id":2,"sub_type":"Reverse Holofoil","market":2}],"score":$score}]}"""

    @Test
    fun flushSendsInOrderAndEmptiesTheQueue() {
        var next = 100
        server.json("POST", "/api/holdings") { """{"id":${next++}}""" }
        val q = PendingAdds(MemoryPendingStore())
        q.add(PendingAdd("a", 1, "A", qty = 1, productId = 10, variantId = 11))
        q.add(PendingAdd("b", 2, "B", qty = 3, productId = 20))
        val r = q.flush(api, { 7 }, { "2026-10-12" })
        assertEquals(2, r.sent)
        assertEquals(0, q.size)
        val bodies = server.calls.map { JSONObject(it.body!!) }
        assertEquals(listOf(10, 20), bodies.map { it.getInt("product_id") })
        assertEquals(7, bodies[0].getInt("portfolio_id"))
        assertEquals(3, bodies[1].getInt("qty"))
        assertEquals("2026-10-12", bodies[0].getString("added_on"))
    }

    @Test
    fun offlineStopsAndKeepsEverything() {
        val q = PendingAdds(MemoryPendingStore())
        q.add(PendingAdd("a", 1, "A", qty = 1, productId = 10))
        server.offline = true
        val r = q.flush(api, { 1 }, { "d" })
        assertTrue(r.stoppedBy is VaultException.Offline)
        assertEquals(1, q.size)
        assertNull(q.all().single().error)
    }

    @Test
    fun unresolvedScansAreLookedUpWhenSent() {
        server.json("POST", "/api/app/resolve") { resolveReply() }
        server.json("POST", "/api/holdings") { """{"id":1}""" }
        val q = PendingAdds(MemoryPendingStore())
        q.add(PendingAdd("a", 1, "Charizard ex", qty = 1, variantKey = "reverse", resolve = resolveReq))
        q.flush(api, { 1 }, { "d" })
        val add = JSONObject(server.calls.last().body!!)
        assertEquals(517045, add.getInt("product_id"))
        assertEquals(2, add.getInt("variant_id")) // "reverse" → Reverse Holofoil
        assertEquals(0, q.size)
    }

    @Test
    fun unsureMatchesStayQueuedWithAReason() {
        server.json("POST", "/api/app/resolve") { resolveReply(score = 0.4) }
        val q = PendingAdds(MemoryPendingStore())
        q.add(PendingAdd("a", 1, "Mystery", qty = 1, resolve = resolveReq))
        q.add(PendingAdd("b", 1, "Known", qty = 1, productId = 5))
        server.json("POST", "/api/holdings") { """{"id":1}""" }
        val r = q.flush(api, { 1 }, { "d" })
        assertEquals(1, r.sent)
        assertEquals(1, r.failed)
        assertEquals("No match in TCG Vault", q.all().single().error)
        q.clearFailed()
        assertEquals(0, q.size)
    }

    @Test
    fun queueSurvivesRestartOnDisk() {
        val file = File(tmp.root, "vault/pending-adds.json")
        PendingAdds(FilePendingStore(file)).add(PendingAdd("a", 5, "Pikachu", qty = 2, variantKey = "holo", resolve = resolveReq, notes = "Scanned"))
        val reloaded = PendingAdds(FilePendingStore(file)).all().single()
        assertEquals(2, reloaded.qty)
        assertEquals("holo", reloaded.variantKey)
        assertEquals("sv03.5", reloaded.resolve!!.setCode)
        assertNull(reloaded.productId)
    }

    // ---- scanner sync

    private fun scanSync(queue: PendingAdds = PendingAdds(MemoryPendingStore())) =
        ScanSync(api, queue, portfolio = { 1 }, today = { "2026-10-12" })

    private val target = ScanTarget("ENGLISH|sv03.5|sv03.5-199", resolveReq, "Charizard ex")

    @Test
    fun scannerAddResolvesThenPostsAndUndoDeletes() = runBlocking {
        server.json("POST", "/api/app/resolve") { resolveReply() }
        server.json("GET", "/api/portfolio") { """{"holdings":[{"id":9,"portfolio_id":1,"product_id":517045,"variant_id":2,"qty":1}],"summary":{},"breakdown":{}}""" }
        server.json("POST", "/api/holdings") { """{"id":50}""" }
        server.json("DELETE", "/api/holdings/50") { """{"ok":true}""" }
        val sync = scanSync()
        sync.change(target, "reverse", 1)
        // One copy already owned + the one just added.
        assertEquals(2, sync.count(target, "reverse"))
        val post = server.calls.first { it.method == "POST" && it.path == "/api/holdings" }
        assertEquals(2, JSONObject(post.body!!).getInt("variant_id"))
        assertEquals(SyncNote.Kind.SAVED, sync.note.value!!.kind)

        sync.change(target, "reverse", -1)
        assertTrue(server.calls.any { it.method == "DELETE" && it.path == "/api/holdings/50" })
        assertEquals(1, sync.count(target, "reverse"))
    }

    @Test
    fun plusOneThenUndoReducesTheSameHolding() = runBlocking {
        server.json("POST", "/api/app/resolve") { resolveReply() }
        server.json("GET", "/api/portfolio") { """{"holdings":[],"summary":{},"breakdown":{}}""" }
        server.json("POST", "/api/holdings") { """{"id":60}""" }
        server.json("PUT", "/api/holdings/60") { """{"ok":true}""" }
        server.json("DELETE", "/api/holdings/") { """{"ok":true}""" }
        val sync = scanSync()
        sync.change(target, "normal", 1)
        sync.change(target, "normal", 1)
        sync.change(target, "normal", -2)
        assertEquals(0, sync.count(target, "normal"))
        assertEquals(2, server.calls.count { it.method == "DELETE" })
    }

    @Test
    fun offlineScanIsQueuedAndUndoTakesItBack() = runBlocking {
        val queue = PendingAdds(MemoryPendingStore())
        val sync = scanSync(queue)
        server.offline = true
        sync.change(target, "holo", 1)
        assertEquals(1, queue.size)
        val queued = queue.all().single()
        assertNull(queued.productId)
        assertEquals(resolveReq, queued.resolve)
        assertEquals(SyncNote.Kind.QUEUED, sync.note.value!!.kind)
        assertEquals(1, sync.count(target, "holo"))

        sync.change(target, "holo", -1)
        assertEquals(0, queue.size)
        assertEquals(0, sync.count(target, "holo"))
    }

    @Test
    fun serverWithoutAMatchAddsNothing() = runBlocking {
        server.json("POST", "/api/app/resolve") { """{"matches":[]}""" }
        val queue = PendingAdds(MemoryPendingStore())
        val sync = scanSync(queue)
        sync.change(target, "normal", 1)
        assertEquals(0, queue.size)
        assertEquals(0, sync.count(target, "normal"))
        assertEquals(SyncNote.Kind.PROBLEM, sync.note.value!!.kind)
        assertTrue(server.calls.none { it.path == "/api/holdings" })
    }
}
