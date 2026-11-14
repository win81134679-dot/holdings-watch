package tw.personal.holdings.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import tw.personal.holdings.core.AlertFiring

/** 到價／轉倉通知。手機發，系統自動橋接到手錶並震動。 */
internal class AlertNotifier(private val context: Context) {

    /**
     * tag＝AlertKey.stableTag()，id 固定：同一條規則重發只會取代舊通知；
     * 加上 setOnlyAlertOnce，舊通知還在時取代也不再震動。
     */
    fun post(firing: AlertFiring): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "notifications disabled; skipped ${firing.key.stableTag()}")
            return false
        }
        val tag = firing.key.stableTag()
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_HOLDING_ID, firing.holding.id.value)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(alertText(firing, todayTaipei()))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        return try {
            manager.notify(tag, NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "notify refused for $tag", e)
            false
        }
    }

    companion object {
        const val CHANNEL_ID = "price-alerts"
        private const val NOTIFICATION_ID = 1

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "到價提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "碰到停損、目標價，以及期貨轉倉前提醒"
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
