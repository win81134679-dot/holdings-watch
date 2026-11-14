package tw.personal.holdings.phone.symbols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.personal.holdings.core.DraftResult
import tw.personal.holdings.core.Holding
import tw.personal.holdings.core.HoldingDraft
import tw.personal.holdings.core.HoldingId
import tw.personal.holdings.core.InstrumentChoice
import tw.personal.holdings.core.TwBoard
import tw.personal.holdings.core.TwQtyUnit
import tw.personal.holdings.core.parse
import java.io.File
import java.time.LocalDate

/** 用 APK 內建的真名單測，名單重產之後這些也要成立。 */
class SymbolIndexTest {
    private val index: SymbolIndex = bundledIndex()

    private fun top(query: String): Pick = index.search(query).first()

    @Test
    fun `every kind of input finds the stock the user means first`() {
        val expected = linkedMapOf(
            "2330" to "TW:2330",
            "台積" to "TW:2330",
            "tsmc" to "TW:2330",
            "TSM" to "US:TSM",
            "蘋果" to "US:AAPL",
            "aapl" to "US:AAPL",
            "輝達" to "US:NVDA",
            "比特" to "C:BTC",
            "btc" to "C:BTC",
            "bitcoin" to "C:BTC",
            "台指" to "F:TX",
            "TX" to "F:TX",
            "小台" to "F:MTX",
            "微台" to "F:TMF",
            "環球晶" to "TW:6488",
            "6488" to "TW:6488",
        )
        val actual = expected.keys.associateWith { top(it).key }
        assertEquals(expected, actual)
    }

    @Test
    fun `a picked stock carries its market board and name`() {
        val gwc = top("環球晶") as Pick.TwStock
        assertEquals(TwBoard.TPEX, gwc.board)
        assertEquals("環球晶", gwc.autoName)
        assertEquals(TwBoard.TWSE, (top("2330") as Pick.TwStock).board)
        assertEquals("輝達", top("輝達").autoName)
    }

    @Test
    fun `full width, case, spaces and 臺 do not matter`() {
        assertEquals("TW:2330", top("２３３０").key)
        assertEquals("US:AAPL", top("ＡＡＰＬ").key)
        assertEquals("US:TSM", top(" tsm ").key)
        assertEquals("TW:2330", top("臺積　").key)
        assertEquals("US:BRK-B", top("brk.b").key)
    }

    @Test
    fun `ranking follows code exact, code prefix, name prefix, name contains, english`() {
        val small = SymbolIndex(
            tw = listOf(
                Pick.TwStock("1101", "台泥", "TCC", TwBoard.TWSE, 50),
                Pick.TwStock("2330", "台積電", "TSMC", TwBoard.TWSE, 10),
                Pick.TwStock("9999", "中台", "TAIWAN", TwBoard.TWSE, 1000),
                Pick.TwStock("1100", "亞台", "AT", TwBoard.TWSE, 1),
                Pick.TwStock("11011", "台泥二", "", TwBoard.TWSE, 5000),
            ),
            us = emptyList(),
            coins = emptyList(),
        )
        assertEquals(listOf("TW:1101", "TW:11011"), small.search("1101").filterNot { it is Pick.StockFut }.map { it.key }.take(2))
        assertEquals(
            "name prefix by volume, then name contains",
            listOf("TW:11011", "TW:1101", "TW:2330", "TW:9999", "TW:1100"),
            small.search("台").filterIsInstance<Pick.TwStock>().map { it.key },
        )
        assertEquals(listOf("TW:9999"), small.search("taiwan").filterIsInstance<Pick.TwStock>().map { it.key })
    }

    @Test
    fun `english names match within words, not across the gap between them`() {
        val small = SymbolIndex(
            tw = listOf(Pick.TwStock("2330", "台積電", "TSMC", TwBoard.TWSE, 1)),
            us = listOf(Pick.UsStock("SMCZ", "", "Defiance Daily Target 2X Short SMCI ETF", null)),
            coins = emptyList(),
        )
        assertEquals(listOf("TW:2330"), small.search("tsmc").filterNot { it.manual || it is Pick.StockFut }.map { it.key })
        assertEquals(listOf("US:SMCZ"), small.search("short smci").filterNot { it.manual }.map { it.key })
    }

    @Test
    fun `stock futures are found by the underlying name or code`() {
        val withSuffix = index.search("台積期貨").first()
        assertEquals("SF:2330:", withSuffix.key)
        assertTrue(index.search("2330").any { it.key == "SF:2330:" })
        assertEquals("2330", (withSuffix as Pick.StockFut).underlying.code)
    }

    @Test
    fun `a code missing from the list can still be added as typed`() {
        val manual = index.search("0000").single { it.manual }
        assertEquals("TW:0000", manual.key)
        assertTrue(index.search("2330").none { it.manual })
        assertTrue(index.search("").isEmpty())
    }

    @Test
    fun `empty input lists recently used then held, without repeats`() {
        val today = LocalDate.of(2026, 11, 14)
        fun holding(choice: InstrumentChoice) = (
            HoldingDraft(choice, "1", averageCostText = "100", entryDate = today).parse(HoldingId(choice.toString()), today)
                as DraftResult.Ok
            ).holding
        val held: List<Holding> = listOf(
            holding(InstrumentChoice.TwStock("2330", TwBoard.TWSE, TwQtyUnit.LOT)),
            holding(InstrumentChoice.Coin("BTC")),
        )
        val s = index.suggest(listOf("US:AAPL", "TW:2330", "US:NO-SUCH"), held)
        assertEquals(listOf("US:AAPL", "TW:2330"), s.recent.map { it.key })
        assertEquals(listOf("C:BTC"), s.held.map { it.key })
    }

    @Test
    fun `recently used keeps the newest first and at most eight`() {
        val keys = (1..8).map { "TW:000$it" }
        assertEquals(listOf("TW:0003") + keys.filterNot { it == "TW:0003" }, pushRecent(keys, "TW:0003"))
        val pushed = pushRecent(keys, "US:AAPL")
        assertEquals(8, pushed.size)
        assertEquals("US:AAPL", pushed.first())
        assertTrue("TW:0008" !in pushed)
    }

    companion object {
        private val ASSETS = File("src/main/assets/symbols")

        fun bundledIndex(): SymbolIndex = SymbolIndex(
            SymbolLists.parseTw(File(ASSETS, "tw.tsv").readText()),
            SymbolLists.parseUs(File(ASSETS, "us.tsv").readText()),
            SymbolLists.parseCrypto(File(ASSETS, "crypto.csv").readText()),
        )
    }
}
