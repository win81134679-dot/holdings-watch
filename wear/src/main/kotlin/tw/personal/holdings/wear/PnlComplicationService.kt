package tw.personal.holdings.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.math.BigDecimal

/** 今日總損益 %。manifest 的 UPDATE_PERIOD_SECONDS = 0：只在手機推新快照時被叫來重拉。 */
class PnlComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val pct = (WatchBoard.read(this) as? WatchView.Ready)?.valuation?.totals?.dayPnlPct
        return build(request.complicationType, pct)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, BigDecimal("1.2"))

    private fun build(type: ComplicationType, pct: BigDecimal?): ComplicationData? {
        val text = pct?.let { signedPct(it, 1) } ?: "--"
        val description = PlainComplicationText.Builder("今日損益 $text").build()
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(PlainComplicationText.Builder(text).build(), description)
                    .setTitle(PlainComplicationText.Builder("今日").build())
                    .setTapAction(tap)
                    .build()
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(
                    value = (pct?.toFloat() ?: 0f).coerceIn(-RANGE_PCT, RANGE_PCT),
                    min = -RANGE_PCT,
                    max = RANGE_PCT,
                    contentDescription = description,
                )
                    .setText(PlainComplicationText.Builder(text).build())
                    .setTapAction(tap)
                    .build()
            else -> null
        }
    }

    private companion object {
        const val RANGE_PCT = 5f
    }
}
