package com.cardprice.app.data.cloud

import android.content.Context
import android.content.pm.ApplicationInfo
import com.cardprice.app.data.scan.ScanLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Beta only: sends what the app is doing to the backup server's live viewer (the control panel on the
 * server's PC). Screens, scanner activity, actions and warnings go out as events; totals (collection,
 * inventory, scanner state) as a snapshot. Everything is batched every few seconds and signed with this
 * phone's [DeviceKey], and only runs when the phone is paired and streaming is switched on.
 *
 * Release builds never stream: [start] does nothing unless the app is a debuggable (beta) build.
 */
object LiveStream {
    private const val SEND_EVERY_MS = 3_000L
    private const val MAX_QUEUE = 500

    private data class Event(val time: Long, val type: String, val text: String)

    private val queue = ConcurrentLinkedQueue<Event>()
    @Volatile private var snapshot: JSONObject? = null
    @Volatile private var snapshotDirty = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile var lastError: String? = null
        private set

    fun isBetaBuild(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Starts (or stops) streaming to match the phone's settings. */
    @Synchronized
    fun refresh(context: Context) {
        val account = CloudAccount(context.applicationContext)
        val on = isBetaBuild(context) && account.liveStreaming && account.deviceId != null && account.serverUrl != null
        if (!on) {
            job?.cancel()
            job = null
            ScanLog.listener = null
            queue.clear()
            return
        }
        if (job?.isActive == true) return
        ScanLog.listener = { warning, message -> event(if (warning) "warning" else if (message.startsWith("cloud:")) "log" else "scan", message) }
        event("action", "Live viewer connected")
        job = scope.launch {
            while (isActive) {
                delay(SEND_EVERY_MS)
                flush(account)
            }
        }
    }

    /** Adds an event (e.g. "screen" / "Collection · Pitch Black"). Cheap; safe from any thread. */
    fun event(type: String, text: String) {
        if (job == null && type != "action") return
        queue.add(Event(System.currentTimeMillis(), type, text.take(1900)))
        while (queue.size > MAX_QUEUE) queue.poll()
    }

    /** The latest totals, shown as cards in the viewer (sent with the next batch if they changed). */
    fun snapshot(values: Map<String, Any?>) {
        val o = JSONObject()
        values.forEach { (k, v) -> if (v != null) o.put(k, v) }
        if (o.toString() != snapshot?.toString()) {
            snapshot = o
            snapshotDirty = true
        }
    }

    private fun flush(account: CloudAccount) {
        val batch = generateSequence { queue.poll() }.take(200).toList()
        val snap = snapshot?.takeIf { snapshotDirty }
        if (batch.isEmpty() && snap == null) return
        val url = account.serverUrl ?: return
        val device = account.deviceId ?: return
        val events = JSONArray()
        batch.forEach { events.put(JSONObject().put("time", it.time).put("type", it.type).put("text", it.text)) }
        try {
            CloudApi(url, deviceId = device).sendLive(events, snap)
            if (snap != null) snapshotDirty = false
            lastError = null
        } catch (e: Exception) {
            // Keep the events for the next try (newest ones win if the queue fills up).
            batch.asReversed().forEach { queue.add(it) }
            lastError = e.message
        }
    }
}
