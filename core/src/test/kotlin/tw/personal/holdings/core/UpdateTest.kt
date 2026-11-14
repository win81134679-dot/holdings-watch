package tw.personal.holdings.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class UpdateTest {
    private val manifestUrl = "https://github.com/o/r/releases/latest/download/update.json"
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    private fun manifestJson(
        code: Int = 3,
        phoneUrl: String = "https://github.com/o/r/releases/download/v0.3/holdings-phone-0.3.apk",
        wearUrl: String = "holdings-wear-0.3.apk",
        format: Int = 1,
        phoneHash: String = hashA,
        phoneSize: Long = 1000,
    ) = """
        {"format":$format,"versionCode":$code,"versionName":"0.3","notes":"修正提醒",
         "phone":{"url":"$phoneUrl","sha256":"$phoneHash","size":$phoneSize},
         "wear":{"url":"$wearUrl","sha256":"$hashB","size":2000},
         "extra":"新版清單可以多欄位"}
    """.trimIndent().toByteArray()

    private fun release(code: Int) = Release(
        versionCode = code,
        versionName = "0.$code",
        notes = "",
        phone = ApkAsset("https://x/p.apk", hashA, 1000),
        wear = ApkAsset("https://x/w.apk", hashB, 2000),
    )

    private fun parsed(bytes: ByteArray): Release {
        val r = UpdateManifest.parse(bytes, manifestUrl)
        assertTrue("$r", r is ManifestResult.Ok)
        return (r as ManifestResult.Ok).release
    }

    @Test
    fun `清單解析出版本、說明與兩支 APK，相對網址對著清單網址補全`() {
        val r = parsed(manifestJson())
        assertEquals(3, r.versionCode)
        assertEquals("0.3", r.versionName)
        assertEquals("修正提醒", r.notes)
        assertEquals(ApkAsset("https://github.com/o/r/releases/download/v0.3/holdings-phone-0.3.apk", hashA, 1000), r.phone)
        assertEquals("https://github.com/o/r/releases/latest/download/holdings-wear-0.3.apk", r.wear.url)
    }

    @Test
    fun `本機測試伺服器的明文網址也能補全`() {
        val r = UpdateManifest.parse(manifestJson(wearUrl = "w.apk"), "http://127.0.0.1:8765/update.json")
        assertEquals("http://127.0.0.1:8765/w.apk", ((r as ManifestResult.Ok).release.wear.url))
    }

    @Test
    fun `大寫雜湊收成小寫`() {
        assertEquals(hashA, parsed(manifestJson(phoneHash = "A".repeat(64))).phone.sha256)
    }

    @Test
    fun `壞的清單回 Invalid，不丟例外`() {
        val bad = listOf(
            "不是 JSON".toByteArray(),
            manifestJson(code = 0),
            manifestJson(phoneHash = "abc"),
            manifestJson(phoneSize = 0),
            manifestJson(phoneUrl = "ftp://x/p.apk"),
            manifestJson(format = 2),
            """{"format":1,"versionCode":3}""".toByteArray(),
        )
        for (b in bad) {
            val r = UpdateManifest.parse(b, manifestUrl)
            assertTrue("${b.decodeToString()} -> $r", r is ManifestResult.Invalid)
        }
    }

    @Test
    fun `清單格式比 App 新時，原因叫使用者手動下載`() {
        val r = UpdateManifest.parse(manifestJson(format = 2), manifestUrl) as ManifestResult.Invalid
        assertTrue(r.detail, r.detail.contains("手動"))
    }

    private val now = Instant.parse("2026-11-14T01:00:00Z")

    @Test
    fun `比裝著的新才通知，同一版只通知一次`() {
        val first = UpdateState.EMPTY.afterCheck(CheckResult.Found(release(3)), installedCode = 2, now = now)
        assertEquals(release(3), first.notify)
        assertEquals(release(3), first.state.latest)
        assertEquals(now, first.state.checkedAt)

        val again = first.state.afterCheck(CheckResult.Found(release(3)), installedCode = 2, now = now.plusSeconds(86400))
        assertNull(again.notify)

        val newer = again.state.afterCheck(CheckResult.Found(release(4)), installedCode = 2, now = now)
        assertEquals(release(4), newer.notify)
    }

    @Test
    fun `一樣或更舊的版本不通知`() {
        assertNull(UpdateState.EMPTY.afterCheck(CheckResult.Found(release(2)), installedCode = 2, now = now).notify)
        assertNull(UpdateState.EMPTY.afterCheck(CheckResult.Found(release(1)), installedCode = 2, now = now).notify)
    }

    @Test
    fun `查不到時保留上次知道的新版，只記原因`() {
        val known = UpdateState.EMPTY.afterCheck(CheckResult.Found(release(3)), installedCode = 2, now = now).state
        val failed = known.afterCheck(CheckResult.Failed("連不上"), installedCode = 2, now = now.plusSeconds(60))
        assertNull(failed.notify)
        assertEquals(release(3), failed.state.latest)
        assertEquals("連不上", failed.state.problem)
        assertEquals(now.plusSeconds(60), failed.state.checkedAt)

        val recovered = failed.state.afterCheck(CheckResult.Found(release(3)), installedCode = 2, now = now)
        assertNull(recovered.state.problem)
        assertNull("同一版不因中間失敗再通知一次", recovered.notify)
    }

    @Test
    fun `橫幅：手機或手錶任一台落後就顯示，各自標出`() {
        val state = UpdateState.EMPTY.afterCheck(CheckResult.Found(release(3)), installedCode = 2, now = now).state

        val both = state.offer(phoneCode = 2, watchCode = 2)!!
        assertTrue(both.phoneBehind)
        assertTrue(both.watchBehind)

        val watchOnly = state.offer(phoneCode = 3, watchCode = 2)!!
        assertFalse(watchOnly.phoneBehind)
        assertTrue(watchOnly.watchBehind)

        val unknownWatch = state.offer(phoneCode = 2, watchCode = null)!!
        assertFalse("沒回報過版本的手錶不算落後", unknownWatch.watchBehind)

        assertNull(state.offer(phoneCode = 3, watchCode = 3))
        assertNull(UpdateState.EMPTY.offer(phoneCode = 2, watchCode = 1))
    }

    @Test
    fun `更新狀態存檔讀回來一樣，壞檔回 null`() {
        val state = UpdateState.EMPTY.afterCheck(CheckResult.Found(release(3)), installedCode = 2, now = now).state
            .afterCheck(CheckResult.Failed("HTTP 404"), installedCode = 2, now = now)
            .state
        assertEquals(state, UpdateStateCodec.decode(UpdateStateCodec.encode(state)))
        assertEquals(UpdateState.EMPTY, UpdateStateCodec.decode(UpdateStateCodec.encode(UpdateState.EMPTY)))
        assertNull(UpdateStateCodec.decode("{".toByteArray()))
    }

    private val mine = setOf("cert-1")
    private val asset = ApkAsset("https://x/p.apk", hashA, 1000)
    private val expect = ApkExpectation(
        packageName = "tw.personal.holdings",
        versionCode = 3,
        installedCode = 2,
        signers = mine,
        asset = asset,
    )
    private val good = ApkFacts(sha256 = hashA, size = 1000, packageName = "tw.personal.holdings", versionCode = 3, signers = mine)

    @Test
    fun `雜湊、大小、套件、版本、簽章都對才通過`() {
        assertNull(verifyApk(good, expect))
    }

    @Test
    fun `每一種不符都回對應的原因`() {
        assertEquals(ApkProblem.WrongSize(999, 1000), verifyApk(good.copy(size = 999), expect))
        assertEquals(ApkProblem.WrongHash, verifyApk(good.copy(sha256 = hashB), expect))
        assertEquals(ApkProblem.NotAnApk, verifyApk(good.copy(packageName = null), expect))
        assertEquals(ApkProblem.WrongPackage("com.evil"), verifyApk(good.copy(packageName = "com.evil"), expect))
        assertEquals(ApkProblem.VersionMismatch(4, 3), verifyApk(good.copy(versionCode = 4), expect))
        assertEquals(ApkProblem.NotNewer(2, 2), verifyApk(good.copy(versionCode = 2), expect.copy(versionCode = 2)))
        assertEquals(ApkProblem.WrongSigner, verifyApk(good.copy(signers = setOf("cert-2")), expect))
        assertEquals(ApkProblem.WrongSigner, verifyApk(good.copy(signers = emptySet()), expect))
    }

    @Test
    fun `大小或雜湊不對時不看後面的欄位`() {
        assertEquals(ApkProblem.WrongHash, verifyApk(good.copy(sha256 = hashB, packageName = null), expect))
    }

    @Test
    fun `原因用使用者看得懂的話`() {
        assertTrue(ApkProblem.WrongSigner.message().contains("簽章"))
        assertTrue(ApkProblem.WrongHash.message().contains("重新"))
        assertTrue(ApkProblem.NotNewer(2, 2).message().contains("已經"))
    }

    @Test
    fun `手錶 APK 的通道路徑帶得回版本、雜湊、大小`() {
        val offer = WearApkOffer(versionCode = 3, sha256 = hashB, size = 2000)
        assertEquals(offer, WearApkOffer.parse(offer.path()))
        assertTrue(offer.path().startsWith(UpdateContract.WEAR_APK_PREFIX))
        assertNull(WearApkOffer.parse("/holdings/snapshot"))
        assertNull(WearApkOffer.parse("${UpdateContract.WEAR_APK_PREFIX}x/y/z"))
    }

    @Test
    fun `手錶回報的安裝狀態與版本編碼讀得回來`() {
        for (s in listOf(
            WearInstallStatus(3, WearInstallStatus.Stage.RECEIVED, ""),
            WearInstallStatus(3, WearInstallStatus.Stage.FAILED, "簽章不同|有分隔符號"),
            WearInstallStatus(3, WearInstallStatus.Stage.INSTALLED, ""),
        )) {
            assertEquals(s, WearInstallStatus.decode(s.encode()))
        }
        assertNull(WearInstallStatus.decode("亂碼".toByteArray()))

        val v = VersionStamp(3, "0.3")
        assertEquals(v, VersionStamp.decode(v.encode()))
        assertNull(VersionStamp.decode("x".toByteArray()))
    }
}
