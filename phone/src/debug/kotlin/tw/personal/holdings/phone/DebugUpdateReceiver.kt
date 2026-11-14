package tw.personal.holdings.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tw.personal.holdings.phone.update.Updates

/**
 * 只在 debug 建置，測自動更新用（手機畫面不能點時，用廣播代替按鈕）：
 *   改清單網址  am broadcast -a tw.personal.holdings.DEBUG_UPDATE_URL -p tw.personal.holdings --es url http://127.0.0.1:8765/update.json
 *   改回 GitHub am broadcast -a tw.personal.holdings.DEBUG_UPDATE_URL -p tw.personal.holdings
 *   （換網址時一併忘掉記著的新版，橫幅不會指向舊來源的檔案）
 *   檢查更新    am broadcast -a tw.personal.holdings.DEBUG_UPDATE_CHECK -p tw.personal.holdings   （跟每天那次一樣會發通知）
 *   按「更新」  am broadcast -a tw.personal.holdings.DEBUG_UPDATE_APPLY -p tw.personal.holdings
 */
class DebugUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = AppGraph.get(context).updates
        when (intent.action) {
            ACTION_URL -> {
                val url = intent.getStringExtra("url")
                context.getSharedPreferences(Updates.DEBUG_PREFS, Context.MODE_PRIVATE).edit()
                    .apply { if (url.isNullOrBlank()) remove(Updates.KEY_URL) else putString(Updates.KEY_URL, url) }
                    .commit()
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        updates.forgetLatest()
                        Log.i(TAG, "debug: update manifest url = ${updates.manifestUrl()}; remembered release cleared")
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_CHECK -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val r = withTimeoutOrNull(9_000) { updates.check(notify = true) }
                        Log.i(TAG, "debug: update check ${r ?: "timed out"}")
                    } catch (e: Exception) {
                        Log.e(TAG, "debug: update check failed", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_APPLY -> {
                updates.requestApply()
                Log.i(TAG, "debug: update apply queued")
            }
        }
    }

    private companion object {
        const val ACTION_URL = "tw.personal.holdings.DEBUG_UPDATE_URL"
        const val ACTION_CHECK = "tw.personal.holdings.DEBUG_UPDATE_CHECK"
        const val ACTION_APPLY = "tw.personal.holdings.DEBUG_UPDATE_APPLY"
    }
}
