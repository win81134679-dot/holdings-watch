package tw.personal.holdings.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Instant

/** 一個 Release 裡的一支 APK。url 已經是絕對網址。 */
data class ApkAsset(val url: String, val sha256: String, val size: Long)

/** 手機與手錶同一個版本號一起發，一個 Release 帶兩支 APK。 */
data class Release(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val phone: ApkAsset,
    val wear: ApkAsset,
)

sealed interface ManifestResult {
    data class Ok(val release: Release) : ManifestResult
    data class Invalid(val detail: String) : ManifestResult
}

/** 發版時附在 Release 上的 update.json（tools/release.py 產生）。App 只信這份，不打 GitHub API。 */
object UpdateManifest {
    /** 清單格式不相容改動才加 1；舊 App 看到新格式就請使用者手動下載。 */
    const val FORMAT = 1

    private val json = Json { ignoreUnknownKeys = true }
    private val SHA256 = Regex("[0-9a-f]{64}")

    @Serializable
    private class AssetDto(val url: String, val sha256: String, val size: Long)

    @Serializable
    private class ManifestDto(
        val format: Int,
        val versionCode: Int,
        val versionName: String,
        val notes: String = "",
        val phone: AssetDto,
        val wear: AssetDto,
    )

    /** APK 網址可以是相對的，對著清單網址補全；這樣同一份清單放 GitHub 或本機測試伺服器都能用。 */
    fun parse(bytes: ByteArray, manifestUrl: String): ManifestResult = try {
        val dto = json.decodeFromString(ManifestDto.serializer(), bytes.decodeToString())
        when {
            dto.format > FORMAT -> ManifestResult.Invalid("更新清單格式 ${dto.format} 比這支 App 新，請到 GitHub 手動下載新版")
            dto.versionCode <= 0 -> ManifestResult.Invalid("版本號 ${dto.versionCode}")
            dto.versionName.isBlank() -> ManifestResult.Invalid("沒有版本名稱")
            else -> ManifestResult.Ok(
                Release(
                    versionCode = dto.versionCode,
                    versionName = dto.versionName,
                    notes = dto.notes,
                    phone = asset(dto.phone, manifestUrl),
                    wear = asset(dto.wear, manifestUrl),
                ),
            )
        }
    } catch (e: Exception) {
        ManifestResult.Invalid("更新清單看不懂：${e.message?.take(120)}")
    }

    private fun asset(dto: AssetDto, base: String): ApkAsset {
        val hash = dto.sha256.lowercase()
        require(SHA256.matches(hash)) { "sha256 不是 64 位十六進位" }
        require(dto.size > 0) { "大小 ${dto.size}" }
        val url = URI(base).resolve(dto.url)
        require(url.scheme == "https" || url.scheme == "http") { "網址 ${dto.url}" }
        return ApkAsset(url.toString(), hash, dto.size)
    }
}

/**
 * 手機記住的更新狀態。只由檢查更新那一步寫。
 * notifiedCode：已經發過通知的最高版本號，同一版不再通知。
 */
data class UpdateState(
    val latest: Release?,
    val notifiedCode: Int,
    val checkedAt: Instant?,
    val problem: String?,
) {
    companion object {
        val EMPTY = UpdateState(latest = null, notifiedCode = 0, checkedAt = null, problem = null)
    }
}

sealed interface CheckResult {
    data class Found(val release: Release) : CheckResult
    data class Failed(val problem: String) : CheckResult
}

data class CheckOutcome(val state: UpdateState, val notify: Release?)

/** 查不到時保留上次知道的新版，橫幅不會因為一次斷線消失。 */
fun UpdateState.afterCheck(result: CheckResult, installedCode: Int, now: Instant): CheckOutcome = when (result) {
    is CheckResult.Failed -> CheckOutcome(copy(checkedAt = now, problem = result.problem), notify = null)
    is CheckResult.Found -> {
        val code = result.release.versionCode
        val notify = code > installedCode && code > notifiedCode
        CheckOutcome(
            UpdateState(
                latest = result.release,
                notifiedCode = if (notify) code else notifiedCode,
                checkedAt = now,
                problem = null,
            ),
            notify = result.release.takeIf { notify },
        )
    }
}

/** 首頁橫幅要不要出現、要更新哪一台。watchCode == null：手錶從沒回報過版本，不當成落後。 */
data class UpdateOffer(val release: Release, val phoneBehind: Boolean, val watchBehind: Boolean)

fun UpdateState.offer(phoneCode: Int, watchCode: Int?): UpdateOffer? {
    val release = latest ?: return null
    val phoneBehind = release.versionCode > phoneCode
    val watchBehind = watchCode != null && release.versionCode > watchCode
    return if (phoneBehind || watchBehind) UpdateOffer(release, phoneBehind, watchBehind) else null
}

object UpdateStateCodec {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class AssetDto(val url: String, val sha256: String, val size: Long)

    @Serializable
    private class ReleaseDto(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val phone: AssetDto,
        val wear: AssetDto,
    )

    @Serializable
    private class StateDto(
        val latest: ReleaseDto? = null,
        val notifiedCode: Int = 0,
        val checkedAt: String? = null,
        val problem: String? = null,
    )

    fun encode(s: UpdateState): ByteArray = json.encodeToString(
        StateDto.serializer(),
        StateDto(s.latest?.let(::toDto), s.notifiedCode, s.checkedAt?.toString(), s.problem),
    ).toByteArray()

    /** 壞檔回 null：呼叫端當成從沒查過，最多多通知一次。 */
    fun decode(bytes: ByteArray): UpdateState? = try {
        val d = json.decodeFromString(StateDto.serializer(), bytes.decodeToString())
        UpdateState(d.latest?.let(::fromDto), d.notifiedCode, d.checkedAt?.let(Instant::parse), d.problem)
    } catch (e: Exception) {
        null
    }

    private fun toDto(r: Release) = ReleaseDto(r.versionCode, r.versionName, r.notes, toDto(r.phone), toDto(r.wear))
    private fun toDto(a: ApkAsset) = AssetDto(a.url, a.sha256, a.size)
    private fun fromDto(r: ReleaseDto) = Release(r.versionCode, r.versionName, r.notes, fromDto(r.phone), fromDto(r.wear))
    private fun fromDto(a: AssetDto) = ApkAsset(a.url, a.sha256, a.size)
}

/** 下載到的檔案實際是什麼。packageName == null：系統讀不出這是 APK。signers 是簽章憑證的 SHA-256。 */
data class ApkFacts(
    val sha256: String,
    val size: Long,
    val packageName: String?,
    val versionCode: Long,
    val signers: Set<String>,
)

/** 這支 APK 應該是什麼：清單說的雜湊、大小、版本，加上裝置上現在這支 App 的套件名、版本、簽章。 */
data class ApkExpectation(
    val packageName: String,
    val versionCode: Int,
    val installedCode: Long,
    val signers: Set<String>,
    val asset: ApkAsset,
)

sealed interface ApkProblem {
    data class WrongSize(val actual: Long, val expected: Long) : ApkProblem
    data object WrongHash : ApkProblem
    data object NotAnApk : ApkProblem
    data class WrongPackage(val actual: String) : ApkProblem
    data class VersionMismatch(val apk: Long, val manifest: Int) : ApkProblem
    data class NotNewer(val apk: Long, val installed: Long) : ApkProblem
    data object WrongSigner : ApkProblem
}

/** 先比位元組（大小、雜湊），對了才相信檔案裡寫的套件名與版本。null＝可以安裝。 */
fun verifyApk(facts: ApkFacts, expect: ApkExpectation): ApkProblem? = when {
    facts.size != expect.asset.size -> ApkProblem.WrongSize(facts.size, expect.asset.size)
    facts.sha256 != expect.asset.sha256 -> ApkProblem.WrongHash
    facts.packageName == null -> ApkProblem.NotAnApk
    facts.packageName != expect.packageName -> ApkProblem.WrongPackage(facts.packageName)
    facts.versionCode != expect.versionCode.toLong() -> ApkProblem.VersionMismatch(facts.versionCode, expect.versionCode)
    facts.versionCode <= expect.installedCode -> ApkProblem.NotNewer(facts.versionCode, expect.installedCode)
    facts.signers.isEmpty() || facts.signers != expect.signers -> ApkProblem.WrongSigner
    else -> null
}

fun ApkProblem.message(): String = when (this) {
    is ApkProblem.WrongSize -> "下載不完整（收到 $actual 位元組，應為 $expected），請重新下載"
    ApkProblem.WrongHash -> "下載的檔案跟發佈的不一樣（雜湊不符），請重新下載"
    ApkProblem.NotAnApk -> "下載的檔案不是可安裝的 App，請重新下載"
    is ApkProblem.WrongPackage -> "下載到別的 App（$actual），已拒絕安裝"
    is ApkProblem.VersionMismatch -> "檔案版本 $apk 跟更新清單寫的 $manifest 不同，已拒絕安裝"
    is ApkProblem.NotNewer -> "已經是這一版或更新的版本（裝著 $installed），不用更新"
    ApkProblem.WrongSigner -> "簽章跟裝著的 App 不同，可能不是本人發佈的版本，已拒絕安裝"
}

/**
 * PackageInstaller 的失敗代碼翻成白話。手機與手錶都要用，:core 碰不到 Android，所以代碼照
 * android.content.pm.PackageInstaller.STATUS_FAILURE*（API 21 起固定的公開常數）抄一份。
 */
fun installFailureText(status: Int, systemMessage: String?): String {
    val text = when (status) {
        2 -> "系統擋下了安裝（可能是 Play Protect 或裝置管理政策）"
        3 -> "安裝被取消了，再按一次更新即可"
        4 -> "安裝檔損壞，請重新下載"
        5 -> "跟裝著的版本衝突（簽章不同或版本較舊）"
        6 -> "儲存空間不足，清出空間後再試"
        7 -> "這台裝置不相容這一版"
        8 -> "安裝逾時，請再試一次"
        else -> "安裝失敗（代碼 $status）"
    }
    return if (systemMessage.isNullOrBlank()) text else "$text。系統訊息：$systemMessage"
}

/** 手機 → 手錶更新用的 Data Layer 路徑。 */
object UpdateContract {
    /** 手機寫：目前知道的最新版（VersionStamp）。手錶比自己舊就顯示「請在手機按更新」。 */
    const val LATEST_PATH = "/holdings/update/latest"

    /** 手錶寫：自己裝著的版本（VersionStamp）。 */
    const val WEAR_INSTALLED_PATH = "/holdings/update/wear-installed"

    /** 手機開到手錶的通道，路徑後面帶版本、雜湊、大小（WearApkOffer）。 */
    const val WEAR_APK_PREFIX = "/holdings/update/wear-apk/"

    /** 手錶回手機的訊息（WearInstallStatus）。 */
    const val WEAR_STATUS_PATH = "/holdings/update/wear-status"
}

data class VersionStamp(val versionCode: Int, val versionName: String) {
    fun encode(): ByteArray = "$versionCode|$versionName".toByteArray()

    companion object {
        fun decode(bytes: ByteArray): VersionStamp? {
            val parts = bytes.decodeToString().split('|', limit = 2)
            val code = parts[0].toIntOrNull() ?: return null
            return VersionStamp(code, parts.getOrElse(1) { "" })
        }
    }
}

/** 通道路徑本身帶著驗證要用的資料，手錶不必另外等一則訊息。 */
data class WearApkOffer(val versionCode: Int, val sha256: String, val size: Long) {
    fun path(): String = "${UpdateContract.WEAR_APK_PREFIX}$versionCode/$sha256/$size"

    fun asset(): ApkAsset = ApkAsset(url = path(), sha256 = sha256, size = size)

    companion object {
        fun parse(path: String): WearApkOffer? {
            if (!path.startsWith(UpdateContract.WEAR_APK_PREFIX)) return null
            val parts = path.removePrefix(UpdateContract.WEAR_APK_PREFIX).split('/')
            if (parts.size != 3) return null
            val code = parts[0].toIntOrNull() ?: return null
            val size = parts[2].toLongOrNull() ?: return null
            return WearApkOffer(code, parts[1], size)
        }
    }
}

data class WearInstallStatus(val versionCode: Int, val stage: Stage, val detail: String) {
    /**
     * NOT_ALLOWED：手錶還沒允許這個 App 安裝 App。Wear OS 沒有「安裝不明應用程式」的設定頁（實測 2026-11-14），
     * 只能用 adb 開一次，手機畫面給步驟。
     */
    enum class Stage { RECEIVED, INSTALLING, NOT_ALLOWED, NEEDS_CONFIRM, INSTALLED, FAILED }

    fun encode(): ByteArray = "$versionCode|${stage.name}|$detail".toByteArray()

    companion object {
        fun decode(bytes: ByteArray): WearInstallStatus? {
            val parts = bytes.decodeToString().split('|', limit = 3)
            if (parts.size != 3) return null
            val code = parts[0].toIntOrNull() ?: return null
            val stage = Stage.entries.firstOrNull { it.name == parts[1] } ?: return null
            return WearInstallStatus(code, stage, parts[2])
        }
    }
}
