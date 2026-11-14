package tw.personal.holdings.quotes

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tw.personal.holdings.core.ContractMonth
import tw.personal.holdings.core.DraftResult
import tw.personal.holdings.core.FetchResult
import tw.personal.holdings.core.Holding
import tw.personal.holdings.core.HoldingDraft
import tw.personal.holdings.core.HoldingId
import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.InstrumentChoice
import tw.personal.holdings.core.MarketData
import tw.personal.holdings.core.RefreshMode
import tw.personal.holdings.core.TwBoard
import tw.personal.holdings.core.TwQtyUnit
import tw.personal.holdings.core.dueForFetch
import tw.personal.holdings.core.parse
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 每天抓多少：docs\battery.md 那組持股，照 WorkManager 每 15 分叫醒一次、由 :core 的 dueForFetch 決定抓哪些，
 * 跑一個完整交易日（週二，含期貨夜盤與加密 24 小時），回應一律用 fixtures。
 * 大小是 fixtures 的整天序列（上限）；gzip 欄是本機壓縮估的傳輸量（Android 的 HttpURLConnection 預設要 gzip）。
 * 數字印在 test-results 的 system-out。
 */
class DailyTrafficTest {

    private val holdings = listOf(
        hold(InstrumentChoice.TwStock("2330", TwBoard.TWSE, TwQtyUnit.LOT), "1", "1000"),
        hold(InstrumentChoice.TwStock("6488", TwBoard.TPEX, TwQtyUnit.LOT), "1", "500"),
        hold(InstrumentChoice.UsStock("AAPL"), "10", "300"),
        hold(InstrumentChoice.IndexFut(IndexFuture.TX, ContractMonth.of(2026, 11)), "1", "43000"),
        hold(InstrumentChoice.IndexFut(IndexFuture.TMF, ContractMonth.of(2026, 11)), "1", "43000"),
        hold(InstrumentChoice.Coin("BTC"), "0.1", "60000"),
    )

    @Test fun `one trading day of background rounds`() {
        val http = ServedFromFixtures()
        val fetcher = QuoteFetchers.create(http, { clock }, {})
        val warmUpFrom = taipei("2026-11-16T00:00:00")
        val dayStart = taipei("2026-11-17T00:00:00")
        val dayEnd = taipei("2026-11-18T00:00:00")

        var market = MarketData.EMPTY
        var rounds = 0
        var got = 0
        var failed = 0
        clock = warmUpFrom
        while (clock < dayEnd) {
            if (clock == dayStart) http.reset()
            val due = dueForFetch(holdings, market, RefreshMode.BACKGROUND, clock, fetcher::capabilityOf)
            if (due.isNotEmpty()) {
                val results = runBlocking { fetcher.fetch(due) { market.entries[it]?.fetchedAt } }
                if (clock >= dayStart) {
                    rounds++
                    got += results.values.count { it is FetchResult.Got }
                    failed += results.values.count { it is FetchResult.Failed }
                }
                market = market.merge(results.mapValues { (_, r) -> freshAsOf(r, clock) }, clock)
            }
            clock += Duration.ofMinutes(15)
        }

        println(http.report(rounds, got, failed))
        assertEquals("unknown URLs: ${http.unknown}", 0, http.unknown.size)
        assertEquals("failed fetches", 0, failed)
    }

    private var clock: Instant = Instant.EPOCH

    /** 模擬開盤中的市場：fixture 的成交時間是 11/13，不改的話 dueForFetch 會把 11/17 當成休市而停抓。 */
    private fun freshAsOf(r: FetchResult, now: Instant): FetchResult =
        if (r is FetchResult.Got) FetchResult.Got(r.quote.copy(asOf = now)) else r

    private fun hold(choice: InstrumentChoice, qty: String, cost: String): Holding {
        val draft = HoldingDraft(instrument = choice, quantityText = qty, averageCostText = cost, entryDate = LocalDate.of(2026, 11, 2))
        return when (val r = draft.parse(HoldingId(choice.toString()), LocalDate.of(2026, 11, 16))) {
            is DraftResult.Ok -> r.holding
            is DraftResult.Invalid -> error("draft rejected: ${r.problems}")
        }
    }
}

private class Tally(var requests: Int = 0, var bytes: Long = 0, var gzipBytes: Long = 0)

/** 依網址回 fixture，並記下每個來源的請求數與位元組。 */
private class ServedFromFixtures : HttpGet {
    private val tallies = linkedMapOf<String, Tally>()
    val unknown = mutableListOf<String>()

    fun reset() {
        tallies.clear()
        unknown.clear()
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        val (source, body) = serve(url) ?: run {
            unknown += url
            throw IOException("no fixture for $url")
        }
        tallies.getOrPut(source) { Tally() }.apply {
            requests++
            bytes += body.size
            gzipBytes += gzipSize(body)
        }
        return ok(body)
    }

    private fun serve(url: String): Pair<String, ByteArray>? {
        val uri = URI(url)
        val query = uri.rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }.associate { it.substringBefore("=") to it.substringAfter("=") }
        return when {
            url == TAIFEX_URL -> "taifex daily" to fixture("taifex_DailyMarketReportFut.json")
            url.startsWith("https://tw.stock.yahoo.com/future/") -> "yahoo tw future page" to futurePage(url.substringAfterLast("/"))
            uri.rawPath.startsWith("/_td-stock/api/resource/FinanceChartService.ApacLibraCharts;") -> {
                val list = URLDecoder.decode(uri.rawPath.substringAfter(";symbols=").substringBefore(";"), Charsets.UTF_8)
                "yahoo tw libra" to libraFor(Json.parseToJsonElement(list).jsonArray.map { it.jsonPrimitive.content }.toSet())
            }
            // v0.1 的網址：把這支工具放到舊版上重跑，量的就是基準。
            uri.path.startsWith("/v8/finance/chart/") -> {
                val symbol = uri.path.substringAfterLast("/")
                "Yahoo chart $symbol" to fixture("yahoo_chart_$symbol.json")
            }
            uri.path == "/v7/finance/spark" -> {
                val symbols = query.getValue("symbols").split(",").toSet()
                "Yahoo spark" to sparkFor(symbols, query.getValue("interval"))
            }
            else -> null
        }
    }

    private fun futurePage(symbol: String): ByteArray = when (symbol) {
        "WTX&" -> fixture("yahoo_tw_future_WTX&.html")
        "WMT@" -> fixture("yahoo_tw_future_WMT@.html")
        else -> error("no page fixture for $symbol")
    }

    /** libra fixture 有 4 個合約，回應只留這次問的。 */
    private fun libraFor(symbols: Set<String>): ByteArray {
        val all = Json.parseToJsonElement(fixtureText("yahoo_tw_libra_tick.json")).jsonArray
        return JsonArray(all.filter { it.jsonObject["symbol"]?.jsonPrimitive?.contentOrNull in symbols }).toString().toByteArray(Charsets.UTF_8)
    }

    /** spark fixture 有 5 檔，回應只留這次問的那幾檔，大小才對得上。 */
    private fun sparkFor(symbols: Set<String>, interval: String): ByteArray {
        val full = Json.parseToJsonElement(fixtureText("yahoo_spark_$interval.json")).jsonObject
        val spark = full.getValue("spark").jsonObject
        val kept = spark.getValue("result").jsonArray.filter { it.jsonObject["symbol"]?.jsonPrimitive?.contentOrNull in symbols }
        val body = JsonObject(mapOf("spark" to JsonObject(spark + ("result" to JsonArray(kept)))))
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    fun report(rounds: Int, got: Int, failed: Int): String = buildString {
        appendLine("== 2026-11-17 (Tue) background rounds=$rounds got=$got failed=$failed ==")
        appendLine("source | requests | body KB | gzip KB")
        for ((source, t) in tallies) appendLine("$source | ${t.requests} | ${t.bytes / 1024} | ${t.gzipBytes / 1024}")
        val requests = tallies.values.sumOf { it.requests }
        val bytes = tallies.values.sumOf { it.bytes }
        val gzip = tallies.values.sumOf { it.gzipBytes }
        appendLine("total | $requests | ${bytes / 1024} | ${gzip / 1024}")
        append("total MB (body / gzip): %.2f / %.2f".format(bytes / 1e6, gzip / 1e6))
    }
}

private fun gzipSize(body: ByteArray): Int {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(body) }
    return out.size()
}

/**
 * spark 只有收盤序列：兩次背景抓取（14 分）之間碰到又彈回的那一下會少看到多少。
 * 以 v0.1 chart fixtures 的 1 分 K 高低為準，比 1 分與 5 分收盤序列（含當下價），單位 bp，印在 system-out。
 */
class CloseSeriesMissTest {
    private class Bar(val t: Long, val low: Double, val high: Double, val close: Double)

    @Test fun `range missed between background fetches by close only series`() {
        for (symbol in listOf("2330.TW", "6488.TWO", "AAPL", "BTC-USD", "TWD=X")) {
            val bars = minuteBars(symbol)
            val oneMinute = gaps(bars, bars.map { it.t to it.close })
            val fiveMinute = gaps(bars, bars.groupBy { it.t - it.t % 300 }.map { (start, b) -> start to b.last().close })
            println("$symbol windows=${oneMinute.size} | 1m close: ${summary(oneMinute)} | 5m close: ${summary(fiveMinute)}")
            org.junit.Assert.assertTrue("no windows for $symbol", oneMinute.isNotEmpty())
        }
    }

    private fun minuteBars(symbol: String): List<Bar> {
        val result = Json.parseToJsonElement(fixtureText("yahoo_chart_$symbol.json")).jsonObject
            .getValue("chart").jsonObject.getValue("result").jsonArray[0].jsonObject
        val quote = result.getValue("indicators").jsonObject.getValue("quote").jsonArray[0].jsonObject
        fun series(key: String) = quote.getValue(key).jsonArray.map { it.jsonPrimitive.contentOrNull?.toDoubleOrNull() }
        val (low, high, close) = Triple(series("low"), series("high"), series("close"))
        return result.getValue("timestamp").jsonArray.mapIndexedNotNull { i, t ->
            val l = low[i] ?: return@mapIndexedNotNull null
            val h = high[i] ?: return@mapIndexedNotNull null
            val c = close[i] ?: return@mapIndexedNotNull null
            Bar(t.jsonPrimitive.content.toLong(), l, h, c)
        }
    }

    private fun gaps(bars: List<Bar>, closes: List<Pair<Long, Double>>): List<Double> =
        bars.filter { it.t - WINDOW_SECONDS >= bars.first().t }.map { end ->
            val from = end.t - WINDOW_SECONDS
            val window = bars.filter { it.t > from && it.t <= end.t }
            val seen = closes.filter { (t, _) -> t > from && t <= end.t }.map { it.second } + end.close
            maxOf(seen.min() - window.minOf { it.low }, window.maxOf { it.high } - seen.max()) / end.close * 1e4
        }

    private fun summary(g: List<Double>): String {
        val s = g.sorted()
        return "median %.1f bp, p95 %.1f bp, max %.1f bp".format(s[s.size / 2], s[(s.size * 95) / 100], s.last())
    }

    private companion object {
        const val WINDOW_SECONDS = 14 * 60L
    }
}
