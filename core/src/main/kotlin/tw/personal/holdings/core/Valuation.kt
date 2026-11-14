package tw.personal.holdings.core

import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 手機與手錶共同的估值輸入：持股＋相關報價（含 USD_TWD）。同一份輸入、同一個 [valuate]，四個畫面數字一定一致。 */
data class PortfolioSnapshot(
    val holdings: List<Holding>,
    val quotes: Map<Quotable, Quote>,
)

/** 純函式，沒有 I/O 也不讀時鐘。金額保留完整精度，四捨五入只在顯示層做。 */
fun valuate(snapshot: PortfolioSnapshot, now: Instant): Valuation {
    val usdTwd = snapshot.quotes[FxPair.USD_TWD]?.last
    val today = now.atZone(TAIPEI).toLocalDate()
    val positions = snapshot.holdings.map { h ->
        val days = ChronoUnit.DAYS.between(h.entryDate, today)
        val quote = snapshot.quotes[h.asset.instrument]
        when {
            h.isExpired(now) -> PositionValue.Expired(h, days)
            quote == null -> PositionValue.NoQuote(h, days)
            else -> priced(h, days, quote, usdTwd)
        }
    }
    val priced = positions.filterIsInstance<PositionValue.Priced>()
    val counted = priced.filter { it.pnlTwd != null }
    val missing = positions.size - counted.size
    val dayPnl = counted.fold(Twd.ZERO) { sum, p -> sum + p.dayPnlTwd!! }
    val costBasis = counted.sumOf { p ->
        val h = p.holding
        toTwd(h.averageCost.value * h.asset.exposure, h.asset.instrument.currency, usdTwd)!!.amount.abs()
    }
    return Valuation(
        positions = positions,
        totals = Totals(
            pnl = Partial(counted.fold(Twd.ZERO) { sum, p -> sum + p.pnlTwd!! }, missing),
            dayPnl = Partial(dayPnl, missing),
            dayPnlPct = if (costBasis.signum() == 0) null else percent(dayPnl.amount, costBasis),
        ),
        oldestQuoteAt = priced.minOfOrNull { it.quote.asOf },
    )
}

private fun priced(h: Holding, days: Long, q: Quote, usdTwd: Price?): PositionValue.Priced {
    val sign = BigDecimal(h.side.sign)
    val exposure = h.asset.exposure
    val currency = h.asset.instrument.currency
    val cost = h.averageCost.value
    val pnl = (q.last.value - cost) * exposure * sign
    val dayPnl = (q.last.value - q.previousClose.value) * exposure * sign
    return PositionValue.Priced(
        holding = h,
        daysHeld = days,
        quote = q,
        pnl = Money(pnl, currency),
        pnlPct = percent(q.last.value - cost, cost) * sign,
        dayPnl = Money(dayPnl, currency),
        dayChangePct = percent(q.last.value - q.previousClose.value, q.previousClose.value),
        pnlTwd = toTwd(pnl, currency, usdTwd),
        dayPnlTwd = toTwd(dayPnl, currency, usdTwd),
        exitStatus = h.exits.status(h.side, q.last),
    )
}

private fun toTwd(amount: BigDecimal, currency: Currency, usdTwd: Price?): Twd? = when (currency) {
    Currency.TWD -> Twd(amount)
    Currency.USD -> usdTwd?.let { Twd(amount * it.value) }
}

private fun percent(part: BigDecimal, whole: BigDecimal): BigDecimal =
    part.divide(whole, MathContext.DECIMAL64) * HUNDRED

private val HUNDRED = BigDecimal(100)

data class Valuation(
    val positions: List<PositionValue>,
    val totals: Totals,
    /** 參與估值的報價裡最舊的一筆；沒有任何報價時為 null。 */
    val oldestQuoteAt: Instant?,
) {
    /** 固定順序 TW_EQUITY、US_EQUITY、TW_FUTURES、CRYPTO；空的市場不出現。 */
    fun byMarket(): Map<Market, List<PositionValue>> {
        val groups = positions.groupBy { it.holding.asset.instrument.market }
        return MARKET_ORDER.mapNotNull { m -> groups[m]?.let { m to it } }.toMap()
    }

    /** 依商品本身今日漲跌 % 的絕對值由大到小；沒報價、已到期的排除。 */
    fun topMovers(n: Int = 3): List<PositionValue.Priced> =
        positions.filterIsInstance<PositionValue.Priced>().sortedByDescending { it.dayChangePct.abs() }.take(n)

    private companion object {
        val MARKET_ORDER = listOf(Market.TW_EQUITY, Market.US_EQUITY, Market.TW_FUTURES, Market.CRYPTO)
    }
}

sealed interface PositionValue {
    val holding: Holding
    val daysHeld: Long

    data class Priced(
        override val holding: Holding,
        override val daysHeld: Long,
        val quote: Quote,
        val pnl: Money,
        /** 百分比數值：1.5 表示 1.5%。已乘方向。 */
        val pnlPct: BigDecimal,
        val dayPnl: Money,
        /** 商品本身的今日漲跌 %，不乘方向。 */
        val dayChangePct: BigDecimal,
        /** 美元部位缺匯率時為 null；台幣部位永遠有值。 */
        val pnlTwd: Twd?,
        val dayPnlTwd: Twd?,
        val exitStatus: ExitStatus,
    ) : PositionValue

    data class NoQuote(override val holding: Holding, override val daysHeld: Long) : PositionValue

    /** 期貨過了最後交易日：畫面標「已到期，請在手機結清」，不再抓價、不再提醒。 */
    data class Expired(override val holding: Holding, override val daysHeld: Long) : PositionValue
}

/** 總計可能不完整：缺報價、缺匯率、已到期的部位不加，但記下少了幾筆。 */
data class Partial<T>(val value: T, val missing: Int)

data class Totals(
    val pnl: Partial<Twd>,
    val dayPnl: Partial<Twd>,
    /** 今日台幣損益 ÷ 各部位台幣成本絕對值總和（期貨用名目成本），百分比數值；分母為 0 時為 null。 */
    val dayPnlPct: BigDecimal?,
)
