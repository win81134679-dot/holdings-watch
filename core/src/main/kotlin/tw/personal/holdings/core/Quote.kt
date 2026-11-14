package tw.personal.holdings.core

import java.time.Duration
import java.time.Instant

/**
 * 一個商品某一刻的報價，已在 :quotes 的邊界解析完。
 * previousClose：股票與加密是昨收；期貨是前一交易日結算價。
 */
data class Quote(
    val last: Price,
    val previousClose: Price,
    /** 來源說的成交時間，不是抓到的時間。 */
    val asOf: Instant,
    val freshness: Freshness,
    /**
     * 上一次抓取（＝上一次判斷提醒）到 [asOf] 之間的最低／最高價，讓兩次抓取之間碰線又彈回不會漏響。
     * 來源給不出來時為 null，判斷退回 [last]。
     */
    val low: Price? = null,
    val high: Price? = null,
)

/** 來源宣告的等級：盤後資料不能看起來像即時。 */
enum class Freshness { LIVE, DELAYED, END_OF_DAY }

/** 由時間推導的新鮮度（[staleness]）。提醒只看 LIVE 與 FINAL。 */
enum class Staleness { LIVE, FINAL, STALE }

/** 來源能力。僅收盤價的商品收盤後抓一次就停。 */
enum class SourceCapability { INTRADAY, CLOSE_ONLY }

sealed interface FetchResult {
    data class Got(val quote: Quote) : FetchResult
    data class Failed(val failure: FetchFailure) : FetchResult
}

sealed interface FetchFailure {
    data object NotFound : FetchFailure
    data class Throttled(val retryAfter: Duration?) : FetchFailure
    data object Network : FetchFailure
    data class Unparseable(val detail: String) : FetchFailure
    data object Unsupported : FetchFailure
}

/** 報價快取的一格，同時是節流狀態：下一次抓不抓完全由它推導（[dueForFetch]）。 */
data class QuoteEntry(
    val quote: Quote?,
    /** 最後一次成功抓到的本機時間。 */
    val fetchedAt: Instant?,
    val failure: Failure?,
) {
    data class Failure(val at: Instant, val reason: FetchFailure, val consecutive: Int)
}

data class MarketData(val entries: Map<Quotable, QuoteEntry>) {

    fun quotes(): Map<Quotable, Quote> = entries.mapNotNull { (k, e) -> e.quote?.let { k to it } }.toMap()

    /** 冪等：同一份結果以同一個 now 併兩次，結果一樣。失敗時保留舊報價。 */
    fun merge(results: Map<Quotable, FetchResult>, now: Instant): MarketData {
        val merged = entries.toMutableMap()
        for ((x, result) in results) {
            val old = entries[x]
            merged[x] = when (result) {
                is FetchResult.Got -> QuoteEntry(result.quote, now, null)
                is FetchResult.Failed -> {
                    val prev = old?.failure
                    val consecutive = when {
                        prev == null -> 1
                        prev.at == now -> prev.consecutive
                        else -> prev.consecutive + 1
                    }
                    QuoteEntry(old?.quote, old?.fetchedAt, QuoteEntry.Failure(now, result.failure, consecutive))
                }
            }
        }
        return MarketData(merged)
    }

    fun retainOnly(wanted: Set<Quotable>): MarketData = MarketData(entries.filterKeys { it in wanted })

    companion object {
        val EMPTY = MarketData(emptyMap())
    }
}
