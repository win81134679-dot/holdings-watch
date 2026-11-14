package tw.personal.holdings.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tw.personal.holdings.core.Market
import tw.personal.holdings.core.PositionValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { WearUpdates.reportInstalled(applicationContext) }
        val startMarket = intent.getStringExtra(EXTRA_MARKET)?.let { name -> Market.entries.firstOrNull { it.name == name } }
        setContent {
            MaterialTheme {
                HoldingsApp(startMarket)
            }
        }
    }

    companion object {
        /** 開啟時直接跳到哪個市場的分頁（Market 的名稱），例如 `--es market US_EQUITY`。 */
        const val EXTRA_MARKET = "market"
    }
}

@Composable
private fun HoldingsApp(startMarket: Market?) {
    val context = LocalContext.current
    val flow = remember(context) { WatchBoard.observe(context) }
    val view by flow.collectAsStateWithLifecycle(initialValue = null)
    val newer by remember(context) { WearUpdates.newerOnPhone(context) }.collectAsStateWithLifecycle(initialValue = null)
    val notice = newer?.let { "有新版 ${it.versionName}\n請在手機按更新" }
    AppScaffold {
        when (val v = view) {
            null -> MessageScreen("讀取中…", notice)
            WatchView.Empty -> MessageScreen("請在手機新增持股", notice)
            WatchView.NeedsUpdate -> MessageScreen("請更新手錶 App", notice ?: "請在手機按更新")
            is WatchView.Ready -> MarketPager(v, startMarket, notice)
        }
    }
}

@Composable
private fun MessageScreen(message: String, notice: String?) {
    ScreenScaffold { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            if (notice != null) UpdateNotice(notice)
        }
    }
}

@Composable
private fun MarketPager(view: WatchView.Ready, startMarket: Market?, notice: String?) {
    val pages = remember(view) { view.valuation.byMarket().toList() }
    val pagerState = rememberPagerState(
        initialPage = pages.indexOfFirst { it.first == startMarket }.coerceAtLeast(0),
        pageCount = { pages.size },
    )
    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(state = pagerState) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                val (market, rows) = pages[page]
                MarketPage(view, market, rows, notice)
            }
        }
    }
}

@Composable
private fun MarketPage(view: WatchView.Ready, market: Market, rows: List<PositionValue>, notice: String?) {
    val columnState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (notice != null) item { UpdateNotice(notice) }
            item { Summary(view) }
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(market.title()) }
            }
            items(rows) { PositionRow(it) }
        }
    }
}

@Composable
private fun UpdateNotice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Summary(view: WatchView.Ready) {
    val total = view.valuation.totals.pnl
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            signedTwd(total.value.amount),
            style = MaterialTheme.typography.numeralSmall,
            color = toneColor(tone(total.value.amount)),
        )
        Text(
            "台幣總損益・資料 ${dataTime(view.valuation.oldestQuoteAt, view.now)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (total.missing > 0) {
            Text(
                "不完整 ${total.missing} 筆",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PositionRow(p: PositionValue) {
    val exitTone = (p as? PositionValue.Priced)?.exitStatus?.tone()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (exitTone != null) {
                Text("●", style = MaterialTheme.typography.labelSmall, color = toneColor(exitTone))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                p.label(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (p is PositionValue.Priced) {
                Text(priceText(p.quote.last.value), style = MaterialTheme.typography.bodyMedium)
            }
        }
        when (p) {
            is PositionValue.Priced -> Row {
                Text(
                    "今日 ${signedPct(p.dayChangePct, 2)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = toneColor(tone(p.dayChangePct)),
                    modifier = Modifier.weight(1f),
                )
                Text(signedMoney(p.pnl), style = MaterialTheme.typography.bodySmall, color = toneColor(tone(p.pnl.amount)))
            }
            is PositionValue.NoQuote -> Text(
                "讀不到報價",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is PositionValue.Expired -> Text(
                "已到期，請在手機結清",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        val exitLabel = (p as? PositionValue.Priced)?.exitStatus?.label()
        Text(
            "持有 ${p.daysHeld} 天" + (exitLabel?.let { "・$it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.UP -> Color(UP_ARGB)
    Tone.DOWN -> Color(DOWN_ARGB)
    Tone.FLAT -> MaterialTheme.colorScheme.onSurface
}

private fun Market.title(): String = when (this) {
    Market.TW_EQUITY -> "台股"
    Market.US_EQUITY -> "美股"
    Market.TW_FUTURES -> "期貨"
    Market.CRYPTO -> "加密"
    Market.FX -> "匯率"
}
