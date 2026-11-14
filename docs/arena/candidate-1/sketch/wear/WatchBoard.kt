package holdings.wear

import android.content.Context
import holdings.core.Valuation
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/*
 * 手錶上三個介面（Tile、App、錶面小格）唯一的讀取入口。
 *
 * 讀取路徑（三個介面都一樣，追到底只有三個檔）：
 *   介面 → WatchBoard.read() → Data Layer 本機快取（DataClient.getDataItems）→ PortfolioCodec.decodeSnapshot → valuate
 *
 * 手錶不自己存一份：Data Layer 已經把最新的 DataItem 持久化在手錶上，再抄進 DataStore 就是兩份要同步的副本。
 * 手錶不連網、不寫任何狀態、不判斷提醒。
 */

/** 介面要畫的東西。三種狀態窮舉，每個介面都必須處理「還沒收到」與「版本太新」。 */
sealed interface WatchView {
    /** 從沒收到手機的資料（剛安裝、手機 App 沒開過）。畫面：「請在手機上新增持股」。 */
    data object Empty : WatchView
    /** 手機 App 比手錶 App 新，封包格式看不懂。畫面：「請更新手錶 App」。 */
    data object NeedsUpdate : WatchView
    data class Ready(val valuation: Valuation, val now: Instant) : WatchView
}

object WatchBoard {
    /**
     * 單次讀取（Tile、小格用）。
     * TODO：
     *   uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).authority("*").path(SyncContract.SNAPSHOT_PATH).build()
     *   buf = Wearable.getDataClient(context).getDataItems(uri).await()
     *   取第一筆（只有一支手機會寫）→ decodeSnapshot → Ok → valuate(snapshot, now)
     *   TooNew → NeedsUpdate；Corrupt 或沒有 → Empty
     *   buf.release()
     */
    suspend fun read(context: Context, now: Instant = Instant.now()): WatchView = TODO("not implemented")

    /**
     * 持續觀察（手錶 App 開著時用）：先 emit read()，之後 DataClient.addListener 有變動就重讀；
     * 另外每分鐘重 emit 一次，讓「資料 N 分鐘前」會走。
     */
    fun observe(context: Context): Flow<WatchView> = TODO("not implemented: callbackFlow")
}
