package tw.personal.holdings.quotes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tw.personal.holdings.core.Currency
import tw.personal.holdings.core.Crypto
import tw.personal.holdings.core.FetchFailure
import tw.personal.holdings.core.FetchResult
import tw.personal.holdings.core.Freshness
import tw.personal.holdings.core.FxPair
import tw.personal.holdings.core.Instrument
import tw.personal.holdings.core.Price
import tw.personal.holdings.core.Quotable
import tw.personal.holdings.core.Quote
import tw.personal.holdings.core.TwBoard
import tw.personal.holdings.core.TwEquity
import tw.personal.holdings.core.UsEquity
import java.math.BigDecimal
import java.time.Instant

/** 1 分還是 5 分：docs/battery.md〈每輪抓多少〉有兩者的流量與漏看幅度。 */
private const val SPARK_INTERVAL = "1m"

/** Yahoo 沒寫上限；20 檔是保守值，超過就分批。 */
private const val SPARK_BATCH = 20

/**
 * Yahoo 國際版 spark 端點（非官方、無文件）：一個請求查多檔，每檔附 chart 同款的 meta 與 [SPARK_INTERVAL] 收盤序列。
 * 同一輪不重試，重試交給 :core 的退避。
 */
internal class YahooSparkSource(private val http: HttpGet) : Source {

    override suspend fun fetch(items: List<Quotable>, round: Round): Map<Quotable, FetchResult> {
        val answers = LinkedHashMap<Quotable, FetchResult>()
        val bySymbol = items.groupBy { yahooSymbol(it) }
        bySymbol[null]?.forEach { answers[it] = failed(FetchFailure.Unsupported) }
        for (batch in bySymbol.keys.filterNotNull().chunked(SPARK_BATCH)) {
            round.beforeYahooRequest()
            val (response, failure) = http.getOrFailure(sparkUrl(batch))
            val spark = if (failure == null) decodeSpark(response!!.body.toString(Charsets.UTF_8)) else null
            for (symbol in batch) {
                for (x in bySymbol.getValue(symbol)) {
                    answers[x] = when (spark) {
                        null -> failed(failure!!)
                        is SparkBody.Bad -> failed(spark.failure)
                        is SparkBody.Ok -> quoteFromSpark(spark.dto, symbol, x, round.since(x))
                    }
                }
            }
        }
        return answers
    }
}

private fun sparkUrl(symbols: List<String>): String =
    "https://query1.finance.yahoo.com/v7/finance/spark?symbols=${symbols.joinToString(",")}&range=1d&interval=$SPARK_INTERVAL"

/** 商品到 Yahoo 代號的對照只存在這裡；期貨回 null（國際版沒有台灣期貨）。 */
internal fun yahooSymbol(x: Quotable): String? = when (x) {
    is TwEquity -> x.code.value + if (x.board == TwBoard.TWSE) ".TW" else ".TWO"
    is UsEquity -> x.ticker.value
    is Crypto -> x.coin.value + "-USD"
    FxPair.USD_TWD -> "TWD=X"
    else -> null
}

/** TWD=X 是「1 美元值多少台幣」，所以幣別是 TWD。 */
private fun expectedCurrency(x: Quotable): String = when (x) {
    is Instrument -> x.currency.name
    FxPair.USD_TWD -> Currency.TWD.name
}

/** 台股盤中是即時還是延遲還沒實測（實測時已收盤），先標延遲。 */
private fun freshnessOf(x: Quotable): Freshness = if (x is TwEquity) Freshness.DELAYED else Freshness.LIVE

/** 有開收盤的市場，meta 的當日高低就是這一盤的高低；加密與匯率的 meta 高低跟序列對不上（實測 BTC 序列低於 meta 當日低）。 */
private fun hasSessionDayRange(x: Quotable): Boolean = x is TwEquity || x is UsEquity

private val json = Json { ignoreUnknownKeys = true; isLenient = false }

private sealed interface SparkBody {
    data class Ok(val dto: SparkResponseDto.SparkDto) : SparkBody
    data class Bad(val failure: FetchFailure) : SparkBody
}

private fun decodeSpark(body: String): SparkBody {
    val spark = try {
        json.decodeFromString<SparkResponseDto>(body).spark
    } catch (e: Exception) {
        return SparkBody.Bad(FetchFailure.Unparseable("spark json: ${e.message?.take(80)}"))
    }
    val error = spark.error ?: return SparkBody.Ok(spark)
    return SparkBody.Bad(FetchFailure.Unparseable("spark error ${error.code}"))
}

/**
 * 低／高：股票在 [since] 早於今天序列第一點時，這段期間涵蓋整盤，用 meta 的當日高低（含 K 棒內的高低點）；
 * 其他情況只剩 [SPARK_INTERVAL] 收盤價，K 棒內碰到又彈回的那一下看不到。
 */
private fun quoteFromSpark(spark: SparkResponseDto.SparkDto, symbol: String, x: Quotable, since: Instant?): FetchResult {
    val currency = expectedCurrency(x)
    val entry = spark.result.orEmpty().firstOrNull { it.symbol == symbol } ?: return failed(FetchFailure.NotFound)
    val result = entry.response?.firstOrNull() ?: return failed(FetchFailure.Unparseable("no spark response for $symbol"))
    val meta = result.meta
    if (meta.symbol != symbol) return failed(FetchFailure.Unparseable("symbol ${meta.symbol} != $symbol"))
    if (meta.currency != currency) return failed(FetchFailure.Unparseable("currency ${meta.currency} != $currency"))

    val last = meta.regularMarketPrice?.let(::priceOf)
        ?: return failed(FetchFailure.Unparseable("no positive regularMarketPrice"))
    val previous = (meta.chartPreviousClose ?: meta.previousClose)?.let(::priceOf)
        ?: return failed(FetchFailure.Unparseable("no positive previous close"))
    val time = meta.regularMarketTime ?: return failed(FetchFailure.Unparseable("no regularMarketTime"))

    val timestamps = result.timestamp.orEmpty()
    val from = since?.epochSecond ?: Long.MIN_VALUE
    val wholeSession = hasSessionDayRange(x) && timestamps.firstOrNull()?.let { from < it } == true
    val (low, high) = if (wholeSession) {
        meta.regularMarketDayLow?.let(::priceOf) to meta.regularMarketDayHigh?.let(::priceOf)
    } else {
        val closes = result.indicators?.quote?.firstOrNull()?.close.orEmpty()
            .filterIndexed { i, v -> v != null && (timestamps.getOrNull(i) ?: Long.MIN_VALUE) > from }
            .mapNotNull { it?.let(::priceOf) }
        closes.minByOrNull { it.value } to closes.maxByOrNull { it.value }
    }

    return FetchResult.Got(
        Quote(
            last = last,
            previousClose = previous,
            asOf = Instant.ofEpochSecond(time),
            freshness = freshnessOf(x),
            low = low,
            high = high,
        ),
    )
}

private fun priceOf(v: Double): Price? = Price.of(BigDecimal.valueOf(v))

@Serializable
internal data class SparkResponseDto(val spark: SparkDto) {
    @Serializable
    data class SparkDto(val result: List<EntryDto>? = null, val error: ErrorDto? = null)

    @Serializable
    data class EntryDto(val symbol: String? = null, val response: List<ResultDto>? = null)

    @Serializable
    data class ResultDto(
        val meta: MetaDto,
        val timestamp: List<Long>? = null,
        val indicators: IndicatorsDto? = null,
    )

    @Serializable
    data class MetaDto(
        val currency: String? = null,
        val symbol: String? = null,
        val regularMarketPrice: Double? = null,
        val chartPreviousClose: Double? = null,
        val previousClose: Double? = null,
        val regularMarketTime: Long? = null,
        val regularMarketDayLow: Double? = null,
        val regularMarketDayHigh: Double? = null,
    )

    @Serializable
    data class IndicatorsDto(val quote: List<SeriesDto>? = null)

    @Serializable
    data class SeriesDto(val close: List<Double?>? = null)

    @Serializable
    data class ErrorDto(val code: String? = null, val description: String? = null)
}
