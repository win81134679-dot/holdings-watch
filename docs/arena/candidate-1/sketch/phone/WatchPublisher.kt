package holdings.phone

import android.content.Context
import holdings.core.PortfolioSnapshot

/**
 * 手機 → 手錶。Data Layer 的 DataItem（不是 MessageClient）：
 *  - DataItem 是「最新狀態」語意，手錶離線時系統保留，連上時自動送到；Message 會丟。
 *  - 同一路徑每次 put 都是整份取代，所以手錶永遠只有一份完整快照，沒有增量合併要處理。
 *  - 大小上限約 100 KB；持股幾十筆＋報價的 JSON 遠低於此。
 *
 * TODO：
 *   Wearable.getDataClient(context).putDataItem(
 *       PutDataRequest.create(SyncContract.SNAPSHOT_PATH)
 *           .setData(PortfolioCodec.encodeSnapshot(snapshot))
 *           .setUrgent()          // 有到價時才需要急件？見 design.md〈接受的取捨〉，草圖先一律 urgent
 *   ).await()                     // kotlinx-coroutines-play-services，版本實作前查
 *   沒配對手錶、Wear OS app 沒裝 → 例外吞掉並記 log；這不是錯誤狀態。
 */
internal class WatchPublisher(private val context: Context) {
    suspend fun publish(snapshot: PortfolioSnapshot): Unit = TODO("not implemented")
}
