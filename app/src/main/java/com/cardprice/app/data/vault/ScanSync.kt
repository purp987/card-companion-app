package com.cardprice.app.data.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A card the scanner identified, as the server needs it: [key] is "LANGUAGE|setId|cardId". */
data class ScanTarget(val key: String, val request: ResolveRequest, val label: String)

/** What happened to the last scanner change on the server side, for the scanner's status line. */
data class SyncNote(val text: String, val kind: Kind, val at: Long = System.nanoTime()) {
    enum class Kind { SAVED, QUEUED, PROBLEM }
}

/**
 * The scanner's link to TCG Vault: looks scanned cards up with `POST /api/app/resolve`, adds copies to
 * the portfolio with `POST /api/holdings`, and takes them back on Undo. When the server can't be
 * reached, adds go to the offline queue ([PendingAdds]) and are sent later. Owned counts start from
 * the portfolio's holdings and follow every change made here.
 */
class ScanSync(
    private val api: VaultApi,
    private val pending: PendingAdds,
    private val portfolio: () -> Long?,
    private val today: () -> String,
    private val onHoldingsChanged: () -> Unit = {},
) {
    /** Server match per scanned card (null = the server has none). */
    private val _matches = MutableStateFlow<Map<String, ResolveMatch?>>(emptyMap())
    val matches: StateFlow<Map<String, ResolveMatch?>> = _matches.asStateFlow()

    /** Copies owned per "cardKey|variantKey" in the scanner's portfolio, once known. */
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    private val _note = MutableStateFlow<SyncNote?>(null)
    val note: StateFlow<SyncNote?> = _note.asStateFlow()

    private sealed interface Added {
        data class Saved(val holdingId: Long, val input: HoldingInput) : Added
        data class Queued(val pendingId: String, val qty: Int) : Added
    }

    /** What this session added per "cardKey|variantKey", newest last, so Undo takes back exactly that. */
    private val added = ConcurrentHashMap<String, ArrayDeque<Added>>()
    private val mutex = Mutex()
    private var holdings: List<Holding>? = null
    private var holdingsPortfolio: Long? = null

    /** Looks the card up on the server (cached for the session). Throws [VaultException] when offline. */
    suspend fun resolve(target: ScanTarget): ResolveMatch? {
        if (_matches.value.containsKey(target.key)) return _matches.value[target.key]
        val list = withContext(Dispatchers.IO) { api.resolve(target.request) }
        val best = list.firstOrNull { it.score >= PendingAdds.MIN_SCORE }
        _matches.update { it + (target.key to best) }
        return best
    }

    /** Resolves without throwing (for prices and counts on screen). */
    suspend fun prefetch(target: ScanTarget, variantKeys: List<String>) {
        val match = runCatching { resolve(target) }.getOrNull() ?: return
        mutex.withLock { seedCounts(target, match, variantKeys) }
    }

    fun count(target: ScanTarget, variantKey: String): Int = _counts.value[slot(target, variantKey)] ?: 0

    /** Adds ([delta] > 0) or takes back ([delta] < 0) copies of one version. Changes run one at a time, in order. */
    suspend fun change(target: ScanTarget, variantKey: String, delta: Int) {
        if (delta == 0) return
        mutex.withLock { if (delta > 0) add(target, variantKey, delta) else remove(target, variantKey, -delta) }
    }

    /** Sets the owned count of one version (scanner's tap-to-add / hold-to-remove). */
    suspend fun setCount(target: ScanTarget, variantKey: String, count: Int) {
        change(target, variantKey, count - count(target, variantKey))
    }

    private suspend fun add(target: ScanTarget, variantKey: String, qty: Int) {
        val key = slot(target, variantKey)
        val match = try {
            resolve(target)
        } catch (e: VaultException.Offline) {
            queue(target, variantKey, qty, null)
            return
        } catch (e: VaultException) {
            if (e is VaultException.NotFound || e is VaultException.Unauthorized || e is VaultException.NotConfigured) {
                // Not paired / older server: keep the scan so nothing is lost, and say why.
                queue(target, variantKey, qty, null, e.message)
            } else {
                _note.value = SyncNote("Couldn't add ${target.label}: ${e.message}", SyncNote.Kind.PROBLEM)
            }
            return
        }
        if (match == null) {
            _note.value = SyncNote("TCG Vault has no product for ${target.label}, so it wasn't added.", SyncNote.Kind.PROBLEM)
            return
        }
        seedCounts(target, match, listOf(variantKey))
        val variant = VariantMap.pick(match.variants, variantKey)
        val pid = withContext(Dispatchers.IO) { runCatching { portfolio() }.getOrNull() }
        if (pid == null) {
            queue(target, variantKey, qty, match)
            return
        }
        val input = HoldingInput(
            portfolioId = pid,
            productId = match.product.id,
            variantId = variant?.id,
            qty = qty,
            condition = CONDITION_NM,
            addedOn = today(),
            notes = "Scanned with Card Companion",
        )
        try {
            val id = withContext(Dispatchers.IO) { api.addHolding(input) }
            if (id != null) added.getOrPut(key) { ArrayDeque() }.addLast(Added.Saved(id, input))
            bump(key, qty)
            onHoldingsChanged()
            _note.value = SyncNote("Added to TCG Vault: ${match.product.name}${variant?.let { " · ${it.subType}" } ?: ""}", SyncNote.Kind.SAVED)
        } catch (e: VaultException.Offline) {
            queue(target, variantKey, qty, match)
        } catch (e: VaultException) {
            _note.value = SyncNote("Couldn't add ${match.product.name}: ${e.message}", SyncNote.Kind.PROBLEM)
        }
    }

    private fun queue(target: ScanTarget, variantKey: String, qty: Int, match: ResolveMatch?, reason: String? = null) {
        val variant = match?.let { VariantMap.pick(it.variants, variantKey) }
        val item = PendingAdd(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            label = match?.product?.name ?: target.label,
            qty = qty,
            productId = match?.product?.id,
            variantId = variant?.id,
            variantKey = variantKey,
            resolve = if (match == null) target.request else null,
            addedOn = today(),
            notes = "Scanned with Card Companion",
        )
        pending.add(item)
        val key = slot(target, variantKey)
        added.getOrPut(key) { ArrayDeque() }.addLast(Added.Queued(item.id, qty))
        bump(key, qty)
        _note.value = SyncNote(
            reason?.let { "Saved on the phone: $it" } ?: "Offline: saved on the phone, sent to TCG Vault when it's reachable (${pending.size} waiting).",
            SyncNote.Kind.QUEUED,
        )
    }

    private suspend fun remove(target: ScanTarget, variantKey: String, qty: Int) {
        val key = slot(target, variantKey)
        var left = qty
        val stack = added[key]
        try {
            while (left > 0 && stack != null && stack.isNotEmpty()) {
                when (val last = stack.removeLast()) {
                    is Added.Queued -> {
                        pending.remove(last.pendingId)
                        val take = minOf(left, last.qty)
                        if (last.qty > take) stack.addLast(last.copy(qty = last.qty - take))
                        left -= take
                        bump(key, -take)
                    }
                    is Added.Saved -> {
                        val take = minOf(left, last.input.qty)
                        try {
                            withContext(Dispatchers.IO) {
                                if (last.input.qty > take) api.updateHolding(last.holdingId, last.input.copy(qty = last.input.qty - take))
                                else api.deleteHolding(last.holdingId)
                            }
                        } catch (e: VaultException) {
                            stack.addLast(last)
                            throw e
                        }
                        if (last.input.qty > take) stack.addLast(last.copy(input = last.input.copy(qty = last.input.qty - take)))
                        left -= take
                        bump(key, -take)
                        onHoldingsChanged()
                    }
                }
            }
            if (left > 0) left = removeExisting(target, variantKey, left)
            if (left < qty) _note.value = SyncNote("Removed from TCG Vault: ${target.label}", SyncNote.Kind.SAVED)
        } catch (e: VaultException) {
            _note.value = SyncNote("Couldn't remove ${target.label}: ${e.message}", SyncNote.Kind.PROBLEM)
        }
    }

    /** Takes copies added before this session out of the portfolio (hold-to-remove). Returns what's left to remove. */
    private suspend fun removeExisting(target: ScanTarget, variantKey: String, qty: Int): Int {
        val match = _matches.value[target.key] ?: return qty
        val variant = VariantMap.pick(match.variants, variantKey)
        var left = qty
        val rows = loadHoldings().filter { it.productId == match.product.id && (variant == null || it.variantId == variant.id) }
        for (h in rows) {
            if (left <= 0) break
            val take = minOf(left, h.qty)
            withContext(Dispatchers.IO) {
                if (h.qty > take) api.updateHolding(h.id, h.toInput(h.qty - take)) else api.deleteHolding(h.id)
            }
            left -= take
            bump(slot(target, variantKey), -take)
        }
        holdings = null
        onHoldingsChanged()
        return left
    }

    /** First time a card's server match is known: start its counts from the portfolio's holdings. */
    private suspend fun seedCounts(target: ScanTarget, match: ResolveMatch, variantKeys: List<String>) {
        val missing = variantKeys.filter { slot(target, it) !in _counts.value }
        if (missing.isEmpty()) return
        val rows = runCatching { loadHoldings() }.getOrNull() ?: return
        val seeded = missing.associate { key ->
            val variant = VariantMap.pick(match.variants, key)
            val owned = rows.filter { it.productId == match.product.id && (variant == null || it.variantId == variant.id) }.sumOf { it.qty }
            slot(target, key) to owned + pending.queuedQty(match.product.id, variant?.id)
        }
        _counts.update { seeded + it }
    }

    private suspend fun loadHoldings(): List<Holding> {
        val pid = withContext(Dispatchers.IO) { runCatching { portfolio() }.getOrNull() }
        holdings?.takeIf { holdingsPortfolio == pid }?.let { return it }
        val list = withContext(Dispatchers.IO) { api.portfolio(pid).holdings }
        holdings = list
        holdingsPortfolio = pid
        return list
    }

    private fun bump(key: String, delta: Int) = _counts.update { it + (key to ((it[key] ?: 0) + delta).coerceAtLeast(0)) }

    private fun slot(target: ScanTarget, variantKey: String) = "${target.key}|$variantKey"
}

fun Holding.toInput(newQty: Int = qty) = HoldingInput(
    portfolioId = portfolioId,
    productId = productId,
    variantId = variantId,
    qty = newQty,
    condition = condition,
    grade = grade,
    purchasePrice = purchasePrice,
    customValue = customValue,
    addedOn = addedOn,
    notes = notes,
)
