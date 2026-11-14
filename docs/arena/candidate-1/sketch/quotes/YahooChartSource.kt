package holdings.quotes

import holdings.core.FetchResult
import holdings.core.Quotable
import kotlinx.serialization.Serializable
import java.time.Clock

/**
 * Yahoo 國際版 chart 端點（實測 2026-11-14，見 Yahoo報價實測.md）：
 *   https://query1.finance.yahoo.com/v8/finance/chart/<代號>?range=1d&interval=1m
 * 非官方、無文件、無保證；一個代號一個請求。
 *
 * 禮貌規則（使用者習慣：不要高頻輪詢）：
 *  - 依序打，每個請求間隔 PACING_MS；同一輪不重試，失敗交給 FetchPolicy 的退避。
 *  - 429 → Throttled(retryAfter)；404 → NotFound；其他 5xx／逾時 → Network。
 *
 * TODO 實作前驗證：range=1d&interval=1d 是否同樣給 meta.regularMarketPrice 與昨收（回應小很多）；
 *   沒驗證之前沿用實測過的 interval=1m。
 * TODO 台股盤中是即時還是延遲（實測時已收盤），決定 Freshness.LIVE 還是 DELAYED。
 */
internal class YahooChartSource(private val http: HttpGet, private val clock: Clock) : Source {

    override fun handles(item: Quotable): Boolean = !isFuture(item)

    override suspend fun fetch(items: Set<Quotable>): Map<Quotable, FetchResult> =
        TODO("not implemented: for x in items: yahooSymbol(x) → http.get → parseChart；delay(PACING_MS)")

    companion object { const val PACING_MS = 500L }
}

/**
 * 領域商品 → Yahoo 代號。這個對照只存在這裡。
 *   TwEquity(TWSE, 2330) → "2330.TW"；TwEquity(TPEX, 6488) → "6488.TWO"
 *   UsEquity(AAPL) → "AAPL"；Crypto(BTC) → "BTC-USD"；FxPair.USD_TWD → "TWD=X"
 *   TwFuture → null（Yahoo 國際版沒有台灣期貨，實測 404）
 */
internal fun yahooSymbol(item: Quotable): String? = TODO("not implemented")

/**
 * chart JSON → FetchResult。邊界：任何欄位缺漏或非正數 → Failed(Unparseable)，不丟例外。
 * 取 meta.regularMarketPrice、meta.chartPreviousClose（或 previousClose）、meta.regularMarketTime。
 */
internal fun parseChart(body: String): FetchResult = TODO("not implemented")

@Serializable
internal data class ChartResponseDto(val chart: ChartDto) {
    @Serializable data class ChartDto(val result: List<ResultDto>? = null, val error: ErrorDto? = null)
    @Serializable data class ResultDto(val meta: MetaDto)
    @Serializable data class MetaDto(
        val currency: String? = null,
        val regularMarketPrice: Double? = null,
        val chartPreviousClose: Double? = null,
        val previousClose: Double? = null,
        val regularMarketTime: Long? = null,
    )
    @Serializable data class ErrorDto(val code: String? = null, val description: String? = null)
}
