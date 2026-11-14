package tw.personal.holdings.phone

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import tw.personal.holdings.core.RefreshMode
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Refresher 只在唯一工作名 [NOW] 下跑（APPEND_OR_REPLACE），背景、前景、手動、存檔後的刷新排在同一條隊伍：
 * 同一時間只有一個寫入者，不需要鎖；存檔後排進來的那一輪一定在進行中那輪之後，看得到剛存的持股。
 * 週期工作只負責把一輪排進隊伍，自己不跑 Refresher，否則兩個唯一名稱可以同時執行，就有兩個寫入者。
 *
 * 要求有網路：Android 15 起，行程離開前景後沒宣告網路條件的工作會被斷網（實測：廣播結束幾秒後
 * 系統 destroy 本 App 的 socket，之後每個請求都 UnknownHostException）。代價是手機完全沒網路時，
 * 改了持股要等網路回來那一輪才推到手錶。
 */
internal object RefreshScheduler {
    const val NOW = "refresh-now"
    const val PERIODIC = "refresh-periodic"
    const val KEY_MODE = "mode"

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<PeriodicKickWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun runSoon(context: Context, mode: RefreshMode): UUID {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInputData(workDataOf(KEY_MODE to mode.name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        return request.id
    }
}

/** 永遠回 success：抓取失敗已記在 RefreshState、由 FetchPolicy 退避；回 retry 會多一套退避，變成兩個真相來源。 */
internal class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val mode = inputData.getString(RefreshScheduler.KEY_MODE)
            ?.let { name -> RefreshMode.entries.firstOrNull { it.name == name } }
            ?: RefreshMode.BACKGROUND
        try {
            AppGraph.get(applicationContext).refresher.run(mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "round crashed", e)
        }
        return Result.success()
    }
}

internal class PeriodicKickWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        RefreshScheduler.runSoon(applicationContext, RefreshMode.BACKGROUND)
        return Result.success()
    }
}
