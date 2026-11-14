package com.example.holdings.quotes

import com.example.holdings.core.*

/**
 * :quotes 模組的公開面只有這個：給一個商品集合，回領域型別的結果。
 * Yahoo 的符號規則（.TW/.TWO/-USD/TWD=X）、JSON 欄位、期交所的契約代號與月份格式，全部是這個模組的 internal。
 * 換來源 = 改 [Routing] 表一行，或新增一個 internal 類別；core、phone、wear 都不用動。
 */
object DefaultQuoteSources {
    /** 預設組裝：台股／美股／加密／匯率走 Yahoo，期貨走期交所。http 由外殼注入（測試給 fixture）。
     *  回傳的 [QuoteSources] 介面定義在 :core（Refresh.kt），:core 因此不依賴 :quotes。 */
    fun create(http: HttpGet): QuoteSources = TODO("not implemented: Router(Routing.default(YahooChart(http), Taifex(http)))")
}

/** 唯一的網路縫。實作可以是 HttpURLConnection；測試給固定回應。 */
fun interface HttpGet { suspend fun get(url: String, headers: Map<String, String>): HttpResult }
data class HttpResult(val status: Int, val body: String)

/** 一個來源 = 對一批商品回結果。internal：外面看不到。 */
internal interface QuoteSource {
    val id: SourceId
    suspend fun fetch(batch: Set<Instrument>): Map<InstrumentKey, FetchResult<Quote>>
}

/** 哪類商品先問誰、失敗再問誰。一張表，是「來源可替換」的唯一設定處。 */
internal class Routing(private val table: Map<Class<out Instrument>, List<QuoteSource>>) {
    fun sourcesFor(i: Instrument): List<QuoteSource> = TODO("not implemented")
    companion object {
        fun default(yahoo: QuoteSource, taifex: QuoteSource): Routing = TODO(
            "TwStock/UsStock/Crypto -> [yahoo]; Future -> [taifex]（想加後備再加在 list 後面）"
        )
    }
}

/**
 * Yahoo chart adapter。
 * 解析是純函式：parse(json, instrument) -> FetchResult<Quote>，用 2026-11-14 的實測回應存成 fixture 做測試。
 * 節制：同一輪每個符號只打一次、請求間隔 >= 300ms、429 就整輪停手（回 RateLimited，不重試）。
 */
internal class YahooChart(private val http: HttpGet) : QuoteSource {
    override val id = SourceId("yahoo-chart")
    override suspend fun fetch(batch: Set<Instrument>): Map<InstrumentKey, FetchResult<Quote>> = TODO("not implemented")
    suspend fun fetchUsdTwd(): FetchResult<FxRate> = TODO("not implemented: TWD=X")

    companion object {
        fun symbolOf(i: Instrument): String? = TODO("not implemented: TwStock LISTED -> '2330.TW', OTC -> '6488.TWO', Us -> 'AAPL', Crypto -> 'BTC-USD'; Future -> null")
        fun parse(json: String, i: Instrument): FetchResult<Quote> = TODO("not implemented: meta.regularMarketPrice / chartPreviousClose / regularMarketTime；缺欄位 -> Unparseable")
    }
}

/**
 * 期交所 adapter。端點與授權待定（上架收費與資料授權研究.md 對「期交所行情在 App 顯示」只有低可信度摘要）。
 * 保證的只有介面：回 Quote(last, prevClose, asOf)。實作前先決定資料等級（盤後 OpenAPI / 延遲 / 即時）。
 */
internal class Taifex(private val http: HttpGet) : QuoteSource {
    override val id = SourceId("taifex")
    override suspend fun fetch(batch: Set<Instrument>): Map<InstrumentKey, FetchResult<Quote>> =
        TODO("endpoint TBD；只處理 Instrument.Future；契約代號（TXF + 月份）由 contract 推導且只存在這個檔案")
}
