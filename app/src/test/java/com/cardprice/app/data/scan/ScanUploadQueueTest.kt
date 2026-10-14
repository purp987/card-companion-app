package com.cardprice.app.data.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ScanUploadQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private val device = ScanLogDevice("Samsung", "SM-S938U", "15", "2.4-beta", 11)

    /** Records what the server was asked to store, and can be told to fail. */
    private class FakeSink(var fail: Boolean = false) : ScanLogSink {
        val sent = mutableListOf<Pair<String, List<ScanLogEvent>>>()
        override fun send(session: String, device: ScanLogDevice, events: List<ScanLogEvent>) {
            if (fail) throw IOException("offline")
            sent += session to events
        }
    }

    private fun ev(session: String, seq: Long, msg: String, level: String = "D") =
        ScanLogEvent(session, seq, 1_000 + seq, level, msg)

    @Test
    fun flushSendsEverythingThenEmptiesTheQueue() {
        val file = File(tmp.root, "q.ndjson")
        val sink = FakeSink()
        val q = ScanUploadQueue(file, device, sink)
        q.add(ev("s1", 0, "scanner opened"))
        q.add(ev("s1", 1, "reading: MEP 073"))

        assertTrue(q.flush())
        assertEquals(1, sink.sent.size)
        assertEquals("s1", sink.sent[0].first)
        assertEquals(listOf(0L, 1L), sink.sent[0].second.map { it.seq })
        assertEquals("", file.readText()) // cleared on success

        // Nothing left to send.
        sink.sent.clear()
        assertTrue(q.flush())
        assertTrue(sink.sent.isEmpty())
    }

    @Test
    fun failedFlushKeepsTheQueueForRetry() {
        val file = File(tmp.root, "q.ndjson")
        val sink = FakeSink(fail = true)
        val q = ScanUploadQueue(file, device, sink)
        q.add(ev("s1", 0, "reading: A 1"))

        assertFalse(q.flush())
        assertTrue(file.readText().isNotBlank()) // kept

        // Back online: the same events go up, in order, and the queue clears.
        sink.fail = false
        assertTrue(q.flush())
        assertEquals(listOf(0L), sink.sent.single().second.map { it.seq })
        assertEquals("", file.readText())
    }

    @Test
    fun leftoverFromAPreviousSessionUploadsSeparately() {
        val file = File(tmp.root, "q.ndjson")
        val sink = FakeSink()
        val q = ScanUploadQueue(file, device, sink)
        // A previous process's session left an unsent line; this process adds its own.
        q.add(ev("old", 5, "lookup done in 9 ms: X 1 -> nothing (LOW)"))
        q.add(ev("new", 0, "scanner opened"))

        assertTrue(q.flush())
        assertEquals(setOf("old", "new"), sink.sent.map { it.first }.toSet())
        assertEquals(2, sink.sent.size) // one batch per session
    }

    @Test
    fun bodyHasSessionDeviceAndEvents() {
        val body = ScanUploadQueue.body("s1", device, listOf(ev("s1", 0, "hi", level = "W")))
        assertEquals("s1", body.getString("session"))
        assertEquals("SM-S938U", body.getJSONObject("device").getString("model"))
        assertEquals(11L, body.getJSONObject("app").getLong("versionCode"))
        val e = body.getJSONArray("events").getJSONObject(0)
        assertEquals(0L, e.getLong("seq"))
        assertEquals("W", e.getString("level"))
        assertEquals("hi", e.getString("message"))
    }

    @Test
    fun corruptLinesAreSkippedNotFatal() {
        val file = File(tmp.root, "q.ndjson")
        file.writeText("not json\n" + JSONObject().put("s", "s1").put("q", 0).put("t", 1).put("l", "D").put("m", "ok").toString() + "\n")
        val sink = FakeSink()
        val q = ScanUploadQueue(file, device, sink)
        assertTrue(q.flush())
        assertEquals("ok", sink.sent.single().second.single().message)
    }
}
