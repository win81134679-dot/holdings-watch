package com.example.holdings.core

import java.time.Instant
import java.time.LocalDate

/**
 * 到價提醒：誰判斷 = 手機（唯一有新鮮報價與唯一寫持股的一端）；怎麼不重複 = 去重閂（latch）。
 *
 * 「碰線」本身不是狀態，是 [ExitLevels.status] / [RolloverNotice] 對現況的推導（手錶上的紅綠點也用它）。
 * 唯一要存的狀態是「這個條件我通知過了沒」，也就是 [LatchState]。
 */
sealed interface AlertKey {
    /** level 是 key 的一部分：使用者改了停損價，key 就變了，新條件天然是 ARMED，舊 key 被 [AlertRules.prune] 回收。不需要「重設」動作。 */
    data class Exit(val holding: HoldingId, val kind: ExitKind, val level: Price) : AlertKey
    /** 轉倉：一個合約月一次，不重複。 */
    data class Rollover(val holding: HoldingId, val delivery: java.time.YearMonth) : AlertKey
}
enum class ExitKind { STOP, TARGET }

/**
 * ARMED  : 還沒通知。
 * PENDING: 已決定要通知、但還沒確認送出（寫在同一個 DB 交易裡，所以崩潰後重跑能補送）。
 * NOTIFIED: 已送出；價格往回退過 [AlertRules.REARM_BAND] 才回 ARMED，避免在線上來回抖動連發。
 */
sealed interface LatchState {
    data object Armed : LatchState
    data class Pending(val since: Instant) : LatchState
    data class Notified(val at: Instant) : LatchState
}

/** 要送給使用者的一則通知內容。id 由 key 決定性推導，重送同一則就是「取代」，不是新增一則。 */
data class AlertNotice(val key: AlertKey, val title: String, val text: String, val notificationId: Int)

object AlertRules {
    val REARM_BAND: java.math.BigDecimal = java.math.BigDecimal("0.005")   // 0.5%，預設值，未解問題 4

    data class Step(val latches: Map<AlertKey, LatchState>, val toSend: List<AlertNotice>)

    /**
     * 純函式。每次刷新後呼叫一次；重複呼叫同一輸入結果相同。
     * 1. prune：丟掉不在現有持股條件裡的 key（改價、刪持股、已過期轉倉）。
     * 2. 只對 freshness 是 LIVE 或 FINAL 的報價判斷（STALE／MISSING 不觸發也不重新武裝）。
     * 3. ARMED 且條件成立 -> PENDING；NOTIFIED 且價格退出 band -> ARMED。
     * 4. toSend = 所有 PENDING（含上次崩潰留下的），所以「新觸發」與「補送」是同一條路徑。
     */
    fun step(board: Board, previous: Map<AlertKey, LatchState>, now: Instant, today: LocalDate): Step = TODO("not implemented")

    /** PENDING -> NOTIFIED，只在通知確實送出（NotificationManager.notify 回來）後呼叫。 */
    fun markSent(latches: Map<AlertKey, LatchState>, sent: Collection<AlertKey>, now: Instant): Map<AlertKey, LatchState> = TODO("not implemented")
}
