@file:OptIn(ExperimentalMaterial3Api::class)

package tw.personal.holdings.phone.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tw.personal.holdings.core.CheckResult
import tw.personal.holdings.core.VersionStamp
import tw.personal.holdings.phone.clockText
import tw.personal.holdings.phone.update.UpdateUi
import tw.personal.holdings.phone.update.UserStep

/** 首頁與設定頁共用：有新版、或按過更新還有進度要講時才出現。 */
@Composable
fun UpdatePanel(update: UpdateUi, onUpdate: () -> Unit) {
    val offer = update.offer
    val p = update.progress
    if (offer == null && p.phoneLine == null && p.watchLine == null) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (offer != null) {
                Text("有新版 ${offer.release.versionName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val which = when {
                    offer.phoneBehind && offer.watchBehind -> "手機與手錶都會更新"
                    offer.phoneBehind -> "手機會更新"
                    else -> "手機已是新版，手錶（${update.watch?.versionName}）會更新"
                }
                Text(which, style = MaterialTheme.typography.bodyMedium)
                if (offer.release.notes.isNotBlank()) Text(offer.release.notes, style = MaterialTheme.typography.bodyMedium)
            }
            p.watchLine?.let { Text("手錶：$it", style = MaterialTheme.typography.bodyMedium) }
            p.phoneLine?.let { Text("手機：$it", style = MaterialTheme.typography.bodyMedium) }
            for (step in UserStep.entries.filter { it in p.steps }) StepHelp(step)
            if (offer != null) {
                Button(onClick = onUpdate, enabled = !p.running) { Text(if (p.running) "更新中…" else "更新") }
            }
        }
    }
}

@Composable
private fun StepHelp(step: UserStep) {
    val context = LocalContext.current
    when (step) {
        UserStep.ALLOW_UNKNOWN_SOURCES -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "在系統設定把「持股看板」的「允許安裝應用程式」打開，回來再按一次更新（只要做一次）。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                )
            }) { Text("去允許") }
        }
        UserStep.CONFIRM_ON_PHONE -> Text(
            "系統會跳出安裝畫面，按「更新」。沒看到的話，點通知列的「點一下完成更新」。",
            style = MaterialTheme.typography.bodyMedium,
        )
        UserStep.OPEN_WATCH_APP -> Text(
            "手錶要你確認安裝：先在手錶上打開持股看板，再回手機按一次更新，然後在手錶上按「安裝」。",
            style = MaterialTheme.typography.bodyMedium,
        )
        UserStep.WATCH_ADB_ONCE -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "手錶上沒有「允許安裝」的設定，要用電腦開一次（之後手錶就會自動更新）：",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "1. 手錶：設定 → 開發人員選項 → 開啟「無線偵錯」，記下 IP 與連接埠\n" +
                    "2. 電腦：adb connect <手錶IP:連接埠>\n" +
                    "3. 電腦：adb -s <手錶IP:連接埠> shell appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow\n" +
                    "4. 回到這裡再按一次「更新」\n" +
                    "不想開權限的話，也可以每次都用電腦裝：從 GitHub Release 下載手錶版 APK，adb -s <手錶> install -r <檔名>",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
fun SettingsScreen(update: UpdateUi?, onCheck: suspend () -> CheckResult, onUpdate: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkLine by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("版本", style = MaterialTheme.typography.titleMedium)
            if (update != null) {
                Text("手機：${stampText(update.phone)}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "手錶：${update.watch?.let(::stampText) ?: "還沒回報（在手錶上打開持股看板一次）"}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                val state = update.state
                val checked = state.checkedAt?.let { "上次檢查 ${clockText(it)}" } ?: "還沒檢查過"
                Text(
                    listOfNotNull(checked, state.problem).joinToString("・"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "每天在 Wi-Fi 下自動檢查一次；有新版會通知，首頁也會出現「更新」。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = !checking,
                onClick = {
                    checking = true
                    checkLine = null
                    scope.launch {
                        val r = onCheck()
                        checkLine = when (r) {
                            is CheckResult.Failed -> "查不到：${r.problem}"
                            is CheckResult.Found ->
                                if (update != null && r.release.versionCode <= update.phone.versionCode &&
                                    (update.watch == null || r.release.versionCode <= update.watch.versionCode)
                                ) {
                                    "已是最新版"
                                } else {
                                    "有新版 ${r.release.versionName}"
                                }
                        }
                        checking = false
                    }
                },
            ) { Text(if (checking) "檢查中…" else "檢查更新") }
            checkLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            HorizontalDivider()
            if (update != null) UpdatePanel(update, onUpdate)
        }
    }
}

private fun stampText(v: VersionStamp): String = "${v.versionName}（版本號 ${v.versionCode}）"
