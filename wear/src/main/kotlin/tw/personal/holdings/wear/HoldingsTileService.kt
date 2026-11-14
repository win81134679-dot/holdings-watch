package tw.personal.holdings.wear

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.weight
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.layout.column
import androidx.wear.protolayout.layout.row
import androidx.wear.protolayout.layout.spacer
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.tile
import tw.personal.holdings.core.Valuation
import java.time.Instant
import kotlin.time.Duration

/** 只在手機推新快照時更新（freshness 0），不自己定時重拉。 */
class HoldingsTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val openApp = clickable(launchApp(this@HoldingsTileService), "open_app")
        val layout = when (val v = WatchBoard.read(this@HoldingsTileService)) {
            WatchView.Empty -> message("請在手機新增持股", openApp)
            WatchView.NeedsUpdate -> message("請更新手錶 App", openApp)
            is WatchView.Ready -> holdings(v.valuation, v.now, openApp)
        }
        return tile(timeline = Timeline.fromLayoutElement(layout), freshness = Duration.ZERO)
    }
}

private fun MaterialScope.message(body: String, onClick: Clickable): LayoutElement =
    primaryLayout(
        titleSlot = { text("持股看板".layoutString) },
        mainSlot = { text(body.layoutString, typography = Typography.BODY_LARGE, maxLines = 2) },
        onClick = onClick,
    )

private fun MaterialScope.holdings(
    valuation: Valuation,
    now: Instant,
    onClick: Clickable,
): LayoutElement {
    val total = valuation.totals.pnl
    val movers = valuation.topMovers(3)
    return primaryLayout(
        titleSlot = { text("資料 ${dataTime(valuation.oldestQuoteAt, now)}".layoutString) },
        mainSlot = {
            val lines = mutableListOf<LayoutElement>(
                text(
                    signedTwd(total.value.amount).layoutString,
                    typography = Typography.NUMERAL_SMALL,
                    color = toneColor(tone(total.value.amount)),
                ),
                text(
                    (if (total.missing > 0) "台幣總損益・不完整 ${total.missing} 筆" else "台幣總損益").layoutString,
                    typography = Typography.LABEL_SMALL,
                    color = colorScheme.onSurfaceVariant,
                ),
            )
            movers.forEach { p ->
                val dot = p.exitStatus.tone()
                lines += row(
                    text("●".layoutString, typography = Typography.BODY_SMALL, color = dot?.let(::toneColor) ?: TRANSPARENT),
                    text(p.label().layoutString, typography = Typography.BODY_SMALL, maxLines = 1),
                    spacer(width = weight(1f)),
                    text(
                        signedPct(p.dayChangePct, 2).layoutString,
                        typography = Typography.BODY_SMALL,
                        color = toneColor(tone(p.dayChangePct)),
                    ),
                    width = expand(),
                )
            }
            column(*lines.toTypedArray(), width = expand(), horizontalAlignment = LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        },
        onClick = onClick,
    )
}

private fun MaterialScope.toneColor(tone: Tone): LayoutColor = when (tone) {
    Tone.UP -> UP_ARGB.argb
    Tone.DOWN -> DOWN_ARGB.argb
    Tone.FLAT -> colorScheme.onSurface
}

private val TRANSPARENT: LayoutColor = 0x00000000.argb

private fun launchApp(context: Context): ActionBuilders.LaunchAction =
    ActionBuilders.LaunchAction.Builder()
        .setAndroidActivity(
            ActionBuilders.AndroidActivity.Builder()
                .setPackageName(context.packageName)
                .setClassName(MainActivity::class.java.name)
                .build(),
        )
        .build()
