package com.cardprice.app.ui

import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

private val money2: NumberFormat = NumberFormat.getCurrencyInstance()

private val money3: NumberFormat = NumberFormat.getCurrencyInstance().apply {
    minimumFractionDigits = 3
    maximumFractionDigits = 3
}

private val money0: NumberFormat = NumberFormat.getCurrencyInstance().apply { maximumFractionDigits = 0 }

/** Axis labels: drop the cents once they stop mattering. */
fun Double.moneyShort(): String = if (this >= 100) money0.format(this) else money2.format(this)

/** Totals and per-pack prices. */
fun Double.money(): String = money2.format(this)

/** Per-card prices get an extra digit so cheap cards can still be compared. */
fun Double.moneyPerCard(): String = money3.format(this)

fun Long.shortDate(): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(this))

/** Accepts both "4.99" and "4,99". */
fun String.toAmount(): Double? = trim().replace(',', '.').toDoubleOrNull()
