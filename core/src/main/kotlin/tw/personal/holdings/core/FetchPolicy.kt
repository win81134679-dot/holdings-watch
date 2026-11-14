package tw.personal.holdings.core

import java.time.Duration
import java.time.Instant

enum class RefreshMode { BACKGROUND, FOREGROUND, MANUAL }

/**
 * 這一輪要抓的商品。WorkManager 只負責叫醒，抓不抓全由這裡決定，頻率只有一個真相來源。
 * 持有美元計價的部位時自動包含 [FxPair.USD_TWD]；已到期的期貨不抓。
 * [capabilityOf] 由 :quotes 的路由提供（哪個商品只有收盤價）。
 */
fun dueForFetch(
    holdings: List<Holding>,
    data: MarketData,
    mode: RefreshMode,
    now: Instant,
    capabilityOf: (Quotable) -> SourceCapability,
): Set<Quotable> {
    val instruments = holdings.filterNot { it.isExpired(now) }.map { it.asset.instrument }.toSet()
    val wanted: Set<Quotable> =
        if (instruments.any { it.currency == Currency.USD }) instruments + FxPair.USD_TWD else instruments
    return wanted.filterTo(linkedSetOf()) { isDue(it, data.entries[it], mode, now, capabilityOf(it)) }
}

private fun isDue(x: Quotable, e: QuoteEntry?, mode: RefreshMode, now: Instant, capability: SourceCapability): Boolean {
    val failure = e?.failure
    if (failure != null && now < failure.at + backoff(failure)) return false
    val quote = e?.quote
    val fetchedAt = e?.fetchedAt
    if (quote == null || fetchedAt == null) return true
    if (x is FxPair) return fetchedAt.atZone(TAIPEI).toLocalDate() < now.atZone(TAIPEI).toLocalDate()

    val schedule = MarketHours.scheduleOf(x) ?: return Duration.between(fetchedAt, now) >= interval(mode, x.market)
    if (capability == SourceCapability.CLOSE_ONLY) {
        return missedClose(schedule.lastClosed(now, primaryOnly = true), fetchedAt, now)
    }
    val open = schedule.current(now) ?: return missedClose(schedule.lastClosed(now), fetchedAt, now)
    val closedToday = quote.asOf < open.open && fetchedAt >= open.open + HOLIDAY_PROBE
    return !closedToday && Duration.between(fetchedAt, now) >= interval(mode, x.market)
}

/** 收盤＋寬限之後還沒抓過，就補抓一次收盤價。 */
private fun missedClose(last: Window?, fetchedAt: Instant, now: Instant): Boolean {
    val settled = (last ?: return false).close + SETTLE_GRACE
    return now >= settled && fetchedAt < settled
}

/** 背景 14 分而不是 15：WorkManager 提早幾秒叫醒時不會整整錯過一輪。 */
internal fun interval(mode: RefreshMode, market: Market): Duration = when (mode) {
    RefreshMode.BACKGROUND -> if (market == Market.CRYPTO) Duration.ofMinutes(29) else Duration.ofMinutes(14)
    RefreshMode.FOREGROUND -> Duration.ofMinutes(2)
    RefreshMode.MANUAL -> Duration.ofSeconds(30)
}

/** 收盤後等多久才補抓收盤價（來源需要時間定稿）。 */
internal val SETTLE_GRACE: Duration = Duration.ofMinutes(10)

/** 開盤這麼久之後報價還停在開盤前，就當作今天休市（沒有假日表）。 */
internal val HOLIDAY_PROBE: Duration = Duration.ofMinutes(30)

internal fun backoff(f: QuoteEntry.Failure): Duration = when (val reason = f.reason) {
    FetchFailure.NotFound -> Duration.ofHours(6)
    is FetchFailure.Throttled -> reason.retryAfter ?: Duration.ofMinutes(30)
    else -> Duration.ofMinutes(minOf(1L shl minOf(f.consecutive, 6), 60L))
}
