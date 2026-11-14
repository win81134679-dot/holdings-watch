package tw.personal.holdings.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import tw.personal.holdings.apk.InstallStatus
import tw.personal.holdings.apk.SelfInstaller
import tw.personal.holdings.apk.ThisApp
import tw.personal.holdings.apk.sha256Of
import tw.personal.holdings.apk.verifyDownloaded
import tw.personal.holdings.core.UpdateContract
import tw.personal.holdings.core.VersionStamp
import tw.personal.holdings.core.WearApkOffer
import tw.personal.holdings.core.WearInstallStatus
import tw.personal.holdings.core.WearInstallStatus.Stage
import tw.personal.holdings.core.message
import java.io.File

private const val TAG = "HoldingsWearUpdate"

/**
 * 手錶不連網，更新全靠手機：手機把手錶 APK 經 Data Layer 通道傳過來，這裡驗證後用 PackageInstaller 裝自己。
 * 每一步都回報給手機（WearInstallStatus），手機畫面才看得到手錶卡在哪。
 */
object WearUpdates {
    private val latestUri: Uri = Uri.Builder()
        .scheme(PutDataRequest.WEAR_URI_SCHEME)
        .authority("*")
        .path(UpdateContract.LATEST_PATH)
        .build()

    /** 把自己裝著的版本寫進 Data Layer，手機據此判斷手錶要不要更新。內容沒變時 Data Layer 不會重送。 */
    suspend fun reportInstalled(context: Context) {
        try {
            val stamp = VersionStamp(ThisApp.versionCode(context).toInt(), ThisApp.versionName(context))
            val request = PutDataRequest.create(UpdateContract.WEAR_INSTALLED_PATH).setData(stamp.encode()).setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.i(TAG, "reported installed ${stamp.versionCode} (${stamp.versionName})")
        } catch (e: Exception) {
            Log.w(TAG, "report installed failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 手機說有比自己新的版本時回那個版本，否則 null。App 開著時跟著 Data Layer 變動重讀。 */
    fun newerOnPhone(context: Context): Flow<VersionStamp?> = callbackFlow {
        val app = context.applicationContext
        val client = Wearable.getDataClient(app)
        val mine = ThisApp.versionCode(app)
        suspend fun read(): VersionStamp? = try {
            val buffer = client.getDataItems(latestUri, DataClient.FILTER_LITERAL).await()
            val stamps = try {
                buffer.mapNotNull { it.data?.let(VersionStamp::decode) }
            } finally {
                buffer.release()
            }
            stamps.maxByOrNull { it.versionCode }?.takeIf { it.versionCode > mine }
        } catch (e: Exception) {
            null
        }
        val listener = DataClient.OnDataChangedListener { launch { send(read()) } }
        client.addListener(listener).await()
        launch {
            while (true) {
                send(read())
                delay(60_000)
            }
        }
        awaitClose { client.removeListener(listener) }
    }

    suspend fun sendStatus(context: Context, status: WearInstallStatus, nodeId: String? = null) {
        Log.i(TAG, "status ${status.stage} ${status.versionCode} ${status.detail}")
        try {
            val messages = Wearable.getMessageClient(context)
            val targets = nodeId?.let(::listOf) ?: Wearable.getNodeClient(context).connectedNodes.await().map { it.id }
            for (target in targets) messages.sendMessage(target, UpdateContract.WEAR_STATUS_PATH, status.encode()).await()
        } catch (e: Exception) {
            Log.w(TAG, "send status failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun incomingFile(context: Context, offer: WearApkOffer): File =
        File(File(context.cacheDir, "update").apply { mkdirs() }, "wear-${offer.versionCode}.apk")

    /**
     * 讓 Google Play 服務自己把通道內容寫進檔案，這裡馬上返回。傳 30 MB 要幾分鐘（實測約 150 KB/s），
     * 如果在這裡讀串流，App 行程在背景會被系統凍結，Play 服務一呼叫就把它殺掉（實測 "Sync transaction while frozen"）。
     */
    internal suspend fun startReceiving(context: Context, channel: ChannelClient.Channel, offer: WearApkOffer) {
        val file = incomingFile(context, offer)
        file.delete()
        Wearable.getChannelClient(context).receiveFile(channel, Uri.fromFile(file), false).await()
        Log.i(TAG, "receiving into ${file.name}")
    }

    /** 通道的輸入端關了：正常關閉才算收完，接著驗證、安裝。 */
    internal suspend fun finishReceiving(context: Context, channel: ChannelClient.Channel, offer: WearApkOffer, closeReason: Int) {
        val phone = channel.nodeId
        val file = incomingFile(context, offer)
        if (closeReason != ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL || !file.isFile) {
            sendStatus(context, WearInstallStatus(offer.versionCode, Stage.FAILED, "傳輸中斷（代碼 $closeReason），再按一次更新"), phone)
            return
        }
        val sha = sha256Of(file)
        Log.i(TAG, "received ${file.length()} bytes sha256=$sha")
        sendStatus(context, WearInstallStatus(offer.versionCode, Stage.RECEIVED, "${file.length()}"), phone)
        verifyAndInstall(context, file, sha, offer, phone)
    }

    internal suspend fun verifyAndInstall(context: Context, file: File, sha: String, offer: WearApkOffer, phone: String?) {
        val installed = ThisApp.versionCode(context)
        val problem = verifyDownloaded(context, file, sha, offer.asset(), offer.versionCode, installed)
        if (problem != null) {
            file.delete()
            sendStatus(context, WearInstallStatus(offer.versionCode, Stage.FAILED, problem.message()), phone)
            return
        }
        // 沒允許時系統的確認畫面只會說「禁止安裝不明應用程式」，而手錶上找不到可以打開它的設定頁，送出去也沒用。
        if (!context.packageManager.canRequestPackageInstalls()) {
            sendStatus(context, WearInstallStatus(offer.versionCode, Stage.NOT_ALLOWED, ""), phone)
            return
        }
        Log.i(TAG, "verified; committing")
        sendStatus(context, WearInstallStatus(offer.versionCode, Stage.INSTALLING, ""), phone)
        try {
            SelfInstaller.commit(context, file, WearInstallResultReceiver::class.java) {
                putExtra(WearInstallResultReceiver.EXTRA_CODE, offer.versionCode)
            }
        } catch (e: Exception) {
            sendStatus(context, WearInstallStatus(offer.versionCode, Stage.FAILED, "手錶無法開始安裝：${e.message}"), phone)
        }
    }
}

class UpdateListenerService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        Log.i(TAG, "channel opened ${channel.path}")
        val offer = WearApkOffer.parse(channel.path) ?: return
        runBlocking {
            try {
                WearUpdates.startReceiving(applicationContext, channel, offer)
            } catch (e: Exception) {
                WearUpdates.sendStatus(
                    applicationContext,
                    WearInstallStatus(offer.versionCode, Stage.FAILED, "手錶無法接收檔案：${e.message}"),
                    channel.nodeId,
                )
            }
        }
    }

    override fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
        Log.i(TAG, "input closed ${channel.path} reason=$closeReason")
        val offer = WearApkOffer.parse(channel.path) ?: return
        runBlocking { WearUpdates.finishReceiving(applicationContext, channel, offer, closeReason) }
    }

    /** 手機公布新版時順便回報自己的版本，手機那邊的「手錶版本」才跟得上。 */
    override fun onDataChanged(events: DataEventBuffer) {
        runBlocking { WearUpdates.reportInstalled(applicationContext) }
    }
}

/** 系統回報安裝結果。更新自己成功時，舊行程已被換掉，這個廣播送到新版本的行程。 */
class WearInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (val s = SelfInstaller.parse(context, intent)) {
                    InstallStatus.Stale -> Log.i(TAG, "ignored result of a replaced session")
                    InstallStatus.Success -> {
                        WearUpdates.reportInstalled(context)
                        WearUpdates.sendStatus(context, WearInstallStatus(code, Stage.INSTALLED, ThisApp.versionName(context)))
                    }
                    is InstallStatus.NeedsUser -> {
                        Log.i(TAG, "pending user action: ${s.confirm}")
                        // 手錶 App 不在前景時系統會擋下這個畫面（背景啟動限制，實測），所以也請使用者打開手錶 App 再按一次。
                        WearUpdates.sendStatus(context, WearInstallStatus(code, Stage.NEEDS_CONFIRM, ""))
                        context.startActivity(s.confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    is InstallStatus.Failed -> WearUpdates.sendStatus(context, WearInstallStatus(code, Stage.FAILED, s.text))
                }
            } catch (e: Exception) {
                Log.e(TAG, "install result handling failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_CODE = "versionCode"
    }
}

/** adb 或手機代裝完新版後，系統送這個廣播給新版本：立刻回報版本給手機。 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WearUpdates.reportInstalled(context)
            } finally {
                pending.finish()
            }
        }
    }
}
