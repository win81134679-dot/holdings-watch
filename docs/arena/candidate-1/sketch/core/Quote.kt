package holdings.core

import java.time.Duration
import java.time.Instant

/**
 * 一個商品某一刻的報價，已從資料來源的格式解析完（:quotes 的邊界）。
 * previousClose：股票與加密是昨收；期貨是前一日結算價。今日漲跌一律 last − previousClose。
 */
data class Quote(
    val last: Price,
    val previousClose: Price,
    /** 資料來源說的成交時間，不是我們抓到的時間。 */
    val asOf: Instant,
    val freshness: Freshness,
)

/** 畫面要誠實標示：盤後資料不能看起來像即時。 */
enum class Freshness { LIVE, DELAYED, END_OF_DAY }

/** 抓一個商品的結果。:quotes 只會回這個，不會丟例外。 */
sealed interface FetchResult {
    data class Got(val quote: Quote) : FetchResult
    data class Failed(val failure: FetchFailure) : FetchResult
}

sealed interface FetchFailure {
    /** 代號不存在（例如 .TW／.TWO 選錯）。重試沒有用，退避拉到最長。 */
    data object NotFound : FetchFailure
    data class Throttled(val retryAfter: Duration?) : FetchFailure
    data object Network : FetchFailure
    /** 來源改版、回了看不懂的東西。保留一小段描述給除錯畫面。 */
    data class Unparseable(val detail: String) : FetchFailure
    /** 沒有任何來源接這個商品（例如期交所來源還沒實作完）。 */
    data object Unsupported : FetchFailure
}

/**
 * 報價快取裡的一格。它同時是「節流狀態」：下一次該不該抓，完全由這格推導（FetchPolicy.kt），
 * 不另外存「上次排程時間」。報價快取落地＝節流狀態落地。
 */
data class QuoteEntry(
    val quote: Quote?,
    /** 最後一次成功抓到的本機時間。 */
    val fetchedAt: Instant?,
    /** 最後一次失敗；成功後清掉。consecutive 用來算退避。 */
    val failure: Failure?,
) {
    data class Failure(val at: Instant, val reason: FetchFailure, val consecutive: Int)
}

/** 手機上的報價快取（含匯率）。只有 Refresher 會寫。 */
data class MarketData(val entries: Map<Quotable, QuoteEntry>) {

    /** 估值與提醒只需要報價本身。 */
    fun quotes(): Map<Quotable, Quote> = TODO("not implemented: entries 中 quote != null 的")

    /**
     * 把一輪抓取結果併進來。冪等：同一份結果併兩次，結果一樣（Got 覆蓋；Failed 的 consecutive 以 at 去重）。
     * Got → quote＝新報價、fetchedAt＝now、failure＝null
     * Failed → 保留舊 quote，failure.consecutive + 1（舊報價還能用，只是畫面標示過期）
     */
    fun merge(results: Map<Quotable, FetchResult>, now: Instant): MarketData = TODO("not implemented")

    /** 刪掉已經沒人持有的商品的快取（持股刪了，快取就跟著消失，不用另外清）。 */
    fun retainOnly(wanted: Set<Quotable>): MarketData = TODO("not implemented")

    companion object { val EMPTY = MarketData(emptyMap()) }
}
