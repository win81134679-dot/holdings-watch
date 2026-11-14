package tw.personal.holdings.quotes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tw.personal.holdings.core.FetchFailure
import tw.personal.holdings.core.FetchResult
import tw.personal.holdings.core.Freshness
import tw.personal.holdings.core.Price
import tw.personal.holdings.core.Quotable
import tw.personal.holdings.core.Quote
import tw.personal.holdings.core.TAIPEI
import tw.personal.holdings.core.TwFuture
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val TAIFEX_DAILY_URL = "https://openapi.taifex.com.tw/v1/DailyMarketReportFut"
private const val REGULAR_SESSION = "一般"
private val DAY_SESSION_END = LocalTime.of(13, 45)
private val DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE

/** 跟 :core 補抓收盤價的寬限（SETTLE_GRACE）同值，兩邊才會對同一個時間點說「可能有新檔了」。 */
private val SETTLE_GRACE: Duration = Duration.ofMinutes(10)

/** 最近一次「日盤收盤＋寬限」的時間點（只算週一到週五）；在它之後抓過的檔不會再變。 */
internal fun publishedSince(now: Instant): Instant {
    var day = now.atZone(TAIPEI).toLocalDate()
    while (true) {
        val settled = day.atTime(DAY_SESSION_END).atZone(TAIPEI).toInstant() + SETTLE_GRACE
        if (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY && settled <= now) return settled
        day = day.minusDays(1)
    }
}

/** 契約代號與 yyyyMM。個股期貨、微台、遠月 TX／MTX 只靠這一條。 */
internal fun taifexKey(x: Quotable): Pair<String, String>? =
    (x as? TwFuture)?.let { it.product.taifexCode to "%04d%02d".format(it.month.yearMonth.year, it.month.yearMonth.monthValue) }

/**
 * 期交所 OpenAPI 每日行情：一輪最多一個請求，從整份（約 860 KB）裡篩出持有的合約。
 * 檔案一個交易日只換一次，所以每個合約在 [publishedSince] 之後抓過就不再問；
 * 這擋的是期貨頁連續失敗時、每輪都退回來重抓同一份檔的情況。
 */
internal class TaifexSource(private val http: HttpGet) : Source {

    override suspend fun fetch(items: List<Quotable>, round: Round): Map<Quotable, FetchResult> {
        val cutoff = publishedSince(round.now)
        if (items.all { x -> round.since(x)?.let { it >= cutoff } == true }) return emptyMap()
        val (response, failure) = http.getOrFailure(TAIFEX_DAILY_URL)
        if (failure != null) return items.associateWith { failed(failure) }
        val rows = try {
            parseRows(response!!.body.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return items.associateWith { failed(FetchFailure.Unparseable("taifex json: ${e.message?.take(80)}")) }
        }
        return items.associateWith { x -> quoteFor(x, rows) }
    }

    private fun quoteFor(x: Quotable, rows: Map<Pair<String, String>, JsonObject>): FetchResult {
        val row = rows[taifexKey(x) ?: return failed(FetchFailure.Unsupported)] ?: return failed(FetchFailure.NotFound)
        fun number(key: String): BigDecimal? = row[key]?.jsonPrimitive?.contentOrNull?.trim()?.toBigDecimalOrNull()
        val last = number("Last")?.let(Price::of)
        // 前一交易日結算價＝收盤價減漲跌；不用 SettlementPrice 是因為那是這一天自己的結算價。
        val previous = number("Last")?.let { l -> number("Change")?.let { l - it } }?.let(Price::of)
        val date = row["Date"]?.jsonPrimitive?.contentOrNull?.let { runCatching { LocalDate.parse(it, DATE_FORMAT) }.getOrNull() }
        if (last == null || previous == null || date == null) {
            return failed(FetchFailure.Unparseable("taifex row has no usable Last/Change/Date"))
        }
        return FetchResult.Got(
            Quote(
                last = last,
                previousClose = previous,
                asOf = date.atTime(DAY_SESSION_END).atZone(TAIPEI).toInstant(),
                freshness = Freshness.END_OF_DAY,
            ),
        )
    }

    private fun parseRows(body: String): Map<Pair<String, String>, JsonObject> {
        val array: JsonArray = Json.parseToJsonElement(body.removePrefix("﻿")).jsonArray
        val rows = LinkedHashMap<Pair<String, String>, JsonObject>()
        for (element in array) {
            val row = element.jsonObject
            if (row["TradingSession"]?.jsonPrimitive?.contentOrNull != REGULAR_SESSION) continue
            val contract = row["Contract"]?.jsonPrimitive?.contentOrNull ?: continue
            val month = row["ContractMonth(Week)"]?.jsonPrimitive?.contentOrNull ?: continue
            rows.putIfAbsent(contract to month, row)
        }
        return rows
    }
}
