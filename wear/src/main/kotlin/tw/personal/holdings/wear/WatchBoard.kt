package tw.personal.holdings.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import tw.personal.holdings.core.Decoded
import tw.personal.holdings.core.PortfolioCodec
import tw.personal.holdings.core.SyncContract
import tw.personal.holdings.core.Valuation
import tw.personal.holdings.core.valuate
import java.time.Instant

/** 手錶三個介面要畫的東西。每個介面都必須處理「還沒收到」與「版本太新」。 */
sealed interface WatchView {
    data object Empty : WatchView
    data object NeedsUpdate : WatchView
    data class Ready(val valuation: Valuation, val now: Instant) : WatchView
}

/**
 * Tile、App、錶面小格唯一的讀取入口：Data Layer 本機快取 → decodeSnapshot → valuate。
 * 手錶不另存一份：Data Layer 已經把 DataItem 持久化在手錶上。
 */
object WatchBoard {
    private val snapshotUri: Uri = Uri.Builder()
        .scheme(PutDataRequest.WEAR_URI_SCHEME)
        .authority("*")
        .path(SyncContract.SNAPSHOT_PATH)
        .build()

    suspend fun read(context: Context, now: Instant = Instant.now()): WatchView {
        val bytes = try {
            snapshotBytes(context)
        } catch (e: ApiException) {
            Log.w(TAG, "data layer unavailable", e)
            null
        } ?: return WatchView.Empty
        return when (val decoded = PortfolioCodec.decodeSnapshot(bytes)) {
            is Decoded.Ok ->
                if (decoded.value.holdings.isEmpty()) WatchView.Empty
                else WatchView.Ready(valuate(decoded.value, now), now)
            is Decoded.TooNew -> WatchView.NeedsUpdate
            is Decoded.Corrupt -> {
                Log.w(TAG, "snapshot corrupt: ${decoded.detail}")
                WatchView.Empty
            }
        }
    }

    /**
     * App 開著時用：手機推新資料就重讀；每分鐘也重讀一次，讓持有天數、到期跟著時間走。
     * 手錶自己寫的 DataItem 不會觸發 listener（實機確認），debug 範例資料要等下一次每分鐘重讀。
     */
    fun observe(context: Context): Flow<WatchView> = callbackFlow {
        val app = context.applicationContext
        val client = Wearable.getDataClient(app)
        val listener = DataClient.OnDataChangedListener { launch { send(read(app)) } }
        client.addListener(listener).await()
        launch {
            while (true) {
                send(read(app))
                delay(60_000)
            }
        }
        awaitClose { client.removeListener(listener) }
    }

    private suspend fun snapshotBytes(context: Context): ByteArray? {
        val localNodeId = Wearable.getNodeClient(context).localNode.await().id
        val buffer = Wearable.getDataClient(context).getDataItems(snapshotUri, DataClient.FILTER_LITERAL).await()
        val items = try {
            buffer.mapNotNull { item -> item.data?.let { SourcedBytes(item.uri.host, it) } }
        } finally {
            buffer.release()
        }
        return pickSnapshot(items, localNodeId)
    }

    private const val TAG = "WatchBoard"
}

internal class SourcedBytes(val nodeId: String?, val bytes: ByteArray)

/** 手機推來的那份優先；手錶本機那份只有 debug 範例廣播會寫，手機資料一到就讓位。 */
internal fun pickSnapshot(items: List<SourcedBytes>, localNodeId: String): ByteArray? =
    (items.firstOrNull { it.nodeId != localNodeId } ?: items.firstOrNull())?.bytes
