# 持股看板：綜合設計（定案，2026-11-14）

自用的 Pixel Watch 5 持股看板。手機 App 編輯持股、抓報價、判斷到價；手錶用 Tile、App、錶面小格顯示。
產品決定見上一層的 `持股看板-決策紀錄.md`；報價實測見 `Yahoo報價實測.md`；期交所資料見 `上架收費與資料授權研究.md` 末節。

## 底與來源

- **底**：候選 1（`docs\arena\candidate-1\`，opus）。它的 `design.md` 與 `sketch\` 是實作合約，下面只寫**跟它不同**的地方。
- **裁判**：fable 評分 候選 1 23／30、候選 2 18／30，推薦候選 1。候選 2（sonnet）的硬傷落在承重處：Refresher 的 commit 會把使用者剛改的持股蓋回去、`KEEP` 會丟掉編輯後的即時刷新、`due` 規則可能讓冷門股整天停抓、商品 key 還原有損。
- 兩個候選收斂在同一個大形狀：`:core`／`:quotes`／`:phone`／`:wear` 四模組，手機是唯一抓取者與寫入者，手錶不連網、直接讀 Data Layer，不另存一份；提醒在手機判斷，用穩定 tag 加 `setOnlyAlertOnce` 去重。

## 從候選 2 嫁接的

1. **由時間推導的新鮮度＋提醒門檻**：`Quote.freshness`（來源等級：即時／延遲／僅收盤）保留，另加純函式 `staleness(quote, now, sessions)` 推導 LIVE／FINAL／STALE。`stepAlerts` 只對 LIVE 或 FINAL 的報價判斷，STALE 不觸發也不重新武裝。
2. **`ExitLevels.status` 同時給提醒與手錶的紅綠點用**：通知漏掉時，看板上仍看得到「已碰停損／目標」。
3. **來源路由**：沿用候選 1 的 `Source.handles(item)` 述詞，加上**有序後備**與 `QuoteEntry.source: SourceId`；`HttpGet` 可帶標頭（Yahoo 要 User-Agent），回應保留 `retryAfterSeconds`。
4. **不變式封進建構子**：`Holding`、`ExitLevels` 建構子私有，只能經 `HoldingDraft.parse` 或解碼建立；數量型別建構子 internal，「> 0」與小數位在建構時檢查。
5. **`Holding.name`**：選填的顯示名稱（Yahoo 沒有中文名）。台灣 Yahoo 期貨頁有中文名，但持股名稱以使用者填的為準，沒填就顯示代號。

## 補兩個候選都漏掉的

| # | 漏洞 | 定案做法 |
|---|---|---|
| 1 | 兩次抓取之間碰線又彈回會漏響 | Yahoo chart 帶 1 分鐘序列：碰線判斷用「上次判斷以來」的最低／最高價，不只看 `last`。台灣 Yahoo 期貨頁只有當日高低，第一次判斷用當日高低、之後用 `last`。 |
| 2 | 抓取節奏只看市場、不看來源能力 | 每個來源宣告能力（盤中／僅收盤）。僅收盤的商品（微台、個股期貨、期交所盤後）收盤後抓一次就停，畫面標「僅收盤價」，提醒一天判斷一次。 |
| 3 | 台灣 Yahoo 期貨頁是「近一／近二」連續代號，換月時會跳 | 解析時讀出頁面上的合約月份，跟持有的合約比對，對不上回 `NotFound`，不顯示別的合約的價。（合約月份欄位要從實測頁面確認，找不到就以結算日推算近一是哪個月，並在換月前後各一天標 STALE。） |
| 4 | 期貨夜盤的「今日」界線 | 期貨的交易日以 15:00 切：15:00 之後的成交算下一個交易日，今日損益的基準是前一交易日結算價（期交所盤後資料）或頁面的 `regularMarketPreviousClose`。 |
| 5 | 合約過了最後交易日 | `PositionValue` 多一個 `Expired` 狀態，畫面標「已到期，請在手機結清」，不再抓價、不再提醒。 |
| 6 | `AlertKey` 的價位 580 與 580.0 被當成不同規則 | key 裡的價位一律 `stripTrailingZeros().toPlainString()`。 |
| 7 | 回應沒自我核對 | 解析時比對回應的代號與幣別等於所問商品，不一致回 `Unparseable`。 |
| 8 | 通知橋接是否一定讓手錶震動 | 實機驗證（驗收項目）。自用情境下，App 第一次開啟時請使用者把它設為「不受電池最佳化限制」，減少 Doze 延遲；不做前景服務。 |
| 9 | 持股只有手機一份 | v1 不做；之後加 JSON 匯出匯入（寫在待辦）。 |

## 使用者沒講、先套的預設（之後可改）

- 錶面小格「今日總損益 %」＝今日台幣損益 ÷ 各部位台幣成本的絕對值總和（期貨用名目成本）。
- Tile「今日漲跌最多 3 檔」看商品本身的漲跌 % 絕對值。
- 提醒觸發後，價格回到安全側超過 1% 才重新武裝；同一價位一天內不會反覆響。
- 「結算日前 3 天」算日曆日。
- 盤中背景約 15 分鐘一輪（WorkManager 下限），App 在前景時 2 分鐘一輪（前景時用協程計時，不靠 WorkManager）。

## 吞吐檢查點

- **擋路的第一步**：`:core` 的領域型別與三個純函式（`valuate`、`stepAlerts`、`dueForFetch`）加 `HoldingDraft.parse`、`PortfolioCodec`，連同 JVM 測試。其他三個模組都依賴這些型別，沒定下來就平行會各自猜形狀。
- **獨立的工作線**（`:core` 綠了之後）：
  1. `:quotes`：Yahoo chart、台灣 Yahoo 期貨頁、期交所 OpenAPI 三個解析器與路由，用實測回應當 fixture 做 JVM 測試。
  2. `:wear`：`WatchBoard`、Tile、錶面小格、手錶 App。只依賴 `:core`。
  3. `:phone`：儲存、`Portfolio`、`Refresher`、WorkManager、通知、Data Layer 發布、編輯畫面。依賴 `:quotes` 的公開介面（`QuoteFetcher`），介面在 `:quotes` 第一個 commit 就定，實作可以晚到。
- **共用的可變狀態**：`gradle\libs.versions.toml`、`settings.gradle.kts` 三條線都要動。做法是主對話在展開前一次把所有依賴與模組加好，worker 不准改這兩個檔；每個 worker 只寫自己的模組資料夾。Gradle 建置會互相排隊（同一個專案鎖），可以接受。
- **最小的安全切法**：`:core` 由一個 worker 做完並驗收；之後 `:quotes` 與 `:wear` 兩個 worker 平行；`:phone` 等 `:quotes` 的介面定了再開（它是最大的一塊，也最需要真機驗證）。
