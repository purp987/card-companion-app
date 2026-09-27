package com.cardprice.app.data.scan

import com.cardprice.app.data.Language
import com.cardprice.app.data.collection.CollectionCard
import com.cardprice.app.data.collection.CollectionStore
import com.cardprice.app.data.collection.ExtraSets
import com.cardprice.app.data.collection.ImageFill
import com.cardprice.app.data.collection.TcgdexApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A scanned card ready to add: the full card (with variants), its set's name, and the set's card list for progress. */
data class ScanResult(val set: ScanSet, val setName: String, val card: CollectionCard, val setCards: List<CollectionCard>)

/** Lookup results (best first) and how sure the scanner is about the first one. */
data class ScanOutcome(val results: List<ScanResult>, val confidence: ScanConfidence, val readKey: String = "")

/**
 * Turns what the scanner read into actual cards, using the collection's cached card lists where
 * possible so repeat scans from the same set are instant.
 */
class ScanResolver(private val store: CollectionStore) {
    private val cards = ConcurrentHashMap<String, List<CollectionCard>>()
    private val names = ConcurrentHashMap<String, String>()

    /** Sets TCGplayer covers but TCGdex doesn't (Japanese 30th Celebration), as scan targets. */
    private val extraSets = ExtraSets.all.map { extra ->
        // Both halves of M6a are printed with the M6a code and "/103".
        ScanSet(extra.language, extra.id, extra.id.substringBefore("-"), officialCount = 103, totalCount = 0)
    }

    suspend fun identify(clues: ScanClues, setup: ScanSetup = ScanSetup(), hints: ScanHints = ScanHints.None): ScanOutcome = coroutineScope {
        val readKey = ScanLearning.readKey(clues, setup)
        val candidates = ScanMatcher.candidateSets(clues, extraSets, setup, hints)
        ScanLog.d("candidate sets for ${clues.summary}: ${candidates.joinToString { "${it.set.setId}(${it.score})" }.ifEmpty { "none" }}")
        val lists = candidates.map { c ->
            async {
                runCatching { c to cardsFor(c.set) }
                    .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) ScanLog.w("card list for ${c.set.setId} failed", e) }
                    .getOrNull()
            }
        }.awaitAll().filterNotNull()
        val matches = ScanMatcher.match(clues, lists, hints, readKey)
        val keep = if (ScanMatcher.isConfident(matches)) matches.take(1) else matches.take(3)
        val results = keep.map { m ->
            async {
                ScanResult(m.set, nameOf(m.set), complete(m.set, m.card), cardsFor(m.set))
            }
        }.awaitAll()
        ScanOutcome(results, ScanMatcher.confidence(matches), readKey)
    }

    private suspend fun cardsFor(set: ScanSet): List<CollectionCard> {
        val key = "${set.language}|${set.setId}"
        cards[key]?.let { return it }
        val started = System.currentTimeMillis()
        val extra = ExtraSets.find(set.language, set.setId)
        val list = when {
            extra != null -> ExtraSets.cards(extra).also { names[key] = extra.name }
            else -> withContext(Dispatchers.IO) { store.cachedCards(set.language, set.setId) }?.let { cached ->
                // Pictures missing from TCGdex (e.g. Mega Evolution promos) come from TCGplayer.
                ImageFill.fillMissing(set.language, set.setId, cached).also { filled ->
                    if (filled != cached) withContext(Dispatchers.IO) { store.cacheCards(set.language, set.setId, filled) }
                }
            } ?: when (set.language) {
                // English lists come in bulk with variants; cache them like the collection does.
                Language.ENGLISH -> ImageFill.fillMissing(set.language, set.setId, TcgdexApi.setCards(set.language, set.setId) { _, _ -> })
                    .also { withContext(Dispatchers.IO) { store.cacheCards(set.language, set.setId, it) } }
                // Other languages: numbers and names in one request; variants are fetched for the matched card.
                else -> TcgdexApi.setSummary(set.language, set.setId).let { (name, list) -> names[key] = name; list }
            }
        }
        ScanLog.d("card list for ${set.language} ${set.setId}: ${list.size} cards in ${System.currentTimeMillis() - started} ms")
        cards[key] = list
        return list
    }

    private suspend fun nameOf(set: ScanSet): String {
        val key = "${set.language}|${set.setId}"
        names[key]?.let { return it }
        val name = runCatching { TcgdexApi.setSummary(set.language, set.setId).first }.getOrDefault(set.setId)
        names[key] = name
        return name
    }

    /** Brief card entries only list a plain variant; fetch the real variants before adding. */
    private suspend fun complete(set: ScanSet, card: CollectionCard): CollectionCard {
        if (set.language == Language.ENGLISH || ExtraSets.find(set.language, set.setId) != null || card.placeholder) return card
        if (card.rarity != null) return card // already a full entry from the collection cache
        return runCatching { TcgdexApi.card(set.language, card.id) }.getOrDefault(card)
    }
}
