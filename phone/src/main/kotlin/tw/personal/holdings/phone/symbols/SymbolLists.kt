package tw.personal.holdings.phone.symbols

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import tw.personal.holdings.core.TwBoard
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * 名單的產生與讀寫。產生規則與 tools/build_symbols.py 一樣（App 每週自己更新時用這份），
 * 兩邊對同一份樣本的輸出由 SymbolListsTest 比對 tools/testdata/expected/。
 */
object SymbolLists {
    /** 跟 build_symbols.py 的 SOURCES 同一組；檔名是原始回應的鍵。 */
    val SOURCES: Map<String, String> = linkedMapOf(
        "twse_companies.json" to "https://openapi.twse.com.tw/v1/opendata/t187ap03_L",
        "twse_day_all.json" to "https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL",
        "tpex_companies.json" to "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap03_O",
        "tpex_daily.json" to "https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes",
        "nasdaqlisted.txt" to "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt",
        "otherlisted.txt" to "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt",
    )
    val OPTIONAL: Set<String> = setOf("tpex_daily.json")

    private val TW_CODE = Regex("^[0-9]{4}[0-9A-Z]{0,2}$")
    private val US_TICKER = Regex("^[A-Z][A-Z0-9.\\-]{0,9}$")
    private val US_EXCLUDE = Regex(
        "\\b(warrants?|rights?|units?|preferred|preference|notes?|debentures?|depositary shares? representing|" +
            "subordinated|perpetual|trust preferred|when issued|contingent value)\\b|%",
        RegexOption.IGNORE_CASE,
    )
    private val US_SUFFIX = Regex(
        "\\s*[-,]?\\s*(class [a-z] )?(common stock|capital stock|ordinary shares?|common shares?|american depositary shares?|" +
            "american depository shares?|shares of beneficial interest|beneficial interest)\\b.*$",
        RegexOption.IGNORE_CASE,
    )
    private val json = Json { ignoreUnknownKeys = true }

    /** TPEx 的回應可能是 UTF-8（含 BOM）也可能是 Big5；UTF-8 解不開才用 Big5。 */
    fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return String(bytes, Charset.forName("Big5"))
        }
        return text.removePrefix("﻿")
    }

    fun clean(text: String?): String =
        Normalizer.normalize(text ?: "", Normalizer.Form.NFKC)
            .splitWhen { it.isWhitespace() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private fun String.splitWhen(isSeparator: (Char) -> Boolean): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (c in this) {
            if (isSeparator(c)) {
                parts += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
        }
        parts += current.toString()
        return parts
    }

    private fun volume(text: String): Long {
        val digits = text.replace(",", "").trim()
        return if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) digits.toLong() else 0
    }

    private fun records(text: String): List<JsonObject> = json.parseToJsonElement(text).jsonArray.map { it as JsonObject }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    private class TwRow(val board: TwBoard) {
        var name = ""
        var english = ""
        var volume = 0L
    }

    fun buildTw(twseCompanies: String, twseDayAll: String, tpexCompanies: String, tpexDaily: String?): List<Pick.TwStock> {
        val rows = linkedMapOf<String, TwRow>()
        fun put(rawCode: String, name: String, english: String, board: TwBoard, vol: Long) {
            val code = clean(rawCode).uppercase()
            if (!TW_CODE.matches(code)) return
            val row = rows.getOrPut(code) { TwRow(board) }
            if (row.name.isEmpty()) row.name = clean(name)
            if (row.english.isEmpty()) row.english = clean(english)
            row.volume = maxOf(row.volume, vol)
        }
        for (r in records(twseDayAll)) put(r.str("Code"), r.str("Name"), "", TwBoard.TWSE, volume(r.str("TradeVolume")))
        for (r in records(twseCompanies)) put(r.str("公司代號"), r.str("公司簡稱"), r.str("英文簡稱"), TwBoard.TWSE, 0)
        val tpexCodes = mutableSetOf<String>()
        for (r in records(tpexCompanies)) {
            tpexCodes += clean(r.str("SecuritiesCompanyCode")).uppercase()
            put(r.str("SecuritiesCompanyCode"), r.str("CompanyAbbreviation"), r.str("Symbol"), TwBoard.TPEX, 0)
        }
        if (tpexDaily != null) {
            for (r in records(tpexDaily)) {
                val code = clean(r.str("SecuritiesCompanyCode")).uppercase()
                // 日行情也有權證、可轉債；只收公司名單裡有的與 00 開頭的 ETF。
                if (code in tpexCodes || code.startsWith("00")) {
                    put(code, r.str("CompanyName"), "", TwBoard.TPEX, volume(r.str("TradingShares")))
                }
            }
        }
        return rows.entries
            .sortedWith(compareByDescending<Map.Entry<String, TwRow>> { it.value.volume }.thenBy { it.key })
            .map { (code, r) -> Pick.TwStock(code, r.name.ifEmpty { code }, r.english, r.board, r.volume) }
    }

    internal fun usName(securityName: String): String {
        var name = US_SUFFIX.replace(clean(securityName), "").trim(' ', '-', ',')
        val half = name.substring(0, name.length / 2)
        if (name.length % 2 == 1 && name == "$half $half") name = half
        return name.ifEmpty { clean(securityName) }
    }

    fun buildUs(nasdaqListed: String, otherListed: String, zhNames: List<Pair<String, String>>): List<Pick.UsStock> {
        val rows = linkedMapOf<String, Pick.UsStock>()
        fun put(rawSymbol: String, name: String, etf: String, test: String) {
            val symbol = clean(rawSymbol).uppercase().replace(".", "-")
            if (test.trim() == "Y" || !US_TICKER.matches(symbol) || '$' in symbol) return
            if (etf.trim() != "Y" && US_EXCLUDE.containsMatchIn(name)) return
            rows.getOrPut(symbol) { Pick.UsStock(symbol, "", usName(name), null) }
        }
        fun eachRow(text: String, take: (List<String>) -> Unit) {
            for (line in text.lines().drop(1)) {
                val f = line.split("|")
                if (f.size >= 8 && !line.startsWith("File Creation Time")) take(f)
            }
        }
        eachRow(nasdaqListed) { f -> put(f[0], f[1], f[6], f[3]) }
        eachRow(otherListed) { f -> put(f[0], f[1], f[4], f[6]) }
        zhNames.forEachIndexed { i, (symbol, zh) ->
            rows[symbol]?.let { rows[symbol] = it.copy(zh = zh, rank = i + 1) }
        }
        return rows.values.sortedWith(compareBy<Pick.UsStock> { it.rank ?: Int.MAX_VALUE }.thenBy { it.code })
    }

    /** App 更新名單時沿用內建名單裡的中文名與順序，不另外帶一份 CSV。 */
    fun zhNames(us: List<Pick.UsStock>): List<Pair<String, String>> =
        us.filter { it.rank != null }.sortedBy { it.rank }.map { it.code to it.zh }

    fun twTsv(rows: List<Pick.TwStock>, stamp: String): String = buildString {
        append("# generated $stamp\n# code\tname\tenglish\tboard\tvolume\n")
        for (r in rows) append("${r.code}\t${r.name}\t${r.english}\t${r.board}\t${r.volume}\n")
    }

    fun usTsv(rows: List<Pick.UsStock>, stamp: String): String = buildString {
        append("# generated $stamp\n# symbol\tzh\tname\trank\n")
        for (r in rows) append("${r.code}\t${r.zh}\t${r.english}\t${r.rank ?: ""}\n")
    }

    private fun dataLines(text: String): List<List<String>> =
        text.lineSequence().filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split("\t") }.toList()

    fun parseTw(text: String): List<Pick.TwStock> = dataLines(text).map { f ->
        Pick.TwStock(f[0], f[1], f[2], TwBoard.valueOf(f[3]), f[4].toLong())
    }

    fun parseUs(text: String): List<Pick.UsStock> = dataLines(text).map { f ->
        Pick.UsStock(f[0], f[1], f[2], f[3].toIntOrNull())
    }

    /** crypto.csv：symbol,zh,english，第一列是表頭，順序就是常用程度。 */
    fun parseCrypto(text: String): List<Pick.Coin> =
        text.removePrefix("﻿").lines().drop(1).filter { it.isNotBlank() }.mapIndexed { i, line ->
            val f = line.split(",")
            Pick.Coin(f[0].trim(), f[1].trim(), f[2].trim(), i + 1)
        }

    fun stampOf(text: String): String? = text.lineSequence().firstOrNull()?.removePrefix("# generated ")?.trim()
}
