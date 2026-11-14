package tw.personal.holdings.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

fun assertNum(expected: String, actual: BigDecimal?) {
    assertTrue("expected $expected but was $actual", actual != null && BigDecimal(expected).compareTo(actual) == 0)
}

fun assertNumApprox(expected: String, actual: BigDecimal?, scale: Int = 4) {
    assertEquals(BigDecimal(expected).setScale(scale, RoundingMode.HALF_UP), actual!!.setScale(scale, RoundingMode.HALF_UP))
}

class ValuationTest {
    private val now = taipei("2026-11-16T10:00")
    private val asOf = taipei("2026-11-16T09:58")

    private fun valuateOne(h: Holding, vararg quotes: Pair<Quotable, Quote>): PositionValue =
        valuate(PortfolioSnapshot(listOf(h), mapOf(*quotes)), now).positions.single()

    private val tsmc = TwEquity(TwCode.parse("2330")!!, TwBoard.TWSE)

    @Test
    fun `多單漲了賺錢，空單同樣的價格賠一樣多`() {
        val q = quote("600", previousClose = "590", asOf = asOf)
        val long = valuateOne(holding(draft(twStock(), "2", "580", side = Side.LONG)), tsmc to q) as PositionValue.Priced
        val short = valuateOne(holding(draft(twStock(), "2", "580", side = Side.SHORT)), tsmc to q) as PositionValue.Priced

        assertEquals(Currency.TWD, long.pnl.currency)
        assertNum("40000", long.pnl.amount)
        assertNum("20000", long.dayPnl.amount)
        assertNum("-40000", short.pnl.amount)
        assertNum("-20000", short.dayPnl.amount)
        assertNumApprox("3.4483", long.pnlPct)
        assertNumApprox("-3.4483", short.pnlPct)
        assertNumApprox("1.6949", short.dayChangePct)
        assertNum("40000", long.pnlTwd?.amount)
    }

    @Test
    fun `美股小數股以美元算損益，再用匯率換台幣`() {
        val aapl = holding(draft(InstrumentChoice.UsStock("AAPL"), "0.5", "300"))
        val q = quote("357.5", previousClose = "350", asOf = asOf)
        val usd = UsEquity(UsTicker.parse("AAPL")!!)

        val p = valuateOne(aapl, usd to q, FxPair.USD_TWD to quote("32", asOf = asOf)) as PositionValue.Priced

        assertEquals(Currency.USD, p.pnl.currency)
        assertNum("28.75", p.pnl.amount)
        assertNum("920", p.pnlTwd?.amount)
        assertNum("120", p.dayPnlTwd?.amount)
    }

    @Test
    fun `缺匯率時美元部位不加進台幣總計，但標出缺了一筆`() {
        val holdings = listOf(
            holding(draft(twStock(), "1", "580"), id = "tw"),
            holding(draft(InstrumentChoice.UsStock("AAPL"), "1", "300"), id = "us"),
        )
        val quotes: Map<Quotable, Quote> = mapOf(
            tsmc to quote("600", previousClose = "590", asOf = asOf),
            UsEquity(UsTicker.parse("AAPL")!!) to quote("310", asOf = asOf),
        )

        val v = valuate(PortfolioSnapshot(holdings, quotes), now)

        assertNum("20000", v.totals.pnl.value.amount)
        assertEquals(1, v.totals.pnl.missing)
        assertEquals(1, v.totals.dayPnl.missing)
        val us = v.positions.first { it.holding.id.value == "us" } as PositionValue.Priced
        assertNum("10", us.pnl.amount)
        assertNull(us.pnlTwd)
    }

    @Test
    fun `期貨損益乘上每點價值與口數`() {
        val month = ContractMonth.of(2026, 12)
        val holdings = listOf(
            holding(draft(tx(), "1", "23000"), id = "tx"),
            holding(draft(InstrumentChoice.IndexFut(IndexFuture.MTX, month), "2", "23000"), id = "mtx"),
            holding(draft(InstrumentChoice.IndexFut(IndexFuture.TMF, month), "3", "23000", side = Side.SHORT), id = "tmf"),
            holding(draft(InstrumentChoice.StockFut("CDF", "2330", StockFutureSize.STANDARD, month), "1", "1500"), id = "cdf"),
            holding(draft(InstrumentChoice.StockFut("QFF", "2330", StockFutureSize.SMALL, month), "1", "1500"), id = "qff"),
        )
        val index = quote("23100", asOf = asOf)
        val stock = quote("1510", asOf = asOf)
        val cdf = (holdings[3].asset.instrument)
        val qff = (holdings[4].asset.instrument)
        val quotes: Map<Quotable, Quote> = mapOf(
            TwFuture(IndexFuture.TX, month) to index,
            TwFuture(IndexFuture.MTX, month) to index,
            TwFuture(IndexFuture.TMF, month) to index,
            cdf to stock,
            qff to stock,
        )

        val pnl = valuate(PortfolioSnapshot(holdings, quotes), now).positions
            .associate { it.holding.id.value to (it as PositionValue.Priced).pnl.amount }

        assertNum("20000", pnl["tx"])
        assertNum("10000", pnl["mtx"])
        assertNum("-3000", pnl["tmf"])
        assertNum("20000", pnl["cdf"])
        assertNum("1000", pnl["qff"])
    }

    @Test
    fun `過了最後交易日的期貨顯示已到期，不算進總計`() {
        val october = holding(draft(tx("2026-10"), "1", "23000", entryDate = LocalDate.of(2026, 9, 1)), today = LocalDate.of(2026, 10, 1))
        val q = quote("23100", asOf = asOf)

        val v = valuate(PortfolioSnapshot(listOf(october), mapOf<Quotable, Quote>(october.asset.instrument to q)), now)

        assertTrue(v.positions.single() is PositionValue.Expired)
        assertEquals(1, v.totals.pnl.missing)
        assertNum("0", v.totals.pnl.value.amount)
    }

    @Test
    fun `今日總損益百分比的分母是各部位台幣成本絕對值總和`() {
        val holdings = listOf(
            holding(draft(twStock(), "1", "500"), id = "long"),
            holding(draft(twStock(code = "0050"), "1", "100", side = Side.SHORT), id = "short"),
        )
        val quotes: Map<Quotable, Quote> = mapOf(
            tsmc to quote("510", previousClose = "505", asOf = asOf),
            TwEquity(TwCode.parse("0050")!!, TwBoard.TWSE) to quote("102", previousClose = "101", asOf = asOf),
        )

        val totals = valuate(PortfolioSnapshot(holdings, quotes), now).totals

        assertNum("4000", totals.dayPnl.value.amount)
        assertNumApprox("0.6667", totals.dayPnlPct)
    }

    @Test
    fun `沒有報價的部位只有一種缺值狀態，總百分比分母為 0 時是 null`() {
        val v = valuate(PortfolioSnapshot(listOf(holding(draft(twStock(), "1", "580"))), emptyMap()), now)

        assertTrue(v.positions.single() is PositionValue.NoQuote)
        assertNull(v.totals.dayPnlPct)
        assertNull(v.oldestQuoteAt)
    }

    @Test
    fun `依市場固定順序分組，漲跌最多的依商品漲跌幅絕對值排序`() {
        val holdings = listOf(
            holding(draft(InstrumentChoice.Coin("BTC"), "0.1", "60000"), id = "btc"),
            holding(draft(twStock(), "1", "580"), id = "tsmc"),
            holding(draft(InstrumentChoice.UsStock("AAPL"), "1", "300"), id = "aapl"),
            holding(draft(twStock(code = "0050"), "1", "100", side = Side.SHORT), id = "0050"),
        )
        val quotes: Map<Quotable, Quote> = mapOf(
            Crypto(CoinSymbol.parse("BTC")!!) to quote("63000", previousClose = "60000", asOf = asOf),
            tsmc to quote("600", previousClose = "599", asOf = asOf),
            TwEquity(TwCode.parse("0050")!!, TwBoard.TWSE) to quote("110", previousClose = "100", asOf = taipei("2026-11-16T09:30")),
        )

        val v = valuate(PortfolioSnapshot(holdings, quotes), now)

        assertEquals(listOf(Market.TW_EQUITY, Market.US_EQUITY, Market.CRYPTO), v.byMarket().keys.toList())
        assertEquals(listOf("tsmc", "0050"), v.byMarket()[Market.TW_EQUITY]!!.map { it.holding.id.value })
        assertEquals(listOf("0050", "btc"), v.topMovers(2).map { it.holding.id.value })
        assertEquals(taipei("2026-11-16T09:30"), v.oldestQuoteAt)
    }

    @Test
    fun `持有天數以台北日曆日計算`() {
        val p = valuateOne(holding(draft(twStock(), "1", "580", entryDate = LocalDate.of(2026, 11, 2))))

        assertEquals(14L, p.daysHeld)
    }

    @Test
    fun `看板的停損目標狀態跟著目前價格`() {
        val h = holding(draft(twStock(), "1", "580", stop = "550", target = "650"))
        fun status(last: String) = (valuateOne(h, tsmc to quote(last, asOf = asOf)) as PositionValue.Priced).exitStatus

        assertEquals(ExitStatus.STOP_HIT, status("550"))
        assertEquals(ExitStatus.CLEAR, status("600"))
        assertEquals(ExitStatus.TARGET_HIT, status("651"))
    }
}
