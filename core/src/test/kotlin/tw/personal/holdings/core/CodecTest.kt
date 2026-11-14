package tw.personal.holdings.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class CodecTest {
    private val asOf = taipei("2026-11-16T10:00").plusNanos(123_456_789)

    private val holdings = listOf(
        holding(draft(twStock(unit = TwQtyUnit.LOT), "2", "580", stop = "550", target = "700").copy(name = "台積電", note = "長抱"), id = "a"),
        holding(draft(twStock(code = "6488", unit = TwQtyUnit.SHARE, board = TwBoard.TPEX), "250", "630"), id = "b"),
        holding(draft(InstrumentChoice.UsStock("BRK-B"), "0.375", "410.25"), id = "c"),
        holding(draft(tx(), "2", "23000", side = Side.SHORT, stop = "23500", target = "22000"), id = "d"),
        holding(draft(InstrumentChoice.StockFut("QFF", "2330", StockFutureSize.SMALL, ContractMonth.of(2027, 3)), "1", "1500"), id = "e"),
        holding(draft(InstrumentChoice.Coin("BTC"), "0.00012345", "68000.5"), id = "f"),
    )

    private val quotes: Map<Quotable, Quote> = mapOf(
        holdings[0].asset.instrument to quote("600", previousClose = "590", asOf = asOf, low = "549", high = "602"),
        holdings[3].asset.instrument to quote("23100", asOf = asOf, freshness = Freshness.DELAYED),
        holdings[4].asset.instrument to quote("1510", asOf = asOf, freshness = Freshness.END_OF_DAY),
        FxPair.USD_TWD to quote("32.87", asOf = asOf),
    )

    private fun <T> ok(d: Decoded<T>): T {
        assertTrue("$d", d is Decoded.Ok)
        return (d as Decoded.Ok).value
    }

    @Test
    fun `持股存檔讀回來一模一樣`() {
        assertEquals(holdings, ok(PortfolioCodec.decodeHoldings(PortfolioCodec.encodeHoldings(holdings))))
    }

    @Test
    fun `同步封包讀回來一樣，而且內容相同時位元組相同`() {
        val snapshot = PortfolioSnapshot(holdings, quotes)
        val reordered = PortfolioSnapshot(holdings, quotes.entries.reversed().associate { it.toPair() })

        val bytes = PortfolioCodec.encodeSnapshot(snapshot)

        assertEquals(snapshot, ok(PortfolioCodec.decodeSnapshot(bytes)))
        assertArrayEquals(bytes, PortfolioCodec.encodeSnapshot(reordered))
    }

    @Test
    fun `刷新狀態連同失敗紀錄與提醒狀態讀回來一樣`() {
        val step = stepAlerts(holdings, quotes, emptyMap(), asOf.plusSeconds(60))
        val market = MarketData(
            mapOf(
                holdings[0].asset.instrument to QuoteEntry(quotes.getValue(holdings[0].asset.instrument), asOf, null),
                holdings[2].asset.instrument to QuoteEntry(
                    null,
                    null,
                    QuoteEntry.Failure(asOf, FetchFailure.Throttled(Duration.ofSeconds(90)), 2),
                ),
                holdings[3].asset.instrument to QuoteEntry(
                    quotes.getValue(holdings[3].asset.instrument),
                    asOf.minusSeconds(900),
                    QuoteEntry.Failure(asOf, FetchFailure.Unparseable("無 price 欄位"), 1),
                ),
                holdings[5].asset.instrument to QuoteEntry(null, null, QuoteEntry.Failure(asOf, FetchFailure.NotFound, 3)),
                FxPair.USD_TWD to QuoteEntry(quotes.getValue(FxPair.USD_TWD), asOf, null),
            ),
        )
        val state = RefreshState(market, step.next)
        assertTrue("fixture should contain a fired alert", step.next.values.any { it is AlertState.Fired })

        assertEquals(state, ok(PortfolioCodec.decodeRefreshState(PortfolioCodec.encodeRefreshState(state))))
    }

    @Test
    fun `比這支 App 新的格式回 TooNew，不丟例外`() {
        val future = """{"version":99,"body":{"holdings":[],"quotes":[],"somethingNew":true}}""".toByteArray()

        assertEquals(Decoded.TooNew(99), PortfolioCodec.decodeSnapshot(future))
        assertEquals(Decoded.TooNew(99), PortfolioCodec.decodeHoldings("""{"version":99,"body":"?"}""".toByteArray()))
    }

    @Test
    fun `看不懂或欄位不合法回 Corrupt，不丟例外`() {
        val valid = String(PortfolioCodec.encodeHoldings(holdings.take(1)))
        val negativeCost = valid.replace("\"580\"", "\"-580\"")
        val wrongSideStop = valid.replace("\"550\"", "\"750\"")
        val unknownMarket = valid.replace("TW_EQUITY", "MARS")

        for (bad in listOf("", "not json", "[]", """{"body":[]}""", negativeCost, wrongSideStop, unknownMarket)) {
            assertTrue("<$bad>", PortfolioCodec.decodeHoldings(bad.toByteArray()) is Decoded.Corrupt)
        }
        assertTrue(PortfolioCodec.decodeRefreshState(byteArrayOf(0x7b, 0x7b)) is Decoded.Corrupt)
        assertTrue(PortfolioCodec.decodeSnapshot("{\"version\":1}".toByteArray()) is Decoded.Corrupt)
    }
}
