package holdings.core

import java.math.BigDecimal
import java.time.Instant

/**
 * 手機與手錶共同的輸入：持股＋相關報價（含 USD_TWD 匯率）。
 * 手機用自己的兩個存檔組出來；手錶從 Data Layer 解碼出來。同一份輸入、同一個 [valuate]，
 * 所以手機畫面、Tile、App、小格的數字不可能對不起來。
 */
data class PortfolioSnapshot(
    val holdings: List<Holding>,
    val quotes: Map<Quotable, Quote>,
)

/**
 * 估值。純函式，無 I/O、無時鐘（now 由呼叫端給），JVM 單元測試的主戰場。
 *
 * TODO 虛擬碼，對每筆 h：
 *   q = quotes[h.asset.instrument] ?: → PositionValue(priced = null)
 *   s = h.side.sign
 *   pnl       = (q.last − h.averageCost) × h.asset.exposure × s           （原幣）
 *   pnlPct    = (q.last − h.averageCost) / h.averageCost × s
 *   dayPnl    = (q.last − q.previousClose) × h.asset.exposure × s
 *   dayPct    = (q.last − q.previousClose) / q.previousClose             （商品本身的漲跌，不乘方向）
 *   toTwd(m)  = m.currency == TWD → Twd(m.amount)
 *               USD → quotes[FxPair.USD_TWD]?.last?.let { Twd(m.amount × it) } ?: null
 *   daysHeld  = entryDate 到 now 在 TAIPEI 的日曆日數
 *   totals：只加 pnlTwd != null 的部位；加不進去的數進 missing
 *   dayPnlPct = Σ dayPnlTwd ÷ Σ |previousClose × exposure 換台幣|（分母定義見 design.md〈未解問題〉）
 *   台幣金額四捨五入到元、百分比到 0.01% 只在顯示層做；這裡保留完整精度。
 */
fun valuate(snapshot: PortfolioSnapshot, now: Instant): Valuation = TODO("not implemented")

data class Valuation(
    val positions: List<PositionValue>,
    val totals: Totals,
    /** 參與估值的報價裡最舊的一筆時間；畫面顯示「資料 HH:mm」。沒有任何報價時為 null。 */
    val oldestQuoteAt: Instant?,
) {
    /** 手錶 App 的分頁、手機的分組。固定順序 TW_EQUITY, US_EQUITY, TW_FUTURES, CRYPTO；空的市場不出現。 */
    fun byMarket(): Map<Market, List<PositionValue>> = TODO("not implemented")

    /** Tile 的「今日漲跌最多 n 檔」：依 |dayChangePct| 由大到小，沒報價的排除。 */
    fun topMovers(n: Int = 3): List<PositionValue> = TODO("not implemented")
}

data class PositionValue(
    val holding: Holding,
    val daysHeld: Long,
    /** 沒有報價時整組為 null：只有一個「缺報價」狀態，不會出現 pnl 有值而 dayPnl 沒值的組合。 */
    val priced: Priced?,
)

data class Priced(
    val quote: Quote,
    val pnl: Money,
    val pnlPct: BigDecimal,
    val dayPnl: Money,
    val dayChangePct: BigDecimal,
    /** 美元部位缺匯率時為 null；台幣部位永遠有值。 */
    val pnlTwd: Twd?,
    val dayPnlTwd: Twd?,
)

/** 總計可能不完整：缺報價或缺匯率的部位不加，但要讓畫面知道少了幾筆。 */
data class Partial<T>(val value: T, val missing: Int)

data class Totals(
    val pnl: Partial<Twd>,
    val dayPnl: Partial<Twd>,
    /** 分母為 0（沒有任何有報價的部位）時為 null。 */
    val dayPnlPct: BigDecimal?,
)
