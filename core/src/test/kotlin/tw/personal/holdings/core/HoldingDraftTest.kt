package tw.personal.holdings.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class HoldingDraftTest {

    private fun problems(d: HoldingDraft, today: LocalDate = TODAY): Map<DraftField, DraftProblem> =
        when (val r = d.parse(HoldingId("x"), today)) {
            is DraftResult.Invalid -> r.problems
            is DraftResult.Ok -> emptyMap()
        }

    @Test
    fun `台股輸入張會換算成股，輸入股就是股`() {
        val lots = holding(draft(twStock(unit = TwQtyUnit.LOT), quantity = "3", cost = "580"))
        val shares = holding(draft(twStock(unit = TwQtyUnit.SHARE), quantity = "250", cost = "580"))

        assertEquals(3000L, (lots.asset as TwEquityAsset).shares.count)
        assertEquals(250L, (shares.asset as TwEquityAsset).shares.count)
    }

    @Test
    fun `台股張數可以是半張，但換算後不是整股就退回`() {
        assertEquals(1500L, (holding(draft(twStock(), "1.5", "580")).asset as TwEquityAsset).shares.count)
        assertEquals(mapOf(DraftField.QUANTITY to DraftProblem.TOO_MANY_DECIMALS), problems(draft(twStock(), "0.0005", "580")))
    }

    @Test
    fun `每個壞欄位都標在自己的欄下`() {
        val bad = draft(
            instrument = twStock(code = "23"),
            quantity = "abc",
            cost = "",
            stop = "-3",
            target = "0",
            entryDate = TODAY.plusDays(1),
        )

        assertEquals(
            mapOf(
                DraftField.INSTRUMENT to DraftProblem.BAD_SYMBOL,
                DraftField.QUANTITY to DraftProblem.NOT_A_NUMBER,
                DraftField.AVERAGE_COST to DraftProblem.REQUIRED,
                DraftField.STOP_LOSS to DraftProblem.NOT_POSITIVE,
                DraftField.TARGET to DraftProblem.NOT_POSITIVE,
                DraftField.ENTRY_DATE to DraftProblem.FUTURE_DATE,
            ),
            problems(bad),
        )
    }

    @Test
    fun `停損與目標的順序依多空方向檢查`() {
        val longWrong = draft(twStock(), "1", "580", side = Side.LONG, stop = "600", target = "550")
        val shortRight = draft(twStock(), "1", "580", side = Side.SHORT, stop = "600", target = "550")

        assertEquals(mapOf(DraftField.STOP_LOSS to DraftProblem.STOP_WRONG_SIDE_OF_TARGET), problems(longWrong))
        assertEquals(emptyMap<DraftField, DraftProblem>(), problems(shortRight))
    }

    @Test
    fun `加密貨幣超過 8 位小數退回，期貨口數要整數，已到期合約不能新增`() {
        assertEquals(
            mapOf(DraftField.QUANTITY to DraftProblem.TOO_MANY_DECIMALS),
            problems(draft(InstrumentChoice.Coin("btc"), "0.123456789", "68000")),
        )
        assertEquals(mapOf(DraftField.QUANTITY to DraftProblem.TOO_MANY_DECIMALS), problems(draft(tx(), "1.5", "23000")))
        assertEquals(
            mapOf(DraftField.INSTRUMENT to DraftProblem.CONTRACT_EXPIRED),
            problems(draft(tx("2026-10"), "1", "23000")),
        )
    }

    @Test
    fun `千分位與空白可以輸入，代號轉大寫，空的選填欄位是 null`() {
        val h = holding(draft(InstrumentChoice.UsStock(" brk-b "), quantity = "0.5", cost = "1,234.50"))

        assertEquals("BRK-B", (h.asset.instrument as UsEquity).ticker.value)
        assertEquals(0, BigDecimal("1234.5").compareTo(h.averageCost.value))
        assertEquals(null, h.stopLoss)
        assertEquals(null, h.name)
    }

    @Test
    fun `持股還原成表單再解析，得到同一筆`() {
        val original = listOf(
            holding(draft(twStock(unit = TwQtyUnit.LOT), "2", "580", stop = "550", target = "700").copyName("台積電")),
            holding(draft(InstrumentChoice.StockFut("CDF", "2330", StockFutureSize.STANDARD, ContractMonth.of(2026, 12)), "1", "1500", side = Side.SHORT)),
            holding(draft(InstrumentChoice.Coin("ETH"), "0.12345678", "3000")),
        )

        for (h in original) {
            val again = h.toDraft().parse(h.id, TODAY)
            assertTrue("$h", again is DraftResult.Ok)
            assertEquals(h, (again as DraftResult.Ok).holding)
        }
    }

    private fun HoldingDraft.copyName(name: String) = copy(name = name)
}

class ContractMonthTest {

    @Test
    fun `最後交易日是交割月份第三個星期三`() {
        assertEquals(LocalDate.of(2026, 12, 16), ContractMonth.of(2026, 12).lastTradingDay())
        assertEquals(LocalDate.of(2026, 11, 18), ContractMonth.of(2026, 11).lastTradingDay())
        assertEquals(LocalDate.of(2027, 1, 20), ContractMonth.of(2027, 1).lastTradingDay())
    }

    @Test
    fun `掛牌月份：指數期貨連續三個月加三個季月，當月過了最後交易日就跳過`() {
        val beforeExpiry = ContractMonth.listed(IndexFuture.TX, LocalDate.of(2026, 11, 18))
        val afterExpiry = ContractMonth.listed(IndexFuture.TX, LocalDate.of(2026, 11, 19))

        assertEquals(listOf("2026-11", "2026-12", "2027-01", "2027-03", "2027-06", "2027-09"), beforeExpiry.map { it.toString() })
        assertEquals(listOf("2026-12", "2027-01", "2027-02", "2027-03", "2027-06", "2027-09"), afterExpiry.map { it.toString() })
    }
}
