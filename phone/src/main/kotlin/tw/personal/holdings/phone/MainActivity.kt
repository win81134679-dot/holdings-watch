package tw.personal.holdings.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.personal.holdings.core.DraftResult
import tw.personal.holdings.core.RefreshMode
import tw.personal.holdings.phone.symbols.RecentSymbols
import tw.personal.holdings.phone.symbols.SymbolIndex
import tw.personal.holdings.phone.symbols.SymbolRepository
import tw.personal.holdings.phone.ui.BoardScreen
import tw.personal.holdings.phone.ui.EditScreen
import tw.personal.holdings.phone.ui.HoldingsTheme
import tw.personal.holdings.phone.ui.SettingsScreen
import tw.personal.holdings.phone.update.Updates

class MainActivity : ComponentActivity() {
    /** 點通知進來時要打開的持股。 */
    private val openHolding = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openHolding.value = intent.getStringExtra(EXTRA_HOLDING_ID)
        val portfolio = Portfolio.get(this)
        val updates = AppGraph.get(this).updates
        lifecycleScope.launch { updates.publishLatest() }

        // 前景時每 2 分鐘排一輪；等上一輪跑完再數，下一輪才會落在 FOREGROUND 的 2 分鐘間隔之外。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    portfolio.refresh(RefreshMode.FOREGROUND)
                    delay(FOREGROUND_PERIOD_MILLIS)
                }
            }
        }

        setContent { HoldingsTheme { HoldingsApp(portfolio, updates, openHolding) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_HOLDING_ID)?.let { openHolding.value = it }
    }

    companion object {
        const val EXTRA_HOLDING_ID = "holdingId"
        private const val FOREGROUND_PERIOD_MILLIS = 2 * 60_000L
    }
}

/** 畫面切換：null＝首頁，""＝新增，其他＝編輯該 id；設定頁蓋在首頁上。 */
@Composable
private fun HoldingsApp(portfolio: Portfolio, updates: Updates, openHolding: MutableState<String?>) {
    val board by portfolio.board.collectAsStateWithLifecycle(initialValue = null)
    val update by updates.ui.collectAsStateWithLifecycle(initialValue = null)
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val recents = remember { RecentSymbols(context) }
    var symbols by remember { mutableStateOf<SymbolIndex?>(null) }

    LaunchedEffect(editing != null) {
        if (editing != null) symbols = SymbolRepository.get(context).index()
    }
    LaunchedEffect(openHolding.value) {
        openHolding.value?.let {
            editing = it
            openHolding.value = null
        }
    }
    BackHandler(enabled = editing != null) { editing = null }
    BackHandler(enabled = settings && editing == null) { settings = false }

    val target = editing
    if (target == null && settings) {
        SettingsScreen(
            update = update,
            onCheck = { updates.check(notify = false) },
            onUpdate = updates::requestApply,
            onBack = { settings = false },
        )
        return
    }
    if (target == null) {
        BoardScreen(
            board = board,
            refreshing = refreshing,
            onRefresh = {
                if (!refreshing) {
                    refreshing = true
                    scope.launch {
                        portfolio.refresh(RefreshMode.MANUAL)
                        refreshing = false
                    }
                }
            },
            onAdd = { editing = "" },
            onEdit = { editing = it },
            update = update,
            onUpdate = updates::requestApply,
            onSettings = { settings = true },
        )
        return
    }

    val current = board
    if (target.isNotEmpty() && current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val existing = current?.holdings?.firstOrNull { it.id.value == target }
    if (target.isNotEmpty() && existing == null) {
        LaunchedEffect(target) { editing = null }
        return
    }
    EditScreen(
        existing = existing,
        symbols = symbols,
        recentKeys = remember(target) { recents.keys() },
        holdings = current?.holdings.orEmpty(),
        onSave = { draft, key ->
            portfolio.save(existing?.id, draft).also { if (it is DraftResult.Ok) recents.record(key) }
        },
        onDelete = { existing?.let { portfolio.delete(it.id) } },
        onClose = { editing = null },
    )
}
