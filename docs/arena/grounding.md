# 打底資料：Pixel Watch 持股看板（自用）

（`..\` 開頭的檔在 repo 外，是作者自己的研究筆記，沒有公開。）

## 目標
使用者自用（不上架、不收費）的持股看板：Android 手機 App 新增／編輯持股並抓報價，Pixel Watch 5 用 Tile、App、錶面小格（complication）顯示損益；碰到停損／目標價時手錶震動提醒。

## 已定案的產品決定
完整表格：`..\持股看板-決策紀錄.md`（必讀）。重點：
- 市場：台股（上市 .TW、上櫃 .TWO、ETF）、美股、台灣期貨（台指期 TX、小台 MTX、微台 TMF、個股期）、加密貨幣。
- 編輯只在手機；手錶只顯示。不架伺服器、不做帳號，資料只存在使用者的手機與手錶。
- 每筆持股欄位：代號、數量、平均成本、停損價、目標價、進場日（算持有天數）、備註、方向（多／空，預設多）。損益不扣手續費與稅。
- 期貨：選商品＋合約月份，自動帶每點價值；結算日前 3 天提醒轉倉。
- 數量單位：台股張或股、美股股（可小數）、期貨口、加密顆（小數 8 位）。
- 幣別：每檔原幣顯示；總損益換台幣，匯率一天更新一次。
- 手錶：Tile＝台幣總損益＋今日漲跌最多 3 檔；App＝依市場分頁；小格＝今日總損益 %。優先序 Tile → App → 小格。
- 到價震動提醒：要做。

## 資料來源（實測）
`..\Yahoo報價實測.md`（必讀）。
- Yahoo chart 端點 `https://query1.finance.yahoo.com/v8/finance/chart/<代號>?range=1d&interval=1m`：台股、美股、加密、USD/TWD 都有；非官方、無保證。沒有中文名。
- 台灣期貨：使用者指定用期交所資料。期交所取得方式的研究還在跑，寫在
  `..\上架收費與資料授權研究.md`（若檔案已有「期交所資料取得方式」一節就讀；沒有就把期交所來源設計成可替換的一個實作，端點細節留 TODO）。
- 使用者習慣：不要高頻輪詢公開 API。

## 平台事實
- 手錶：Pixel Watch 5，Wear OS 7（Android 17，API 37），426×426 圓形，armeabi-v7a。手機：Android（版本未知，≥12）。
- 平台研究：repo 外 `..\..\共用研究\Pixel Watch 自行開發APP研究.md`（從 repo 根目錄算）。在 Pixel Watch 上做 Tile（不是 Wear Widgets）。
- 建置：AGP 9.5.1（內建 Kotlin）、Kotlin 2.4.21、compileSdk/targetSdk 37、Compose BOM 2026.09.00、Wear Compose Material3 1.7.1、Tiles 1.6.2、ProtoLayout Material3 1.4.2、watchface-complications-data-source-ktx 1.3.0、play-services-wearable 20.0.1、WorkManager 2.12.0、Room 2.8.5、DataStore 1.3.0、kotlinx-serialization-json 1.13.0。
- 手機與手錶是兩支 APK，同一個 applicationId 與簽章時可用 Wearable Data Layer（DataClient／MessageClient）同步。
- Android 背景限制：WorkManager 週期工作最短 15 分鐘；更密要前景服務。手機發的通知會自動橋接到手錶並震動。

## 未定、可由設計提出建議
- 誰抓報價（手機、手錶、或兩者）、抓的頻率、盤中與收盤的差別。
- 到價提醒由哪一端判斷與發出、如何避免同一個條件重複提醒。
- 持股與報價快照的儲存格式與同步方式。
