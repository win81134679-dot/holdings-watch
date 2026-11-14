package tw.personal.holdings.apk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import tw.personal.holdings.core.ApkExpectation
import tw.personal.holdings.core.ApkFacts
import tw.personal.holdings.core.ApkProblem
import tw.personal.holdings.core.ApkAsset
import tw.personal.holdings.core.installFailureText
import tw.personal.holdings.core.verifyApk
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** 手機與手錶共用：這支 App 自己的版本與簽章、驗一支下載來的 APK、用 PackageInstaller 安裝自己。 */
object ThisApp {
    fun versionCode(context: Context): Long = info(context, 0).longVersionCode

    fun versionName(context: Context): String = info(context, 0).versionName.orEmpty()

    fun signers(context: Context): Set<String> = signersOf(info(context, PackageManager.GET_SIGNING_CERTIFICATES))

    @Suppress("DEPRECATION") // PackageInfoFlags needs API 33; the phone still supports 31.
    private fun info(context: Context, flags: Int): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, flags)
}

private fun signersOf(info: PackageInfo?): Set<String> =
    info?.signingInfo?.apkContentsSigners.orEmpty().map(::sha256Hex).toSet()

private fun sha256Hex(sig: Signature): String = hex(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()))

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

/**
 * 邊寫邊算 SHA-256。先寫到 .part，讀完才改名成 [target]：中途斷掉留下的只會是 .part，下次重下。
 * 超過 [maxBytes] 就停，不讓錯的來源塞爆儲存空間。回傳 (雜湊, 位元組數)。
 */
fun copyHashing(input: InputStream, target: File, maxBytes: Long): Pair<String, Long> {
    val part = File(target.parentFile, "${target.name}.part")
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    part.outputStream().use { out ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("檔案比預期大（超過 $maxBytes 位元組）")
            digest.update(buf, 0, n)
            out.write(buf, 0, n)
        }
        out.fd.sync()
    }
    if (target.exists()) target.delete()
    if (!part.renameTo(target)) throw IOException("無法改名 ${part.name}")
    return hex(digest.digest()) to total
}

fun sha256Of(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
    }
    hex(digest.digest())
}

/** 比對檔案位元組與系統讀出的套件資訊。null＝可以安裝。 */
fun verifyDownloaded(
    context: Context,
    file: File,
    sha256: String,
    asset: ApkAsset,
    versionCode: Int,
    installedCode: Long,
): ApkProblem? {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
    val facts = ApkFacts(
        sha256 = sha256,
        size = file.length(),
        packageName = info?.packageName,
        versionCode = info?.longVersionCode ?: -1,
        signers = signersOf(info),
    )
    val expect = ApkExpectation(
        packageName = context.packageName,
        versionCode = versionCode,
        installedCode = installedCode,
        signers = ThisApp.signers(context),
        asset = asset,
    )
    return verifyApk(facts, expect)
}

sealed interface InstallStatus {
    data object Success : InstallStatus

    /** 系統要使用者按確認；confirm 是系統給的畫面，要 startActivity 它。 */
    data class NeedsUser(val confirm: Intent) : InstallStatus
    data class Failed(val text: String) : InstallStatus

    /** 被新的一次取代掉的 session 送回來的結果，不用理。 */
    data object Stale : InstallStatus
}

object SelfInstaller {
    /**
     * 開一個安裝自己的 session 並送出；結果由系統送到 [statusReceiver] 這個 BroadcastReceiver，用 [parse] 讀。
     * 先放掉之前沒完成的 session（上次當在半路），連按兩次也只會有一個在跑。
     */
    fun commit(context: Context, apk: File, statusReceiver: Class<*>, extras: Intent.() -> Unit = {}): Int {
        val installer = context.packageManager.packageInstaller
        for (old in installer.mySessions) {
            runCatching { installer.abandonSession(old.sessionId) }
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SESSION, sessionId).commit()
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // 系統要往 Intent 裡填狀態，所以必須是可變的；目標是明確的元件，不會被別的 App 攔走。
            val status = Intent(context, statusReceiver).apply(extras)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                status,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(pending.intentSender)
        }
        return sessionId
    }

    /**
     * 上面放掉的舊 session 也會送一個「已取消」回來；只認最後一次 commit 的那個，舊的回 [InstallStatus.Stale]。
     * 記在 SharedPreferences：更新自己成功時結果送到新行程，記憶體裡的狀態已經不在了。
     */
    fun parse(context: Context, intent: Intent): InstallStatus {
        val current = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SESSION, -1)
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != current) return InstallStatus.Stale
        return parseStatus(intent)
    }

    private const val PREFS = "self-installer"
    private const val KEY_SESSION = "session"

    private fun parseStatus(intent: Intent): InstallStatus =
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> InstallStatus.Success
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) InstallStatus.Failed("系統要求確認，但沒有給確認畫面") else InstallStatus.NeedsUser(confirm)
            }
            else -> InstallStatus.Failed(installFailureText(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
}
