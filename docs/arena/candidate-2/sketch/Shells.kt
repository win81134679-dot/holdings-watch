package com.example.holdings.shells

// 這個檔是兩個 Android 外殼的簽章地圖（實際會分在 :phone 與 :wear 兩個模組）。
// 外殼的規則：每個方法 <= 一個純函式呼叫 + 一個平台 API 呼叫，沒有商業邏輯。

import com.example.holdings.core.*

// ───────────────────────── :phone ─────────────────────────

/** Room 實作 [LedgerStore]。Entity（HoldingRow／QuoteRow／FxRow／LatchRow）全部 internal，數值欄位存 TEXT（BigDecimal.toPlainString）。 */
internal class RoomLedgerStore /* (private val db: LedgerDb) */ : LedgerStore {
    override suspend fun read(): LedgerState = TODO("not implemented: 單一 @Transaction 讀四張表")
    override suspend fun commit(state: LedgerState) = TODO("not implemented: 與現存比對，只寫差異；單一 @Transaction")
    override fun observe() = TODO("not implemented: Room invalidation Flow 合併四張表")
    override suspend fun upsert(h: Holding) = TODO("not implemented")
    override suspend fun delete(id: HoldingId) = TODO("not implemented: 同交易刪掉屬於它的 latch")
}

/**
 * WorkManager 進入點。周期 15 分鐘（平台下限）+ 手機 App 開啟／編輯持股時的一次性「立刻刷新」。
 * 兩者共用同一個 unique name，ExistingWorkPolicy.KEEP：同時只會有一輪在跑，不需要自己的鎖。
 * plan 為空時整輪不碰網路（全部收盤）。
 */
internal class RefreshWorker /* : CoroutineWorker */ {
    suspend fun doWork(): Any = TODO("not implemented: RefreshBoard.run() -> Result.success / retry（只有網路類失敗才 retry，且 WorkManager 自己退避）")
    companion object {
        fun schedule(/* ctx */) { TODO("not implemented: enqueueUniquePeriodicWork(15min, UPDATE) + enqueueUniqueWork(KEEP) 的 helper") }
    }
}

/** 把 Board 寫進 Data Layer。putDataItem 是 upsert，相同內容不觸發手錶端回呼；本身冪等。 */
internal class DataLayerPublisher /* (private val dataClient: DataClient) */ : BoardPublisher {
    override suspend fun publish(b: Board) = TODO("not implemented: PutDataRequest.create(BoardCodec.PATH).setData(BoardCodec.encode(b)).setUrgent()")
}

/**
 * 手機通知：高重要性頻道、決定性 notificationId、setOnlyAlertOnce(true)，
 * 這樣補送同一則是靜默取代，不會再震一次。不設 localOnly -> 由 Wear OS 通知橋接到手錶並震動。
 * 是否橋接成功（手機鎖屏、手錶配戴時）要實機驗證，這是整個設計最大的平台依賴（未解問題 1）。
 */
internal class PhoneNotificationSink : AlertSink {
    override suspend fun send(n: AlertNotice): Boolean = TODO("not implemented: NotificationManagerCompat.notify(n.notificationId, ...)；權限被拒回 false")
}

// ───────────────────────── :wear ─────────────────────────

/** 手錶端 [BoardReplica]：直接讀 Data Layer 裡手機推來的項目，不另存第二份。 */
internal class DataLayerReplica /* (private val dataClient: DataClient) */ : BoardReplica {
    override suspend fun current(): BoardReplica.State = TODO("not implemented: getDataItems(path) -> BoardCodec.decode")
    override fun observe() = TODO("not implemented: callbackFlow { addListener }")
}

/** 收到手機推送 -> 叫 Tile 與小格重畫。唯一的「事件」路徑；其餘都是讀。 */
internal class BoardChangedListener /* : WearableListenerService */ {
    fun onDataChanged(/* events */) { TODO("not implemented: TileService.getUpdater(ctx).requestUpdate(HoldingsTile::class.java); ComplicationDataSourceUpdateRequester.requestUpdateAll()") }
}

/** Tile：replica.current() -> BoardViews.tile() -> 版面。freshnessInterval 15 分鐘，讓「更新於 N 分鐘前」自己走動。 */
internal class HoldingsTile /* : TileService */ {
    suspend fun onTileRequest(): Any = TODO("not implemented")
}

/** 小格 SHORT_TEXT：replica.current() -> BoardViews.complication() -> 「+1.2%」或「--」。 */
internal class DayRatioComplication /* : SuspendingComplicationDataSourceService */ {
    suspend fun onComplicationRequest(): Any = TODO("not implemented")
}

/** 手錶 App（Compose Wear M3）：replica.observe() -> BoardViews.tabs() -> 分頁。只有顯示，沒有任何寫入。 */
internal class WatchApp {
    fun content(): Unit = TODO("not implemented")
}
