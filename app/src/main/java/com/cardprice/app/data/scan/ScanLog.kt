package com.cardprice.app.data.scan

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A plain-text log of what the scanner does (readings, lookups, decisions, camera health), kept in the
 * app's files ("logs/scan-log.txt", with the previous file as "scan-log.1.txt") so problems like a stuck
 * scanner can be looked at afterwards. Lines are also sent to Logcat under the "CardScan" tag.
 * Writing happens on one background thread, so logging never slows the camera down.
 */
object ScanLog {
    private const val TAG = "CardScan"
    private const val MAX_BYTES = 512 * 1024L
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "scan-log").apply { isDaemon = true } }
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    /** Also told about every line (the beta's live viewer listens here). */
    @Volatile var listener: ((warning: Boolean, message: String) -> Unit)? = null

    /** Call once with the app's files directory; until then lines only go to Logcat. */
    fun init(filesDir: File) {
        if (file != null) return
        file = File(File(filesDir, "logs").apply { mkdirs() }, "scan-log.txt")
    }

    /**
     * Records crashes in the log (with the stack trace) before the app closes, so a crash on someone
     * else's phone can be looked into from their shared log.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previous))
    }

    private class CrashHandler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            // Written directly: the background writer may not get to run before the process ends.
            runCatching {
                file?.appendText("${time.format(Date())} E [${thread.name}] CRASH ${Log.getStackTraceString(error)}\n")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun d(message: String) = write("D", message)

    fun w(message: String, error: Throwable? = null) =
        write("W", if (error == null) message else "$message: ${error.javaClass.simpleName}: ${error.message}")

    private fun write(level: String, message: String) {
        if (level == "W") Log.w(TAG, message) else Log.d(TAG, message)
        listener?.let { runCatching { it(level == "W", message) } }
        val target = file ?: return
        val line = "${time.format(Date())} $level [${Thread.currentThread().name}] $message\n"
        writer.execute {
            runCatching {
                if (target.length() > MAX_BYTES) {
                    val old = File(target.parentFile, "scan-log.1.txt")
                    old.delete()
                    target.renameTo(old)
                }
                target.appendText(line)
            }
        }
    }
}
