package com.example.holdings.core

import java.time.Instant
import java.time.LocalDate

/**
 * 一輪刷新 = 讀狀態 -> 決定要抓什麼 -> 抓 -> 合併 -> 判斷提醒 -> 一次寫回 -> 發佈 -> 通知 -> 標記已通知。
 * 難的決定都在純函式裡（[plan]、[merge]、[AlertRules.step]、[Valuation.valuate]）；
 * 外殼 [RefreshBoard] 只負責 IO 順序。任何一步崩潰，下一輪都從現存狀態重新推導，會收斂到同樣結果。
 */
object Refresh {
    data class Plan(val instruments: Set<Instrument>, val needFx: Boolean) {
        val isEmpty get() = instruments.isEmpty() && !needFx
    }

    /** 全部收盤後 plan 為空 -> Worker 不碰網路。這是「不要高頻輪詢」的結構保證，不是節流參數。 */
    fun plan(state: LedgerState, now: Instant): Plan = TODO("not implemented: QuoteRules.due per holding instrument, QuoteRules.fxDue if any USD holding")

    /** 抓回結果併入狀態。單一商品失敗只留舊紀錄，不影響其他商品；失敗不改 fetchedAt（下一輪還會重試）。 */
    fun merge(state: LedgerState, batch: FetchBatch, now: Instant): LedgerState = TODO("not implemented")
}

/** 抓取結果（領域型別）。失敗原因是有限幾種，UI 可以顯示「來源暫時無法使用」而不是吃掉。 */
data class FetchBatch(
    val quotes: Map<InstrumentKey, FetchResult<Quote>>,
    val fx: FetchResult<FxRate>?,
    val source: Map<InstrumentKey, SourceId>,
)

sealed interface FetchResult<out T> {
    data class Ok<T>(val value: T) : FetchResult<T>
    sealed interface Failed : FetchResult<Nothing> {
        data object Network : Failed
        data object RateLimited : Failed
        data class Unparseable(val detail: String) : Failed     // 對方改格式 = 要修 adapter 的訊號
        data object UnknownSymbol : Failed
    }
}

/** 一輪刷新的 IO 外殼（手機端）。薄：每行都是呼叫一個純函式或一個 port。 */
class RefreshBoard(
    private val ledger: LedgerStore,
    private val sources: QuoteSources,
    private val publisher: BoardPublisher,
    private val alerts: AlertSink,
    private val clock: java.time.Clock,
    private val calendar: SettlementCalendar,
) {
    suspend fun run(): Outcome = TODO(
        """not implemented:
        val now = clock.instant()
        val before = ledger.read()                                 // 一次交易讀完整個 LedgerState
        val plan   = Refresh.plan(before, now)
        val merged = if (plan.isEmpty) before else Refresh.merge(before, sources.fetch(plan), now)
        val board  = Valuation.valuate(merged, now, calendar, today)
        val step   = AlertRules.step(board, merged.latches, now, today)
        ledger.commit(merged.copy(latches = step.latches))         // 報價＋PENDING 閂 同一個交易；崩潰在此之前 = 什麼都沒發生
        publisher.publish(board)                                   // 內容相同時 Data Layer 不會重複通知手錶，所以不另存雜湊
        val sent = step.toSend.filter { alerts.send(it) }          // notificationId 決定性：重送 = 取代同一則
        ledger.commit(ledger.read().let { it.copy(latches = AlertRules.markSent(it.latches, sent.map { n -> n.key }, now)) })
        """
    )

    data class Outcome(val fetched: Int, val failed: Int, val alertsSent: Int)
}

/** 手機端持久化的唯一入口（Room 實作）。回傳與接受的全是領域型別，Entity 不外露。 */
interface LedgerStore {
    suspend fun read(): LedgerState
    suspend fun commit(state: LedgerState)                 // 以 diff 寫入，單一交易
    fun observe(): kotlinx.coroutines.flow.Flow<LedgerState>   // 手機 UI 訂閱，每次變動重新 valuate
    suspend fun upsert(h: Holding)
    suspend fun delete(id: HoldingId)
}

/** 報價來源的埠：給計畫，回領域型別結果。實作在 :quotes，:core 只認這個介面。 */
fun interface QuoteSources { suspend fun fetch(plan: Refresh.Plan): FetchBatch }

fun interface AlertSink { suspend fun send(n: AlertNotice): Boolean }
fun interface BoardPublisher { suspend fun publish(b: Board) }
