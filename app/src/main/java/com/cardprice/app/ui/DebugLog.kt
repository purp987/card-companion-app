package com.cardprice.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/** "Card Companion 1.0 (2)" from the installed package. */
fun appVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    "${info.versionName} ($code)"
}.getOrDefault("unknown")

/**
 * Opens the share sheet with the scanner/crash log (previous and current file, with the app and phone
 * versions on top), so someone using a shared copy of the app can send it to whoever looks into problems.
 * Returns false when there's no log yet.
 */
fun shareDebugLog(context: Context): Boolean {
    val logs = File(context.filesDir, "logs")
    val parts = listOf(File(logs, "scan-log.1.txt"), File(logs, "scan-log.txt")).filter { it.exists() }
    if (parts.isEmpty()) return false
    val out = File(File(context.cacheDir, "share").apply { mkdirs() }, "card-companion-log.txt")
    out.writeText(
        "Card Companion ${appVersion(context)} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL}\n\n",
    )
    parts.forEach { out.appendText(it.readText()) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", out)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Card Companion debug log")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Send debug log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}
