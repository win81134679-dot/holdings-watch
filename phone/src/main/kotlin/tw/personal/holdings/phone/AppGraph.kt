package tw.personal.holdings.phone

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import tw.personal.holdings.core.Decoded
import tw.personal.holdings.core.Holding
import tw.personal.holdings.core.PortfolioCodec
import tw.personal.holdings.core.RefreshState
import kotlinx.coroutines.CancellationException
import tw.personal.holdings.phone.update.UpdateStateSerializer
import tw.personal.holdings.phone.update.Updates
import tw.personal.holdings.quotes.HttpGet
import tw.personal.holdings.quotes.HttpResponse
import tw.personal.holdings.quotes.QuoteFetchers
import tw.personal.holdings.quotes.UrlConnectionHttpGet
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

internal const val TAG = "HoldingsPhone"

/**
 * 行程內唯一的一組物件。DataStore 規定同一個檔在同一個行程只能有一個實例，
 * 所以 UI（Portfolio）與 Worker（Refresher）都從這裡拿同一份。
 *
 *   holdings.json      只有 Portfolio.save/delete 寫
 *   refresh-state.json 只有 Refresher 寫
 *   update-state.json  只有 Updates.check 寫
 */
internal class AppGraph private constructor(context: Context) {
    private val holdings: DataStore<List<Holding>> = DataStoreFactory.create(
        serializer = HoldingsSerializer(context.dataStoreFile(HOLDINGS_FILE).parentFile!!),
        corruptionHandler = ReplaceFileCorruptionHandler { emptyList() },
        produceFile = { context.dataStoreFile(HOLDINGS_FILE) },
    )

    private val refreshState: DataStore<RefreshState> = DataStoreFactory.create(
        serializer = RefreshStateSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { RefreshState.EMPTY },
        produceFile = { context.dataStoreFile(REFRESH_STATE_FILE) },
    )

    val portfolio: Portfolio = PhonePortfolio(context, holdings, refreshState)

    val updates = Updates(
        context,
        DataStoreFactory.create(
            serializer = UpdateStateSerializer,
            produceFile = { context.dataStoreFile(UPDATE_STATE_FILE) },
        ),
    )

    val refresher = Refresher(
        holdings = holdings,
        refreshState = refreshState,
        fetcher = QuoteFetchers.default(LoggingHttpGet(UrlConnectionHttpGet()), Instant::now),
        post = AlertNotifier(context)::post,
        publish = WatchPublisher(context)::publish,
        clock = Instant::now,
        log = { Log.i(TAG, it) },
    )

    companion object {
        const val HOLDINGS_FILE = "holdings.json"
        const val REFRESH_STATE_FILE = "refresh-state.json"
        const val UPDATE_STATE_FILE = "update-state.json"

        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) { instance ?: AppGraph(context.applicationContext).also { instance = it } }
    }
}

/**
 * :quotes 把連線例外與解析例外都收成 FetchFailure.Network；這裡記下每個請求的 HTTP 狀態，
 * logcat 才分得出是來源拒絕、連不上，還是回了 200 卻解析失敗。
 */
private class LoggingHttpGet(private val inner: HttpGet) : HttpGet {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = try {
        inner.get(url, headers).also { Log.i(TAG, "  http ${it.status} ${it.body.size}B $url") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "  http ${e.javaClass.simpleName}: ${e.message} $url")
        throw e
    }
}

/** 持股是使用者的正本：讀不懂時先把原檔留一份副本，再讓 DataStore 換成空清單，不默默蓋掉。 */
internal class HoldingsSerializer(private val backupDir: File) : Serializer<List<Holding>> {
    override val defaultValue: List<Holding> = emptyList()

    override suspend fun readFrom(input: InputStream): List<Holding> {
        val bytes = input.readBytes()
        val problem = when (val d = PortfolioCodec.decodeHoldings(bytes)) {
            is Decoded.Ok -> return d.value
            is Decoded.TooNew -> "版本 ${d.version} 比這支 App 新"
            is Decoded.Corrupt -> d.detail
        }
        val backup = File(backupDir, "holdings.corrupt-${System.currentTimeMillis()}.json")
        backup.writeBytes(bytes)
        Log.e(TAG, "holdings.json unreadable ($problem); backed up to ${backup.name}")
        throw CorruptionException("holdings.json: $problem")
    }

    override suspend fun writeTo(t: List<Holding>, output: OutputStream) {
        output.write(PortfolioCodec.encodeHoldings(t))
    }
}

/** 這個檔壞了直接丟：報價會重抓，提醒狀態重來最多多響一次。 */
internal object RefreshStateSerializer : Serializer<RefreshState> {
    override val defaultValue: RefreshState = RefreshState.EMPTY

    override suspend fun readFrom(input: InputStream): RefreshState =
        when (val d = PortfolioCodec.decodeRefreshState(input.readBytes())) {
            is Decoded.Ok -> d.value
            is Decoded.TooNew -> throw CorruptionException("refresh-state.json: 版本 ${d.version}")
            is Decoded.Corrupt -> throw CorruptionException("refresh-state.json: ${d.detail}")
        }

    override suspend fun writeTo(t: RefreshState, output: OutputStream) {
        output.write(PortfolioCodec.encodeRefreshState(t))
    }
}
