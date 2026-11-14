package tw.personal.holdings.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tw.personal.holdings.apk.sha256Of
import tw.personal.holdings.core.WearApkOffer
import java.io.File

/**
 * 只在 debug 建置：不經手機，直接拿手錶 App 自己資料夾裡的 APK 走同一段驗證與安裝，單獨測手錶能不能更新自己。
 *   adb exec-in run-as tw.personal.holdings sh -c "cat > files/t.apk" < holdings-wear.apk
 *   adb shell am broadcast -a tw.personal.holdings.DEBUG_INSTALL_FILE -p tw.personal.holdings --es file t.apk --ei code 3
 */
class DebugInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(context.filesDir, intent.getStringExtra("file") ?: "t.apk")
                val sha = sha256Of(file)
                val offer = WearApkOffer(intent.getIntExtra("code", 0), sha, file.length())
                Log.i("DebugInstall", "installing ${file.name} as $offer")
                WearUpdates.verifyAndInstall(context.applicationContext, file, sha, offer, phone = null)
            } catch (e: Exception) {
                Log.e("DebugInstall", "failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
