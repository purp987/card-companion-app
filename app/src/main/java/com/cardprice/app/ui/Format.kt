package com.cardprice.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Date
import java.util.Locale

/**
 * The currency picked in Settings and its rate from TCG Vault's `/api/rates` (USD base). Compose state,
 * so prices on screen update when it changes.
 */
object CurrencyState {
    var code by mutableStateOf("USD")
    var rate by mutableStateOf(1.0)

    fun set(code: String, rate: Double?) {
        this.code = code
        this.rate = rate ?: 1.0
    }

    fun fromUsd(usd: Double): Double = usd * rate
    fun toUsd(amount: Double): Double = if (rate == 0.0) amount else amount / rate
}

/** Formatters are costly to create and every price tile needs one, so they're made once per currency. */
private val formatters = java.util.concurrent.ConcurrentHashMap<String, ThreadLocal<NumberFormat>>()

private fun formatter(code: String, fraction: Int? = null): NumberFormat =
    formatters.getOrPut("$code|$fraction") {
        ThreadLocal.withInitial {
            NumberFormat.getCurrencyInstance().apply {
                runCatching { currency = Currency.getInstance(code) }
                if (fraction != null) {
                    minimumFractionDigits = fraction
                    maximumFractionDigits = fraction
                }
            }
        }
    }.get()!!

private val longDay = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val shortDay = DateTimeFormatter.ofPattern("MMM d")

/** An amount already in the chosen currency (what you typed in the calculator). */
fun Double.money(): String = formatter(CurrencyState.code).format(this)

/** Per-card prices get an extra digit so cheap cards can still be compared. */
fun Double.moneyPerCard(): String = formatter(CurrencyState.code, 3).format(this)

/** Axis labels: drop the cents once they stop mattering. */
fun Double.moneyShort(): String = if (this >= 100) formatter(CurrencyState.code, 0).format(this) else money()

/** A USD price from the server, converted to the chosen currency. "—" when missing. */
fun Double?.usd(): String = this?.let { CurrencyState.fromUsd(it).money() } ?: "—"

/** Compact USD → chosen currency ("$12.3K") for big totals and chart axes. */
fun Double.usdShort(): String {
    val v = CurrencyState.fromUsd(this)
    if (kotlin.math.abs(v) < 10_000) return v.moneyShort()
    val sym = formatter(CurrencyState.code).currency?.getSymbol(Locale.getDefault()) ?: ""
    return when {
        kotlin.math.abs(v) >= 1_000_000 -> "$sym%.1fM".format(v / 1_000_000)
        else -> "$sym%.1fK".format(v / 1_000)
    }
}

/** "+$1.20" / "-$3.40" for a USD difference. */
fun Double.usdSigned(): String = (if (this >= 0) "+" else "-") + kotlin.math.abs(this).usd()

/** "4.2%" from a fraction (sign shown separately with ▲/▼). */
fun Double.percent(): String = "%.1f%%".format(kotlin.math.abs(this * 100))

fun Long.shortDate(): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(this))

/** "2026-10-12" or "2026-10-12T00:00:00" → "Oct 12, 2026" (or "Oct 12" when [long] is false). */
fun String.dayLabel(long: Boolean = true): String = runCatching {
    val d = LocalDate.parse(take(10))
    if (long) d.format(longDay) else d.format(shortDay)
}.getOrDefault(this)

/** Accepts both "4.99" and "4,99". */
fun String.toAmount(): Double? = trim().replace(',', '.').toDoubleOrNull()
