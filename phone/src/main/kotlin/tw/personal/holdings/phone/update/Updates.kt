package tw.personal.holdings.phone.update

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tw.personal.holdings.apk.SelfInstaller
import tw.personal.holdings.apk.ThisApp
import tw.personal.holdings.apk.copyHashing
import tw.personal.holdings.apk.sha256Of
import tw.personal.holdings.apk.verifyDownloaded
import tw.personal.holdings.core.ApkAsset
import tw.personal.holdings.core.CheckResult
import tw.personal.holdings.core.ManifestResult
import tw.personal.holdings.core.Release
import tw.personal.holdings.core.UpdateContract
import tw.personal.holdings.core.UpdateManifest
import tw.personal.holdings.core.UpdateOffer
import tw.personal.holdings.core.UpdateState
import tw.personal.holdings.core.UpdateStateCodec
import tw.personal.holdings.core.VersionStamp
import tw.personal.holdings.core.WearApkOffer
import tw.personal.holdings.core.WearInstallStatus
import tw.personal.holdings.core.WearInstallStatus.Stage
import tw.personal.holdings.core.afterCheck
import tw.personal.holdings.core.message
import tw.personal.holdings.core.offer
import tw.personal.holdings.phone.BuildConfig
import tw.personal.holdings.phone.TAG
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** 使用者接下來要自己做的事。可能同時有兩件（手機要允許安裝、手錶要用電腦開權限）。 */
enum class UserStep { ALLOW_UNKNOWN_SOURCES, CONFIRM_ON_PHONE, WATCH_ADB_ONCE, OPEN_WATCH_APP }

/** 按「更新」之後的進度。只活在記憶體：行程被換掉（手機更新自己）就從頭算。 */
data class ApplyProgress(
    val running: Boolean = false,
    val phoneLine: String? = null,
    val watchLine: String? = null,
    val steps: Set<UserStep> = emptySet(),
)

data class UpdateUi(
    val phone: VersionStamp,
    /** null：手錶從沒回報過版本（沒配對，或手錶還是 0.1）。 */
    val watch: VersionStamp?,
    val state: UpdateState,
    val offer: UpdateOffer?,
    val progress: ApplyProgress,
)

/**
 * 自動更新的唯一入口：檢查（每天一次的背景工作與設定頁的按鈕）、套用（按「更新」排進的工作）、給畫面的狀態。
 *
 *   update-state.json  只有 check 寫
 *   進度               只有 apply 與安裝結果的廣播寫（記憶體）
 */
internal class Updates(private val context: Context, private val store: DataStore<UpdateState>) {
    private val _progress = MutableStateFlow(ApplyProgress())
    val progress: StateFlow<ApplyProgress> = _progress.asStateFlow()

    private val phone = VersionStamp(ThisApp.versionCode(context).toInt(), ThisApp.versionName(context))

    val ui: Flow<UpdateUi> = combine(store.data, watchVersion(), progress) { state, watch, p ->
        UpdateUi(phone, watch, state, state.offer(phone.versionCode, watch?.versionCode), p)
    }

    fun manifestUrl(): String {
        if (BuildConfig.DEBUG) {
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).getString(KEY_URL, null)?.let { return it }
        }
        return BuildConfig.UPDATE_MANIFEST_URL
    }

    /** 抓 update.json、記下結果、要不要通知由 core 決定；不論結果都把「目前最新版」告訴手錶。 */
    suspend fun check(notify: Boolean): CheckResult {
        val url = manifestUrl()
        val result = fetchManifest(url)
        var notifyRelease: Release? = null
        store.updateData { s ->
            val outcome = s.afterCheck(result, phone.versionCode, Instant.now())
            notifyRelease = outcome.notify
            outcome.state
        }
        Log.i(TAG, "update check $url -> $result (notify=${notifyRelease?.versionCode})")
        if (notify) notifyRelease?.let { UpdateNotifier.newVersion(context, it) }
        publishLatest()
        return result
    }

    private suspend fun fetchManifest(url: String): CheckResult = withContext(Dispatchers.IO) {
        try {
            val bytes = httpGet(url).use { input ->
                input.readBytes().also { if (it.size > MAX_MANIFEST_BYTES) throw IOException("update.json ${it.size} bytes") }
            }
            when (val parsed = UpdateManifest.parse(bytes, url)) {
                is ManifestResult.Ok -> CheckResult.Found(parsed.release)
                is ManifestResult.Invalid -> CheckResult.Failed(parsed.detail)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            CheckResult.Failed(if (e.status == 404) "還沒有發佈任何版本（HTTP 404）" else "更新伺服器回應 HTTP ${e.status}")
        } catch (e: Exception) {
            CheckResult.Failed("連不上更新伺服器（${e.javaClass.simpleName}）")
        }
    }

    /** 手錶據此顯示「請在手機按更新」：手機自己的版本與查到的新版取大的。內容沒變就不寫。 */
    suspend fun publishLatest() {
        try {
            val latest = store.data.first().latest
            val stamp = if (latest != null && latest.versionCode > phone.versionCode) {
                VersionStamp(latest.versionCode, latest.versionName)
            } else {
                phone
            }
            val data = Wearable.getDataClient(context)
            val local = Wearable.getNodeClient(context).localNode.await().id
            val mine = wearUri(local, UpdateContract.LATEST_PATH)
            if (data.getDataItem(mine).await()?.data?.contentEquals(stamp.encode()) == true) return
            data.putDataItem(PutDataRequest.create(UpdateContract.LATEST_PATH).setData(stamp.encode()).setUrgent()).await()
            Log.i(TAG, "update: told watch latest=${stamp.versionCode}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update: publish latest failed ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** debug 換清單網址時用：記著的新版屬於舊來源，留著會讓橫幅指向舊來源的檔案。 */
    suspend fun forgetLatest() {
        store.updateData { UpdateState.EMPTY }
        _progress.value = ApplyProgress()
        publishLatest()
    }

    /** 按「更新」：排進唯一工作，連按也只跑一次；離開 App 也會跑完。 */
    fun requestApply() {
        _progress.value = ApplyProgress(running = true, phoneLine = "準備中…")
        UpdateWork.applySoon(context)
    }

    /** 先手錶、後手機：手機裝自己時行程會被換掉，之後的步驟就跑不到了。 */
    suspend fun apply() {
        val state = store.data.first()
        val watch = readWatchVersion()
        val offer = state.offer(phone.versionCode, watch?.versionCode)
        if (offer == null) {
            _progress.value = ApplyProgress(phoneLine = "已是最新版 ${phone.versionName}")
            return
        }
        _progress.value = ApplyProgress(running = true)
        cleanCache(offer.release)
        if (offer.watchBehind && watch != null) updateWatch(offer.release, watch)
        if (offer.phoneBehind) updatePhone(offer.release) else _progress.update { it.copy(running = false) }
    }

    private suspend fun updateWatch(release: Release, watch: VersionStamp) {
        fun line(text: String, step: UserStep? = null) =
            _progress.update { it.copy(watchLine = text, steps = if (step == null) it.steps else it.steps + step) }

        val node = Wearable.getNodeClient(context).connectedNodes.await().firstOrNull()
        if (node == null) {
            line("手錶沒連上，手錶這次先不更新；連上後再按一次更新")
            return
        }
        line("下載手錶版 ${release.versionName}…")
        val (file, sha) = try {
            download(release.wear)
        } catch (e: IOException) {
            line("手錶版下載失敗：${e.message}")
            return
        }
        verifyDownloaded(context, file, sha, release.wear, release.versionCode, watch.versionCode.toLong())?.let {
            line("手錶版沒通過檢查：${it.message()}")
            return
        }
        line("傳到手錶…")
        val statuses = Channel<WearInstallStatus>(Channel.UNLIMITED)
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == UpdateContract.WEAR_STATUS_PATH) WearInstallStatus.decode(event.data)?.let { statuses.trySend(it) }
        }
        val messages = Wearable.getMessageClient(context)
        messages.addListener(listener).await()
        try {
            val offer = WearApkOffer(release.versionCode, sha, file.length())
            val channels = Wearable.getChannelClient(context)
            val channel = channels.openChannel(node.id, offer.path()).await()
            Log.i(TAG, "update: channel open to ${node.displayName} nearby=${node.isNearby}")
            try {
                withContext(Dispatchers.IO) {
                    channels.getOutputStream(channel).await().use { out ->
                        file.inputStream().use { input ->
                            val started = System.currentTimeMillis()
                            val buf = ByteArray(64 * 1024)
                            var sent = 0L
                            var nextLog = LOG_EVERY_BYTES
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                sent += n
                                if (sent >= nextLog) {
                                    nextLog += LOG_EVERY_BYTES
                                    val secs = (System.currentTimeMillis() - started) / 1000.0
                                    Log.i(TAG, "update: to watch $sent/${file.length()} bytes in ${"%.0f".format(secs)} s")
                                    line("傳到手錶… ${sent * 100 / file.length()}%")
                                }
                            }
                        }
                    }
                }
            } finally {
                runCatching { channels.close(channel).await() }
            }
            Log.i(TAG, "update: sent ${file.length()} bytes to watch ${node.displayName}")
            line("已傳到手錶，等手錶安裝…")
            val final = withTimeoutOrNull(WATCH_TIMEOUT_MILLIS) {
                var last: WearInstallStatus
                do {
                    last = statuses.receive()
                    Log.i(TAG, "update: watch says ${last.stage} ${last.detail}")
                    if (last.stage == Stage.RECEIVED) line("手錶收到了，檢查中…")
                    if (last.stage == Stage.INSTALLING) line("手錶安裝中…")
                } while (last.stage == Stage.RECEIVED || last.stage == Stage.INSTALLING)
                last
            }
            when (final?.stage) {
                null -> line("手錶沒有回應；過幾分鐘看手錶版本有沒有變，沒變再按一次更新")
                Stage.INSTALLED -> line("手錶已更新到 ${final.detail.ifBlank { release.versionName }}")
                Stage.NOT_ALLOWED -> line("手錶還沒允許這個 App 安裝更新，要用電腦開一次（步驟在下面）", UserStep.WATCH_ADB_ONCE)
                Stage.NEEDS_CONFIRM -> line("請打開手錶上的持股看板，再按一次更新", UserStep.OPEN_WATCH_APP)
                Stage.FAILED -> line("手錶更新失敗：${final.detail}")
                Stage.RECEIVED, Stage.INSTALLING -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update: watch transfer failed", e)
            line("傳到手錶失敗（${e.javaClass.simpleName}），再按一次更新")
        } finally {
            messages.removeListener(listener)
        }
    }

    private suspend fun updatePhone(release: Release) {
        fun line(text: String, step: UserStep? = null, running: Boolean = false) = _progress.update {
            it.copy(phoneLine = text, running = running, steps = if (step == null) it.steps else it.steps + step)
        }
        line("下載手機版 ${release.versionName}…", running = true)
        val (file, sha) = try {
            download(release.phone)
        } catch (e: IOException) {
            line("手機版下載失敗：${e.message}")
            return
        }
        verifyDownloaded(context, file, sha, release.phone, release.versionCode, phone.versionCode.toLong())?.let {
            line("手機版沒通過檢查：${it.message()}")
            return
        }
        Log.i(TAG, "update: phone apk verified ${release.versionCode} sha256=$sha")
        if (!context.packageManager.canRequestPackageInstalls()) {
            Log.i(TAG, "update: waiting for the user to allow installs from this app")
            line("已下載並檢查過。要先允許「持股看板」安裝應用程式，回來再按一次更新", UserStep.ALLOW_UNKNOWN_SOURCES)
            return
        }
        line("安裝中…", running = true)
        try {
            SelfInstaller.commit(context, file, PhoneInstallResultReceiver::class.java) {
                putExtra(PhoneInstallResultReceiver.EXTRA_VERSION_NAME, release.versionName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "update: commit failed", e)
            line("無法開始安裝：${e.message}")
        }
    }

    /** 安裝結果的廣播回報到這裡。 */
    fun onPhoneInstall(text: String, step: UserStep? = null) = _progress.update {
        it.copy(phoneLine = text, running = false, steps = if (step == null) it.steps else it.steps + step)
    }

    /** 同一個雜湊已經下載並寫完就不重下（使用者去允許安裝、回來再按一次的情況）。 */
    private suspend fun download(asset: ApkAsset): Pair<File, String> = withContext(Dispatchers.IO) {
        val file = File(cacheDir(), "${asset.sha256}.apk")
        if (file.isFile && file.length() == asset.size && sha256Of(file) == asset.sha256) return@withContext file to asset.sha256
        val (sha, _) = httpGet(asset.url).use { copyHashing(it, file, asset.size) }
        Log.i(TAG, "update: downloaded ${file.length()} bytes from ${asset.url}")
        file to sha
    }

    private fun cleanCache(keep: Release) {
        val wanted = setOf("${keep.phone.sha256}.apk", "${keep.wear.sha256}.apk")
        cacheDir().listFiles()?.filter { it.name !in wanted }?.forEach { it.delete() }
    }

    private fun cacheDir(): File = File(context.cacheDir, "update").apply { mkdirs() }

    private suspend fun readWatchVersion(): VersionStamp? = try {
        val local = Wearable.getNodeClient(context).localNode.await().id
        val buffer = Wearable.getDataClient(context).getDataItems(wearUri("*", UpdateContract.WEAR_INSTALLED_PATH), DataClient.FILTER_LITERAL).await()
        try {
            buffer.filter { it.uri.host != local }.mapNotNull { it.data?.let(VersionStamp::decode) }.maxByOrNull { it.versionCode }
        } finally {
            buffer.release()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun watchVersion(): Flow<VersionStamp?> = callbackFlow {
        val client = Wearable.getDataClient(context)
        val listener = DataClient.OnDataChangedListener { launch { send(readWatchVersion()) } }
        client.addListener(listener).await()
        send(readWatchVersion())
        awaitClose { client.removeListener(listener) }
    }

    private class HttpStatusException(val status: Int) : IOException("HTTP $status")

    private fun httpGet(url: String): InputStream {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MILLIS
        conn.readTimeout = TIMEOUT_MILLIS
        conn.setRequestProperty("User-Agent", "holdings-app")
        val status = conn.responseCode
        if (status !in 200..299) {
            conn.disconnect()
            throw HttpStatusException(status)
        }
        return conn.inputStream
    }

    companion object {
        const val DEBUG_PREFS = "update-debug"
        const val KEY_URL = "manifestUrl"
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val TIMEOUT_MILLIS = 30_000
        private const val WATCH_TIMEOUT_MILLIS = 3 * 60_000L
        private const val LOG_EVERY_BYTES = 2L * 1024 * 1024

        fun wearUri(host: String, path: String): Uri =
            Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).authority(host).path(path).build()
    }
}

/** 壞檔當成從沒查過：最多多通知一次。 */
internal object UpdateStateSerializer : Serializer<UpdateState> {
    override val defaultValue: UpdateState = UpdateState.EMPTY
    override suspend fun readFrom(input: InputStream): UpdateState = UpdateStateCodec.decode(input.readBytes()) ?: UpdateState.EMPTY
    override suspend fun writeTo(t: UpdateState, output: OutputStream) = output.write(UpdateStateCodec.encode(t))
}
