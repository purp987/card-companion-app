package com.cardprice.app.data.vault

import com.cardprice.app.data.vault.VaultJson.int
import com.cardprice.app.data.vault.VaultJson.list
import com.cardprice.app.data.vault.VaultJson.lng
import com.cardprice.app.data.vault.VaultJson.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A card the scanner added while TCG Vault couldn't be reached. Either the product is already known
 * ([productId]) or only what was read is ([resolve]), in which case it's looked up when sent.
 */
data class PendingAdd(
    val id: String,
    val createdAt: Long,
    val label: String,
    val qty: Int,
    val productId: Long? = null,
    val variantId: Long? = null,
    /** Scanner variant key, used to pick the printing once the product is known. */
    val variantKey: String? = null,
    val resolve: ResolveRequest? = null,
    val portfolioId: Long? = null,
    val condition: String? = CONDITION_NM,
    val addedOn: String? = null,
    val notes: String? = null,
    /** Why the last attempt failed for this item alone (e.g. no match); it's kept for the user to retry or discard. */
    val error: String? = null,
)

data class FlushResult(val sent: Int, val failed: Int, val remaining: Int, val stoppedBy: VaultException? = null)

/** Where the queue is kept between app runs. */
interface PendingStore {
    fun load(): List<PendingAdd>
    fun save(items: List<PendingAdd>)
}

class FilePendingStore(private val file: File) : PendingStore {
    override fun load(): List<PendingAdd> = runCatching { PendingAdds.fromJson(file.readText()) }.getOrDefault(emptyList())

    override fun save(items: List<PendingAdd>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(PendingAdds.toJson(items))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

class MemoryPendingStore(private var items: List<PendingAdd> = emptyList()) : PendingStore {
    override fun load() = items
    override fun save(items: List<PendingAdd>) { this.items = items }
}

/** The offline add queue: saved on every change, sent in order by [flush]. Thread-safe. */
class PendingAdds(private val store: PendingStore) {
    private val lock = Any()
    private var items: List<PendingAdd> = store.load()
    @Volatile private var listener: ((List<PendingAdd>) -> Unit)? = null

    fun all(): List<PendingAdd> = synchronized(lock) { items }

    val size: Int get() = all().size

    fun observe(onChange: (List<PendingAdd>) -> Unit) {
        listener = onChange
        onChange(all())
    }

    fun add(item: PendingAdd) = mutate { it + item }

    /** Takes an item back out (undo before it was sent). Returns true if it was still queued. */
    fun remove(id: String): Boolean {
        var removed = false
        mutate { list -> list.filterNot { (it.id == id).also { hit -> if (hit) removed = true } } }
        return removed
    }

    fun clearFailed() = mutate { list -> list.filter { it.error == null } }

    /** Queued copies of a product/printing not yet sent (for owned counts). */
    fun queuedQty(productId: Long, variantId: Long?): Int =
        all().filter { it.productId == productId && (variantId == null || it.variantId == variantId) }.sumOf { it.qty }

    /**
     * Sends queued adds in order. Stops (keeping the rest) when the server can't be reached or doesn't
     * accept this phone; an item that fails on its own (no match, unknown product) is marked and skipped.
     */
    fun flush(api: VaultApi, defaultPortfolio: () -> Long?, today: () -> String): FlushResult {
        var sent = 0
        var failed = 0
        for (item in all()) {
            if (item.error != null) { failed++; continue }
            try {
                val portfolio = item.portfolioId ?: defaultPortfolio()
                    ?: return FlushResult(sent, failed, size, VaultException.Http(0, "No portfolio to add to."))
                var productId = item.productId
                var variantId = item.variantId
                if (productId == null) {
                    val req = item.resolve
                    if (req == null) {
                        markError(item.id, "Nothing to look up")
                        failed++
                        continue
                    }
                    val match = api.resolve(req).firstOrNull { it.score >= MIN_SCORE }
                    if (match == null) {
                        markError(item.id, "No match in TCG Vault")
                        failed++
                        continue
                    }
                    productId = match.product.id
                    variantId = VariantMap.pick(match.variants, item.variantKey)?.id
                }
                api.addHolding(
                    HoldingInput(
                        portfolioId = portfolio,
                        productId = productId,
                        variantId = variantId,
                        qty = item.qty,
                        condition = item.condition,
                        addedOn = item.addedOn ?: today(),
                        notes = item.notes,
                    ),
                )
                remove(item.id)
                sent++
            } catch (e: VaultException.Http) {
                if (e.code in 400..499) {
                    markError(item.id, e.message ?: "Rejected by the server")
                    failed++
                } else {
                    return FlushResult(sent, failed, size, e)
                }
            } catch (e: VaultException) {
                // Offline, unpaired or an older server: try again later, in the same order.
                return FlushResult(sent, failed, size, e)
            }
        }
        return FlushResult(sent, failed, size)
    }

    private fun markError(id: String, error: String) = mutate { list -> list.map { if (it.id == id) it.copy(error = error) else it } }

    private fun mutate(change: (List<PendingAdd>) -> List<PendingAdd>) {
        val snapshot = synchronized(lock) {
            items = change(items)
            store.save(items)
            items
        }
        listener?.invoke(snapshot)
    }

    companion object {
        /** Matches below this are too unsure to add without the user looking (same bar as `/api/app/import`). */
        const val MIN_SCORE = 0.8

        fun toJson(items: List<PendingAdd>): String {
            val a = JSONArray()
            items.forEach { p ->
                a.put(
                    JSONObject()
                        .put("id", p.id).put("createdAt", p.createdAt).put("label", p.label).put("qty", p.qty)
                        .put("productId", p.productId ?: JSONObject.NULL)
                        .put("variantId", p.variantId ?: JSONObject.NULL)
                        .put("variantKey", p.variantKey ?: JSONObject.NULL)
                        .put("resolve", p.resolve?.let(VaultJson::resolveJson) ?: JSONObject.NULL)
                        .put("portfolioId", p.portfolioId ?: JSONObject.NULL)
                        .put("condition", p.condition ?: JSONObject.NULL)
                        .put("addedOn", p.addedOn ?: JSONObject.NULL)
                        .put("notes", p.notes ?: JSONObject.NULL)
                        .put("error", p.error ?: JSONObject.NULL),
                )
            }
            return a.toString()
        }

        fun fromJson(json: String): List<PendingAdd> = JSONArray(json).list { o ->
            PendingAdd(
                id = o.str("id") ?: return@list null,
                createdAt = o.lng("createdAt") ?: 0,
                label = o.str("label") ?: "",
                qty = o.int("qty") ?: 1,
                productId = o.lng("productId"),
                variantId = o.lng("variantId"),
                variantKey = o.str("variantKey"),
                resolve = o.optJSONObject("resolve")?.let(VaultJson::resolveRequest),
                portfolioId = o.lng("portfolioId"),
                condition = o.str("condition"),
                addedOn = o.str("addedOn"),
                notes = o.str("notes"),
                error = o.str("error"),
            )
        }
    }
}
