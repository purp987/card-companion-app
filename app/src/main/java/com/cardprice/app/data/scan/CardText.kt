package com.cardprice.app.data.scan

import com.cardprice.app.data.Language
import com.cardprice.app.data.collection.CollectionCard

/** What could be read off a card. [number] keeps any letter prefix (e.g. "TG05"). */
data class ScanClues(
    val number: String?,
    val total: Int?,
    val codes: List<ScanSet>,
    val nameLines: List<String>,
    val englishMarker: Boolean,
    /** Read in a promo's format ("MEP EN 073", "SWSH291", "001/SV-P"): the set code stands in for the missing total. */
    val promo: Boolean = false,
) {
    val usable: Boolean get() = number != null && (total != null || codes.isNotEmpty())

    /** With a set chosen up front, the card number alone is enough. */
    fun usableWith(setup: ScanSetup): Boolean = if (setup.setId != null) number != null else usable

    /** Short summary shown while scanning, e.g. "PBL 111/084". */
    val summary: String
        get() = listOfNotNull(codes.firstOrNull()?.code, number?.let { n -> total?.let { "$n/${"%03d".format(it)}" } ?: n })
            .joinToString(" ")
}

/**
 * Reads the printed set code and card number from recognized text. Modern cards print them at the
 * bottom (e.g. "PBL EN 111/084" or Japanese "SV2a 001/165"); older ones only print "215/203".
 */
object CardTextParser {
    // A letter prefix only counts when it touches the digits ("TG05"), not "EN 111".
    private val NUMBER = Regex("""(?<![A-Za-z0-9])([A-Z]{0,3})(\d{1,3})\s?/\s?[A-Z]{0,3}\s?(\d{2,3})(?!\d)""")
    private val TOKEN = Regex("""[A-Za-z0-9+]{2,6}""")
    private val STOP_WORDS = setOf(
        "basic", "stage", "stage1", "stage2", "evolves", "from", "pokemon", "pokémon", "weakness", "resistance",
        "retreat", "trainer", "item", "supporter", "stadium", "energy", "illus", "hp", "ability", "tool", "mega",
    )

    private val englishCodes = ScanIndex.sets.filter { it.language == Language.ENGLISH && it.code != null }.groupBy { it.code!! }
    private val japaneseCodes = ScanIndex.sets.filter { it.language == Language.JAPANESE && it.code != null }
        .groupBy { it.code!!.uppercase() }
    private val chineseCodes = ScanIndex.sets.filter { it.language == Language.CHINESE_SIMPLIFIED && it.code != null }
        .groupBy { it.code!!.uppercase() }
    // Mainland Chinese codes: CSV9C, csv4C, CSV9.5C, CS4aC, CSM2.5C, CBB2C. Printed in a box of their own,
    // so they're looked for on every line.
    private val CHINESE_CODE = Regex("""(?<![A-Za-z0-9])(C(?:SV|SM|S|BB)\d{1,2}(?:\.\d)?[A-Ca-c]?C)(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)

    fun parse(lines: List<String>): ScanClues {
        var number: String? = null
        var total: Int? = null
        var numberLine: String? = null
        for (line in lines) {
            val m = NUMBER.find(fixDigits(line)) ?: continue
            number = m.groupValues[1] + m.groupValues[2]
            total = m.groupValues[3].toInt()
            numberLine = line
            break
        }
        // Promos print no "/total", so they're only read when no regular number was found.
        val promo = if (number == null) lines.firstNotNullOfOrNull { promoOf(fixDigits(it)) } else null
        if (promo != null) {
            val nameLines = nameLinesOf(lines)
            return ScanClues(promo.second, null, listOf(promo.first), nameLines, lines.any { "EN" in TOKEN.findAll(it).map { t -> t.value } }, promo = true)
        }

        fun tokensOf(line: String) = TOKEN.findAll(line).map { it.value }.toList()
        val englishMarker = lines.any { "EN" in tokensOf(it) }
        // Set codes are printed beside the number ("PBL EN 111/084"), so only look there; elsewhere
        // words like "HP" would collide with old set codes (HP = Holon Phantoms).
        val tokens = lines.filter { it == numberLine || "EN" in tokensOf(it) }.flatMap(::tokensOf)
        // English codes are printed in capitals (PBL, MEW); Japanese ones are set ids (SV2a, M6).
        // The regulation mark sits right before the code and OCR often joins them ("JPBL").
        val withoutMark = tokens.filter { it.length == 4 && it[0].isUpperCase() }.map { it.drop(1) }
        val chinese = lines.flatMap { line -> CHINESE_CODE.findAll(line).map { it.groupValues[1].uppercase() }.toList() }
            .flatMap { chineseCodes[it].orEmpty() }
        val codes = ((tokens + withoutMark).flatMap { englishCodes[it].orEmpty() } + tokens.flatMap { japaneseCodes[it.uppercase()].orEmpty() } + chinese)
            .distinct()

        return ScanClues(number, total, codes, nameLinesOf(lines), englishMarker)
    }

    private fun nameLinesOf(lines: List<String>) = lines.map { it.trim() }
        .filter { line -> line.count(Char::isLetter) >= 3 && line.count(Char::isDigit) <= 2 }
        .filter { line -> normalize(line) !in STOP_WORDS }
        .take(8)

    // "MEP EN 073" / "SVP EN 085" (the regulation mark may be joined on: "IMEP").
    private val CODED_PROMO = Regex("""(?<![A-Za-z0-9])[A-Z]?(MEP|SVP)\s*(?:EN\s*)?(\d{1,3})(?![\d/])""")
    // "SWSH291", "SM213", "XY121", "BW82": the number carries the series.
    private val PREFIXED_PROMO = Regex("""(?<![A-Za-z0-9])(SWSH|SM|XY|BW)\s?(\d{2,3})(?![\d/])""")
    // Japanese "001/SV-P", "012/M-P".
    private val JAPANESE_PROMO = Regex("""(?<!\d)(\d{1,3})\s?/\s?([A-Z]{1,2})\s?-\s?P(?![A-Za-z])""")
    private val prefixedPromoSets = mapOf("SWSH" to "swshp", "SM" to "smp", "XY" to "xyp", "BW" to "bwp")

    /** The promo set and card number (as TCGdex numbers it) printed on this line, if any. */
    private fun promoOf(line: String): Pair<ScanSet, String>? {
        CODED_PROMO.find(line)?.let { m ->
            val set = englishCodes[m.groupValues[1]]?.firstOrNull() ?: return@let
            return set to m.groupValues[2].padStart(3, '0')
        }
        PREFIXED_PROMO.find(line)?.let { m ->
            val set = ScanIndex.sets.firstOrNull { it.language == Language.ENGLISH && it.setId == prefixedPromoSets[m.groupValues[1]] } ?: return@let
            return set to m.groupValues[1] + m.groupValues[2]
        }
        JAPANESE_PROMO.find(line)?.let { m ->
            val set = japaneseCodes["${m.groupValues[2]}-P"]?.firstOrNull() ?: return@let
            return set to m.groupValues[1].padStart(3, '0')
        }
        return null
    }

    /** OCR often reads 0 as O and 1 as I/l next to other digits; fix those around numbers and slashes. */
    internal fun fixDigits(s: String): String = s
        .replace(Regex("""(?<=[\d/])[Oo]|[Oo](?=[\d/])"""), "0")
        .replace(Regex("""(?<=[\d/])[Il|]|[Il|](?=[\d/])"""), "1")

    internal fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}

data class ScanCandidate(
    val set: ScanSet,
    val score: Int,
    val codeMatched: Boolean = false,
    val totalMatched: Boolean = false,
    val promo: Boolean = false,
)

data class ScanMatch(
    val set: ScanSet,
    val card: CollectionCard,
    val score: Int,
    val codeMatched: Boolean = false,
    val totalMatched: Boolean = false,
    val nameScore: Int = 0,
    /** The user confirmed this card for this exact reading before. */
    val learned: Boolean = false,
    /** Read from a promo number (no "/total"). */
    val promo: Boolean = false,
)

/** How sure the scanner is about its best match. Only [HIGH] may add a card automatically. */
enum class ScanConfidence { HIGH, MEDIUM, LOW }

object ScanMatcher {
    /** Sets the card could belong to, best first. */
    fun candidateSets(
        clues: ScanClues,
        extra: List<ScanSet> = emptyList(),
        setup: ScanSetup = ScanSetup(),
        hints: ScanHints = ScanHints.None,
    ): List<ScanCandidate> {
        if (!clues.usableWith(setup)) return emptyList()
        val all = (ScanIndex.sets + extra).filter { setup.language == null || it.language == setup.language }
        val found = baseCandidates(clues, all, setup)
        // A set this reading was confirmed in before is always considered, even if the code wasn't read this time.
        val learnedSet = hints.learned(ScanLearning.readKey(clues, setup))?.let { l ->
            all.firstOrNull { it.language == l.language && it.setId == l.setId }
        }
        val withLearned = if (learnedSet != null && found.none { it.set == learnedSet }) {
            found + ScanCandidate(learnedSet, 15, codeMatched = true, totalMatched = true)
        } else found
        // Sets scanned often win close calls.
        return withLearned.map { it.copy(score = it.score + hints.setBoost(it.set.language, it.set.setId)) }
            .sortedByDescending { it.score }
            .take(MAX_SETS)
    }

    private fun baseCandidates(clues: ScanClues, all: List<ScanSet>, setup: ScanSetup): List<ScanCandidate> {
        // A set the user picked counts like a printed code: they've said where the card is from.
        if (setup.setId != null) {
            return all.filter { it.setId == setup.setId }.map { set ->
                val totalMatched = clues.total == null || set.officialCount == clues.total || set.officialCount == 0
                ScanCandidate(set, 10 + if (totalMatched) 5 else 0, codeMatched = true, totalMatched = totalMatched)
            }
        }
        // Some promo sets print no code of their own ("SWSH291"), so also match the set itself.
        val byCode = clues.codes.map { code ->
            all.filter { it.language == code.language && (it.setId == code.setId || (code.code != null && it.code.equals(code.code, true))) }
        }.flatten()
        // Chinese cards have no names to check against, so a count alone only points to a Chinese set
        // when the user said they're scanning Chinese cards (or its code was read).
        val byTotal = clues.total?.let { t ->
            all.filter { it.officialCount == t && (it.language != Language.CHINESE_SIMPLIFIED || setup.language == Language.CHINESE_SIMPLIFIED) }
        }.orEmpty()
        return (byCode + byTotal).distinct().map { set ->
            val codeMatched = set in byCode
            // A promo's code is its only set clue; it stands in for the total.
            val totalMatched = (clues.total != null && set.officialCount == clues.total) || (clues.promo && codeMatched)
            var score = 0
            if (codeMatched) score += 10
            if (totalMatched) score += 5
            if (clues.englishMarker && set.language == Language.ENGLISH) score += 2
            if (clues.englishMarker && set.language != Language.ENGLISH) score -= 5
            ScanCandidate(set, score, codeMatched, totalMatched, promo = clues.promo)
        }
    }

    /** Picks the card with the scanned number from each candidate set's list, scored by how well the name matches. */
    fun match(
        clues: ScanClues,
        candidates: List<Pair<ScanCandidate, List<CollectionCard>>>,
        hints: ScanHints = ScanHints.None,
        readKey: String? = null,
    ): List<ScanMatch> {
        val number = clues.number ?: return emptyList()
        val learned = readKey?.let(hints::learned)
        return candidates.mapNotNull { (candidate, cards) ->
            val card = cards.firstOrNull { sameNumber(it.number, number) } ?: return@mapNotNull null
            val name = nameScore(card.name, clues.nameLines)
            val isLearned = learned != null && learned.cardId == card.id && learned.language == candidate.set.language
            val rejected = readKey != null && hints.isRejected(readKey, card.id)
            val score = candidate.score + name + (if (isLearned) LEARNED_BONUS else 0) - (if (rejected) REJECTED_PENALTY else 0)
            ScanMatch(candidate.set, card, score, candidate.codeMatched, candidate.totalMatched, name, isLearned && !rejected, candidate.promo)
        }.sortedByDescending { it.score }
    }

    /** True when the best match is clearly ahead of the next one. */
    fun isConfident(matches: List<ScanMatch>): Boolean {
        val best = matches.firstOrNull() ?: return false
        val runnerUp = matches.getOrNull(1) ?: return best.score >= 10
        return best.score - runnerUp.score >= 4
    }

    /**
     * [ScanConfidence.HIGH] needs three things: the number's "/total" matches the set, either the printed
     * set code or the card's name confirms it, and no other match comes close.
     */
    fun confidence(matches: List<ScanMatch>): ScanConfidence {
        val best = matches.firstOrNull() ?: return ScanConfidence.LOW
        if (!isConfident(matches)) return ScanConfidence.LOW
        // A reading the user confirmed before counts as sure.
        if (best.learned) return ScanConfidence.HIGH
        // Promos have no total to cross-check the code, so the name must agree too.
        val confirmed = best.totalMatched && when {
            best.promo -> best.codeMatched && best.nameScore >= NAME_CONFIRMS
            else -> best.codeMatched || best.nameScore >= NAME_CONFIRMS
        }
        return if (confirmed) ScanConfidence.HIGH else ScanConfidence.MEDIUM
    }

    internal fun sameNumber(cardNumber: String, scanned: String): Boolean {
        fun split(s: String) = s.uppercase().let { v -> v.takeWhile(Char::isLetter) to v.dropWhile(Char::isLetter).trimStart('0') }
        return split(cardNumber) == split(scanned)
    }

    internal fun nameScore(cardName: String, lines: List<String>): Int {
        val name = CardTextParser.normalize(cardName)
        if (name.length < 3) return 0
        var best = 0
        for (line in lines) {
            val text = CardTextParser.normalize(line)
            if (text.isEmpty()) continue
            val score = when {
                text == name -> 8
                text.contains(name) || (text.length >= 4 && name.contains(text)) -> 6
                similarity(text, name) >= 0.75 -> 4
                else -> 0
            }
            best = maxOf(best, score)
        }
        return best
    }

    private fun similarity(a: String, b: String): Double {
        val d = levenshtein(a, b)
        return 1.0 - d.toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private const val MAX_SETS = 6

    /** A name that contains or equals the card's name (see [nameScore]). */
    private const val NAME_CONFIRMS = 6

    /** Enough to put a previously confirmed card clearly on top. */
    private const val LEARNED_BONUS = 20

    /** Enough to push a card the user rejected for this reading below the others. */
    private const val REJECTED_PENALTY = 15
}
