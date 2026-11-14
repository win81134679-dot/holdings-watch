package tw.personal.holdings.quotes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tw.personal.holdings.core.FetchFailure
import tw.personal.holdings.core.FetchResult
import tw.personal.holdings.core.Freshness
import tw.personal.holdings.core.Price
import tw.personal.holdings.core.Quotable
import tw.personal.holdings.core.Quote
import java.net.URLEncoder
import java.time.Instant
import kotlin.math.roundToLong

private const val LIBRA_BASE = "https://tw.stock.yahoo.com/_td-stock/api/resource/FinanceChartService.ApacLibraCharts"
private const val SECONDS_PER_DAY = 86_400L
private const val BAR_SECONDS = 60L

internal fun libraUrl(symbols: List<String>): String {
    val list = symbols.joinToString(",", "[", "]") { "\"$it\"" }
    return "$LIBRA_BASE;symbols=${URLEncoder.encode(list, Charsets.UTF_8)};type=tick"
}

/**
 * 台灣 Yahoo 期貨頁背後的走勢圖服務（非官方、無文件）：一個請求查多個合約，每個約 44 KB，
 * 附即時報價與整個交易日的 1 分 K。代號跟期貨頁一樣（[yahooFutureSymbol]），整頁 546 KB 的期貨頁留在路由裡當後備。
 */
internal class YahooLibraSource(private val http: HttpGet) : Source {

    override suspend fun fetch(items: List<Quotable>, round: Round): Map<Quotable, FetchResult> {
        val answers = LinkedHashMap<Quotable, FetchResult>()
        val symbols = LinkedHashMap<Quotable, String>()
        for (x in items) {
            val symbol = yahooFutureSymbol(x, round.now)
            if (symbol == null) answers[x] = failed(FetchFailure.Unsupported) else symbols[x] = symbol
        }
        if (symbols.isEmpty()) return answers
        round.beforeYahooRequest()
        val (response, failure) = http.getOrFailure(libraUrl(symbols.values.distinct()))
        val entries = if (failure == null) decodeLibra(response!!.body.toString(Charsets.UTF_8)) else null
        for ((x, symbol) in symbols) {
            answers[x] = when {
                failure != null -> failed(failure)
                entries == null -> failed(FetchFailure.Unparseable("libra json"))
                else -> entries.firstOrNull { it.symbol == symbol }?.let { quoteFromLibra(it, round.since(x)) }
                    ?: failed(FetchFailure.NotFound)
            }
        }
        return answers
    }
}

private val json = Json { ignoreUnknownKeys = true; isLenient = false }

private fun decodeLibra(body: String): List<LibraEntryDto>? =
    runCatching { json.decodeFromString<List<LibraEntryDto>>(body) }.getOrNull()

/**
 * 時間：夜盤的 1 分 K 與 meta.regularMarketTime 記在「所屬交易日」的前一天（週五夜盤記成週日），
 * 實際時間要用 quote.refreshedTs；兩者相差整數天，用它把序列移回實際時間再跟 [since] 比。
 * 低／高：[since] 是 null（這一盤第一次抓）用當日高低，其他情況用跟 since 之後重疊的 1 分 K 高低。
 */
private fun quoteFromLibra(entry: LibraEntryDto, since: Instant?): FetchResult {
    val chart = entry.chart ?: return failed(FetchFailure.Unparseable("no chart for ${entry.symbol}"))
    val quote = chart.quote ?: return failed(FetchFailure.Unparseable("no quote for ${entry.symbol}"))
    if (chart.meta?.currency != "TWD") return failed(FetchFailure.Unparseable("currency ${chart.meta?.currency}"))
    val last = quote.price?.let(Price::parse) ?: return failed(FetchFailure.Unparseable("price ${quote.price}"))
    val previous = quote.previousClose?.let(Price::parse) ?: return failed(FetchFailure.Unparseable("previousClose ${quote.previousClose}"))
    val asOf = quote.refreshedTs?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return failed(FetchFailure.Unparseable("refreshedTs ${quote.refreshedTs}"))

    val (low, high) = if (since == null) {
        quote.dayLowPrice?.let(Price::parse) to quote.dayHighPrice?.let(Price::parse)
    } else {
        val shift = chart.meta?.regularMarketTime
            ?.let { ((it - asOf.epochSecond).toDouble() / SECONDS_PER_DAY).roundToLong() * SECONDS_PER_DAY } ?: 0L
        val bars = chart.indicators?.quote?.firstOrNull()
        val stamps = chart.timestamp.orEmpty()
        fun after(series: List<Double?>?): List<Price> = series.orEmpty().mapIndexedNotNull { i, v ->
            val t = stamps.getOrNull(i) ?: return@mapIndexedNotNull null
            if (v == null || t - shift + BAR_SECONDS <= since.epochSecond) null else Price.of(v.toBigDecimal())
        }
        after(bars?.low).minByOrNull { it.value } to after(bars?.high).maxByOrNull { it.value }
    }
    return FetchResult.Got(Quote(last = last, previousClose = previous, asOf = asOf, freshness = Freshness.LIVE, low = low, high = high))
}

@Serializable
internal data class LibraEntryDto(val symbol: String? = null, val chart: ChartDto? = null) {
    @Serializable
    data class ChartDto(
        val meta: MetaDto? = null,
        val timestamp: List<Long>? = null,
        val indicators: IndicatorsDto? = null,
        val quote: QuoteDto? = null,
    )

    @Serializable
    data class MetaDto(val currency: String? = null, val regularMarketTime: Long? = null)

    @Serializable
    data class IndicatorsDto(val quote: List<BarsDto>? = null)

    @Serializable
    data class BarsDto(val low: List<Double?>? = null, val high: List<Double?>? = null)

    @Serializable
    data class QuoteDto(
        val price: String? = null,
        val previousClose: String? = null,
        val dayLowPrice: String? = null,
        val dayHighPrice: String? = null,
        val refreshedTs: String? = null,
    )
}
