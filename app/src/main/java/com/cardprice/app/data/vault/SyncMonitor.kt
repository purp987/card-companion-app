package com.cardprice.app.data.vault

/**
 * `GET /api/app/sync` reply (Bearer token). The revs are counters the server bumps with SQLite triggers on
 * every insert/update/delete of holdings, portfolios and watchlist rows, whatever made the change (this
 * app, the other app, the web UI, an import or a cleanup). [pricesDay] changes when a new daily price set
 * lands, which changes portfolio values too.
 *
 * `{ holdingsRev, portfoliosRev, watchlistRev, pricesDay, lastSync, serverTime }`
 */
data class SyncState(
    val holdingsRev: Long,
    val portfoliosRev: Long,
    val watchlistRev: Long,
    val pricesDay: String?,
    val lastSync: String? = null,
    val serverTime: String? = null,
)

/** Which kinds of server data changed since the last poll. */
data class SyncSignals(
    val holdings: Boolean = false,
    val portfolios: Boolean = false,
    val watchlist: Boolean = false,
    val prices: Boolean = false,
) {
    val any: Boolean get() = holdings || portfolios || watchlist || prices
}

sealed interface PollResult {
    /** First answer: the baseline (screens just loaded fresh data anyway). */
    data object Baseline : PollResult
    data object Unchanged : PollResult
    data class Changed(val signals: SyncSignals) : PollResult
    /** Server unreachable; polling backs off. */
    data class Offline(val error: VaultException) : PollResult
    /** 401: the phone isn't paired (or was revoked). */
    data object Unauthorized : PollResult
    /** 404: an older server without `/api/app/sync`; polling stops for this run. */
    data object Unsupported : PollResult
}

/**
 * Remembers the last revs seen and turns each `/api/app/sync` answer into "what changed". Pure logic
 * (no Android, no timers) so it can be unit-tested; [Vault] runs it every [INTERVAL_MS] while the app is
 * in the foreground.
 */
class SyncMonitor(private val fetch: () -> SyncState) {
    var last: SyncState? = null
        private set
    /** True after a 404: the server can't tell us about changes, so the app falls back to reloading after its own edits. */
    var unsupported = false
        private set
    private var failures = 0

    /** Time of the last successful poll (ms), for "Updated hh:mm". */
    var lastOkAt: Long? = null
        private set

    fun poll(now: Long = System.currentTimeMillis()): PollResult {
        if (unsupported) return PollResult.Unsupported
        val state = try {
            fetch()
        } catch (e: VaultException.NotFound) {
            unsupported = true
            return PollResult.Unsupported
        } catch (e: VaultException.Unauthorized) {
            return PollResult.Unauthorized
        } catch (e: VaultException) {
            failures++
            return PollResult.Offline(e)
        }
        failures = 0
        lastOkAt = now
        val before = last
        last = state
        if (before == null) return PollResult.Baseline
        val signals = SyncSignals(
            holdings = state.holdingsRev != before.holdingsRev,
            portfolios = state.portfoliosRev != before.portfoliosRev,
            watchlist = state.watchlistRev != before.watchlistRev,
            prices = state.pricesDay != before.pricesDay,
        )
        return if (signals.any) PollResult.Changed(signals) else PollResult.Unchanged
    }

    /** Forget the baseline (e.g. after re-pairing or a server change): the next answer is a new baseline. */
    fun reset() {
        last = null
        failures = 0
        unsupported = false
    }

    /** Wait before the next poll: every 20 s, backing off (40 s, 80 s … 5 min) while the server can't be reached. */
    fun nextDelayMs(): Long =
        if (failures == 0) INTERVAL_MS else (INTERVAL_MS shl (failures.coerceAtMost(4))).coerceAtMost(MAX_BACKOFF_MS)

    companion object {
        const val INTERVAL_MS = 20_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
    }
}
