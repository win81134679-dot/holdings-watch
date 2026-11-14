package tw.personal.holdings.phone.symbols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class SymbolListsTest {
    private val tools = File("../tools")
    private fun raw(name: String) = SymbolLists.decode(File(tools, "testdata/raw/$name").readBytes())
    private fun expected(name: String) = File(tools, "testdata/expected/$name").readText()

    private val zhNames: List<Pair<String, String>> =
        File(tools, "us_zh_names.csv").readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            line.substringBefore(',').trim() to line.substringAfter(',').trim()
        }

    @Test
    fun `the app builds the same lists as tools build_symbols py`() {
        val tw = SymbolLists.buildTw(raw("twse_companies.json"), raw("twse_day_all.json"), raw("tpex_companies.json"), raw("tpex_daily.json"))
        val us = SymbolLists.buildUs(raw("nasdaqlisted.txt"), raw("otherlisted.txt"), zhNames)
        assertEquals(expected("tw.tsv"), SymbolLists.twTsv(tw, "TEST"))
        assertEquals(expected("us.tsv"), SymbolLists.usTsv(us, "TEST"))
    }

    @Test
    fun `without the TPEx daily file only TPEx companies are listed for that board`() {
        val tw = SymbolLists.buildTw(raw("twse_companies.json"), raw("twse_day_all.json"), raw("tpex_companies.json"), null)
        val codes = tw.map { it.code }
        assertTrue("6488" in codes)
        assertTrue("00679B" !in codes)
    }

    @Test
    fun `bundled lists read back exactly as written`() {
        val assets = File("src/main/assets/symbols")
        for ((name, roundTrip) in listOf<Pair<String, (String) -> String>>(
            "tw.tsv" to { t -> SymbolLists.twTsv(SymbolLists.parseTw(t), SymbolLists.stampOf(t)!!) },
            "us.tsv" to { t -> SymbolLists.usTsv(SymbolLists.parseUs(t), SymbolLists.stampOf(t)!!) },
        )) {
            val text = File(assets, name).readText()
            assertEquals(name, text, roundTrip(text))
        }
        assertEquals(20, SymbolLists.parseCrypto(File(assets, "crypto.csv").readText()).size)
    }

    @Test
    fun `TPEx responses decode whether they come as UTF-8 with BOM or Big5`() {
        val json = """[{"CompanyAbbreviation":"環球晶"}]"""
        assertEquals(json, SymbolLists.decode(json.toByteArray(Charset.forName("Big5"))))
        assertEquals(json, SymbolLists.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + json.toByteArray()))
    }

    @Test
    fun `US names drop the share class boilerplate`() {
        assertEquals("Apple Inc.", SymbolLists.usName("Apple Inc. - Common Stock"))
        assertEquals("Ternium S.A.", SymbolLists.usName("Ternium S.A. Ternium S.A. American Depositary Shares (each representing ten shares)"))
    }
}
