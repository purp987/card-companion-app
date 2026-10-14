package com.cardprice.app.data.scan

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** One scanner-log event queued for upload. */
data class ScanLogEvent(val session: String, val seq: Long, val at: Long, val level: String, val message: String)

/** Where the device/app it came from, sent once per batch. */
data class ScanLogDevice(val manufacturer: String, val model: String, val android: String, val appVersion: String, val appVersionCode: Long)

/** Posts one session's events; must throw on failure so the queue is kept and retried. */
fun interface ScanLogSink { fun send(session: String, device: ScanLogDevice, events: List<ScanLogEvent>) }

/**
 * A durable, file-backed queue of scanner-log events waiting to go to the server. Appending and flushing
 * are both synchronous and not thread-safe on their own — [ScanLogUploader] serializes them on one thread.
 * The file survives a crash or force-stop, so leftovers upload on the next launch; each line carries its
 * own session id, so the server groups them and de-dupes by session + sequence number.
 */
internal class ScanUploadQueue(
    private val file: File,
    private val device: ScanLogDevice,
    private val sink: ScanLogSink,
    private val maxBatch: Int = 500,
    private val maxBytes: Long = 2L * 1024 * 1024,
) {
    fun add(e: ScanLogEvent) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() > maxBytes) trim()
            file.appendText(line(e) + "\n")
        }
    }

    /** Uploads everything queued, grouped by session. Keeps the file untouched if any batch fails. */
    fun flush(): Boolean {
        val events = runCatching { read() }.getOrDefault(emptyList())
        if (events.isEmpty()) return true
        var allSent = true
        outer@ for ((session, group) in events.groupBy { it.session }) {
            for (batch in group.chunked(maxBatch)) {
                if (!runCatching { sink.send(session, device, batch) }.isSuccess) {
                    allSent = false
                    break@outer
                }
            }
        }
        if (allSent) runCatching { file.writeText("") }
        return allSent
    }

    private fun read(): List<ScanLogEvent> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { raw ->
            if (raw.isBlank()) return@mapNotNull null
            runCatching {
                val o = JSONObject(raw)
                ScanLogEvent(o.getString("s"), o.getLong("q"), o.optLong("t"), o.optString("l", "D"), o.optString("m"))
            }.getOrNull()
        }
    }

    /** Drops the oldest half when the queue gets too big (a long offline stretch). */
    private fun trim() {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.size < 2) return
        file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
    }

    private fun line(e: ScanLogEvent): String =
        JSONObject().put("s", e.session).put("q", e.seq).put("t", e.at).put("l", e.level).put("m", e.message).toString()

    companion object {
        /** The request body the server expects (see docs/app-api.md). */
        fun body(session: String, device: ScanLogDevice, events: List<ScanLogEvent>): JSONObject {
            val arr = JSONArray()
            events.forEach { e -> arr.put(JSONObject().put("seq", e.seq).put("at", e.at).put("level", e.level).put("message", e.message)) }
            return JSONObject()
                .put("session", session)
                .put("device", JSONObject().put("manufacturer", device.manufacturer).put("model", device.model).put("android", device.android))
                .put("app", JSONObject().put("version", device.appVersion).put("versionCode", device.appVersionCode))
                .put("events", arr)
        }
    }
}

/**
 * Sends the scanner's log to the TCG Vault server (beta builds only), so scanning problems can be looked
 * into and the readings that fail to match can be mined to make detection more accurate. It listens to
 * [ScanLog], queues every line, and flushes batches on a timer and when the scanner closes. Everything
 * runs on one background thread, so logging never blocks the camera and the queue file is never appended
 * to and cleared at the same time.
 */
object ScanLogUploader {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "scan-upload").apply { isDaemon = true } }
    private val session = UUID.randomUUID().toString()
    private val seq = AtomicLong(0)
    @Volatile private var queue: ScanUploadQueue? = null
    @Volatile private var started = false

    private val listener: (Boolean, String) -> Unit = { warning, message ->
        queue?.let { q ->
            val e = ScanLogEvent(session, seq.getAndIncrement(), System.currentTimeMillis(), if (warning) "W" else "D", message)
            worker.execute { q.add(e) }
        }
    }

    /** Wires the uploader to [ScanLog]. Safe to call more than once. */
    fun start(filesDir: File, device: ScanLogDevice, sink: ScanLogSink) {
        if (started) return
        started = true
        queue = ScanUploadQueue(File(File(filesDir, "logs").apply { mkdirs() }, "scan-upload.ndjson"), device, sink)
        ScanLog.addListener(listener)
    }

    /** Uploads everything queued (does nothing if the server can't be reached; the queue is kept). */
    fun flush() {
        val q = queue ?: return
        worker.execute { q.flush() }
    }

    /** Body the server expects, exposed so the production sink can build it. */
    fun body(session: String, device: ScanLogDevice, events: List<ScanLogEvent>): JSONObject =
        ScanUploadQueue.body(session, device, events)

    // Kept so callers don't have to import the data classes separately.
    fun deviceInfo(manufacturer: String, model: String, android: String, appVersion: String, appVersionCode: Long) =
        ScanLogDevice(manufacturer, model, android, appVersion, appVersionCode)
}
