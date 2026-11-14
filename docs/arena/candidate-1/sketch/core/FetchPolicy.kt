package holdings.core

import java.time.Duration
import java.time.Instant

/*
 * 什麼時候抓哪些報價。純函式：輸入「持股、快取、模式、現在」，輸出「這一輪要抓的集合」。
 * 背景排程只負責每 15 分鐘叫醒一次（WorkManager 下限）；叫醒之後抓不抓，全由這裡決定。
 * 所以「頻率」只有這一個真相來源，WorkManager 的設定與這裡不會互相矛盾。
 *
 * 原則（使用者習慣：不要高頻輪詢公開 API）：
 *  - 收盤後只補抓一次收盤價，之後整晚不抓。
 *  - 盤中背景約 15 分鐘一次；App 開著時 2 分鐘；手動下拉 30 秒下限。
 *  - 匯率一天一次。
 *  - 失敗指數退避，NotFound 直接退到最長。
 */

enum class RefreshMode { BACKGROUND, FOREGROUND, MANUAL }

/**
 * 這一輪要抓的商品。持有美元計價的部位時自動包含 [FxPair.USD_TWD]。
 *
 * TODO 虛擬碼，對 wanted = holdings 的商品 ∪ (有 USD 部位 ? {USD_TWD} : ∅) 中每個 x：
 *   e = data.entries[x]
 *   e?.failure 存在 且 now < e.failure.at + backoff(e.failure) → 不抓
 *   e?.quote == null → 抓
 *   x 是 FxPair → e.fetchedAt 在 TAIPEI 的日期 < 今天 → 抓
 *   s = MarketHours.session(x, now)
 *   s 是 Open   → now − e.fetchedAt ≥ interval(mode, x.market) → 抓
 *   s 是 Closed → s.lastClose != null 且 e.fetchedAt < s.lastClose + SETTLE_GRACE 且 now ≥ s.lastClose + SETTLE_GRACE → 抓（補收盤價）
 *   休市日偵測（沒有假日表）：s 是 Open 但 e.quote.asOf 的當地日期 < 今天 且 e.fetchedAt > 今天開盤 + 30 分
 *     → 視為今天休市，不抓（從快取推導，不另存旗標）
 */
fun dueForFetch(
    holdings: List<Holding>,
    data: MarketData,
    mode: RefreshMode,
    now: Instant,
): Set<Quotable> = TODO("not implemented")

/** 盤中最短間隔。背景 14 分而不是 15：WorkManager 提早幾秒叫醒時不會整整錯過一輪。 */
internal fun interval(mode: RefreshMode, market: Market): Duration = TODO(
    "not implemented: BACKGROUND → 14m（CRYPTO 29m）；FOREGROUND → 2m；MANUAL → 30s",
)

/** 收盤後等多久才去補抓收盤價（來源需要時間定稿）。 */
internal val SETTLE_GRACE: Duration = Duration.ofMinutes(10)

internal fun backoff(f: QuoteEntry.Failure): Duration = TODO(
    "not implemented: NotFound → 6h；Throttled(retryAfter) → retryAfter ?: 30m；其他 → min(2^consecutive 分, 60 分)",
)

/** 交易時段。只用來決定抓不抓，不顯示給使用者。 */
internal sealed interface Session {
    data class Open(val closesAt: Instant) : Session
    /** lastClose：最近一次已發生的收盤時刻；24 小時市場不會是 Closed。 */
    data class Closed(val lastClose: Instant?) : Session
}

/**
 * 各市場的交易時段表。資料而不是分支：新增市場＝加一列。
 * 2026-11-14 查（實作前到期交所／證交所再確認一次）：
 *  - TW_EQUITY：週一至五 09:00–13:30 Asia/Taipei
 *  - US_EQUITY：週一至五 09:30–16:00 America/New_York（用 ZoneId 處理夏令時間；不抓盤前盤後）
 *  - TW_FUTURES：日盤 08:45–13:45；夜盤 15:00–次日 05:00（指數期貨）。個股期貨夜盤只有部分標的有，
 *    TODO 依商品區分；先把個股期貨視為只有日盤。
 *  - CRYPTO：24 小時，永遠 Open。
 */
internal object MarketHours {
    fun session(x: Quotable, now: Instant): Session = TODO("not implemented")
}
