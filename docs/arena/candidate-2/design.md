# 候選 2：手機單一寫入、手錶只讀「已算好的看板」

## 問題

要做自用的持股看板：手機 App 編輯持股並抓報價，Pixel Watch 5 用 Tile、App、小格顯示損益，碰到停損／目標價要震動。
限制（來自打底資料）：

- 四個市場（台股含上櫃與 ETF、美股、台灣期貨、加密）、兩種幣別，總損益要換台幣。
- 不架伺服器、不做帳號；持股只在手機與手錶。編輯只在手機。
- 報價來源兩個：Yahoo chart（非官方、無保證、台指期 404）與期交所（端點與授權未定）。使用者不要高頻輪詢。
- WorkManager 週期工作下限 15 分鐘；手機通知會自動橋接到手錶並震動。
- 手機與手錶是兩支 APK，同 applicationId、同簽章，可用 Data Layer。

形狀不明顯的地方：誰抓報價、提醒誰判斷、期貨的乘數與轉倉日放哪、「今日總損益 %」的分母沒定義。

## 用法（呼叫端視角）

模組：`:core`（純 JVM）、`:quotes`（純 JVM）、`:phone`、`:wear`。

**1. 手機表單新增一筆台指期多單。** 表單只有字串，一次拿回所有欄位錯誤：

```kotlin
val tx = Instrument.Future(FutureContract(FuturesProduct.TX, YearMonth.of(2026, 12)))
val parsed = Holding.create(
    HoldingId(uuid()),
    Holding.Draft(tx, Side.LONG, qtyRaw = "2", qtyUnit = QtyInputUnit.CONTRACT, avgCostRaw = "21850",
        stopRaw = "21500", targetRaw = "22600", entryDate = today, name = "台指期12月", note = ""),
    today,
)
when (parsed) {
    is Holding.Parsed.Ok -> ledger.upsert(parsed.holding); RefreshWorker.scheduleNow(ctx)
    is Holding.Parsed.Invalid -> form.show(parsed.fieldErrors)   // Map<Field, String>
}
```

**2. 背景一輪刷新。** Worker 只有一行業務：

```kotlin
override suspend fun doWork() = RefreshBoard(ledger, DefaultQuoteSources.create(http), DataLayerPublisher(dc),
                                             PhoneNotificationSink(ctx), clock, calendar).run().toResult()
```

**3. 手錶 Tile 與小格。** 兩者讀法完全一樣，差別只在最後一個純函式：

```kotlin
override suspend fun onTileRequest(req: RequestBuilders.TileRequest): TileBuilders.Tile =
    when (val s = replica.current()) {
        is BoardReplica.State.Ready -> tileLayout(BoardViews.tile(s.board, clock.instant()))
        BoardReplica.State.NeverSynced -> messageLayout("請先在手機開啟持股看板")
        is BoardReplica.State.Unreadable -> messageLayout("請更新手錶 App")
    }
// 小格： BoardViews.complication(board, now).dayRatio -> ShortTextComplicationData
```

使用者要看到的三件事（損益、提醒不重複、不高頻輪詢）在這三個呼叫點都不用呼叫端操心：損益在 `valuate` 算完，重複由閂擋掉，輪詢由 `plan` 決定。

## 形狀

**核心資料結構：`LedgerState` → `Board`。** `LedgerState`（持股、每商品最新報價紀錄、匯率、提醒閂）是唯一被持久化的東西；`Board` 是它的純函式推導（`Valuation.valuate`），不存。
走一遍存取模式：手機 UI 訂閱 `LedgerState` 再 valuate；同步到手錶的是 valuate 後編碼的 `Board`；Tile／小格／手錶 App 從 `Board` 再做各自版面（`BoardViews`）。
沒有一條路徑需要「再加個索引或快取」；也沒有兩處各自算損益。〈Model the Domain〉〈每個不變式一個真相來源〉

**商品是 sealed `Instrument`，每個分支自己回答幣別、乘數、數量規則、時段、報價鍵。** 損益只有一條公式：
`side.sign × (last − cost) × qty × multiplier`（股票乘數 1，期貨乘數來自 `FuturesProduct` 登記表）。
期貨的結算日與轉倉日由合約月推導（`settlementDate`，轉倉＝前 3 天），沒有「提醒已排程」狀態。
數量一律存最小成交單位（台股存股數，1 張＝1000 股），所以「張或股」只是輸入單位，不是儲存分支。〈Model the Domain〉〈Type System Discipline〉

**不變式寫進型別：**
`Price` 必為正（`of` 回 null）；`Quantity` 建構子 internal，只能經 `Instrument.quantity` 得到，小數位規則因此只在商品型別裡出現一次；
`ExitLevels` 建構子私有，`of(side, stop, target)` 檢查多空的大小順序；`Holding` 建構子私有，`create` 一次驗完跨欄位規則。
`Board.last` 缺報價時是 `null`、不是 0；`Totals.incompleteLines > 0` 強制 UI 標「不完整」。`Money` 帶幣別，換算只走 `FxRate.convert`。

**共享狀態：每個行動者自己的狀態，沒有合併。**

| 狀態 | 唯一寫入者 | 讀者 |
|---|---|---|
| 持股 | 手機（表單） | 手機 |
| 報價、匯率 | 手機的刷新（Refresher） | 手機 |
| 提醒閂 | 手機的刷新 | 手機 |
| 手錶上的看板 | 手機（Data Layer 單一路徑 `/board/v1`） | 手錶 |

手錶不寫任何東西回手機，所以「兩邊同時改會怎樣」不會發生。手錶端也不另存一份：`BoardReplica` 直接讀 Data Layer 裡那個項目。〈Separate Before Serializing Shared State〉

**誰抓報價：只有手機。** 手錶只讀。理由：一個抓取者代表一份速率與一份失敗處理；手錶在藍牙斷線時沒有新資料，但看板顯示「N 分鐘前」並把 `Freshness` 標成 STALE，比兩邊各抓各的、數字不一致更誠實。使用者的「不要高頻輪詢」因此是單一出口。

**抓的頻率是結構性的，不是節流參數。** `QuoteRules.due` 一條規則：沒有報價、或報價不是收盤終值、或報價抓取時間早於「最近一次排定開盤」才抓。
「是否終值」由報價時間戳與該市場時段推導：全部收盤後 `Refresh.plan` 為空，Worker 整輪不碰網路；加密 24 小時永遠要抓；休市日開盤後抓一次拿到舊時間戳就停，不需要休市日曆。
匯率以台北日期為準，一天一次。〈Make Operations Idempotent〉

**到價提醒：手機判斷、閂去重、補送冪等。**
碰線不是狀態，是 `ExitLevels.status` 與 `RolloverNotice` 對現況的推導（手錶上的紅／綠點也用同一個函式，所以就算通知漏掉，看板上仍看得到）。唯一要存的是「通知過了沒」：
`AlertKey`（持股、停損或目標、價位）→ `Armed | Pending | Notified`。價位是 key 的一部分：改停損價就是新 key、天然 Armed，舊 key 被 `prune` 掉，沒有「重設」動作。
`Notified` 價格退出 0.5% 帶才回 `Armed`，避免在線上抖動連發。轉倉閂每個合約月只一次。
跑到一半掛掉：報價與 `Pending` 在同一個資料庫交易裡提交；崩潰在前＝什麼都沒發生；崩潰在後＝下一輪 `step` 把所有 `Pending`（含上次留下的）再送一次，通知 id 由 key 決定性推導並 `setOnlyAlertOnce`，重送是靜默取代。語意是至少一次送達、至多一次震動。〈Make Operations Idempotent〉

**背景排程。** 一個週期 15 分鐘的 `RefreshWorker`（只在手機），加上開 App 或改持股時的一次性刷新，兩者同一個 unique name、`KEEP`，所以同時只有一輪，不需要自己的鎖。
Doze 與待機桶會讓盤中提醒延遲超過 15 分鐘；這是平台限制，設計不假裝能避免（見風險）。

**同步：** 手機 → 手錶一個方向、一個項目。`BoardCodec` 的線上格式是 internal DTO，公開面只有 `encode` 與 `decode`；解碼失敗回 `Unreadable` 而不是丟例外。schema 升級換路徑（`/board/v1` → `/v2`），舊手錶顯示「請更新」。
`putDataItem` 是 upsert 且相同內容不重複觸發，所以不另存雜湊。

**來源可替換，只在一處。** `:quotes` 公開面只有 `DefaultQuoteSources.create(http)`；`QuoteSources` 介面在 `:core`。
Yahoo 符號規則、JSON 欄位、期交所契約代號與月份格式全是 `:quotes` 的 internal；`Routing` 一張表決定哪類商品問誰、失敗再問誰。
解析是純函式，用 2026-11-14 的實測回應做 fixture。`HttpGet` 是唯一網路縫。〈Boundary Discipline〉

**驗證放邊界、內部信任型別：** 表單（`Holding.create`）、Yahoo／期交所解析、`BoardCodec.decode`、`Instrument.parse` 各是一個邊界；之後的 `valuate`、`step`、`plan` 全是不做空值與格式防禦的純函式，JVM 單元測試即可。外殼（Room、Worker、Data Layer、Tile）每個方法一個純函式加一個平台 API。

**介面深度：**
- `Refresh.plan` + `RefreshBoard.run()`：呼叫端只有「跑一輪」；背後藏了何時抓、抓誰、失敗隔離、估值、提醒判斷、交易、發佈、補送。
- `BoardViews`：三個出口各一個函式，手錶端零商業邏輯。
- `QuoteSources`：一個 `fetch(plan)`，Yahoo／期交所／路由／節制全藏在後面。
- 沒有暴露給呼叫端的：Room Entity、DTO、Yahoo 符號、期交所代號、WorkManager 細節。
呼叫鏈：Worker → `RefreshBoard.run` → 純函式（`Refresh`／`Valuation`／`AlertRules`），三檔以內；Tile → `BoardReplica.current` → `BoardViews.tile`，兩檔。

**刻意不做：** 手錶抓報價、手錶寫回手機、持股上手錶、手續費與稅、前景服務盤中監控（預設關，見未解問題）、備份匯出（見風險）。

## 綜合決定

（由 arena 填。）

## 接受的取捨

- 我們接受「手機沒開機或藍牙斷線時手錶數字會舊」，換得單一抓取者、單一失敗處理、兩端永遠數字一致；舊的程度以 STALE 與「N 分鐘前」顯示。
- 我們接受提醒最長可能延遲數十分鐘（15 分鐘下限加 Doze），換得不用前景服務與常駐通知；看起來像疏忽，其實是平台下限。
- 我們接受每次刷新整份 `Board` 重編碼推送，不做差量，換得手錶端永遠是完整一致的快照；持股在百筆以內遠小於 Data Layer 單項 100KB 上限（**推論**，實作時量）。
- 我們接受 `LedgerState` 每輪整讀整寫（以 diff 寫入），換得一個交易邊界與崩潰可重入；資料量小，這不是過早簡化。
- 我們接受「碰線」不存、只存閂，換得手錶紅綠點與通知永遠一致；代價是改規則（例如加「接近停損」）要動 `ExitLevels.status` 與 `AlertRules` 兩處，兩處都在 `:core` 同一包。
- 我們接受 `Instrument` 的商品鍵自己有一套字串表示（`TWSE:2330`、`TAIFEX:TX:2026-12`），換得儲存與同步只認這一個身分；`parse` 與 `key` 要做 round-trip 測試。

## 考慮過的替代方案

- **手錶自己抓報價（兩端都抓）。** 輸在共享狀態：兩個抓取者就有兩份快取、兩種新鮮度、兩套節流，數字會不一致，還要決定提醒誰發；手錶 Tile 在背景連網的限制更多。它暴露給呼叫端的是「兩端各自可能不同的數字」。
- **手機只同步持股與報價原料，手錶自己估值。** 看似手錶更獨立，但估值規則（匯率、空單、期貨乘數、分母口徑）要在兩端各跑一份，雖然可以共用 `:core`，卻要把 `LedgerState` 的一部分搬上手錶並處理它的版本與新鮮度。單向推導好的 `Board` 讓手錶端沒有商業邏輯，介面更小。
- **Room 存估值後的 Board。** 會出現第二份可與 `LedgerState` 分歧的真相，改成本或匯率時要同步失效；推導代替同步。
- **提醒在手錶判斷。** 手錶沒有新鮮報價（除非也抓），且閂的狀態會落在第二個寫入者；輸。

## 未解問題與風險

1. **通知橋接是否在「手機鎖屏、手錶配戴」時一定震動？** 這是平台行為，沒有實機證據。方案 A：靠橋接（預設，最簡）；方案 B：另加 `WatchMessageSink`，手錶自己發本機通知並震動（需要在快照外另開一個緊急項目，並處理與橋接重複）。要不要在 v1 先驗證再決定？
2. **小格「今日總損益 %」的分母？** 預設用台幣成本基礎總和（期貨名目價值會讓比例失真）。你要成本、市值、還是保證金？
3. **Tile 的「今日漲跌最多 3 檔」按幅度（%）還是金額（台幣）？** 預設按 % 的絕對值。
4. **到價回退多少才重新武裝？** 預設 0.5%。要不要改成「隔天重新武裝」？
5. **期交所資料用哪一級？** 盤後 OpenAPI（乾淨、非即時）、延遲、還是盤中即時；端點細節與授權沒有查到，`Taifex` adapter 暫為 TODO。盤中停損提醒對期貨是否必須即時？
6. **期貨規格數字**（TX 200、MTX 50、TMF 10、個股期貨 2000／100 股、第三個星期三結算）本 session 沒有對期交所原文核對；實作前要核對，個股期貨清單怎麼取得也未定。
7. **要不要盤中前景服務**（比 15 分鐘更密，代價是常駐通知）？預設不做。
8. **持股只存在手機一份：** 手機重置會遺失。要做 JSON 匯出／匯入嗎？手錶上只有推導後的看板，無法還原持股。
9. **中文股名：** Yahoo 沒有；現設計由使用者在 `Holding.name` 自填。是否要打包證交所名稱對照表？
10. 風險：Yahoo 非官方端點可能改格式或限速（`Unparseable`／`RateLimited` 會顯示，但需要人修 adapter）；Doze 與電池最佳化會讓 Worker 更稀。

## 下一步實作

先在 `:core` 寫 `Valuation.valuate` 與 `AlertRules.step` 的 JVM 測試（多單、空單、期貨乘數、USD 換算缺匯率、改停損價重新武裝、`Pending` 崩潰補送），再實作它們。

## 模組地圖

```
:core   (JVM, kotlinx-serialization)          // 領域型別 + 全部商業規則 + 編碼；無 Android
  Money.kt        Currency, Money, Price, Quantity, Ratio, FxRate
  Instrument.kt   Instrument(4 分支), FutureContract, FuturesProduct 登記表, MarketSessions, SettlementCalendar
  Holding.kt      Side, ExitLevels, Holding(create)
  Quote.kt        Quote, QuoteRecord, Freshness, QuoteRules(freshness/due/fxDue)
  Board.kt        LedgerState, Board, Valuation.valuate, BoardViews
  Alerts.kt       AlertKey, LatchState, AlertRules.step/markSent
  Refresh.kt      Refresh.plan/merge, FetchBatch, RefreshBoard(外殼), ports: LedgerStore/QuoteSources/BoardPublisher/AlertSink
  Sync.kt         BoardCodec, BoardReplica
:quotes (JVM)     // 依賴 :core
  QuoteSources.kt DefaultQuoteSources, HttpGet, Routing, YahooChart, Taifex（後三者 internal）
:phone  (Android) // 依賴 :core, :quotes；Room、WorkManager、Compose、DataClient、通知
  RoomLedgerStore, RefreshWorker, DataLayerPublisher, PhoneNotificationSink, 持股表單與看板 UI
:wear   (Android) // 只依賴 :core；不依賴 :quotes、不依賴 Room
  DataLayerReplica, BoardChangedListener, HoldingsTile, DayRatioComplication, WatchApp
```

編譯期強制：`:wear` 沒有 `:quotes` 依賴，手錶端寫不出抓取；`:quotes` 的 adapter 是 internal，外面只能走 `DefaultQuoteSources`；Room Entity 是 `:phone` 的 internal。
型別草圖在 `sketch\`：`Money.kt`、`Instrument.kt`、`Holding.kt`、`Quote.kt`、`Board.kt`、`Alerts.kt`、`Refresh.kt`、`Sync.kt`（皆在 `:core`）、`QuoteSources.kt`（`:quotes`）、`Shells.kt`（兩個 Android 外殼簽章）。草圖未編譯。
