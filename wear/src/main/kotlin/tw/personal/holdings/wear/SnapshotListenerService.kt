package tw.personal.holdings.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

/** 只發「重拉」訊號；資料的唯一來源仍是 Data Layer，這裡不解碼也不存檔。 */
class SnapshotListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        requestSurfaceUpdates(this)
    }
}

/** Tile 與小格都設成不定時重拉，資料變了只能靠這裡叫它們。 */
fun requestSurfaceUpdates(context: Context) {
    TileService.getUpdater(context).requestUpdate(HoldingsTileService::class.java)
    ComplicationDataSourceUpdateRequester
        .create(context, ComponentName(context, PnlComplicationService::class.java))
        .requestUpdateAll()
}
