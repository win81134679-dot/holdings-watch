package tw.personal.holdings.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.personal.holdings.core.ContractMonth
import tw.personal.holdings.core.DraftField
import tw.personal.holdings.core.DraftProblem
import tw.personal.holdings.core.DraftResult
import tw.personal.holdings.core.HoldingId
import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.Side
import tw.personal.holdings.core.StockFutureSize
import tw.personal.holdings.core.TwBoard
import tw.personal.holdings.core.TwQtyUnit
import tw.personal.holdings.core.parse
import tw.personal.holdings.core.toDraft
import tw.personal.holdings.phone.symbols.Pick
import java.time.LocalDate

class EditFormTest {
    private val today = LocalDate.of(2026, 11, 14)
    private val id = HoldingId("h")

    private fun parse(form: EditForm) = form.toDraft(today).parse(id, today)

    @Test
    fun `every market survives holding to form to holding`() {
        val forms = listOf(
            EditForm.blank(today).copy(twCode = "6488", twBoard = TwBoard.TPEX, twUnit = TwQtyUnit.SHARE, quantity = "300", averageCost = "400.5"),
            EditForm.blank(today).copy(market = MarketTab.US, usTicker = "aapl", quantity = "1.25", averageCost = "200", side = Side.SHORT),
            EditForm.blank(today).copy(market = MarketTab.CRYPTO, coin = "BTC", quantity = "0.00000001", averageCost = "90000", stopLoss = "80000"),
            EditForm.blank(today).copy(market = MarketTab.FUTURES, futureKind = FutureKind.TMF, month = "2026-12", quantity = "2", averageCost = "27000"),
            EditForm.blank(today).copy(
                market = MarketTab.FUTURES, futureKind = FutureKind.STOCK, stockFutureCode = "cdf", stockFutureUnderlying = "2330",
                stockFutureSize = StockFutureSize.SMALL, month = "2026-12", quantity = "1", averageCost = "1000", target = "1200",
            ),
        )
        for (form in forms) {
            val first = (parse(form) as DraftResult.Ok).holding
            val again = (first.toDraft().toForm().toDraft(today).parse(id, today) as DraftResult.Ok).holding
            assertEquals(form.market.toString(), first, again)
        }
    }

    @Test
    fun `month defaults to the nearest listed contract`() {
        val form = EditForm.blank(today).copy(market = MarketTab.FUTURES, futureKind = FutureKind.TX)
        assertEquals(ContractMonth.of(2026, 11), form.selectedMonth(today))
        assertEquals(200, form.pointValueTwd())
        assertEquals(2000, form.copy(futureKind = FutureKind.STOCK).pointValueTwd())
    }

    @Test
    fun `an old contract month stays selectable when editing`() {
        val form = EditForm.blank(today).copy(market = MarketTab.FUTURES, futureKind = FutureKind.TX, month = "2026-10")
        val options = form.monthOptions(today)
        assertTrue(ContractMonth.of(2026, 10) in options)
        assertEquals(options.sorted(), options)
    }

    @Test
    fun `parse problems reach the right field with a readable message`() {
        val form = EditForm.blank(today).copy(twCode = "23", quantity = "0.0005", averageCost = "abc", stopLoss = "700", target = "650")
        val problems = (parse(form) as DraftResult.Invalid).problems
        assertEquals(DraftProblem.BAD_SYMBOL, problems[DraftField.INSTRUMENT])
        assertEquals(DraftProblem.NOT_A_NUMBER, problems[DraftField.AVERAGE_COST])
        assertEquals(DraftProblem.STOP_WRONG_SIDE_OF_TARGET, problems[DraftField.STOP_LOSS])
        assertEquals("多單的停損要低於目標", problemText(DraftField.STOP_LOSS, DraftProblem.STOP_WRONG_SIDE_OF_TARGET, form))
        assertEquals(
            "換算成股數要是整數（1 張＝1000 股）",
            problemText(DraftField.QUANTITY, problems.getValue(DraftField.QUANTITY), form),
        )
    }

    private val tsmc = Pick.TwStock("2330", "台積電", "TSMC", TwBoard.TWSE, 1)
    private val gwc = Pick.TwStock("6488", "環球晶", "GWC", TwBoard.TPEX, 1)

    @Test
    fun `picking a stock fills market, code, board and name, and the name stays editable`() {
        val picked = EditForm.blank(today).pick(gwc)
        assertEquals(MarketTab.TW, picked.market)
        assertEquals("6488", picked.twCode)
        assertEquals(TwBoard.TPEX, picked.twBoard)
        assertEquals("環球晶", picked.name)

        val renamed = picked.copy(name = "環球晶長抱", quantity = "1", averageCost = "400")
        assertEquals("環球晶長抱", (parse(renamed) as DraftResult.Ok).holding.name)
        assertEquals("picking the same stock again keeps the user's name", "環球晶長抱", renamed.pick(gwc).name)
        assertEquals("picking another stock takes its name", "台積電", renamed.pick(tsmc).name)
    }

    @Test
    fun `picking from other markets switches the form to that market`() {
        val blank = EditForm.blank(today)
        val nvda = blank.pick(Pick.UsStock("NVDA", "輝達", "NVIDIA Corporation", 1))
        assertEquals(MarketTab.US to "NVDA", nvda.market to nvda.usTicker)
        assertEquals("輝達", nvda.name)
        val btc = blank.pick(Pick.Coin("BTC", "比特幣", "Bitcoin", 1))
        assertEquals(MarketTab.CRYPTO to "BTC", btc.market to btc.coin)
        val tx = blank.pick(Pick.IndexFut(IndexFuture.TX))
        assertEquals(FutureKind.TX, tx.futureKind)
        assertEquals("the futures label already says 台指期", "", tx.name)
        val sf = blank.pick(Pick.StockFut(tsmc, ""))
        assertEquals(FutureKind.STOCK to "2330", sf.futureKind to sf.stockFutureUnderlying)
        assertEquals("", sf.stockFutureCode)
        val filled = sf.copy(stockFutureCode = "CDF").pick(Pick.StockFut(tsmc, ""))
        assertEquals("re-picking the same underlying keeps the typed TAIFEX code", "CDF", filled.stockFutureCode)
    }

    @Test
    fun `preview spells out the position before saving`() {
        val tw = EditForm.blank(today).pick(tsmc).copy(quantity = "1", averageCost = "1085")
        assertEquals("台積電 1 張 × 1,085 ＝ 1,085,000", tw.preview(today))
        assertEquals("台積電 300 股 × 1,085 ＝ 325,500", tw.copy(twUnit = TwQtyUnit.SHARE, quantity = "300").preview(today))
        assertEquals("空 台積電 1 張 × 1,085 ＝ 1,085,000", tw.copy(side = Side.SHORT).preview(today))
        val tx = EditForm.blank(today).pick(Pick.IndexFut(IndexFuture.TX)).copy(quantity = "2", averageCost = "23000")
        assertEquals("台指期 2026-11 2 口 × 23,000 ＝ 名目 9,200,000", tx.preview(today))
        val us = EditForm.blank(today).pick(Pick.UsStock("AAPL", "蘋果", "Apple Inc.", 2)).copy(quantity = "1.5", averageCost = "200.10")
        assertEquals("蘋果 1.5 股 × 200.1 ＝ 300.15 USD", us.preview(today))
        assertEquals(null, tw.copy(averageCost = "").preview(today))
        assertEquals(null, tw.copy(quantity = "0").preview(today))
    }

    @Test
    fun `a new form waits for a pick, an existing holding opens straight to the form`() {
        assertEquals(false, EditForm.blank(today).picked)
        val saved = (parse(EditForm.blank(today).pick(tsmc).copy(quantity = "1", averageCost = "1000")) as DraftResult.Ok).holding
        val reopened = saved.toDraft().toForm()
        assertEquals(true, reopened.picked)
        assertEquals("TW:2330", reopened.key())
        assertEquals("台積電", reopened.name)
    }

    @Test
    fun `form survives the saved-state round trip`() {
        val form = EditForm.blank(today).copy(market = MarketTab.FUTURES, futureKind = FutureKind.STOCK, note = "備註\n第二行")
        assertEquals(form, EditForm.decode(EditForm.encode(form)))
    }
}
