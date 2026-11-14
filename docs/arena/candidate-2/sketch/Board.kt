package com.example.holdings.core

import java.time.Instant
import java.time.LocalDate

/**
 * Board = 看板的唯一讀模型。手機 UI、同步到手錶的快照、Tile、小格、手錶 App 全部讀它。
 * 它是 [LedgerState] 的純函式推導（[valuate]），從不被存起來當第二份真相。
 * 損益、幣別換算、新鮮度、出場狀態都在這裡算完，手錶上只剩排版。
 */
data class Board(
    val asOf: Instant,                 // 做出這份看板時用到的最新報價時間，不是現在時鐘，避免每次推導都產生「不同」的快照
    val lines: List<BoardLine>,
    val totals: Totals,
)

data class BoardLine(
    val holdingId: HoldingId,
    val instrument: Instrument,
    val side: Side,
    val name: String,
    val qty: Quantity,
    val avgCost: Price,
    val last: Price?,                  // MISSING 時為 null；不用 0 代替（0 會被當成真的報價參與損益）
    val freshness: Freshness,
    val pnl: Pnl?,                     // last 為 null 時也為 null
    val day: Pnl?,                     // 今日損益（相對昨收）
    val exit: ExitStatus,
    val heldDays: Int,
    val rollover: RolloverNotice?,     // 僅期貨，且已進入結算前 3 天
)

/** 一筆損益：原幣一份、台幣一份（匯率缺時 twd 為 null，總計會標「不完整」）。 */
data class Pnl(val native: Money, val ratio: Ratio, val twd: Money?)

data class RolloverNotice(val settlement: LocalDate, val daysLeft: Int)

data class Totals(
    val pnlTwd: Money,
    val dayPnlTwd: Money,
    val dayRatio: Ratio?,              // 分母見 [Valuation.dayRatioBasis]
    val incompleteLines: Int,          // 缺報價或缺匯率而沒算進去的筆數；> 0 時 UI 必須標示「不完整」
)

/** 推導 Board 所需的全部輸入。持久化的就是這個形狀（手機 Room 一次交易讀出）。 */
data class LedgerState(
    val holdings: List<Holding>,
    val quotes: Map<InstrumentKey, QuoteRecord>,
    val fx: Map<Currency, FxRate>,                // 以「換成 TWD」為準：USD -> 匯率
    val latches: Map<AlertKey, LatchState>,
)

object Valuation {
    /**
     * 純函式，JVM 單元測試即可。
     * 每筆：pnl = side.sign * (last - cost) * qty * multiplier；pct = pnl / |cost*qty*multiplier|。
     * 換台幣：原幣 == TWD 直接帶；USD 乘 fx[USD]；沒匯率 twd = null 並計入 incompleteLines。
     * 排序：維持 holdings 的使用者順序（排序是檢視決定，不在這裡）。
     */
    fun valuate(state: LedgerState, now: Instant, calendar: SettlementCalendar, today: LocalDate): Board = TODO("not implemented")

    /**
     * 小格「今日總損益 %」的分母。預設：持股成本基礎（台幣）總和，期貨以保證金之外的名目價值會失真，故先用成本。
     * 這是決策紀錄沒定的口徑，放在單一函式，改一處即可（design.md 未解問題 2）。
     */
    fun dayRatioBasis(lines: List<BoardLine>): Money? = TODO("not implemented")
}

/** 手錶三個出口的版面資料。全是 Board 的純函式；Tile／小格／App 只負責把這些丟進 UI 元件。 */
object BoardViews {
    data class TileModel(val totalPnl: Money, val incomplete: Boolean, val movers: List<BoardLine>, val ageMinutes: Int?)
    data class ComplicationModel(val dayRatio: Ratio?, val stale: Boolean)
    data class MarketTab(val title: String, val lines: List<BoardLine>)

    /** movers = 今日漲跌幅絕對值最大的 [n] 檔（口徑為預設，見未解問題 3）。 */
    fun tile(b: Board, now: Instant, n: Int = 3): TileModel = TODO("not implemented")
    fun complication(b: Board, now: Instant): ComplicationModel = TODO("not implemented")
    /** 依市場分頁（台股／美股／期貨／加密），空市場不出分頁。 */
    fun tabs(b: Board): List<MarketTab> = TODO("not implemented")
}
