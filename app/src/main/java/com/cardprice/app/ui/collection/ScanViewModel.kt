package com.cardprice.app.ui.collection

import android.app.Application
import android.util.Log
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cardprice.app.data.collection.CollectionStore
import com.cardprice.app.data.collection.CardPhotos
import com.cardprice.app.data.market.userMessage
import com.cardprice.app.data.scan.AutoAddPolicy
import com.cardprice.app.data.scan.ScanConfidence
import com.cardprice.app.data.scan.CardTextParser
import com.cardprice.app.data.scan.ScanAction
import com.cardprice.app.data.scan.ScanDecision
import com.cardprice.app.data.scan.ScanHistoryEntry
import com.cardprice.app.data.scan.ScanHistoryStore
import com.cardprice.app.data.scan.ScanOutcome
import com.cardprice.app.data.scan.ScanResolver
import com.cardprice.app.data.scan.ScanResult
import com.cardprice.app.data.scan.ScanSetup
import com.cardprice.app.data.scan.ScanSetupStore
import com.cardprice.app.data.scan.SameCardTracker
import com.cardprice.app.data.scan.LearnedCard
import com.cardprice.app.data.scan.ScanLearning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import com.cardprice.app.data.collection.CardPrices
import com.cardprice.app.data.collection.VariantPrice
import com.cardprice.app.data.collection.TcgdexApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import com.cardprice.app.data.scan.ScanLog
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch

sealed interface ScanStatus {
    /** Waiting for a readable card number. */
    data object Searching : ScanStatus
    data class LookingUp(val summary: String) : ScanStatus
    /** A sure match that needs one more matching camera read before it's added. */
    data class HoldSteady(val result: ScanResult, val readKey: String = "", val summary: String = "") : ScanStatus
    /**
     * A result for the user to act on. [askVariant] means the scanner is sure of the card but it has
     * several variants, so nothing is added until one is picked.
     */
    data class Found(
        val results: List<ScanResult>,
        val confidence: ScanConfidence,
        val askVariant: Boolean,
        val chosen: Int = 0,
        /** What was read, so choices here can teach the scanner. */
        val readKey: String = "",
        /** The reading ("MEP 073"); another reading from the camera replaces this result. */
        val summary: String = "",
        val fromPhoto: Boolean = false,
        val shownAt: Long = System.currentTimeMillis(),
    ) : ScanStatus
    /** Added automatically; shown with an Undo until the next card is read. Scanning carries on meanwhile. */
    data class AutoAdded(
        val result: ScanResult,
        val variantKey: String,
        val event: Long,
        val copies: Int = 1,
        val readKey: String = "",
    ) : ScanStatus
    data class NotFound(val summary: String, val fromPhoto: Boolean) : ScanStatus
    data class Failed(val message: String) : ScanStatus
}

private const val TAG = "CardScan"
/** Readings of another card in a row (about a second) that replace a suggestion on screen. */
private const val OTHER_READINGS_TO_SWITCH = 3
/** A suggestion that just appeared isn't dropped by the tail end of the movement that brought the card in. */
private const val MOTION_GRACE_MS = 800L

enum class ScanMode { SINGLE, BINDER }

/** Binder page layouts: pockets per row and column. */
enum class BinderLayout(val label: String, val rows: Int, val cols: Int) {
    NINE("9-pocket", 3, 3),
    TWELVE("12-pocket", 4, 3),
    FOUR("4-pocket", 2, 2),
}

enum class SlotState { EMPTY, UNREADABLE, NOT_FOUND, FOUND }

/**
 * One pocket of a scanned binder page. [selected] pockets are added by "Add all"; a pocket is only
 * pre-selected when the match is sure and its version is known (never guessed for multi-version cards).
 */
data class BulkSlot(
    val index: Int,
    val state: SlotState,
    val results: List<ScanResult> = emptyList(),
    val confidence: ScanConfidence = ScanConfidence.LOW,
    val chosen: Int = 0,
    val variantKey: String? = null,
    val selected: Boolean = false,
    val readKey: String = "",
    val summary: String = "",
    /** The user picked the version themselves (rather than it coming from the chosen finish). */
    val variantPicked: Boolean = false,
) {
    val result: ScanResult? get() = results.getOrNull(chosen)
}

data class BulkState(
    val slots: List<BulkSlot> = emptyList(),
    /** Pockets read so far / total, while a page is being processed. */
    val reading: Pair<Int, Int>? = null,
)

/** A page that was just added, kept so the whole page can be undone. */
data class BulkAdded(val added: List<BulkSlot>, val event: Long)

/** A copy added by hand from a scan result (tapping a version), for the "Added" splash. */
data class ManualAdded(val result: ScanResult, val variantKey: String, val event: Long)

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CollectionStore(application)
    private val resolver = ScanResolver(store)
    private val history = ScanHistoryStore(File(application.filesDir, "collection"))
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val historyWriter = Dispatchers.IO.limitedParallelism(1)
    /** Learns from confirmations and corrections so scanning improves with use. */
    private val learning = ScanLearning.get(application.filesDir)
    private val _status = MutableStateFlow<ScanStatus>(ScanStatus.Searching)
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val _autoAdd = MutableStateFlow(true)
    val autoAdd: StateFlow<Boolean> = _autoAdd.asStateFlow()

    private val setupStore = ScanSetupStore(application)
    /** What's being scanned; pre-filled with last time's choices. */
    private val _setup = MutableStateFlow(setupStore.load() ?: ScanSetup())
    val setup: StateFlow<ScanSetup> = _setup.asStateFlow()
    /** The "What are you scanning?" panel shows first; no frames are read until it's confirmed. */
    private val _choosing = MutableStateFlow(true)
    val choosing: StateFlow<Boolean> = _choosing.asStateFlow()

    private val _mode = MutableStateFlow(ScanMode.SINGLE)
    val mode: StateFlow<ScanMode> = _mode.asStateFlow()
    private val _layout = MutableStateFlow(BinderLayout.NINE)
    val layout: StateFlow<BinderLayout> = _layout.asStateFlow()
    private val _bulk = MutableStateFlow<BulkState?>(null)
    val bulk: StateFlow<BulkState?> = _bulk.asStateFlow()
    private val _bulkAdded = MutableStateFlow<BulkAdded?>(null)
    val bulkAdded: StateFlow<BulkAdded?> = _bulkAdded.asStateFlow()
    /** Market prices of cards shown in scan results, by card id. */
    private val _prices = MutableStateFlow<Map<String, CardPrices>>(emptyMap())
    val prices: StateFlow<Map<String, CardPrices>> = _prices.asStateFlow()
    private val requestedPrices = mutableSetOf<String>()

    init {
        ScanLog.init(application.filesDir)
        ScanLog.d("scanner opened; setup=${_setup.value.summary} mode=${_mode.value}")
        // Whatever card a result shows gets its price looked up; every change of state is logged.
        viewModelScope.launch {
            _status.collect { s ->
                ScanLog.d("status -> ${describe(s)}")
                when (s) {
                    is ScanStatus.Found -> s.results.forEach(::ensurePrice)
                    is ScanStatus.AutoAdded -> ensurePrice(s.result)
                    is ScanStatus.HoldSteady -> ensurePrice(s.result)
                    else -> Unit
                }
            }
        }
    }

    /** Uses the collection's price cache (a day old at most), else asks TCGdex; cards from TCGplayer carry their price. */
    private fun ensurePrice(result: ScanResult) {
        val card = result.card
        if (card.placeholder || card.id in _prices.value || !requestedPrices.add(card.id)) return
        card.marketPrice?.let { price ->
            val listed = CardPrices(card.variants.associate { it.key to VariantPrice(price, "USD", "TCGplayer") })
            _prices.update { it + (card.id to listed) }
            return
        }
        val language = result.set.language
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { store.cachedPrices(language, PRICE_MAX_AGE_MS)[card.id] }
            val prices = cached ?: runCatching { TcgdexApi.cardPrices(language, card.id) }.getOrNull()
                ?.also { fetched -> withContext(Dispatchers.IO) { store.cachePrices(language, mapOf(card.id to fetched)) } }
            if (prices == null) requestedPrices.remove(card.id) else _prices.update { it + (card.id to prices) }
        }
    }

    private val _manualAdded = MutableStateFlow<ManualAdded?>(null)
    val manualAdded: StateFlow<ManualAdded?> = _manualAdded.asStateFlow()

    fun setMode(mode: ScanMode) {
        ScanLog.d("mode $mode")
        _mode.value = mode
        next()
    }

    fun setLayout(layout: BinderLayout) {
        _layout.value = layout
    }

    fun onPageReading(done: Int, total: Int) {
        _bulk.value = BulkState(reading = done to total)
    }

    /** Text read from each pocket of a binder page, in reading order (left to right, top to bottom). */
    fun onBinderPage(pockets: List<List<String>>) {
        _bulkAdded.value = null
        val setup = _setup.value
        _bulk.value = BulkState(reading = 0 to pockets.size)
        viewModelScope.launch {
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val slots = kotlinx.coroutines.coroutineScope {
                pockets.mapIndexed { i, lines ->
                    async {
                        slotFor(i, lines, setup).also {
                            _bulk.value = BulkState(reading = done.incrementAndGet() to pockets.size)
                        }
                    }
                }.map { it.await() }
            }
            _bulk.value = BulkState(slots = slots)
        }
    }

    private suspend fun slotFor(index: Int, lines: List<String>, setup: ScanSetup): BulkSlot {
        if (lines.none { line -> line.count(Char::isLetterOrDigit) >= 3 }) return BulkSlot(index, SlotState.EMPTY)
        val clues = CardTextParser.parse(lines)
        if (!clues.usableWith(setup)) return BulkSlot(index, SlotState.UNREADABLE)
        val outcome = try {
            resolver.identify(clues, setup, learning)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BulkSlot(index, SlotState.NOT_FOUND, summary = clues.summary)
        }
        val best = outcome.results.firstOrNull() ?: return BulkSlot(index, SlotState.NOT_FOUND, summary = clues.summary)
        val variant = AutoAddPolicy.variantFor(
            best.card.variants.map { it.key },
            setup.finish,
            learning.preferredVariant(best.set.language, best.card.id),
        )
        return BulkSlot(
            index = index,
            state = SlotState.FOUND,
            results = outcome.results,
            confidence = outcome.confidence,
            variantKey = variant,
            selected = outcome.confidence == ScanConfidence.HIGH && variant != null,
            readKey = outcome.readKey,
            summary = clues.summary,
        )
    }

    fun toggleSlot(index: Int) = updateSlot(index) { s ->
        if (s.state == SlotState.FOUND && s.variantKey != null) s.copy(selected = !s.selected) else s
    }

    /** Picks the version for a pocket; picking one also selects it for adding. */
    fun setSlotVariant(index: Int, variantKey: String) =
        updateSlot(index) { it.copy(variantKey = variantKey, selected = true, variantPicked = true) }

    /** Picks a different candidate card for a pocket. */
    fun chooseSlotResult(index: Int, resultIndex: Int) = updateSlot(index) { s ->
        val r = s.results.getOrNull(resultIndex) ?: return@updateSlot s
        val variant = AutoAddPolicy.variantFor(
            r.card.variants.map { it.key },
            _setup.value.finish,
            learning.preferredVariant(r.set.language, r.card.id),
        )
        s.copy(chosen = resultIndex, variantKey = variant, selected = variant != null)
    }

    private fun updateSlot(index: Int, change: (BulkSlot) -> BulkSlot) {
        val state = _bulk.value ?: return
        _bulk.value = state.copy(slots = state.slots.map { if (it.index == index) change(it) else it })
    }

    /** Adds every selected pocket (one copy each) and teaches the scanner from the choices made. */
    fun addBulk() {
        val slots = _bulk.value?.slots.orEmpty().filter { it.selected && it.result != null && it.variantKey != null }
        if (slots.isEmpty()) return
        slots.forEach { s ->
            val r = s.result!!
            addCopies(r, s.variantKey!!, 1)
            record(r, s.variantKey, 1, ScanAction.ADDED)
            if (s.readKey.isNotEmpty()) {
                learning.confirm(s.readKey, LearnedCard(r.set.language, r.set.setId, r.card.id), corrected = s.chosen > 0)
            }
            // Only a version the user actually picked is remembered, not one that came from the finish setting.
            if (s.variantPicked && r.card.variants.size > 1) learning.preferVariant(r.set.language, r.card.id, s.variantKey)
        }
        _bulkAdded.value = BulkAdded(slots, System.nanoTime())
        _bulk.value = null
    }

    /** Takes back the whole page that was just added. */
    fun undoBulk() {
        val added = _bulkAdded.value ?: return
        added.added.forEach { s ->
            val r = s.result ?: return@forEach
            addCopies(r, s.variantKey!!, -1)
            record(r, s.variantKey, -1, ScanAction.UNDONE)
            // Undoing a page means "not now", not "these were misread", so only the confirmations are withdrawn.
            if (s.readKey.isNotEmpty()) learning.unconfirm(s.readKey, LearnedCard(r.set.language, r.set.setId, r.card.id))
        }
        _bulkAdded.value = null
    }

    /** Ready for the next binder page. */
    fun nextPage() {
        _bulk.value = null
        _bulkAdded.value = null
    }

    fun startScanning(setup: ScanSetup) {
        ScanLog.d("start scanning: ${setup.summary} (${setup.language}/${setup.setId})")
        _setup.value = setup
        setupStore.save(setup)
        _choosing.value = false
        next()
    }

    fun changeSetup() {
        ScanLog.d("change setup")
        _choosing.value = true
    }

    /** Adds [delta] copies of a variant to the collection; supplied by the screen. */
    var addCopies: (ScanResult, variantKey: String, delta: Int) -> Unit = { _, _, _ -> }

    private var busy = false
    /** Last reading that found nothing, so the camera doesn't look the same thing up every frame. */
    private var lastMiss: String? = null
    /** Card the camera identified on its previous read; a sure match must repeat before it's added. */
    private var previousReadCardId: String? = null
    /** Keeps a card from being added twice while in view, and remembers versions picked this session. */
    private val sameCard = SameCardTracker()
    /** Cards turned down with "Not this card" this session, per reading, so they aren't suggested again. */
    private val skipped = mutableMapOf<String, MutableSet<String>>()
    /** A different reading seen while a suggestion is up; enough of them in a row replace it. */
    private var otherReading: String? = null
    private var otherReadingCount = 0

    fun setAutoAdd(enabled: Boolean) {
        _autoAdd.value = enabled
    }

    /** Text from a live camera frame. Ignored while a result waits for the user or a lookup is running. */
    fun onCameraText(lines: List<String>) {
        // Binder pages are read from a captured picture, not from live frames.
        if (_choosing.value || _mode.value == ScanMode.BINDER) return
        val setup = _setup.value
        val clues = CardTextParser.parse(lines)
        val now = System.currentTimeMillis()
        noteFrame(clues.usableWith(setup), clues.summary, now)
        // Every frame counts towards noticing that a card was taken away (so the same card can count again).
        sameCard.onFrame(clues.usableWith(setup), now)
        // An error (e.g. no connection) doesn't stop scanning: the next readable card starts over.
        if (_status.value is ScanStatus.Failed && clues.usableWith(setup) && !busy) {
            ScanLog.d("recovering from error on new reading ${clues.summary}")
            next()
        }
        val found = _status.value as? ScanStatus.Found
        if (found != null) {
            // A photo's result isn't about what the camera sees.
            if (found.fromPhoto || !clues.usableWith(setup) || clues.summary == found.summary) {
                otherReadingCount = 0
                return
            }
            // A different card is being read steadily: the suggestion was for the previous one.
            otherReadingCount = if (clues.summary == otherReading) otherReadingCount + 1 else 1
            otherReading = clues.summary
            if (otherReadingCount < OTHER_READINGS_TO_SWITCH) return
            ScanLog.d("reading changed to ${clues.summary}; replacing suggestion")
            next()
        }
        if (!clues.usableWith(setup)) return
        if (busy) {
            ignored("busy with a lookup (${busyFor(now)} ms)", clues.summary)
            return
        }
        if (clues.summary == lastMiss) {
            ignored("same reading as the last miss", clues.summary)
            return
        }
        // Remember what the camera saw for this reading, in case the card turns out to have no picture.
        lookupFrame = latestCardFrame
        lookUp(clues.summary, fromPhoto = false) { resolver.identify(clues, setup, learning) }
    }

    /** Text from a chosen photo: always answer, even if nothing readable was found. */
    fun onPhotoText(lines: List<String>) {
        ScanLog.d("photo text: $lines")
        val setup = _setup.value
        val clues = CardTextParser.parse(lines)
        if (!clues.usableWith(setup)) {
            _status.value = ScanStatus.NotFound("no card number", fromPhoto = true)
            return
        }
        lastMiss = null
        lookupFrame = photoFrame
        lookUp(clues.summary, fromPhoto = true) { resolver.identify(clues, setup, learning) }
    }

    fun onPhotoError(message: String) {
        _status.value = ScanStatus.Failed(message)
    }

    fun choose(index: Int) {
        (_status.value as? ScanStatus.Found)?.let { _status.value = it.copy(chosen = index) }
    }

    /** Takes back an automatic add. The card stays "just added" so it isn't re-added while in view. */
    fun undo(added: ScanStatus.AutoAdded) {
        ScanLog.d("undo ${added.result.card.id} x${added.copies}")
        // Takes back everything added under this banner, including any +1s.
        addCopies(added.result, added.variantKey, -added.copies)
        record(added.result, added.variantKey, -added.copies, ScanAction.UNDONE)
        // Undoing says this card was wrong for what was read.
        if (added.readKey.isNotEmpty()) learning.reject(added.readKey, added.result.card.id)
        _status.value = ScanStatus.Searching
    }

    private var busySince = 0L
    private fun busyFor(now: Long) = now - busySince

    // Frame statistics, logged every few seconds so a stall shows up in the log.
    private var framesSinceBeat = 0
    private var usableSinceBeat = 0
    private var lastBeat = 0L
    private var lastReading: String? = null
    private var lastIgnored: String? = null

    private fun noteFrame(usable: Boolean, summary: String, now: Long) {
        framesSinceBeat++
        if (usable) {
            usableSinceBeat++
            if (summary != lastReading) ScanLog.d("reading: $summary")
            lastReading = summary
        }
        if (now - lastBeat >= HEARTBEAT_MS) {
            if (lastBeat != 0L) {
                ScanLog.d(
                    "frames: $framesSinceBeat read, $usableSinceBeat with a card number; status ${describe(_status.value)}" +
                        (if (busy) ", busy ${busyFor(now)} ms" else "") + (lastMiss?.let { ", last miss $it" } ?: ""),
                )
            }
            lastBeat = now
            framesSinceBeat = 0
            usableSinceBeat = 0
        }
    }

    /** Logs why a reading was skipped, once per reason and reading. */
    private fun ignored(reason: String, summary: String) {
        val key = "$reason|$summary"
        if (key == lastIgnored) return
        lastIgnored = key
        ScanLog.d("ignoring $summary: $reason")
    }

    /** Called by the screen when the camera stops delivering frames. */
    fun onCameraEvent(message: String) = ScanLog.w(message)

    override fun onCleared() {
        ScanLog.d("scanner closed")
        super.onCleared()
    }

    // ---- Pictures for cards that have none (e.g. Simplified Chinese cards): the scan itself.
    @Volatile private var latestCardFrame: android.graphics.Bitmap? = null
    @Volatile private var photoFrame: android.graphics.Bitmap? = null
    private var lookupFrame: android.graphics.Bitmap? = null

    fun onCardFrame(bitmap: android.graphics.Bitmap) { latestCardFrame = bitmap }
    fun onPhotoBitmap(bitmap: android.graphics.Bitmap) { photoFrame = bitmap }

    /** Saves the scan as the card's picture when it has none yet (its own, or one taken before). */
    private fun savePictureIfMissing(result: ScanResult) {
        val card = result.card
        if (card.image != null || CardPhotos.has(card.id)) return
        val frame = lookupFrame ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (CardPhotos.save(card.id, frame)) {
                ScanLog.d("saved the scan as the picture for ${card.id}")
                CardPhotos.changed()
            }
        }
    }

    /** Back to scanning. */
    fun next() {
        lastIgnored = null
        lastMiss = null
        previousReadCardId = null
        otherReading = null
        otherReadingCount = 0
        _status.value = ScanStatus.Searching
    }

    /**
     * The card in view was shaken or moved a lot: drop whatever the scanner was stuck on and read
     * again. An added card's banner stays (moving to the next card is normal), and a result from a
     * photo isn't about the camera, so it stays too.
     */
    fun onMotion() {
        if (_choosing.value || _mode.value == ScanMode.BINDER) return
        ScanLog.d("big move while ${describe(_status.value)}")
        lastMiss = null
        when (val s = _status.value) {
            is ScanStatus.Found -> if (!s.fromPhoto && System.currentTimeMillis() - s.shownAt > MOTION_GRACE_MS) retry()
            is ScanStatus.NotFound -> if (!s.fromPhoto) retry()
            is ScanStatus.HoldSteady, is ScanStatus.Failed -> retry()
            else -> Unit
        }
    }

    private fun retry() {
        ScanLog.d("big move; reading again")
        next()
    }

    /**
     * "Not this card": the suggestion is wrong. Other possible matches stay up if there are any;
     * otherwise scanning carries on. The scanner remembers the rejection for this reading.
     */
    fun skip() {
        when (val s = _status.value) {
            is ScanStatus.Found -> {
                val wrong = s.results[s.chosen]
                reject(s.readKey, s.summary, wrong)
                val rest = s.results.filterIndexed { i, _ -> i != s.chosen }
                if (rest.isEmpty()) {
                    keepSearchingPast(s.summary)
                } else {
                    // What's left was never the best guess, so nothing here is sure.
                    _status.value = s.copy(
                        results = rest,
                        confidence = ScanConfidence.LOW,
                        askVariant = false,
                        chosen = 0,
                        shownAt = System.currentTimeMillis(),
                    )
                }
            }
            is ScanStatus.HoldSteady -> {
                reject(s.readKey, s.summary, s.result)
                keepSearchingPast(s.summary)
            }
            else -> Unit
        }
    }

    private fun reject(readKey: String, summary: String, result: ScanResult) {
        ScanLog.d("skipped ${result.card.id} for $summary")
        skipped.getOrPut(readKey) { mutableSetOf() } += result.card.id
        if (readKey.isNotEmpty()) learning.reject(readKey, result.card.id)
    }

    /** Back to scanning, without looking the same reading up again until the card moves or changes. */
    private fun keepSearchingPast(summary: String) {
        next()
        lastMiss = summary
    }

    private fun lookUp(summary: String, fromPhoto: Boolean, identify: suspend () -> ScanOutcome) {
        busy = true
        busySince = System.currentTimeMillis()
        val started = busySince
        ScanLog.d("lookup start: $summary${if (fromPhoto) " (photo)" else ""}")
        // Keep the "Added" banner or "hold steady" hint up while looking up the next read.
        if (_status.value !is ScanStatus.AutoAdded && _status.value !is ScanStatus.HoldSteady) {
            _status.value = ScanStatus.LookingUp(summary)
        }
        viewModelScope.launch {
            try {
                // A lookup that hangs (slow network) would stop the scanner, so give up after a while.
                val outcome = withoutSkipped(withTimeout(LOOKUP_TIMEOUT_MS) { identify() })
                val best = outcome.results.firstOrNull()
                ScanLog.d(
                    "lookup done in ${System.currentTimeMillis() - started} ms: $summary -> " +
                        (outcome.results.joinToString { it.card.id }.ifEmpty { "nothing" }) + " (${outcome.confidence})",
                )
                if (best == null) {
                    lastMiss = summary
                    previousReadCardId = null
                    if (_status.value !is ScanStatus.AutoAdded) _status.value = ScanStatus.NotFound(summary, fromPhoto)
                    return@launch
                }
                handle(outcome, best, fromPhoto, summary)
            } catch (e: TimeoutCancellationException) {
                ScanLog.w("lookup timed out after ${System.currentTimeMillis() - started} ms: $summary")
                lastMiss = null
                _status.value = ScanStatus.Failed("Looking the card up took too long. Check your connection; scanning carries on.")
            } catch (e: CancellationException) {
                ScanLog.d("lookup cancelled: $summary")
                throw e
            } catch (e: Exception) {
                ScanLog.w("lookup failed after ${System.currentTimeMillis() - started} ms: $summary", e)
                _status.value = ScanStatus.Failed(e.userMessage())
            } finally {
                busy = false
            }
        }
    }

    /** Leaves out cards turned down for this reading; if any were left out, nothing left is sure. */
    private fun withoutSkipped(outcome: ScanOutcome): ScanOutcome {
        val turnedDown = skipped[outcome.readKey].orEmpty()
        val rest = outcome.results.filter { it.card.id !in turnedDown }
        if (rest.size == outcome.results.size) return outcome
        return outcome.copy(results = rest, confidence = ScanConfidence.LOW)
    }

    private fun handle(outcome: ScanOutcome, best: ScanResult, fromPhoto: Boolean, summary: String) {
        val cardId = best.card.id
        sameCard.onRead(cardId)
        val decision = AutoAddPolicy.decide(
            cardId = cardId,
            variantKeys = best.card.variants.map { it.key },
            confidence = outcome.confidence,
            fromPhoto = fromPhoto,
            autoAddEnabled = _autoAdd.value,
            previousReadCardId = previousReadCardId,
            // A photo is picked on purpose, so the same card may be added again from another photo.
            lastAutoAddedCardId = if (fromPhoto) null else sameCard.justAdded,
            finish = _setup.value.finish,
            rememberedVariant = sameCard.rememberedChoice(cardId) ?: learning.preferredVariant(best.set.language, cardId),
        )
        previousReadCardId = cardId
        ScanLog.d("decision for $cardId: ${decision.javaClass.simpleName} (auto-add ${_autoAdd.value}, finish ${_setup.value.finish})")
        when (decision) {
            is ScanDecision.AutoAdd -> {
                savePictureIfMissing(best)
                addCopies(best, decision.variantKey, 1)
                record(best, decision.variantKey, 1, ScanAction.AUTO_ADDED)
                sameCard.onAdded(cardId)
                // Kept unless undone: counts as confirming this card for this reading.
                learning.confirm(outcome.readKey, LearnedCard(best.set.language, best.set.setId, cardId))
                previousReadCardId = null
                // The banner (with Undo) stays until the next card is read.
                _status.value = ScanStatus.AutoAdded(best, decision.variantKey, System.nanoTime(), readKey = outcome.readKey)
            }
            ScanDecision.HoldSteady -> _status.value = ScanStatus.HoldSteady(best, outcome.readKey, summary)
            ScanDecision.AlreadyAdded -> Unit // still in view after being added; leave the banner as is
            ScanDecision.ChooseVariant -> _status.value = ScanStatus.Found(
                outcome.results, outcome.confidence, askVariant = true, readKey = outcome.readKey, summary = summary, fromPhoto = fromPhoto,
            )
            ScanDecision.Review -> _status.value = ScanStatus.Found(
                outcome.results, outcome.confidence, askVariant = false, readKey = outcome.readKey, summary = summary, fromPhoto = fromPhoto,
            )
        }
    }

    /** One more copy of the card that was just added, without moving it. */
    fun addAnother(added: ScanStatus.AutoAdded) {
        ScanLog.d("+1 ${added.result.card.id}")
        addCopies(added.result, added.variantKey, 1)
        record(added.result, added.variantKey, 1, ScanAction.ADDED)
        _status.value = added.copy(event = System.nanoTime(), copies = added.copies + 1)
    }

    /** Logs a copy added or removed by tapping a variant button on a scan result. */
    fun recordManual(result: ScanResult, variantKey: String, delta: Int) {
        if (delta == 0) return
        ScanLog.d("manual ${if (delta > 0) "+" else ""}$delta ${result.card.id} ${variantKey}")
        if (delta > 0) {
            savePictureIfMissing(result)
            // Same confirmation as an automatic add.
            _manualAdded.value = ManualAdded(result, variantKey, System.nanoTime())
            // Later copies of this card get the same version without asking, and this copy
            // isn't auto-added again while it's still in front of the camera.
            sameCard.rememberChoice(result.card.id, variantKey)
            sameCard.onAdded(result.card.id)
            if (result.card.variants.size > 1) learning.preferVariant(result.set.language, result.card.id, variantKey)
            // Adding from a result confirms it for what was read; picking one that wasn't the first
            // guess is a correction the scanner learns from.
            val found = _status.value as? ScanStatus.Found
            val index = found?.results?.indexOfFirst { it.card.id == result.card.id } ?: -1
            if (found != null && index >= 0 && found.readKey.isNotEmpty()) {
                learning.confirm(found.readKey, LearnedCard(result.set.language, result.set.setId, result.card.id), corrected = index > 0)
            }
        }
        record(result, variantKey, delta, if (delta > 0) ScanAction.ADDED else ScanAction.REMOVED)
    }

    private fun record(result: ScanResult, variantKey: String, delta: Int, action: ScanAction) {
        val card = result.card
        val entry = ScanHistoryEntry(
            at = System.currentTimeMillis(),
            action = action,
            language = result.set.language,
            setId = result.set.setId,
            setName = result.setName,
            cardId = card.id,
            number = card.number,
            cardName = card.name,
            image = card.imageUrl("low"),
            variantKey = variantKey,
            variantLabel = card.variants.firstOrNull { it.key == variantKey }?.label ?: variantKey,
            delta = delta,
        )
        // One at a time and in order, so entries logged together (a binder page) are all kept.
        viewModelScope.launch(historyWriter) { history.append(entry) }
    }
}

/** Short description of a scan state for the log. */
private fun describe(s: ScanStatus): String = when (s) {
    ScanStatus.Searching -> "Searching"
    is ScanStatus.LookingUp -> "LookingUp(${s.summary})"
    is ScanStatus.HoldSteady -> "HoldSteady(${s.result.card.id})"
    is ScanStatus.Found -> "Found(${s.results.joinToString { it.card.id }}, ${s.confidence}${if (s.askVariant) ", ask version" else ""}${if (s.fromPhoto) ", photo" else ""})"
    is ScanStatus.AutoAdded -> "AutoAdded(${s.result.card.id} ${s.variantKey} x${s.copies})"
    is ScanStatus.NotFound -> "NotFound(${s.summary}${if (s.fromPhoto) ", photo" else ""})"
    is ScanStatus.Failed -> "Failed(${s.message})"
}

/** A lookup taking longer than this is given up (it would otherwise leave the scanner waiting). */
private const val LOOKUP_TIMEOUT_MS = 25_000L
private const val HEARTBEAT_MS = 5_000L
