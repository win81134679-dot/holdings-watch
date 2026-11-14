package tw.personal.holdings.phone.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import kotlinx.coroutines.CancellationException
import tw.personal.holdings.apk.InstallStatus
import tw.personal.holdings.apk.SelfInstaller
import tw.personal.holdings.core.Release
import tw.personal.holdings.phone.AppGraph
import tw.personal.holdings.phone.MainActivity
import tw.personal.holdings.phone.R
import tw.personal.holdings.phone.TAG
import java.util.concurrent.TimeUnit

/**
 * 檢查：每天一次、只在不計量網路（Wi-Fi）。套用：使用者按「更新」才排，唯一名稱 KEEP，連按只跑一次。
 * 兩者跟報價刷新用不同的工作名，互不排隊。
 */
internal object UpdateWork {
    private const val DAILY = "update-check-daily"
    private const val APPLY = "update-apply"

    fun ensureDaily(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun applySoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<UpdateApplyWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(APPLY, ExistingWorkPolicy.KEEP, request)
    }
}

/** 失敗不重試：明天這個時候再查一次就好，不對 GitHub 連發。 */
internal class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try {
            AppGraph.get(applicationContext).updates.check(notify = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "update check crashed", e)
        }
        return Result.success()
    }
}

internal class UpdateApplyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val updates = AppGraph.get(applicationContext).updates
        try {
            updates.apply()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "update apply crashed", e)
            updates.onPhoneInstall("更新中斷（${e.javaClass.simpleName}），再按一次更新")
        }
        return Result.success()
    }
}

/** 系統回報手機安裝結果。更新自己成功時，這個廣播送到新版本的行程。 */
class PhoneInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = AppGraph.get(context).updates
        val name = intent.getStringExtra(EXTRA_VERSION_NAME).orEmpty()
        when (val s = SelfInstaller.parse(context, intent)) {
            InstallStatus.Stale -> Log.i(TAG, "update: ignored result of a replaced session")
            InstallStatus.Success -> {
                Log.i(TAG, "update: phone installed $name")
                UpdateNotifier.cancelConfirm(context)
                UpdateNotifier.installed(context, name)
                updates.onPhoneInstall("已更新到 $name")
            }
            is InstallStatus.NeedsUser -> {
                Log.i(TAG, "update: pending user action ${s.confirm}")
                updates.onPhoneInstall("請在跳出的系統畫面按「更新」", UserStep.CONFIRM_ON_PHONE)
                // App 在前景時直接跳確認畫面；不在前景時系統會擋下這一步，所以同時發一則通知讓使用者點。
                UpdateNotifier.confirm(context, s.confirm, name)
                context.startActivity(Intent(s.confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            is InstallStatus.Failed -> {
                Log.w(TAG, "update: phone install failed: ${s.text}")
                UpdateNotifier.cancelConfirm(context)
                updates.onPhoneInstall(s.text)
            }
        }
    }

    companion object {
        const val EXTRA_VERSION_NAME = "versionName"
    }
}

internal object UpdateNotifier {
    private const val CHANNEL_ID = "app-updates"
    private const val TAG_NEW = "update-new"
    private const val TAG_CONFIRM = "update-confirm"
    private const val TAG_DONE = "update-done"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "App 更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "有新版、需要你確認安裝、更新完成"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun newVersion(context: Context, release: Release) {
        val open = PendingIntent.getActivity(
            context,
            TAG_NEW.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            context,
            TAG_NEW,
            builder(context)
                .setContentTitle("持股看板有新版 ${release.versionName}")
                .setContentText(release.notes.ifBlank { "打開 App 按「更新」" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(release.notes.ifBlank { "打開 App 按「更新」" }))
                .setContentIntent(open),
        )
    }

    fun confirm(context: Context, confirm: Intent, versionName: String) {
        val pending = PendingIntent.getActivity(
            context,
            TAG_CONFIRM.hashCode(),
            Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            context,
            TAG_CONFIRM,
            builder(context)
                .setContentTitle("點一下完成更新 $versionName")
                .setContentText("系統要你確認一次安裝")
                .setContentIntent(pending),
        )
    }

    fun installed(context: Context, versionName: String) {
        NotificationManagerCompat.from(context).cancel(TAG_NEW, ID)
        post(context, TAG_DONE, builder(context).setContentTitle("持股看板已更新到 $versionName").setTimeoutAfter(60 * 60_000L))
    }

    fun cancelConfirm(context: Context) = NotificationManagerCompat.from(context).cancel(TAG_CONFIRM, ID)

    private fun builder(context: Context) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_alert)
        .setAutoCancel(true)

    private fun post(context: Context, tag: String, b: NotificationCompat.Builder) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "update: notifications disabled; skipped $tag")
            return
        }
        try {
            manager.notify(tag, ID, b.build())
            Log.i(TAG, "update: posted notification $tag")
        } catch (e: SecurityException) {
            Log.w(TAG, "update: notify refused for $tag", e)
        }
    }

    private const val ID = 2
}
