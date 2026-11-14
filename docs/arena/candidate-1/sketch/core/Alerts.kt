package holdings.core

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/*
 * 到價與轉倉提醒。
 *
 * 誰判斷：只有手機（Refresher 每輪抓完價之後）。手錶不判斷、不發通知；
 * 手機的通知由系統橋接到手錶並震動。理由見 design.md〈到價提醒〉。
 *
 * 不重複的機制有兩層：
 *  1. 狀態機：每條規則 Armed → Fired，Fired 只在價格回到安全側超過緩衝帶才回到 Armed。
 *  2. 通知的 tag＝規則鍵的穩定字串：就算同一個 Fired 被重送（當機重跑），也只會「取代」同一則通知，
 *     加上 setOnlyAlertOnce，通知還在時不會再震一次。
 */

/** 提醒的種類。 */
enum class AlertKind { STOP_LOSS, TARGET, ROLLOVER }

/**
 * 規則的身分。level 或合約月份是鍵的一部分：使用者把停損從 580 改成 560，就是一條新規則、從 Armed 開始；
 * 舊的那條在下一輪因為推不出來而被丟掉（對帳，不是另外清）。
 */
data class AlertKey(val holdingId: HoldingId, val kind: AlertKind, val discriminator: String) {
    /**
     * 通知 tag 與存檔鍵。必須跨行程穩定：不能用 hashCode（enum 的 hashCode 是物件身分，每次開 App 都不同）。
     * 例："3f2c…|STOP_LOSS|580.00"
     */
    fun stableTag(): String = TODO("not implemented: holdingId | kind.name | discriminator 以直線串起")
}

sealed interface AlertState {
    data object Armed : AlertState
    data class Fired(val at: Instant) : AlertState
}

/** 這一輪要發的一則通知。內容用領域值，文字由手機的 Notifier 組（中文字串不進 core）。 */
data class AlertFiring(
    val key: AlertKey,
    val holding: Holding,
    /** 價格提醒時是觸發價；轉倉提醒時為 null。 */
    val price: Price?,
    /** 轉倉提醒時是最後交易日。 */
    val lastTradingDay: LocalDate?,
)

data class AlertStep(
    /** 下一輪的完整狀態表：只含從目前持股推得出來的鍵（刪掉的持股、改掉的價位自然消失）。 */
    val next: Map<AlertKey, AlertState>,
    val fire: List<AlertFiring>,
)

/** 價格回到安全側要超過觸發價多少比例才重新武裝。預設 1%，見 design.md〈未解問題〉。 */
val DEFAULT_REARM_BAND: BigDecimal = BigDecimal("0.01")

/** 轉倉提醒提前幾個日曆日（「結算日前 3 天」）。 */
const val ROLLOVER_DAYS_BEFORE: Long = 3

/**
 * 提醒的唯一入口。純函式；跑兩次（同輸入）得到同樣的 next 與 fire；
 * 把 next 當 states 再跑一次，fire 為空（已 Fired 的不會再發）。
 *
 * TODO 虛擬碼：
 *   rules = holdings.flatMap { h ->
 *     stopLoss?.let  { 規則(STOP_LOSS, level, 觸發 = if LONG 價 ≤ level else 價 ≥ level) } +
 *     target?.let    { 規則(TARGET,    level, 觸發 = if LONG 價 ≥ level else 價 ≤ level) } +
 *     (asset 是 FutureAsset) → 規則(ROLLOVER, discriminator = "YYYY-MM",
 *                              觸發 = today(TAIPEI) ≥ lastTradingDay − ROLLOVER_DAYS_BEFORE)
 *   }
 *   對每條規則 r：
 *     prev = states[r.key] ?: Armed
 *     price 規則需要 quotes[h.asset.instrument]，沒有報價 → 狀態原樣保留、不發
 *     prev == Armed  且 觸發  → next = Fired(now)，加入 fire
 *     prev == Fired  且 價格回到安全側超過 rearmBand × level → next = Armed（ROLLOVER 不回 Armed）
 *     其他 → next = prev
 *   next 只放 rules 的鍵（對帳：不在 rules 的舊鍵丟掉）
 */
fun stepAlerts(
    holdings: List<Holding>,
    quotes: Map<Quotable, Quote>,
    states: Map<AlertKey, AlertState>,
    now: Instant,
    rearmBand: BigDecimal = DEFAULT_REARM_BAND,
): AlertStep = TODO("not implemented")
