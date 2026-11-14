# 候選 1：手機是唯一寫入者，手錶只讀同一份快照、用同一個估值函式

型別草圖在 `sketch\`（Kotlin，函式本體全是 `TODO("not implemented")`）。這份是理由文件與模組地圖。

## 問題

要做一個自用的持股看板：手機 App 編輯持股、抓報價；Pixel Watch 5 用 Tile、App、錶面小格顯示損益，碰到停損或目標價時震動。
形狀不明顯的地方有五個：

- 四個市場的數量單位、幣別、乘數都不同（台股張／股、美股小數股、期貨口×每點價值、加密 8 位小數），而且空單損益反向。
- 資料來源不可靠又不能高頻打：Yahoo 是非官方端點，期交所的取得方式還沒定（研究檔只有授權面），使用者習慣不要高頻輪詢。
- 有兩台裝置、三個顯示介面，數字必須一致；手錶網路耗電「Very high」，藍牙頻寬可能只有約 4 KB/s。
- 提醒不能重複響，背景工作會被系統殺掉、重跑。
- WorkManager 週期工作最短 15 分鐘。

打底時浮現的限制：

- 不架伺服器、不做帳號，資料只在手機與手錶。手機與手錶同 applicationId、同簽章，所以 Data Layer 可用。
- grounding 寫「自用（不上架、不收費）」，決策紀錄寫「上架、一次買斷」。兩者矛盾，本設計以 grounding 為準。
  設計本身不依賴這個答案；只有資料來源的授權會受影響，而資料來源已經藏在 `:quotes` 後面。
- Pixel Watch 上做 Tile，不做 Wear Widgets（官方遷移表）。
- 期貨規格（2026-11-14 查）：每點價值 TX 200、MTX 50、TMF 10；個股期貨每口 2,000 股，小型 100 股。最後交易日是交割月份第三個星期三。

## 用法（呼叫端視角）

**手機 UI** 只認得一個介面 `Portfolio`。它不知道有 DataStore、WorkManager、Data Layer、Yahoo。

```kotlin
val portfolio = Portfolio.get(context)

// 首頁
val board by portfolio.board.collectAsStateWithLifecycle(null)
board?.valuation?.byMarket()?.forEach { (market, rows) -> MarketSection(market, rows) }
board?.valuation?.totals?.pnl            // Partial<Twd>：台幣總損益，加上缺了幾筆
LifecycleResumeEffect(Unit) { portfolio.refresh(foreground = true); onPauseOrDispose {} }

// 編輯畫面：存檔就是驗證的地方
when (val r = portfolio.save(existing?.id, draft)) {
    is DraftResult.Ok      -> navigateBack()          // 幾秒內手錶也會更新
    is DraftResult.Invalid -> showErrors(r.problems)  // Map<DraftField, DraftProblem>
}

// 期貨：選商品＋月份，每點價值唯讀顯示
ContractMonth.listed(IndexFuture.TX, LocalDate.now(TAIPEI))
IndexFuture.TX.pointValueTwd  // 200
```

**手錶的三個介面**都只呼叫一個函式，再把結果轉成自己的畫面模型：

```kotlin
// Tile
when (val v = WatchBoard.read(context)) {
    WatchView.Empty       -> 提示版面("請在手機上新增持股")
    WatchView.NeedsUpdate -> 提示版面("請更新手錶 App")
    is WatchView.Ready    -> TileLayouts.holdings(TileModel.from(v.valuation), v.now)
}
// 錶面小格：v.valuation.totals.dayPnlPct
// 手錶 App：WatchBoard.observe(context) → v.valuation.byMarket() 每個市場一頁
```

**core 的純函式**是 JVM 測試的對象，也是手機與手錶共用的計算：

```kotlin
valuate(PortfolioSnapshot(holdings, quotes), now)              // → Valuation
dueForFetch(holdings, marketData, RefreshMode.BACKGROUND, now)  // → Set<Quotable>，要抓哪些
stepAlerts(holdings, quotes, alertStates, now)                  // → AlertStep(next, fire)
draft.parse(id, today)                                          // → DraftResult
```

## 形狀

### 資料結構（`sketch\core\`）

- **商品** `Instrument`（sealed）：`TwEquity(code, board)`、`UsEquity`、`Crypto`、`TwFuture(product, month)`。
  `Quotable` 是 `Instrument` 加上 `FxPair.USD_TWD`。匯率跟報價走同一條抓取與快取路徑，不另開一套。
  幣別、市場、每點價值、最後交易日都是**推導的屬性**，不是欄位（〈Model the Domain〉）。
- **期貨商品** `FutureProduct`：`IndexFuture` enum（TX／MTX／TMF，各自帶每點價值），以及 `StockFuture(taifexCode, underlying, size)`。
- **持有** `Asset`（sealed）：商品與數量綁成一組，數量型別跟著市場走，分別是 `Shares`、`FractionalShares`、`Contracts`、`CoinAmount`。
  台股拿不到口數、期貨拿不到小數，因為編譯器會擋（〈Type System Discipline〉）。
  每個 `Asset` 提供 `exposure`，代表價格動 1 元時損益動多少。估值因此只有一條公式，不必對每個市場各寫一條分支。
- **持股** `Holding`：id、asset、side、平均成本、停損、目標、進場日、備註。**沒有**幣別、乘數、結算日、提醒條件這些欄位，它們都從別的欄位推導。
- **金額**：`Money`（原幣，沒有加法）與 `Twd`（台幣，可以相加）。混幣相加寫不出來，總計也不需要執行期檢查。
  `Price` 是 > 0 的 `BigDecimal`，只能經 `of()`／`parse()` 建立，而這兩個函式只在邊界上用。
- **報價** `Quote(last, previousClose, asOf, freshness)`。`Freshness` 讓盤後資料不會看起來像即時資料。
- **報價快取** `MarketData = Map<Quotable, QuoteEntry>`，每格是最後報價、成功時間與失敗紀錄。**這格就是節流狀態**，下一輪抓不抓完全從它推導，不另外存排程時間。
- **快照** `PortfolioSnapshot(holdings, quotes)`：手機與手錶共同的估值輸入。
- **估值** `Valuation`：每筆是 `PositionValue(holding, daysHeld, priced: Priced?)`。
  缺報價時整組 `priced` 為 null，只有這一種缺值狀態。總計型別是 `Partial<Twd>`，記錄缺了幾筆。

### 主要存取模式走一遍

| 存取 | 怎麼滿足 | 需要額外索引嗎 |
|---|---|---|
| 首頁、Tile、App 列出全部持股與損益 | `valuate()` 掃一遍清單（幾十筆） | 不需要 |
| 依市場分頁 | `Valuation.byMarket()` 以 `instrument.market` 分組 | 不需要 |
| 今日漲跌最多 3 檔 | `topMovers(3)` 排序 | 不需要 |
| 報價查商品 | `Map<Quotable, Quote>` | 本身就是 map |
| 這一輪要抓什麼 | `dueForFetch` 讀快取的每一格 | 不需要，快取就是索引 |
| 某條提醒的狀態 | `Map<AlertKey, AlertState>` | 本身就是 map |
| 編輯一筆 | 依 `HoldingId` 取代清單中的元素 | 幾十筆，線性掃描 |
| 刪持股後清快取與提醒狀態 | 下一輪 `retainOnly`／`stepAlerts` 的對帳 | 不需要另外清 |

所有讀取都是「整份讀進記憶體」。這也是選 DataStore、不選 Room 的原因（見〈接受的取捨〉）。

### 模組地圖

```
:phone ──► :quotes ──► :core
   └─────────────────► :core
:wear  ──────────────► :core
```

| 模組 | 種類 | 公開介面 | 藏起來的東西 |
|---|---|---|---|
| `:core` | 純 Kotlin／JVM | 領域型別、`valuate`、`stepAlerts`、`dueForFetch`、`HoldingDraft.parse`、`PortfolioCodec`、`SyncContract` | 所有 DTO（`internal`）、交易時段表、退避規則、存檔格式 |
| `:quotes` | 純 Kotlin／JVM | `QuoteFetcher`（一個 fun interface）、`HttpGet`、`QuoteFetchers.default()` | Yahoo 網址與代號對照（`.TW`／`.TWO`／`-USD`／`TWD=X`）、JSON DTO、期交所端點、路由、節奏 |
| `:phone` | Android app | UI 只用 `Portfolio` | 兩個 DataStore、`Refresher`、WorkManager、通知、Data Layer 發布 |
| `:wear` | Wear OS app | 三個介面只用 `WatchBoard.read/observe` | Data Layer 讀取、解碼 |

`internal` 讓「內部可被匯入」這種錯誤直接編譯失敗：`:phone` 匯入不到 Yahoo 的 DTO，`:wear` 匯入不到存檔的 DTO。

### 報價抓取（`sketch\quotes\`）

- 對外只有 `QuoteFetcher.fetch(Set<Quotable>): Map<Quotable, FetchResult>`。它保證每個輸入都有結果，而且不丟例外。
  失敗是值（`NotFound`／`Throttled`／`Network`／`Unparseable`／`Unsupported`），不是例外。
- 路由寫在工廠裡，只寫一次：台灣期貨送期交所，其他送 Yahoo chart。
  換來源（期交所端點定案、Yahoo 失效）只改 `:quotes` 的 internal，呼叫端不動。
- Yahoo 依序打，每兩個請求間隔 500 ms，同一輪內不重試。重試交給 `FetchPolicy` 的退避，只保留一套退避。
- 期交所目前是空殼：形狀已經確定，端點留 TODO。它應該一輪只發一個請求，從整份行情裡篩出持有的合約。
- HTTP 用 `HttpURLConnection` 實作 `HttpGet`，不新增依賴。`:quotes` 用固定 JSON 就能做 JVM 測試。

### 儲存（手機）

| 檔 | 內容 | 唯一寫入者 |
|---|---|---|
| `holdings.json` | `List<Holding>`，使用者的正本 | 編輯畫面（`Portfolio.save`／`delete`） |
| `refresh-state.json` | `RefreshState(MarketData, alerts)` | `Refresher` |

兩個寫入者從不寫同一個檔，所以不需要鎖，只在讀取時合併（〈Separate Before Serializing Shared State〉）。
DataStore 寫入是寫暫存檔再改名，當機只會留下舊版或新版。持股檔壞掉時先備份壞檔再回空清單；刷新狀態檔壞掉直接丟掉。
格式由 `PortfolioCodec` 統一處理，一份 DTO 家族同時服務存檔與同步，解碼就是驗證邊界（〈Boundary Discipline〉）。

### 手機到手錶同步

- 用 Data Layer 的 **DataItem**，路徑 `/holdings/snapshot`，每次整份取代。沒有增量，手錶永遠只有一份完整快照。
  手錶離線時由系統保留，連上後自動送達。
- 封包內容是 `PortfolioSnapshot`（持股加報價，含匯率），**不是**算好的畫面。手錶用同一個 `valuate()` 算，所以四個畫面的數字一定一致。
- 封包**不放發布時間**。內容沒變時位元組相同，Data Layer 不會觸發變更，Tile 不會白白重畫，所以重送是冪等的（〈Make Operations Idempotent〉）。
- 封包有版本欄位：手機比手錶新、看不懂封包時，手錶顯示「請更新手錶 App」，不會崩潰。

### Tile、App、錶面小格的讀取路徑

```
介面 → WatchBoard.read() → DataClient.getDataItems（手錶本機快取）→ decodeSnapshot → valuate
```

從介面追到資料只經過三個檔（〈Minimize Reader Load〉）。手錶**不另存一份**：Data Layer 本身已經把 DataItem 持久化在手錶上，再抄進 DataStore 就多一份要同步的副本。

- `SnapshotListenerService.onDataChanged` 只負責通知 Tile 與小格重拉，不解碼、不存檔。
- Tile 的 `freshnessIntervalMillis = 0`，小格 `UPDATE_PERIOD_SECONDS = 0`，兩者都只在收到推播時更新，不自己定時重拉，比較省電。
- 手錶 App 開著時用 `observe()`：Data Layer 的 listener 加上每分鐘重送一次，讓「資料 N 分鐘前」會跟著時間走。
- 手錶完全不連網，也不寫任何狀態。

### 到價提醒：誰判斷、怎麼不重複

- **只有手機判斷**，在每一輪刷新抓完價之後執行。手機發通知，系統自動橋接到手錶並震動。
  手錶的報價全是手機推過去的，讓手錶也判斷不會比較快，只會跟手機的通知重複，還得額外關掉橋接。
- **規則是推導的**：`stepAlerts` 從持股的停損、目標、期貨合約當場算出規則，沒有存起來的「提醒清單」要跟持股同步。
- **規則鍵**是 `AlertKey(holdingId, kind, 價位或合約月份)`。改了停損價就是一條新規則，從 Armed 開始；
  舊規則因為推不出來，在下一輪對帳時自然消失。
- **狀態機**：Armed 觸發後變成 Fired。價格回到安全側並超過 1% 緩衝帶，才會回到 Armed。轉倉提醒觸發後不再回到 Armed。
- **不重複的兩層保證**：
  1. 狀態機讓 Fired 狀態下不會再發。
  2. 通知的 `tag` 用 `AlertKey.stableTag()`，再加上 `setOnlyAlertOnce`。當機重跑時同一則通知只會被取代，不會再震一次。
  tag 不能用 `hashCode`，因為 enum 的 hashCode 是物件身分，每次開 App 都不一樣。
- **先發通知、再存狀態**。兩步中間當機，下一輪會重發同一則，被 tag 取代掉。反過來（先存再發）會漏發，漏發比重發糟。
- **轉倉提醒**：在最後交易日前 3 個日曆日觸發，鍵含合約月份，換月之後就是新規則。

### 背景排程

- WorkManager 只負責叫醒。抓不抓、抓哪些由純函式 `dueForFetch` 決定，所以頻率只有一個真相來源。規則如下：
  - 盤中：背景約 15 分鐘一次（加密 30 分鐘）；App 在前景時 2 分鐘；手動刷新至少間隔 30 秒。
  - 收盤後：等 10 分鐘補抓一次收盤價，之後整晚不抓。
  - 匯率：一天一次。
  - 失敗：指數退避，最長 60 分鐘；NotFound 退 6 小時；Throttled 照 Retry-After。
  - 沒有假日表時的休市偵測：盤中時段過了 30 分鐘，報價日期還停在昨天，就當作今天休市。這也是從快取推導出來的。
  - 交易時段表見 `FetchPolicy.kt`，用 `ZoneId` 處理美股夏令時間。
- **單一寫入者由 WorkManager 的唯一工作保證**。真正的 `Refresher` 只在唯一名稱 `refresh-now` 下跑（`APPEND_OR_REPLACE`）。
  15 分鐘的週期工作只負責把一輪排進 `refresh-now`，所以背景、前景、手動、編輯後的刷新都排在同一條隊伍裡，不需要鎖。
  編輯持股之後排進來的那一輪一定在進行中那輪之後跑，因此一定看得到剛存的持股。
- Worker 永遠回 success：失敗已經記在 `RefreshState` 裡，回 retry 會多出一套 WorkManager 的退避，變成兩個真相來源。
- 一輪的內容（`Refresher.run`）依序是：讀兩個檔、`dueForFetch`、fetch、merge、`retainOnly`、`stepAlerts`、發通知、寫狀態、發布到手錶。
  每一步都從存著的狀態推導，從哪一行當掉，下一輪都會收斂到同一個結果。

### 介面深度判斷

- `Portfolio` 只有 4 個成員，背後是解析驗證、兩個存檔、估值、刷新排程、單一寫入者保證、提醒與發布。UI 協調一個方法就能完成一件事。
- `QuoteFetcher` 只有 1 個方法，背後是路由、代號對照、節奏、HTTP 狀態碼對應、JSON 解析、期交所批次篩選。
- `WatchBoard` 有 2 個方法，背後是 Data Layer URI、Buffer 釋放、版本相容、解碼、估值。
- 仍然暴露給呼叫端的：`Valuation` 的形狀（UI 一定要知道才能畫）、`RefreshMode`（UI 知道自己在前景還是手動）。
  介面不需要更大，因為沒有呼叫端需要介入中間階段。

### 刻意不做的

- 手錶不連網、不寫狀態、不判斷提醒。
- 不存任何推導得出來的值（幣別、乘數、結算日、提醒清單、節流時間）。
- 不做假日表。休市時最多多打幾次請求，之後由休市偵測擋下。
- 不做手續費與稅，也不做盤前盤後價。
- 不用 Room，也不新增 OkHttp。

### 設計紅旗自檢

| 紅旗 | 結果 |
|---|---|
| 淺模組 | 三個公開介面都是 1–4 個成員，藏了整條管線 |
| 資訊外洩 | Yahoo 代號、期交所代碼字串、JSON 與存檔 DTO 都只出現在一處，全是 `internal` |
| 時間序拆分 | 模組依知識切（領域、資料來源、手機殼、手錶殼），不依「抓、算、存、送」切 |
| 轉手方法 | `QuoteFetchers.default` 加了路由政策；`WatchBoard.read` 把 Data Layer 換成領域抽象；沒有原樣轉手的層 |
| 歸屬分裂 | 每個檔、每個 DataItem 只有一個寫入者 |
| 一件事兩種做法 | 刷新只有 `Refresher.run` 一條路；估值只有 `valuate` 一個；手錶讀取只有 `WatchBoard` |
| 內部可被匯入 | DTO 全是 `internal`，跨模組匯入會編譯失敗 |
| 手動同步的清單 | 路徑在 `SyncContract` 只寫一次；交易時段一張表；期貨每點價值寫在 enum 上 |

## 綜合決定

（由 arena 填。）

## 接受的取捨

- 我們接受**不用 Room**，換得少一套 schema、migration 與 KSP。所有資料都是幾十筆、整份讀寫，Room 的查詢與索引用不到。
  將來如果要做成交紀錄、歷史損益曲線這類會長大的資料，那一塊再用 Room，現有兩個檔不用動。
- 我們接受**手錶自己跑 `valuate()`**（多一點 CPU），換得四個畫面的數字由同一個函式算出。
  這是幾十次 BigDecimal 乘法，跟網路與螢幕的耗電相比可以忽略。
- 我們接受**手錶直接讀 Data Layer 快取、不另存**，換得沒有副本要同步。風險是 `getDataItems` 在 Tile 請求裡的延遲。
  實測太慢的話，退路是在 listener 裡把解碼後的快照放進行程內快取，但不落地第二份檔案。
- 我們接受**提醒延遲最多約 15 分鐘**（背景）或一天（期交所若只有盤後資料），換得不高頻輪詢、不跑前景服務。
  這不是疏忽：要即時只能開前景服務，那是一個需要使用者決定的產品選擇。
- 我們接受**持股檔與刷新狀態檔分開、彼此不保證同一時刻一致**，換得兩個寫入者不用鎖。
  刷新狀態裡可能有已刪持股的殘留，下一輪對帳就會清掉；估值以持股檔為準，殘留不會被顯示。
- 我們接受**同步封包一律 `setUrgent()`**（比較耗電），換得編輯後手錶幾秒內就更新。量到耗電問題時，改成只有提醒觸發或編輯時才標急件。
- 我們接受**把報價從 Yahoo 的 Double 轉成 BigDecimal 時用 `toString()`**，換得 DTO 簡單。報價本身只到小數幾位，不會有累積誤差。

## 考慮過的替代方案

- **手錶自己抓報價、自己判斷提醒**：輸在耗電（官方把網路列為 Very high），而且同一套抓取政策要在兩台裝置各跑一份、各自節流，打 API 的次數加倍。
  它唯一的好處是手機不在身邊時仍能更新，但 Pixel Watch 5 的使用情境多半跟手機在一起。
- **手機算好畫面模型（TileModel、小格文字、分頁）再推給手錶**：手錶最簡單，但每改一次顯示都要改兩邊的合約；
  「資料 N 分鐘前」這類跟時間有關的顯示，手錶也算不了。推送領域快照、共用 `valuate` 的形狀，公開介面更小。
- **用 Room 存持股、報價與提醒狀態，三張表一個資料庫**：介面看起來統一，但 UI 和 Refresher 會寫同一個資料庫，
  得靠交易隔離才不會出錯；對幾十筆資料來說，這套機制的成本大於它的效益。
- **提醒條件存成獨立的使用者資料（Alert 表）**：可以支援「任意價位提醒」，但要跟持股的停損與目標保持同步。
  需求只講停損、目標、轉倉，所以從持股推導。要加任意價位提醒時，再加一種規則來源。

## 未解問題與風險

1. **自用還是上架？** grounding 說自用，決策紀錄說上架並一次買斷。上架的話，Yahoo 與期交所行情能不能放進收費 App（研究檔 C10、C-FUT），
   要先問清楚才寫 `:quotes`。架構不變，只換 `:quotes` 裡的來源。
2. **期交所的資料要用哪一種？** 盤後的 OpenAPI 授權最乾淨，但期貨提醒一天只能判斷一次；盤中資料的來源與條款還沒查到。你接受期貨只有收盤後更新嗎？
3. **停損觸發之後，價格回升再跌破，要不要再響一次？** 草圖預設是價格回到安全側超過觸發價 1% 後重新武裝，所以會再響。你要「同一個價位只響一次，改了價位才重來」嗎？
4. **「結算日前 3 天」算日曆日還是交易日？** 草圖用日曆日；遇到國定假日順延時，結算日本身也會變。
5. **小格的「今日總損益 %」分母用什麼？** 草圖用「各部位昨收市值（期貨用名目價值）的絕對值總和」。期貨名目價值很大，會把百分比稀釋掉。你要改用成本或保證金嗎？
6. **Tile 的「今日漲跌最多 3 檔」看商品本身的漲跌 %，還是看你這筆部位的台幣損益？** 草圖看商品漲跌 %。空單持有的股票大漲，在草圖裡會被列為「漲最多」。
7. **中文股名要不要？** Yahoo 沒有中文名。可以讓你在持股上自填一個名稱，或內建證交所與櫃買的開放資料清單（多一個每月更新的來源）。
8. **風險**：Yahoo 端點非官方，隨時可能改版或封鎖。`parseChart` 失敗會記成 `Unparseable`，畫面會顯示，但沒有自動切換的備援來源。
9. **風險**：Data Layer 在 Tile 請求裡讀取的延遲沒實測過；`SuspendToFutureAdapter` 與 Tiles 1.6.2 是否已有 suspend 版 TileService，也還沒查。

## 下一步實作

先寫 `:core` 的 `valuate` 與 `stepAlerts`，連同 JVM 單元測試：多空、四個市場、缺匯率、提醒跑兩次只發一次、改價位後重新武裝。這兩個函式是整個設計的承重點。
