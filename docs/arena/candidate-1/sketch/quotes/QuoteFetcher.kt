package holdings.quotes

import holdings.core.FetchResult
import holdings.core.Quotable
import java.time.Clock

/*
 * :quotes 的公開介面只有這個檔：一個 fun interface、一個 HTTP 抽象、一個工廠。
 * 換資料來源（期交所端點定案、Yahoo 失效改用別家）＝改 :quotes 的 internal，:phone 不用動。
 */

/** 抓一批報價。保證：輸入的每個 Quotable 在輸出裡都有一格（Got 或 Failed），不丟例外。 */
fun interface QuoteFetcher {
    suspend fun fetch(items: Set<Quotable>): Map<Quotable, FetchResult>
}

/**
 * 最小的 HTTP 抽象，讓 :quotes 不依賴 Android、可用 JVM 測試餵固定 JSON。
 * :phone 用 HttpURLConnection 實作（不新增 OkHttp 依賴）。
 */
fun interface HttpGet {
    suspend fun get(url: String): HttpResponse
}

data class HttpResponse(val status: Int, val body: String, val retryAfterSeconds: Long?)

object QuoteFetchers {
    /**
     * 預設路由：台灣期貨 → 期交所；其他（台股、美股、加密、USD_TWD）→ Yahoo chart。
     * 路由規則寫在這裡一次，不散在呼叫端。
     */
    fun default(http: HttpGet, clock: Clock = Clock.systemUTC()): QuoteFetcher {
        TODO(
            "not implemented: sources = [TaifexSource(http, clock), YahooChartSource(http, clock)]；" +
                "回傳的 QuoteFetcher 依 handles() 分組 → 各來源 fetch → 合併；沒人接的 → unsupported()",
        )
    }
}

/** 單一資料來源。internal：呼叫端只認得 QuoteFetcher。 */
internal interface Source {
    fun handles(item: Quotable): Boolean
    suspend fun fetch(items: Set<Quotable>): Map<Quotable, FetchResult>
}

internal fun unsupported(items: Set<Quotable>): Map<Quotable, FetchResult> =
    TODO("not implemented: 每個 item → Failed(Unsupported)")

internal fun isFuture(item: Quotable): Boolean = TODO("not implemented: item is TwFuture")
