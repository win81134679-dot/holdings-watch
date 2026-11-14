package tw.personal.holdings.core

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class AlertKind { STOP_LOSS, TARGET, ROLLOVER }

/**
 * 規則的身分。價位或合約月份是鍵的一部分：停損從 580 改成 560 就是新規則、從 Armed 開始；
 * 舊規則推不出來，下一輪對帳時自然消失。
 */
@ConsistentCopyVisibility
data class AlertKey internal constructor(val holdingId: HoldingId, val kind: AlertKind, val discriminator: String) {
    /** 通知 tag 與存檔鍵，跨行程穩定（不能用 hashCode：enum 的 hashCode 是物件身分）。 */
    fun stableTag(): String = "${holdingId.value}|${kind.name}|$discriminator"

    companion object {
        internal fun forLevel(id: HoldingId, kind: AlertKind, level: Price) =
            AlertKey(id, kind, level.value.stripTrailingZeros().toPlainString())

        internal fun forRollover(id: HoldingId, month: ContractMonth) = AlertKey(id, AlertKind.ROLLOVER, month.toString())
    }
}

sealed interface AlertState {
    /** 最後一次判斷用的報價時間。同一筆報價不判斷第二次，否則區間低點會在重新武裝後再觸發一次。 */
    val judgedAsOf: Instant?

    data class Armed(override val judgedAsOf: Instant? = null) : AlertState
    data class Fired(val at: Instant, override val judgedAsOf: Instant?) : AlertState
}

/** 這一輪要發的通知。文字由手機組，中文字串不進 core。 */
sealed interface AlertFiring {
    val key: AlertKey
    val holding: Holding

    data class PriceCrossed(
        override val key: AlertKey,
        override val holding: Holding,
        val level: Price,
        val last: Price,
    ) : AlertFiring

    data class Rollover(
        override val key: AlertKey,
        override val holding: Holding,
        val lastTradingDay: LocalDate,
    ) : AlertFiring
}

data class AlertStep(
    /** 下一輪的完整狀態表，只含從目前持股推得出來的鍵。 */
    val next: Map<AlertKey, AlertState>,
    val fire: List<AlertFiring>,
)

val DEFAULT_REARM_BAND: BigDecimal = BigDecimal("0.01")

const val ROLLOVER_DAYS_BEFORE: Long = 3

/**
 * 提醒的唯一入口，純函式。呼叫端要「先發 [AlertStep.fire]、再存 [AlertStep.next]」：
 * 兩步之間當掉會重發同一則（被通知 tag 取代），反過來會漏發。
 */
fun stepAlerts(
    holdings: List<Holding>,
    quotes: Map<Quotable, Quote>,
    states: Map<AlertKey, AlertState>,
    now: Instant,
    rearmBand: BigDecimal = DEFAULT_REARM_BAND,
): AlertStep {
    val next = linkedMapOf<AlertKey, AlertState>()
    val fire = mutableListOf<AlertFiring>()
    val today = now.atZone(TAIPEI).toLocalDate()

    fun priceRule(h: Holding, kind: AlertKind, direction: Direction, level: Price, quote: Quote?) {
        val key = AlertKey.forLevel(h.id, kind, level)
        val prev = states[key] ?: AlertState.Armed()
        val judged = prev.judgedAsOf
        if (quote == null || (judged != null && quote.asOf <= judged)) {
            next[key] = prev
            return
        }
        val low = minOf(quote.low?.value ?: quote.last.value, quote.last.value)
        val high = maxOf(quote.high?.value ?: quote.last.value, quote.last.value)
        next[key] = when (prev) {
            is AlertState.Armed ->
                if (crossed(direction, low, high, level)) {
                    fire += AlertFiring.PriceCrossed(key, h, level, quote.last)
                    AlertState.Fired(now, quote.asOf)
                } else {
                    AlertState.Armed(quote.asOf)
                }
            is AlertState.Fired ->
                if (backBeyondBand(direction, quote.last, level, rearmBand)) AlertState.Armed(quote.asOf)
                else prev.copy(judgedAsOf = quote.asOf)
        }
    }

    for (h in holdings) {
        val instrument = h.asset.instrument
        if (h.isExpired(now)) continue
        val quote = quotes[instrument]?.takeIf { staleness(instrument, it, now) != Staleness.STALE }
        h.stopLoss?.let { priceRule(h, AlertKind.STOP_LOSS, h.side.stopDirection, it, quote) }
        h.target?.let { priceRule(h, AlertKind.TARGET, h.side.targetDirection, it, quote) }
        if (instrument is TwFuture) {
            val lastDay = instrument.month.lastTradingDay()
            val key = AlertKey.forRollover(h.id, instrument.month)
            val prev = states[key] ?: AlertState.Armed()
            next[key] = if (prev is AlertState.Armed && today >= lastDay.minusDays(ROLLOVER_DAYS_BEFORE)) {
                fire += AlertFiring.Rollover(key, h, lastDay)
                AlertState.Fired(now, null)
            } else {
                prev
            }
        }
    }
    return AlertStep(next, fire)
}

private fun backBeyondBand(direction: Direction, last: Price, level: Price, band: BigDecimal): Boolean = when (direction) {
    Direction.BELOW -> last.value > level.value * (BigDecimal.ONE + band)
    Direction.ABOVE -> last.value < level.value * (BigDecimal.ONE - band)
}
