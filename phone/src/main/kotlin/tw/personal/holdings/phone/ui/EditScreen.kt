@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package tw.personal.holdings.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.personal.holdings.core.ContractMonth
import tw.personal.holdings.core.DraftField
import tw.personal.holdings.core.DraftProblem
import tw.personal.holdings.core.DraftResult
import tw.personal.holdings.core.Holding
import tw.personal.holdings.core.HoldingDraft
import tw.personal.holdings.core.Side
import tw.personal.holdings.core.StockFutureSize
import tw.personal.holdings.core.TwBoard
import tw.personal.holdings.core.TwQtyUnit
import tw.personal.holdings.core.toDraft
import tw.personal.holdings.phone.EditForm
import tw.personal.holdings.phone.FutureKind
import tw.personal.holdings.phone.MarketTab
import tw.personal.holdings.phone.label
import tw.personal.holdings.phone.problemText
import tw.personal.holdings.phone.symbols.Pick
import tw.personal.holdings.phone.symbols.SymbolIndex
import tw.personal.holdings.phone.symbols.detail
import tw.personal.holdings.phone.symbols.title
import tw.personal.holdings.phone.toForm
import tw.personal.holdings.phone.todayTaipei
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val FormSaver = Saver<EditForm, String>(save = { EditForm.encode(it) }, restore = { EditForm.decode(it) })

/**
 * 新增（existing == null）：先搜尋商品，選定後只問數量與成本，其他收在「更多設定」。
 * 編輯：直接顯示表單，商品卡上的「更換」回到搜尋。驗證只在按「儲存」時由 core 的 HoldingDraft.parse 做。
 *
 * [onSave] 的第二個參數是商品鍵，存檔成功後記進「最近用過」。
 */
@Composable
fun EditScreen(
    existing: Holding?,
    symbols: SymbolIndex?,
    recentKeys: List<String>,
    holdings: List<Holding>,
    onSave: suspend (HoldingDraft, String) -> DraftResult,
    onDelete: suspend () -> Unit,
    onClose: () -> Unit,
) {
    val today = remember { todayTaipei() }
    var form by rememberSaveable(stateSaver = FormSaver) {
        mutableStateOf(existing?.toDraft()?.toForm() ?: EditForm.blank(today))
    }
    var searching by rememberSaveable { mutableStateOf(!form.picked) }
    var problems by remember { mutableStateOf<Map<DraftField, DraftProblem>>(emptyMap()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var focusQuantity by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun error(field: DraftField): String? = problems[field]?.let { problemText(field, it, form) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "新增持股" else "編輯 ${existing.label()}") },
                navigationIcon = {
                    TextButton(onClick = { if (searching && form.picked) searching = false else onClose() }) { Text("取消") }
                },
                actions = {
                    if (!searching) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    when (val r = onSave(form.toDraft(today), form.key())) {
                                        is DraftResult.Ok -> onClose()
                                        is DraftResult.Invalid -> problems = r.problems
                                    }
                                    busy = false
                                }
                            },
                        ) { Text("儲存", fontWeight = FontWeight.SemiBold) }
                    }
                },
            )
        },
    ) { padding ->
        if (searching) {
            SearchPane(
                symbols = symbols,
                recentKeys = recentKeys,
                holdings = holdings,
                modifier = Modifier.padding(padding),
            ) { pick ->
                form = form.pick(pick)
                problems = emptyMap()
                searching = false
                focusQuantity = true
            }
            return@Scaffold
        }
        FormPane(
            form = form,
            today = today,
            current = symbols?.resolve(form.key()),
            existing = existing,
            error = ::error,
            focusQuantity = focusQuantity,
            onFocused = { focusQuantity = false },
            modifier = Modifier.padding(padding),
            onChange = { form = it },
            onChangeInstrument = { searching = true },
            onDelete = { confirmDelete = true },
        )
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("刪除 ${existing.label()}？") },
            text = { Text("刪除後無法復原，手錶上也會一起移除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        onDelete()
                        onClose()
                    }
                }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SearchPane(
    symbols: SymbolIndex?,
    recentKeys: List<String>,
    holdings: List<Holding>,
    modifier: Modifier,
    onPick: (Pick) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // 一萬四千筆逐字比對，在手機上可能要十幾毫秒，不放在畫面執行緒上算。
    val searched by produceState("" to emptyList<Pick>(), symbols, query) {
        value = query to withContext(Dispatchers.Default) { symbols?.search(query).orEmpty() }
    }
    val results = searched.second
    val suggestions = remember(symbols, recentKeys, holdings) { symbols?.suggest(recentKeys, holdings) }

    Column(modifier.fillMaxSize().imePadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜尋商品") },
            placeholder = { Text("代號、中文或英文，例如 2330、台積電、AAPL、比特幣") },
            leadingIcon = { Icon(SearchIcon, contentDescription = null) },
            trailingIcon = if (query.isEmpty()) null else {
                { IconButton(onClick = { query = "" }) { Icon(ClearIcon, contentDescription = "清除") } }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let(onPick) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            when {
                symbols == null -> item { Hint("股票名單載入中…") }
                query.isBlank() -> {
                    val s = suggestions
                    if (s == null || (s.recent.isEmpty() && s.held.isEmpty())) {
                        item { Hint("台股、美股、ETF、期貨、加密貨幣都可以搜尋。打代號或名稱的開頭就會列出來，選一個再填數量和成本。") }
                    } else {
                        if (s.recent.isNotEmpty()) {
                            item { SectionTitle("最近用過") }
                            items(s.recent, key = { "r-" + it.key }) { PickRow(it, onPick) }
                        }
                        if (s.held.isNotEmpty()) {
                            item { SectionTitle("持有中") }
                            items(s.held, key = { "h-" + it.key }) { PickRow(it, onPick) }
                        }
                    }
                }
                results.isEmpty() && searched.first == query -> item { Hint("找不到「${query.trim()}」。換個寫法試試，例如只打代號或名稱的前兩個字。") }
                else -> items(results, key = { it.key + if (it.manual) "-manual" else "" }) { PickRow(it, onPick) }
            }
        }
    }
}

@Composable
private fun PickRow(pick: Pick, onPick: (Pick) -> Unit) {
    ListItem(
        headlineContent = {
            Text(if (pick.manual) "用「${pick.code}」新增" else pick.title(), fontWeight = FontWeight.Medium)
        },
        supportingContent = { Text(pick.detail()) },
        trailingContent = { MarketTag(pick.market) },
        modifier = Modifier.clickable { onPick(pick) },
    )
}

@Composable
private fun MarketTag(market: MarketTab) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(market.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun FormPane(
    form: EditForm,
    today: LocalDate,
    current: Pick?,
    existing: Holding?,
    error: (DraftField) -> String?,
    focusQuantity: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier,
    onChange: (EditForm) -> Unit,
    onChangeInstrument: () -> Unit,
    onDelete: () -> Unit,
) {
    val quantityFocus = remember { FocusRequester() }
    LaunchedEffect(focusQuantity) {
        if (focusQuantity) {
            quantityFocus.requestFocus()
            onFocused()
        }
    }
    val moreErrors = listOf(DraftField.STOP_LOSS, DraftField.TARGET, DraftField.ENTRY_DATE).any { error(it) != null }
    val hasMore = form.side == Side.SHORT || form.stopLoss.isNotBlank() || form.target.isNotBlank() || form.note.isNotBlank()
    var showMore by rememberSaveable { mutableStateOf(hasMore) }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InstrumentCard(form, current, error(DraftField.INSTRUMENT), onChangeInstrument)
        if (form.market == MarketTab.TW && (current == null || current.manual)) {
            Choices(listOf(TwBoard.TWSE to "上市", TwBoard.TPEX to "上櫃"), form.twBoard) { onChange(form.copy(twBoard = it)) }
        }
        if (form.market == MarketTab.FUTURES) FuturesFields(form, today, onChange)

        Field("名稱", form.name, null, hint = "選商品時自動帶入，可以改") { onChange(form.copy(name = it)) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(
                quantityLabel(form),
                form.quantity,
                error(DraftField.QUANTITY),
                number = true,
                modifier = Modifier.weight(1f).focusRequester(quantityFocus),
            ) { onChange(form.copy(quantity = it)) }
            if (form.market == MarketTab.TW) {
                Spacer(Modifier.width(12.dp))
                Choices(listOf(TwQtyUnit.LOT to "張", TwQtyUnit.SHARE to "股"), form.twUnit) { onChange(form.copy(twUnit = it)) }
            }
        }
        Field(costLabel(form), form.averageCost, error(DraftField.AVERAGE_COST), number = true, imeDone = true) {
            onChange(form.copy(averageCost = it))
        }
        form.preview(today)?.let { Preview(it) }

        HorizontalDivider()
        TextButton(onClick = { showMore = !showMore }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showMore || moreErrors) "收起更多設定" else "更多設定：停損、目標、進場日、方向、備註")
        }
        if (showMore || moreErrors) {
            Text("方向", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Choices(listOf(Side.LONG to "做多", Side.SHORT to "做空"), form.side) { onChange(form.copy(side = it)) }
            Field("停損價（選填）", form.stopLoss, error(DraftField.STOP_LOSS), number = true) { onChange(form.copy(stopLoss = it)) }
            Field("目標價（選填）", form.target, error(DraftField.TARGET), number = true) { onChange(form.copy(target = it)) }
            EntryDateField(LocalDate.parse(form.entryDate), today, error(DraftField.ENTRY_DATE)) {
                onChange(form.copy(entryDate = it.toString()))
            }
            Field("備註（選填）", form.note, null, singleLine = false) { onChange(form.copy(note = it)) }
        }

        if (existing != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text("刪除這筆持股", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun quantityLabel(form: EditForm): String = when (form.market) {
    MarketTab.TW -> "數量"
    MarketTab.US -> "股數（可小數）"
    MarketTab.FUTURES -> "口數"
    MarketTab.CRYPTO -> "數量（最多 8 位小數）"
}

private fun costLabel(form: EditForm): String = when (form.market) {
    MarketTab.TW -> "平均成本（每股，元）"
    MarketTab.US -> "平均成本（每股，美元）"
    MarketTab.FUTURES -> "平均成交點數"
    MarketTab.CRYPTO -> "平均成本（每顆，美元）"
}

@Composable
private fun InstrumentCard(form: EditForm, current: Pick?, error: String?, onChange: () -> Unit) {
    val title = current?.takeUnless { it.manual }?.title() ?: fallbackTitle(form)
    val detail = current?.detail() ?: fallbackDetail(form)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onChange) { Text("更換") }
        }
    }
    error?.let { ErrorText(it) }
}

private fun fallbackTitle(form: EditForm): String = when (form.market) {
    MarketTab.TW -> form.name.ifBlank { form.twCode }
    MarketTab.US -> form.name.ifBlank { form.usTicker }
    MarketTab.CRYPTO -> form.name.ifBlank { form.coin }
    MarketTab.FUTURES -> form.futureKind.index?.let { Pick.INDEX_FUTURE_NAMES.getValue(it).first() }
        ?: "${form.name.ifBlank { form.stockFutureUnderlying }} 個股期貨"
}

private fun fallbackDetail(form: EditForm): String = when (form.market) {
    MarketTab.TW -> "${form.twCode}・台股"
    MarketTab.US -> "${form.usTicker}・美股"
    MarketTab.CRYPTO -> "${form.coin}・加密貨幣（以美元計價）"
    MarketTab.FUTURES -> "期貨"
}

@Composable
private fun FuturesFields(form: EditForm, today: LocalDate, onChange: (EditForm) -> Unit) {
    if (form.futureKind == FutureKind.STOCK) {
        Field(
            "期交所商品代號",
            form.stockFutureCode,
            null,
            caps = true,
            hint = "例如 CDF（台積電）",
            supporting = "期交所網站查得到各檔個股期貨的代號；填錯的話首頁會顯示查不到。",
        ) { onChange(form.copy(stockFutureCode = it)) }
        Choices(listOf(StockFutureSize.STANDARD to "一般（2000 股）", StockFutureSize.SMALL to "小型（100 股）"), form.stockFutureSize) {
            onChange(form.copy(stockFutureSize = it))
        }
    }
    Text("合約月份", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val selected = form.selectedMonth(today)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in form.monthOptions(today)) {
            FilterChip(
                selected = m == selected,
                onClick = { onChange(form.copy(month = m.toString())) },
                label = { Text(monthChipText(m, today)) },
            )
        }
    }
    Text(
        "每點 ${form.pointValueTwd()} 元",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun monthChipText(m: ContractMonth, today: LocalDate): String {
    val last = m.lastTradingDay()
    val month = if (m.yearMonth.year == today.year) "${m.yearMonth.monthValue} 月" else "${m.yearMonth.year}/${m.yearMonth.monthValue}"
    return "$month・最後交易 ${last.monthValue}/${last.dayOfMonth}"
}

@Composable
private fun Preview(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

@Composable
private fun EntryDateField(value: LocalDate, today: LocalDate, error: String?, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("進場日 $value") }
        error?.let { ErrorText(it) }
    }
    if (!open) return
    val todayMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = value.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayMillis
        },
    )
    DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                open = false
            }) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
    ) { DatePicker(state) }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, text) in options) {
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) })
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: String?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    number: Boolean = false,
    caps: Boolean = false,
    singleLine: Boolean = true,
    imeDone: Boolean = false,
    hint: String? = null,
    supporting: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text,
            capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
            imeAction = if (!singleLine) ImeAction.Default else if (imeDone) ImeAction.Done else ImeAction.Next,
        ),
        modifier = modifier,
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
