@file:OptIn(ExperimentalMaterial3Api::class)

package tw.personal.holdings.phone.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import tw.personal.holdings.core.ExitStatus
import tw.personal.holdings.core.Freshness
import tw.personal.holdings.core.Market
import tw.personal.holdings.core.PositionValue
import tw.personal.holdings.core.Side
import tw.personal.holdings.phone.Board
import tw.personal.holdings.phone.agoText
import tw.personal.holdings.phone.clockText
import tw.personal.holdings.phone.displaySign
import tw.personal.holdings.phone.failureText
import tw.personal.holdings.phone.label
import tw.personal.holdings.phone.moneyFraction
import tw.personal.holdings.phone.moneyText
import tw.personal.holdings.phone.pctText
import tw.personal.holdings.phone.priceText
import tw.personal.holdings.phone.quantityText
import tw.personal.holdings.phone.twdText
import tw.personal.holdings.phone.update.UpdateUi

@Composable
fun BoardScreen(
    board: Board?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    update: UpdateUi?,
    onUpdate: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("持股看板") },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !refreshing) { Text("刷新") }
                    TextButton(onClick = onSettings) { Text("設定") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(AddIcon, contentDescription = null) }, text = { Text("新增持股") })
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (board == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@PullToRefreshBox
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (update != null) item { UpdatePanel(update, onUpdate) }
                item { SetupCard() }
                if (board.holdings.isEmpty()) {
                    item { EmptyState(onAdd) }
                } else {
                    item { TotalsCard(board) }
                }
                for ((market, rows) in board.valuation.byMarket()) {
                    item(key = "header-$market") {
                        Text(
                            marketTitle(market),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(rows, key = { it.holding.id.value }) { row ->
                        PositionCard(row, board, onClick = { onEdit(row.holding.id.value) })
                    }
                }
            }
        }
    }
}

private fun marketTitle(m: Market): String = when (m) {
    Market.TW_EQUITY -> "台股"
    Market.US_EQUITY -> "美股"
    Market.TW_FUTURES -> "期貨"
    Market.CRYPTO -> "加密"
    Market.FX -> "匯率"
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("還沒有持股", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "打代號或名稱就找得到，例如 2330、台積電、AAPL、蘋果、台指期、比特幣。填好數量和成本，手錶幾秒內就會顯示。",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAdd) { Text("新增第一筆持股") }
    }
}

/** 總損益最大、今日損益次之；漲紅跌綠。 */
@Composable
private fun TotalsCard(board: Board) {
    val totals = board.valuation.totals
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("總損益（台幣）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                twdText(totals.pnl.value),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
                color = trendColor(displaySign(totals.pnl.value.amount, 0)),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("今日 ", style = MaterialTheme.typography.titleMedium)
                val daySign = displaySign(totals.dayPnl.value.amount, 0)
                Text(twdText(totals.dayPnl.value), style = MaterialTheme.typography.headlineSmall, color = trendColor(daySign))
                totals.dayPnlPct?.let {
                    Text("  ${pctText(it)}", style = MaterialTheme.typography.titleMedium, color = trendColor(displaySign(it, 2)))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (totals.pnl.missing > 0) {
                Text(
                    "有 ${totals.pnl.missing} 筆沒算進上面的數字（讀不到報價、還沒有匯率或已到期），看下方標示。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val oldest = board.valuation.oldestQuoteAt
            Text(
                if (oldest == null) "還沒有報價，正在取得…" else "報價時間 ${clockText(oldest)}（最舊一筆，${agoText(oldest, board.now)}）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PositionCard(row: PositionValue, board: Board, onClick: () -> Unit) {
    val h = row.holding
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val full = h.label()
                val code = h.name?.let { full.removeSuffix(" $it") } ?: full
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(h.name ?: code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (row is PositionValue.Priced) ExitBadge(row.exitStatus)
                }
                val side = if (h.side == Side.LONG) "" else "做空 "
                Text(
                    listOfNotNull(code.takeIf { h.name != null }, "$side${h.quantityText()}", "持有 ${row.daysHeld} 天").joinToString("・"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row is PositionValue.Priced) {
                    val sign = displaySign(row.dayChangePct, 2)
                    Row {
                        Text("現價 ${priceText(row.quote.last)}  ", style = MaterialTheme.typography.bodyMedium)
                        Text("今日 ${pctText(row.dayChangePct)}", style = MaterialTheme.typography.bodyMedium, color = trendColor(sign))
                        if (row.quote.freshness == Freshness.END_OF_DAY) {
                            Text("  收盤價", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            when (row) {
                is PositionValue.Priced -> Column(horizontalAlignment = Alignment.End) {
                    val sign = displaySign(row.pnl.amount, moneyFraction(row.pnl))
                    Text(moneyText(row.pnl), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = trendColor(sign))
                    Text(pctText(row.pnlPct), style = MaterialTheme.typography.bodyMedium, color = trendColor(displaySign(row.pnlPct, 2)))
                }
                is PositionValue.NoQuote -> Column(horizontalAlignment = Alignment.End) {
                    Text("讀不到報價", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    val reason = board.fetchStatus[h.asset.instrument]?.failure?.reason
                    Text(
                        reason?.let(::failureText) ?: "正在取得報價…",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is PositionValue.Expired -> Text(
                    "已過最後交易日\n請刪除或改月份",
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ExitBadge(status: ExitStatus) {
    val text = when (status) {
        ExitStatus.CLEAR -> return
        ExitStatus.STOP_HIT -> "碰停損"
        ExitStatus.TARGET_HIT -> "到目標"
    }
    val color = if (status == ExitStatus.STOP_HIT) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val onColor = if (status == ExitStatus.STOP_HIT) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Spacer(Modifier.width(8.dp))
    Surface(color = color, contentColor = onColor, shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/**
 * 提醒要準時響的兩個設定：通知權限、不受電池最佳化限制（自用，不做前景服務）。
 * 兩個都好了就不顯示；第一次開 App 直接跳通知權限的系統對話框。
 */
@Composable
private fun SetupCard() {
    val context = LocalContext.current
    var notificationsOk by remember { mutableStateOf(notificationsAllowed(context)) }
    var batteryOk by remember { mutableStateOf(ignoresBatteryOptimization(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsOk = notificationsAllowed(context)
        batteryOk = ignoresBatteryOptimization(context)
        onPauseOrDispose { }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsOk = granted
        if (!granted) context.startActivity(appNotificationSettings(context))
    }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!notificationsOk && !askedOnce && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askedOnce = true
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (notificationsOk && batteryOk) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("讓到價提醒準時震動", style = MaterialTheme.typography.titleMedium)
            if (!notificationsOk) {
                SetupRow("允許通知：提醒會從手機橋接到手錶震動。", "允許") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(appNotificationSettings(context))
                    }
                }
            }
            if (!notificationsOk && !batteryOk) HorizontalDivider()
            if (!batteryOk) {
                SetupRow("不受電池最佳化限制：背景每 15 分鐘的檢查比較不會被系統延後。", "設定") {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupRow(text: String, button: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onClick) { Text(button) }
    }
}

private fun notificationsAllowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun ignoresBatteryOptimization(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
