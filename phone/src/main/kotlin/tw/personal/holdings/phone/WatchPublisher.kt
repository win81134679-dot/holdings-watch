package tw.personal.holdings.phone

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import tw.personal.holdings.core.PortfolioCodec
import tw.personal.holdings.core.PortfolioSnapshot
import tw.personal.holdings.core.SyncContract

/**
 * 手機 → 手錶。Data Layer 的 DataItem：最新狀態語意，手錶離線時系統保留、連上自動送到；同一路徑每次整份取代。
 * 內容跟手機上已存的那份一樣就不送（封包刻意不含時間，位元組相同＝內容相同）。
 */
internal class WatchPublisher(private val context: Context) {

    suspend fun publish(snapshot: PortfolioSnapshot): String = try {
        val bytes = PortfolioCodec.encodeSnapshot(snapshot)
        val nodes = Wearable.getNodeClient(context)
        val local = nodes.localNode.await().id
        val connected = nodes.connectedNodes.await().joinToString { it.displayName }
        val data = Wearable.getDataClient(context)
        val mine = Uri.Builder()
            .scheme(PutDataRequest.WEAR_URI_SCHEME)
            .authority(local)
            .path(SyncContract.SNAPSHOT_PATH)
            .build()
        val existing = data.getDataItem(mine).await()?.data
        if (existing != null && existing.contentEquals(bytes)) {
            "unchanged ${bytes.size} bytes (holdings=${snapshot.holdings.size} quotes=${snapshot.quotes.size}); connected=[$connected]"
        } else {
            val request = PutDataRequest.create(SyncContract.SNAPSHOT_PATH).setData(bytes).setUrgent()
            val item = data.putDataItem(request).await()
            "sent ${bytes.size} bytes to ${item.uri} (holdings=${snapshot.holdings.size} quotes=${snapshot.quotes.size}); " +
                "connected=[$connected]"
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "failed ${e.javaClass.simpleName}: ${e.message}"
    }
}
