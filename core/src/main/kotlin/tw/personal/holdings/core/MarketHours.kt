package tw.personal.holdings.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal data class Window(val open: Instant, val close: Instant)

/** end ≤ start 表示跨午夜（期貨夜盤 15:00–次日 05:00）。primary＝日盤，僅收盤價的來源只對它。 */
internal data class DailySession(val start: LocalTime, val end: LocalTime, val primary: Boolean = true)

internal class Schedule(val zone: ZoneId, private val sessions: List<DailySession>) {

    fun current(now: Instant, primaryOnly: Boolean = false): Window? =
        windows(now, primaryOnly).firstOrNull { now >= it.open && now < it.close }

    fun lastClosed(now: Instant, primaryOnly: Boolean = false): Window? =
        windows(now, primaryOnly).filter { it.close <= now }.maxByOrNull { it.close }

    /** 夠涵蓋連假前後的範圍就好；ZonedDateTime 處理夏令時間。 */
    private fun windows(now: Instant, primaryOnly: Boolean): List<Window> {
        val today = now.atZone(zone).toLocalDate()
        return (-8L..1L).flatMap { offset ->
            val date = today.plusDays(offset)
            if (date.dayOfWeek !in WEEKDAYS) return@flatMap emptyList()
            sessions.filter { it.primary || !primaryOnly }.map { s ->
                val endDate = if (s.end <= s.start) date.plusDays(1) else date
                Window(date.atTime(s.start).atZone(zone).toInstant(), endDate.atTime(s.end).atZone(zone).toInstant())
            }
        }
    }
}

/** 交易時段表：資料而不是分支。沒有假日表，休市由 [dueForFetch] 從快取推導。 */
object MarketHours {
    private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    private val FUTURES_DAY = DailySession(LocalTime.of(8, 45), LocalTime.of(13, 45))
    private val FUTURES_NIGHT = DailySession(LocalTime.of(15, 0), LocalTime.of(5, 0), primary = false)
    private val FUTURES_DAY_CUT: LocalTime = LocalTime.of(15, 0)

    private val twEquity = Schedule(TAIPEI, listOf(DailySession(LocalTime.of(9, 0), LocalTime.of(13, 30))))
    private val usEquity = Schedule(NEW_YORK, listOf(DailySession(LocalTime.of(9, 30), LocalTime.of(16, 0))))
    private val futuresWithNight = Schedule(TAIPEI, listOf(FUTURES_DAY, FUTURES_NIGHT))
    private val futuresDayOnly = Schedule(TAIPEI, listOf(FUTURES_DAY))

    /** null＝24 小時（加密、匯率）。 */
    internal fun scheduleOf(x: Quotable): Schedule? = when (x) {
        is TwEquity -> twEquity
        is UsEquity -> usEquity
        is TwFuture -> if (x.product.hasNightSession) futuresWithNight else futuresDayOnly
        is Crypto, is FxPair -> null
    }

    /** 這一刻屬於哪個交易日。期貨以 15:00 切：夜盤算下一個交易日。 */
    fun tradingDay(x: Quotable, at: Instant): LocalDate {
        if (x !is TwFuture) return at.atZone(scheduleOf(x)?.zone ?: TAIPEI).toLocalDate()
        val local = at.atZone(TAIPEI)
        var day = local.toLocalDate()
        if (local.toLocalTime() >= FUTURES_DAY_CUT) day = day.plusDays(1)
        while (day.dayOfWeek !in WEEKDAYS) day = day.plusDays(1)
        return day
    }
}

/** 盤中報價離現在多久以內算即時：涵蓋背景 14 分（加密 29 分）一輪加上來源延遲。 */
internal val LIVE_WINDOW: Duration = Duration.ofMinutes(45)

/** 由時間推導報價現在還能不能拿來判斷提醒。 */
fun staleness(x: Quotable, quote: Quote, now: Instant): Staleness {
    val recent = Duration.between(quote.asOf, now) <= LIVE_WINDOW
    val schedule = MarketHours.scheduleOf(x) ?: return if (recent) Staleness.LIVE else Staleness.STALE
    val closeOnly = quote.freshness == Freshness.END_OF_DAY
    val open = schedule.current(now, primaryOnly = closeOnly)
    if (open != null) {
        return if (!closeOnly && recent && quote.asOf >= open.open) Staleness.LIVE else Staleness.STALE
    }
    val last = schedule.lastClosed(now, primaryOnly = closeOnly) ?: return Staleness.STALE
    return if (quote.asOf >= last.open) Staleness.FINAL else Staleness.STALE
}
