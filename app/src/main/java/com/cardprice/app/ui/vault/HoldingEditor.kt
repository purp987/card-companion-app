package com.cardprice.app.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.vault.CONDITIONS
import com.cardprice.app.data.vault.CONDITION_NM
import com.cardprice.app.data.vault.Holding
import com.cardprice.app.data.vault.HoldingInput
import com.cardprice.app.data.vault.Portfolio
import com.cardprice.app.data.vault.SEALED_CONDITIONS
import com.cardprice.app.data.vault.Variant
import com.cardprice.app.data.vault.Vault
import com.cardprice.app.ui.CurrencyState
import com.cardprice.app.ui.theme.Palette
import com.cardprice.app.ui.toAmount
import com.cardprice.app.ui.usd

/**
 * Add-to-collection / edit dialog (web `holdingModal`). Money is typed in the chosen currency and
 * sent as USD. [variants] may be empty (they're loaded from the product when needed).
 */
@Composable
fun HoldingEditor(
    productId: Long,
    productName: String,
    isSealed: Boolean,
    variants: List<Variant>?,
    primaryVariantId: Long?,
    holding: Holding?,
    onDismiss: () -> Unit,
    onSave: (HoldingInput) -> Unit,
) {
    var portfolios by remember { mutableStateOf<List<Portfolio>>(emptyList()) }
    var printings by remember { mutableStateOf(variants.orEmpty()) }
    LaunchedEffect(productId) {
        (load { Vault.api.portfolios() } as? Load.Ready)?.value?.let { portfolios = it }
        if (variants == null) (load { Vault.api.product(productId) } as? Load.Ready)?.value?.let { printings = it.variants }
    }
    fun fromUsd(v: Double?) = v?.let { "%.2f".format(java.util.Locale.US, CurrencyState.fromUsd(it)) }.orEmpty()

    var pid by remember { mutableStateOf(holding?.portfolioId ?: Vault.settings.portfolioId) }
    var variantId by remember { mutableStateOf(holding?.variantId ?: primaryVariantId) }
    var qty by remember { mutableStateOf((holding?.qty ?: 1).toString()) }
    var condition by remember { mutableStateOf(holding?.condition ?: if (isSealed) "Sealed" else CONDITION_NM) }
    var price by remember { mutableStateOf(fromUsd(holding?.purchasePrice)) }
    var date by remember { mutableStateOf(holding?.addedOn ?: Vault.today()) }
    var grade by remember { mutableStateOf(holding?.grade.orEmpty()) }
    var manual by remember { mutableStateOf(fromUsd(holding?.customValue)) }
    var notes by remember { mutableStateOf(holding?.notes.orEmpty()) }

    val portfolio = pid ?: portfolios.firstOrNull()?.id
    val valid = portfolio != null && (qty.toIntOrNull() ?: 0) > 0 && Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceSolid,
        title = { Text(if (holding == null) "Add to collection" else "Edit", color = Palette.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(productName, style = MaterialTheme.typography.bodyMedium, color = Palette.text2)
                Choice("Portfolio", portfolios.firstOrNull { it.id == portfolio }?.name ?: "…", portfolios.map { it.name }) { i -> pid = portfolios[i].id }
                if (printings.isNotEmpty()) {
                    val current = printings.firstOrNull { it.id == variantId } ?: printings.first()
                    Choice("Printing", "${current.subType} — ${current.market.usd()}", printings.map { "${it.subType} — ${it.market.usd()}" }) { i -> variantId = printings[i].id }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Quantity", qty, { qty = it.filter(Char::isDigit).take(5) }, KeyboardType.Number, Modifier.weight(1f))
                    Box(Modifier.weight(1.4f)) {
                        Choice("Condition", condition, if (isSealed) SEALED_CONDITIONS else CONDITIONS) { i -> condition = (if (isSealed) SEALED_CONDITIONS else CONDITIONS)[i] }
                    }
                }
                Field("Purchase price each (${CurrencyState.code})", price, { price = it }, KeyboardType.Decimal)
                Field("Date acquired (YYYY-MM-DD)", date, { date = it.take(10) }, KeyboardType.Number)
                if (!isSealed) Field("Grade (e.g. PSA 10, optional)", grade, { grade = it }, KeyboardType.Text)
                Field("Manual value each (${CurrencyState.code}, optional)", manual, { manual = it }, KeyboardType.Decimal)
                Field("Notes", notes, { notes = it }, KeyboardType.Text)
                Text(
                    "Graded cards are valued from recent graded sales when available. A manual value overrides it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.text3,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    HoldingInput(
                        portfolioId = portfolio!!,
                        productId = productId,
                        variantId = variantId ?: printings.firstOrNull()?.id,
                        qty = qty.toInt(),
                        condition = condition,
                        grade = grade.trim().ifEmpty { null },
                        purchasePrice = price.toAmount()?.let(CurrencyState::toUsd),
                        customValue = manual.toAmount()?.let(CurrencyState::toUsd),
                        addedOn = date,
                        notes = notes.trim().ifEmpty { null },
                    ),
                )
            }) { Text(if (holding == null) "Add" else "Save", color = Palette.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.text2) } },
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        colors = vaultFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = modifier,
    )
}

/** A labelled dropdown. */
@Composable
fun Choice(label: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.text3)
            Pill("$value ▾", active = false, onClick = { open = true })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}
