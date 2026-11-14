# 省電：基準與目標

## v0.1 基準（2026-11-14）

每輪抓多少，來自 `quotes\src\test\resources\fixtures\` 的實際回應大小（**實測**）與手機 logcat（`http 200 559041B …WTX&`、`863157B …DailyMarketReportFut`）。
每天幾輪是照 `:core` 的 `dueForFetch` 規則推算（**推論**，還沒在手機上用 batterystats 量過）。假設持有：台股 2 檔、美股 1 檔、台指期近月 1 口、微台 1 口、BTC。

| 來源 | 每次大小 | 每天幾次 | 每天流量 |
|---|---|---|---|
| 台灣 Yahoo 期貨頁（台指期） | 559 KB | 日盤 5h＋夜盤 14h，每 14 分 ≈ 81 次 | **≈ 45 MB** |
| Yahoo chart BTC（1 分 K 一整天） | 74 KB（越晚越大） | 24h，每 29 分 ≈ 50 次 | ≈ 3.7 MB |
| Yahoo chart 台股 ×2 | 13 KB ×2 | 4.5h，每 14 分 ≈ 20 次 | ≈ 0.5 MB |
| Yahoo chart 美股 | 19 KB | 6.5h ≈ 28 次 | ≈ 0.5 MB |
| 匯率 TWD=X | 85 KB | 1 次 | 0.1 MB |
| 期交所盤後（微台） | 863 KB | 1 次 | 0.9 MB |
| **合計** | | **約 200 次請求** | **≈ 50 MB／天** |

叫醒次數：期貨夜盤加上加密 24 小時，等於手機**整天每 14 分鐘被叫醒一次**（約 100 次／天），收盤後不叫醒的設計對期貨與加密沒有作用。

## 主要浪費（依影響排序）

1. **台指期每次抓整頁 559 KB 的網頁**，只為了一個價格，佔總流量約 90%。
2. **Yahoo chart 每次都要整天的 1 分 K**（`range=1d&interval=1m`），越晚越大；只是為了算「上次以來的最低／最高」。
3. **每檔一個請求**，同一輪 5 檔就是 5 次連線、5 次 TLS 握手。
4. 加密與期貨夜盤讓手機整夜被叫醒。

## v0.2 目標

- 每天流量降到 **5 MB 以下**（基準的 1/10），請求數減半，叫醒次數不增加。
- 提醒延遲不變（背景維持 15 分鐘，使用者決定 V9）。
- 改完用 `adb shell dumpsys batterystats --reset` 後跑一個交易日，再看 `dumpsys batterystats tw.personal.holdings` 的網路位元組、job 次數、wakelock 時間，跟上表對照。

## 每輪抓多少：v0.1 → v0.2 對照（2026-11-14，`:quotes`）

**量法（可重跑）**：`.\gradlew.bat :quotes:test --rerun`，`DailyTrafficTest` 把結果印在
`%USERPROFILE%\.holdings-build\quotes\test-results\test\TEST-tw.personal.holdings.quotes.DailyTrafficTest.xml` 的 system-out。
同一組持股（台股 2330、6488，美股 AAPL，台指期 2026-11，微台 2026-11，BTC，加上自動帶入的 USD/TWD），WorkManager 每 15 分叫醒一次、
`:core` 的 `dueForFetch` 決定每輪抓哪些，跑 2026-11-17（週二）整天，前一天先暖機。回應全用 fixtures：

- Yahoo 用的是**整天的序列**，所以 Yahoo 的數字是上限（早上的回應比較小）。
- 「gzip」是本機壓縮估的傳輸量。spark 的兩份樣本跟伺服器實際回的 gzip 大小一位元組不差（**實測**：12,284 B、3,844 B）。
  Android 的 `HttpURLConnection` 預設就要 gzip 並自動解壓（**推論**，這次沒在手機上驗）；若成立，手機實際收的是 gzip 欄，
  logcat 印的 `559041B` 是解壓後的內容大小。
- v0.1 的數字是同一支工具在改動前的程式上跑的（**實測**）。

| | 每天輪數 | 每天請求數 | 內容 MB／天 | gzip MB／天 |
|---|---|---|---|---|
| v0.1 | 88 | 193 | 49.11 | 6.38 |
| v0.2（spark） | 88 | 151 | 46.05 | 5.58 |
| v0.2（spark＋走勢圖服務） | 88 | 151 | **5.92** | **1.39** |

| 來源 | v0.1 請求 | v0.1 內容 KB | v0.2 請求 | v0.2 內容 KB | v0.2 gzip KB |
|---|---|---|---|---|---|
| Yahoo 股票、加密、匯率 | 114（每檔一個 chart） | 4,538 | 72（spark，一輪一個） | 1,550 | 449 |
| 台灣 Yahoo 期貨頁 | 78 | 42,574 | 78 | 42,574 | 4,934 |
| 台灣 Yahoo 走勢圖服務（取代期貨頁，最後一欄同一列重量） | — | — | 78 | 3,390 | 842 |
| 期交所盤後 | 1 | 842 | 1 | 842 | 63 |

輪數與每天拿到的報價筆數（193 筆）都沒變，排程沒動，所以提醒的延遲不變。兩版都 0 筆失敗、0 個沒對應到 fixture 的網址。

### 期貨頁換成走勢圖服務（2026-11-14，使用者：「2 放寬再找」）

用無頭瀏覽器開一次期貨頁，看它背後發的請求，找到 `FinanceChartService.ApacLibraCharts`（**實測**）：

- 網址 `https://tw.stock.yahoo.com/_td-stock/api/resource/FinanceChartService.ApacLibraCharts;symbols=["WTX&","WMT@"];type=tick`（JSON 陣列要 URL 編碼），
  不用 cookie、crumb，也不用頁面上的其他查詢參數。
- 一個請求可查多個合約；每個合約約 44 KB 內容、約 9–11 KB gzip（期貨頁 546 KB／63 KB）。四個合約一起 176 KB。
- 每個合約有 `chart.quote`（`price`、`previousClose`、`dayLowPrice`、`dayHighPrice`、實際時間 `refreshedTs`）
  與整個交易日 1,140 根 1 分 K（`indicators.quote[0]` 有 open／high／low／close／volume），碰線判斷跟 v0.1 一樣看得到 K 棒內的高低。
- **時間陷阱**：夜盤的 1 分 K 與 `meta.regularMarketTime` 記在所屬交易日的時間軸上，週五夜盤被記成週日（比實際晚兩天）。
  `YahooLibraSource` 用 `refreshedTs` 當報價時間，並用兩者相差的整數天把序列移回實際時間。

DailyTrafficTest 重跑：每天 5.92 MB 內容、**1.39 MB gzip**。gzip 才是手機實際收的量（**推論**，見上），遠低於 5 MB；內容量略超過。
期貨頁留在路由裡，只在走勢圖服務失敗時才抓。這次對真實網路的請求：瀏覽器開期貨頁一次（含頁面自己的子請求）、走勢圖服務 4 次（單一、兩個、最簡網址、四個合約各一次）。

### 為什麼（換來源前）沒降到 5 MB 以下

限制因素是**台灣 Yahoo 期貨頁**：每次 546 KB 內容（約 63 KB gzip），台指期日盤加夜盤每 15 分一次、一天 78 次，
佔 v0.2 內容的 94.7%、gzip 的 90.6%。其他來源全部歸零，光它就是 42.6 MB 內容／4.9 MB gzip，所以不換掉這個來源就到不了 5 MB。

這次找過頁面背後的 JSON（請求額度 6 次，全部用完）：

- 頁面 HTML 寫著資料走 Fluxible Fetchr，路徑 `/_td-stock/api/resource`；資源名稱在 JS 裡。
- `main`、`common`、`Quote` 三個 JS 檔裡出現的 `StockServices.*` 都是財報、法人、籌碼、搜尋之類，沒有期貨報價。
  唯一沾得上邊的 `StockServices.symbolsInfo;q=WTX&` 實際只回代號與名稱（64 B，沒有價格）。
- 頁面的即時更新疑似在另一個叫 `createQuotePush` 的 JS 檔（**猜測**：推播通道，不是一般 GET）。
- 期交所行情網（mis.taifex.com.tw）沒看：額度用完；它的 API 可能要 POST（**猜測**），`HttpGet` 只會 GET，要改公開介面。

這次對期貨頁做到的是：**一頁內嵌台指期、小台的近一與近二四個報價物件**（**實測**，兩份頁面樣本都是），
所以同時持有這四個合約裡的幾個，一輪也只抓一頁（v0.1 是每個合約一頁）。上面這組持股只有一口台指期，所以數字沒變。

### Yahoo：1 分還是 5 分收盤

spark 只給收盤序列、沒有每根 K 的高低，兩次抓取之間「碰到又彈回」的那一下可能看不到。
`CloseSeriesMissTest` 拿 v0.1 的 1 分 K 高低當真值，量每個 14 分鐘間隔裡收盤序列比真實高低少看到多少（bp，**實測**，週五樣本）：

| 代號 | 1 分收盤：中位數／p95／最大 | 5 分收盤：中位數／p95／最大 |
|---|---|---|
| 2330.TW（一檔 5 元 ≈ 21 bp） | 0／21／42 | 21／43／85 |
| 6488.TWO（一檔 1 元 ≈ 16 bp） | 16／31／64 | 47／94／127 |
| AAPL | 4／13／22 | 10／31／44 |
| BTC-USD | 5／17／62 | 16／48／117 |
| TWD=X | 1／9／23 | 3／10／17 |

用 1 分：Yahoo 每天 1.55 MB 內容（0.45 MB gzip）；用 5 分：0.43 MB（0.14 MB gzip）。
多 1.1 MB 內容，換到少看到的幅度中位數約只剩 5 分的三分之一、p95 約一半；而且期貨頁換掉之後，1 分版的其餘來源（Yahoo＋期交所約 2.4 MB）仍在 5 MB 以內，所以選 1 分。
改設定只要動 `YahooSparkSource.kt` 的 `SPARK_INTERVAL`，`DailyTrafficTest` 會自動改用 `yahoo_spark_5m.json` 重量。

對提醒的影響：停損或目標線剛好落在這個缺口裡、價格在同一根 1 分 K 裡碰線又彈回，這一輪不會響，要等價格再回到線上。
v0.1 用 1 分 K 的高低，沒有這個缺口。股票在當天第一次抓（上次抓在開盤前）時改用 meta 的當日高低，那一輪是精確的；
加密與匯率不用 meta 的當日高低，因為樣本裡 BTC 的序列低點比 meta 的當日低還低，兩者不是同一段時間。

### 期交所盤後

只有收盤價的合約本來就由 `dueForFetch` 控制成收盤後抓一次。這次補的是另一條路：期貨頁失敗時會退回期交所，
v0.1 每輪都會重抓一次 863 KB 的同一份檔。現在每個合約在最近一次「週一到週五 13:55」之後抓過，就不再問期交所，
那一輪回報期貨頁原本的失敗，交給 `:core` 的退避。期貨頁整天失敗時，每天少抓約 77 × 842 KB ≈ 65 MB（**推論**：算術，對應的行為有單元測試）。

### 本次對真實網路的請求

| 代號 | 網址 | 狀態 | 傳輸 B | 內容 B |
|---|---|---|---|---|
| F1 | `https://s.yimg.com/nb/td-app-stock/js/main.c4eb3c3f404a1d7566d1.js` | 200 | 189,036 | 675,719 |
| F2 | `https://s.yimg.com/nb/td-app-stock/js/common.bec658a56c09727f822b.js` | 200 | 154,076 | 587,083 |
| F3 | 同 F1（第一次沒存檔，重抓） | 200 | 189,036 | 675,719 |
| F4 | `https://s.yimg.com/nb/td-app-stock/js/Quote.store.79e88e427a8314b19e9d.js` | 200 | 260 | 260 |
| F5 | `https://s.yimg.com/nb/td-app-stock/js/Quote.bfa879e7bbcb6d55418c.js` | 200 | 107,406 | 510,607 |
| F6 | `https://tw.stock.yahoo.com/_td-stock/api/resource/StockServices.symbolsInfo;q=WTX%26?device=desktop&intl=tw&lang=zh-Hant-TW&partner=none&region=TW&site=finance&tz=Asia%2FTaipei&ver=1.4.943` | 200 | 64 | 64 |
| Y1 | `https://query1.finance.yahoo.com/v7/finance/spark?symbols=2330.TW,6488.TWO,AAPL,BTC-USD,TWD=X&range=1d&interval=5m` | 200 | 3,844 | 17,717 |
| Y2 | 同 Y1，`interval=1m` | 200 | 12,284 | 58,126 |
| Y3 | `https://query1.finance.yahoo.com/v7/finance/spark?symbols=2330.TW,ZZZZ9999.TW&range=1d&interval=5m` | 200 | 1,948 | 1,948 |

### 下一步

- 手機上照〈v0.2 目標〉跑一個交易日的 batterystats，確認 gzip 欄才是實際流量。
