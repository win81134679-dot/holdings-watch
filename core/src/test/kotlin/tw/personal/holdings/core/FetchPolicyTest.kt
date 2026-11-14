package tw.personal.holdings.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class FetchPolicyTest {
    private val tsmcHolding = holding(draft(twStock(), "1", "580"))
    private val tsmc = tsmcHolding.asset.instrument
    private val cdfHolding = holding(
        draft(InstrumentChoice.StockFut("CDF", "2330", StockFutureSize.STANDARD, ContractMonth.of(2026, 12)), "1", "1500"),
        id = "cdf",
    )
    private val cdf = cdfHolding.asset.instrument
    private val intraday: (Quotable) -> SourceCapability = { SourceCapability.INTRADAY }
    private val cdfCloseOnly: (Quotable) -> SourceCapability =
        { if (it == cdf) SourceCapability.CLOSE_ONLY else SourceCapability.INTRADAY }

    private fun cached(x: Quotable, fetchedAt: Instant, asOf: Instant = fetchedAt, failure: QuoteEntry.Failure? = null) =
        MarketData(mapOf(x to QuoteEntry(quote("600", asOf = asOf), fetchedAt, failure)))

    private fun due(
        holdings: List<Holding>,
        data: MarketData,
        now: String,
        mode: RefreshMode = RefreshMode.BACKGROUND,
        capability: (Quotable) -> SourceCapability = intraday,
    ): Set<Quotable> = dueForFetch(holdings, data, mode, taipei(now), capability)

    @Test
    fun `沒有快取的商品一定抓`() {
        assertEquals(setOf(tsmc), due(listOf(tsmcHolding), MarketData.EMPTY, "2026-11-16T20:00"))
    }

    @Test
    fun `盤中背景約 15 分鐘一輪，前景 2 分鐘`() {
        val data = cached(tsmc, taipei("2026-11-16T10:00"))
        val holdings = listOf(tsmcHolding)

        assertEquals(emptySet<Quotable>(), due(holdings, data, "2026-11-16T10:10"))
        assertEquals(setOf(tsmc), due(holdings, data, "2026-11-16T10:14"))
        assertEquals(setOf(tsmc), due(holdings, data, "2026-11-16T10:02", RefreshMode.FOREGROUND))
    }

    @Test
    fun `收盤後等 10 分鐘補抓一次，之後整晚不抓`() {
        val holdings = listOf(tsmcHolding)
        val beforeClose = cached(tsmc, taipei("2026-11-16T13:20"))
        val afterCatchUp = cached(tsmc, taipei("2026-11-16T13:41"))

        assertEquals(emptySet<Quotable>(), due(holdings, beforeClose, "2026-11-16T13:35"))
        assertEquals(setOf(tsmc), due(holdings, beforeClose, "2026-11-16T13:41"))
        assertEquals(emptySet<Quotable>(), due(holdings, afterCatchUp, "2026-11-16T20:00"))
        assertEquals(emptySet<Quotable>(), due(holdings, afterCatchUp, "2026-11-17T08:00", RefreshMode.MANUAL))
    }

    @Test
    fun `僅收盤價的商品收盤後抓一次，盤中與夜裡都不抓`() {
        val holdings = listOf(cdfHolding)
        val gotClose = cached(cdf, taipei("2026-11-16T14:00"))
        val gotMorning = cached(cdf, taipei("2026-11-16T09:00"))

        assertEquals(emptySet<Quotable>(), due(holdings, gotClose, "2026-11-16T22:00", capability = cdfCloseOnly))
        assertEquals(emptySet<Quotable>(), due(holdings, gotClose, "2026-11-17T10:00", capability = cdfCloseOnly))
        assertEquals(setOf(cdf), due(holdings, gotClose, "2026-11-17T13:56", capability = cdfCloseOnly))
        assertEquals(emptySet<Quotable>(), due(holdings, gotMorning, "2026-11-16T11:00", capability = cdfCloseOnly))
        assertEquals(setOf(cdf), due(holdings, gotMorning, "2026-11-16T22:00", capability = cdfCloseOnly))
    }

    @Test
    fun `持有美元部位時一起抓匯率，匯率一天一次`() {
        val aaplHolding = holding(draft(InstrumentChoice.UsStock("AAPL"), "1", "300"), id = "aapl")
        val aapl = aaplHolding.asset.instrument
        val now = "2026-11-16T23:00"
        val aaplFresh = QuoteEntry(quote("300", asOf = taipei("2026-11-16T22:55")), taipei("2026-11-16T22:55"), null)
        fun fx(fetchedAt: String) = QuoteEntry(quote("32", asOf = taipei(fetchedAt)), taipei(fetchedAt), null)

        val fetchedToday = MarketData(mapOf(aapl to aaplFresh, FxPair.USD_TWD to fx("2026-11-16T08:00")))
        val fetchedYesterday = MarketData(mapOf(aapl to aaplFresh, FxPair.USD_TWD to fx("2026-11-15T23:30")))

        assertEquals(setOf(aapl, FxPair.USD_TWD), due(listOf(aaplHolding), MarketData.EMPTY, now))
        assertEquals(emptySet<Quotable>(), due(listOf(aaplHolding), fetchedToday, now))
        assertEquals(setOf<Quotable>(FxPair.USD_TWD), due(listOf(aaplHolding), fetchedYesterday, now))
        assertEquals(setOf(tsmc), due(listOf(tsmcHolding), MarketData.EMPTY, now))
    }

    @Test
    fun `失敗後退避：找不到代號退 6 小時，網路錯誤依次數加長`() {
        val holdings = listOf(tsmcHolding)
        fun failedAt(at: String, reason: FetchFailure, consecutive: Int) =
            cached(tsmc, taipei("2026-11-16T09:00"), failure = QuoteEntry.Failure(taipei(at), reason, consecutive))

        assertEquals(emptySet<Quotable>(), due(holdings, failedAt("2026-11-16T10:00", FetchFailure.NotFound, 1), "2026-11-16T13:00"))
        assertEquals(emptySet<Quotable>(), due(holdings, failedAt("2026-11-16T10:00", FetchFailure.Network, 1), "2026-11-16T10:01"))
        assertEquals(setOf(tsmc), due(holdings, failedAt("2026-11-16T10:00", FetchFailure.Network, 1), "2026-11-16T10:03"))
        assertEquals(emptySet<Quotable>(), due(holdings, failedAt("2026-11-16T10:00", FetchFailure.Network, 5), "2026-11-16T10:30"))
    }

    @Test
    fun `開盤半小時後報價還停在前一天，當作今天休市不再抓`() {
        val holdings = listOf(tsmcHolding)
        val yesterdayClose = taipei("2026-11-13T13:30")

        assertEquals(emptySet<Quotable>(), due(holdings, cached(tsmc, taipei("2026-11-16T09:45"), asOf = yesterdayClose), "2026-11-16T11:00"))
        assertEquals(setOf(tsmc), due(holdings, cached(tsmc, taipei("2026-11-16T09:10"), asOf = yesterdayClose), "2026-11-16T11:00"))
    }

    @Test
    fun `到期的期貨不抓`() {
        val expired = holding(
            draft(tx("2026-10"), "1", "23000", entryDate = LocalDate.of(2026, 9, 1)),
            today = LocalDate.of(2026, 10, 1),
        )

        assertEquals(emptySet<Quotable>(), due(listOf(expired), MarketData.EMPTY, "2026-11-16T10:00"))
    }

    @Test
    fun `同一輪的失敗結果併兩次，連續失敗次數不會多算`() {
        val now = taipei("2026-11-16T10:00")
        val failed = mapOf<Quotable, FetchResult>(tsmc to FetchResult.Failed(FetchFailure.Network))

        val once = MarketData.EMPTY.merge(failed, now)
        val twice = once.merge(failed, now)
        val nextRound = twice.merge(failed, now.plusSeconds(900))

        assertEquals(once, twice)
        assertEquals(2, nextRound.entries.getValue(tsmc).failure?.consecutive)
    }
}
