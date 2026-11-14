package tw.personal.holdings.phone

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.personal.holdings.core.AlertFiring
import tw.personal.holdings.core.ContractMonth
import tw.personal.holdings.core.DraftResult
import tw.personal.holdings.core.Freshness
import tw.personal.holdings.core.Holding
import tw.personal.holdings.core.HoldingDraft
import tw.personal.holdings.core.HoldingId
import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.InstrumentChoice
import tw.personal.holdings.core.Price
import tw.personal.holdings.core.Quote
import tw.personal.holdings.core.TwBoard
import tw.personal.holdings.core.TwQtyUnit
import tw.personal.holdings.core.parse
import tw.personal.holdings.core.stepAlerts
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class FormatTest {
    private val today = LocalDate.of(2026, 11, 14)

    private fun holding(choice: InstrumentChoice, qty: String, name: String = "", stop: String = ""): Holding {
        val draft = HoldingDraft(choice, qty, averageCostText = "600", stopLossText = stop, entryDate = today, name = name)
        return (draft.parse(HoldingId("x"), today) as DraftResult.Ok).holding
    }

    /** 用 core 的 stepAlerts 產生真的 AlertFiring（建構子是 internal），文字由手機組。 */
    private fun firings(h: Holding, last: String, now: Instant): List<AlertFiring> {
        val q = Quote(Price.parse(last)!!, Price.parse(last)!!, now.minusSeconds(60), Freshness.LIVE)
        return stepAlerts(listOf(h), mapOf(h.asset.instrument to q), emptyMap(), now).fire
    }

    @Test
    fun `stop loss text names the stock, level and current price`() {
        val h = holding(InstrumentChoice.TwStock("2330", TwBoard.TWSE, TwQtyUnit.LOT), "1", name = "台積電", stop = "580")
        val now = Instant.parse("2026-11-13T03:00:00Z")
        val text = alertText(firings(h, "576", now).single(), today)
        assertEquals("2330 台積電 碰到停損 580（現價 576）", text)
    }

    @Test
    fun `rollover text counts calendar days to the last trading day`() {
        val month = ContractMonth.of(2026, 12)
        val h = holding(InstrumentChoice.IndexFut(IndexFuture.TX, month), "1")
        val dayBefore = month.lastTradingDay().minusDays(3)
        val now = dayBefore.atStartOfDay(tw.personal.holdings.core.TAIPEI).plusHours(10).toInstant()
        val text = alertText(firings(h, "27000", now).single { it is AlertFiring.Rollover }, dayBefore)
        assertEquals("台指期 2026-12 三天後最後交易日，記得轉倉", text)
    }

    @Test
    fun `signed numbers never show minus zero and keep grouping`() {
        assertEquals("0", signed(BigDecimal("-0.4"), 0))
        assertEquals("+1,235", signed(BigDecimal("1234.5"), 0))
        assertEquals("-12,345.68", signed(BigDecimal("-12345.678"), 2, 2))
        assertEquals("+0.50%", pctText(BigDecimal("0.5")))
        assertEquals(0, displaySign(BigDecimal("-0.004"), 2))
        assertEquals(-1, displaySign(BigDecimal("-0.005"), 2))
    }

    @Test
    fun `quantity text uses the unit the user typed`() {
        assertEquals("1.5 張", holding(InstrumentChoice.TwStock("2330", TwBoard.TWSE, TwQtyUnit.LOT), "1.5").quantityText())
        assertEquals("300 股", holding(InstrumentChoice.TwStock("2330", TwBoard.TWSE, TwQtyUnit.SHARE), "300").quantityText())
        assertEquals("0.01 BTC", holding(InstrumentChoice.Coin("btc"), "0.01").quantityText())
    }
}
