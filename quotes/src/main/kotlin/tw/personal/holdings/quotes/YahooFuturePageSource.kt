package tw.personal.holdings.quotes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tw.personal.holdings.core.ContractMonth
import tw.personal.holdings.core.FetchFailure
import tw.personal.holdings.core.FetchResult
import tw.personal.holdings.core.Freshness
import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.Price
import tw.personal.holdings.core.Quotable
import tw.personal.holdings.core.Quote
import tw.personal.holdings.core.TAIPEI
import tw.personal.holdings.core.TwFuture
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * 台灣 Yahoo 期貨頁（網頁內嵌 JSON，非 API）：台指期與小台的近一、近二。
 * 每一頁都內嵌這四個代號的報價物件，所以一輪通常只抓一頁；某個代號在已抓的頁裡讀不到，才去抓它自己的頁。
 */
internal class YahooFuturePageSource(private val http: HttpGet) : Source {

    override suspend fun fetch(items: List<Quotable>, round: Round): Map<Quotable, FetchResult> {
        val answers = LinkedHashMap<Quotable, FetchResult>()
        val symbols = LinkedHashMap<Quotable, String>()
        for (x in items) {
            val symbol = yahooFutureSymbol(x, round.now)
            if (symbol == null) answers[x] = failed(FetchFailure.Unsupported) else symbols[x] = symbol
        }
        for (page in symbols.values.distinct()) {
            val pending = symbols.filterKeys { it !in answers }
            if (page !in pending.values) continue
            round.beforeYahooRequest()
            val (response, failure) = http.getOrFailure("https://tw.stock.yahoo.com/future/$page")
            val html = response?.body?.toString(Charsets.UTF_8)
            for ((x, symbol) in pending) {
                val result = if (failure != null) failed(failure) else parseFuturePage(html!!, symbol, round.since(x))
                if (result is FetchResult.Got || symbol == page) answers[x] = result
            }
        }
        return answers
    }
}

/**
 * 頁面不給合約月份，所以只在「現在的近一、近二」確定時才信它。
 * 其他月份、其他商品、換月不明確的那段時間回 null，路由會改走期交所。
 */
internal fun yahooFutureSymbol(x: Quotable, now: Instant): String? {
    if (x !is TwFuture) return null
    val base = when (x.product) {
        IndexFuture.TX -> "WTX"
        IndexFuture.MTX -> "WMT"
        else -> return null
    }
    val (near, far) = frontMonths(now) ?: return null
    return when (x.month) {
        near -> "$base&"
        far -> "$base@"
        else -> null
    }
}

private val ROLL_SETTLE = LocalTime.of(15, 0)

/**
 * 第一個還沒到期的月份是近一，下一個是近二。
 * 最後交易日 13:30 到下一個交易日 15:00 之間 Yahoo 何時換月不明確，回 null。
 */
internal fun frontMonths(now: Instant): Pair<ContractMonth, ContractMonth>? {
    val thisMonth = YearMonth.from(now.atZone(TAIPEI))
    val months = (-1L..2L).map { ContractMonth(thisMonth.plusMonths(it)) }
    val nearIndex = months.indexOfFirst { TwFuture(IndexFuture.TX, it).expiresAt > now }
    val previous = months[nearIndex - 1]
    val rolledAt = nextWeekday(previous.lastTradingDay()).atTime(ROLL_SETTLE).atZone(TAIPEI).toInstant()
    if (now < rolledAt) return null
    return months[nearIndex] to months[nearIndex + 1]
}

private fun nextWeekday(date: LocalDate): LocalDate {
    var d = date.plusDays(1)
    while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
    return d
}

private const val MAX_OBJECT_CHARS = 20_000

/**
 * 報價物件在頁面的 `"symbol":"WTX&","symbolName":` 前後：price 等在前面、currency 等在後面，
 * 所以從錨點往前找外層 `{`，再往後取到配對的 `}`。頁面其他地方也提到同一個代號，逐一試到解得出來的那個。
 */
internal fun parseFuturePage(html: String, symbol: String, since: Instant?): FetchResult {
    val anchor = "\"symbol\":\"$symbol\",\"symbolName\":"
    var at = html.indexOf(anchor)
    while (at >= 0) {
        val quote = runCatching { quoteFromObject(enclosingObject(html, at), symbol, since) }.getOrNull()
        if (quote != null) return FetchResult.Got(quote)
        at = html.indexOf(anchor, at + 1)
    }
    return failed(FetchFailure.Unparseable("no readable quote object for $symbol"))
}

private fun enclosingObject(html: String, anchorAt: Int): JsonObject {
    var depth = 0
    var start = -1
    var i = anchorAt - 1
    while (i >= 0 && anchorAt - i < MAX_OBJECT_CHARS) {
        when (html[i]) {
            '}' -> depth++
            '{' -> if (depth == 0) { start = i; break } else depth--
        }
        i--
    }
    require(start >= 0) { "no enclosing object" }
    return Json.parseToJsonElement(html.substring(start, matchingBrace(html, start) + 1)).jsonObject
}

private fun matchingBrace(text: String, start: Int): Int {
    var depth = 0
    var inString = false
    var i = start
    while (i < text.length) {
        val c = text[i]
        when {
            inString -> if (c == '\\') i++ else if (c == '"') inString = false
            c == '"' -> inString = true
            c == '{' -> depth++
            c == '}' -> if (--depth == 0) return i
        }
        i++
    }
    error("unbalanced object")
}

private fun quoteFromObject(obj: JsonObject, symbol: String, since: Instant?): Quote? {
    if (obj["symbol"]?.jsonPrimitive?.contentOrNull != symbol) return null
    if (obj["currency"]?.jsonPrimitive?.contentOrNull != "TWD") return null
    fun raw(key: String): Price? = obj[key]?.jsonObject?.get("raw")?.jsonPrimitive?.contentOrNull?.let(Price::parse)
    val last = raw("price") ?: return null
    val previous = raw("regularMarketPreviousClose") ?: return null
    val asOf = Instant.parse(obj["regularMarketTime"]?.jsonPrimitive?.contentOrNull ?: return null)
    val dayRange = since == null
    return Quote(
        last = last,
        previousClose = previous,
        asOf = asOf,
        freshness = Freshness.LIVE,
        low = if (dayRange) raw("regularMarketDayLow") else null,
        high = if (dayRange) raw("regularMarketDayHigh") else null,
    )
}
