package holdings.wear

import android.content.ComponentName
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import com.google.common.util.concurrent.ListenableFuture
import holdings.core.PositionValue
import holdings.core.Twd
import holdings.core.Valuation
import java.math.BigDecimal
import java.time.Instant

/*
 * 三個介面＋一個接收服務。每個介面只做：WatchBoard.read() → 把 WatchView 轉成畫面模型 → 畫。
 * 畫面模型的轉換是純函式（下面 *Model.from），可在 :wear 的 JVM 測試裡測；外殼只剩 protolayout／complication 的組裝。
 */

// ---------------------------------------------------------------------------------------------
// 接收：手機 put 了新快照 → 叫 Tile 與小格重拉。手錶 App 開著時由 WatchBoard.observe 自己看到變動。
// ---------------------------------------------------------------------------------------------

/**
 * Manifest：intent-filter DATA_CHANGED，data scheme=wear host=* pathPrefix=/holdings/snapshot。
 * 不在這裡解碼或存檔，只發「重拉」訊號：資料的唯一真相來源仍是 Data Layer。
 */
class SnapshotListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        TODO(
            "not implemented: TileService.getUpdater(this).requestUpdate(HoldingsTileService::class.java)；" +
                "ComplicationDataSourceUpdateRequester.create(this, ComponentName(this, PnlComplicationService::class.java)).requestUpdateAll()",
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Tile（優先序第一）：台幣總損益＋今日漲跌最多 3 檔
// ---------------------------------------------------------------------------------------------

data class TileModel(
    val totalPnl: Twd,
    val totalMissing: Int,
    val dayPnl: Twd,
    val movers: List<Mover>,
    val dataAt: Instant?,
) {
    data class Mover(val label: String, val dayChangePct: BigDecimal)

    companion object {
        /** label：台股代號、美股代號、期貨「TX 12月」、加密幣種。中文名來源見 design.md〈未解問題〉。 */
        fun from(valuation: Valuation): TileModel = TODO("not implemented: totals + valuation.topMovers(3)")
    }
}

/**
 * 只在收到新快照時更新（freshnessIntervalMillis = 0）：Tile 不自己定時重拉，省電。
 * 黑底（Wear 品質準則 WO-V13）、需要 preview 圖。
 * TODO 確認 Tiles 1.6.2 是否已有 suspend 版 TileService；沒有就用 SuspendToFutureAdapter（concurrent-futures-ktx）橋接。
 */
class HoldingsTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            TODO(
                "not implemented: when (val v = WatchBoard.read(this)) { Empty/NeedsUpdate → 提示版面；" +
                    "Ready → TileLayouts.holdings(TileModel.from(v.valuation), v.now) }，ProtoLayout Material3 1.4.2",
            )
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        TODO("not implemented: 無圖片資源，回空 Resources（版本字串固定）")
}

// ---------------------------------------------------------------------------------------------
// 錶面小格（優先序第三）：今日總損益 %
// ---------------------------------------------------------------------------------------------

/**
 * 支援 SHORT_TEXT（"+1.24%"）。更新週期在 manifest 設 UPDATE_PERIOD_SECONDS = 0：只靠推播觸發。
 * 缺報價時文字加「*」，缺資料時回 NoDataComplicationData。
 */
class PnlComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        TODO("not implemented: WatchBoard.read(this) → Ready 時 ShortTextComplicationData(valuation.totals.dayPnlPct 格式化)")

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        TODO("not implemented: 固定示意 \"+1.2%\"")
}

// ---------------------------------------------------------------------------------------------
// 手錶 App（優先序第二）：依市場分頁
// ---------------------------------------------------------------------------------------------

/**
 * TODO Compose for Wear OS（Material3 1.7.1）：
 *   val view by WatchBoard.observe(context).collectAsState(initial = null)
 *   Ready → HorizontalPager(pages = valuation.byMarket().keys) { market ->
 *       TransformingLazyColumn { 每列：代號、現價、原幣損益、今日 %、持有天數 }
 *   }
 *   頁首：台幣總損益＋「資料 HH:mm」（oldestQuoteAt）；END_OF_DAY 的列標「收盤」。
 *   空單列標「空」。沒有任何編輯控制項（手錶只顯示）。
 */
internal object WatchAppScreens

/** 列的排序與顯示字串在 :wear 的純函式裡決定，Tile 與 App 共用同一個 label 規則。 */
internal fun PositionValue.label(): String = TODO("not implemented")
