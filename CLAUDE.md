# 持股看板（Pixel Watch 5 自用 App）

使用者自用、不上架。手機 App（Pixel 9）編輯持股、抓報價、判斷到價；手錶（Pixel Watch 5）用 Tile、App、錶面小格顯示。
產品決定在上一層 `持股看板-決策紀錄.md`，設計定案在 `docs\design.md`（底是 `docs\arena\candidate-1\`）。改設計前先讀這兩份。

## 模組

| 模組 | 種類 | 負責 |
|---|---|---|
| `:core` | 純 JVM | 領域型別、`valuate`、`stepAlerts`、`dueForFetch`、`HoldingDraft.parse`、`PortfolioCodec`。手機與手錶共用，有 JVM 測試 |
| `:quotes` | 純 JVM | `QuoteFetcher`：Yahoo spark、台灣 Yahoo 走勢圖服務與期貨頁、期交所 OpenAPI 的解析與路由。測試樣本在 `quotes\src\test\resources\fixtures\` |
| `:apk` | Android 函式庫 | 手機與手錶共用的自我更新：自己的版本與簽章、驗下載的 APK、PackageInstaller 裝自己 |
| `:phone` | Android | 儲存、`Portfolio`、`Refresher`、WorkManager、通知、Data Layer 發布、編輯畫面 |
| `:wear` | Wear OS | `WatchBoard`、Tile、錶面小格、手錶 App。不連網、不判斷提醒 |

手機與手錶同一個 applicationId `tw.personal.holdings`、同一把正式簽章（專案外 `..\signing\`，debug 也用它），Data Layer 與自動更新才通。

## 建置

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; $env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :core:test :quotes:test :phone:testDebugUnitTest assembleDebug assembleRelease
```

- 股票名單重產：`python tools/build_symbols.py`（連網，原始回應存 `tools\.raw\`）或加 `--offline`；改規則後 `--testdata` 重產測試期望檔。
  APK 只內建美股手工表 `tools\us_zh_names.csv`，完整美股名單由 App 自己下載（Nasdaq 條款，決策 V10）。

- **編譯產物不在專案資料夾裡**，在 `%USERPROFILE%\.holdings-build\<模組>\`（APK 在 `outputs\apk\debug\`，測試結果在 `test-results\`）。
  原因：專案路徑有中文，JVM 測試啟動器用系統碼頁 MS950 讀 Gradle 的 classpath 參數檔，中文路徑變亂碼，每個測試都 `ClassNotFoundException`。
  根目錄 `build.gradle.kts` 偵測到非 ASCII 路徑就自動改 build 目錄。不要刪那段。
- AGP 9.5.1 要 Gradle ≥ 9.6.0（wrapper 已設）。SDK 在 `%LOCALAPPDATA%\Android\Sdk`（platform 37、build-tools 37）。
- 新的 SDK 管理指令是 `cmdline-tools\latest\bin\android.exe`（`sdkmanager` 已標棄用）。

## 裝到裝置

- 兩台都開無線偵錯：`adb mdns services` 找 `adb-<序號>…_adb-tls-connect` 的 IP 與埠（重開或更新後埠會變），再 `adb connect <IP:埠>`。
- **連裝置前先斷 VPN**：VPN 若推了一條跟家裡區網同網段的路由，封包走錯會讓 adb 配對失敗或 offline。
- `adb devices` 每台會出現兩筆（手動連的＋mDNS 自動連的），下指令一律 `-s <IP:埠>`。
- 手錶的 debug 建置有範例資料廣播 `tw.personal.holdings.DEBUG_SAMPLE`，手機 App 還沒推資料前可以先看畫面。
  手錶自己寫進 Data Layer 的資料**不會**叫醒本機監聽，所以那個 receiver 寫完直接叫 `requestSurfaceUpdates`。
- 手機的 debug 建置有 `tw.personal.holdings.DEBUG_SEED`（灌 6 筆範例，可帶 `--es stop <價>` 驗提醒）與 `DEBUG_REFRESH`（跑一輪）。
  App 剛裝、還沒打開過時是 stopped 狀態，廣播要加 `--include-stopped-packages`；待機分組是 NEVER，背景工作拿不到額度，**要先打開 App 一次**。
- 清掉範例資料：兩台各跑 `adb -s <裝置> shell pm clear tw.personal.holdings`（也會清掉通知權限，打開 App 時再允許）。
- 手機一輪刷新的紀錄：`adb -s <手機> logcat -d -s HoldingsPhone:V`（抓了哪些、HTTP 狀態、發幾則通知、推到手錶）。
- 刷新工作要求有網路（`NetworkType.CONNECTED`）：Android 15 起，行程生命週期外連網會 `UnknownHostException`。
- 側載安裝時 Play Protect 給了 `HARMFUL` 判定（對側載 App 的分類），App 沒被擋；哪天被停用先查這個。
- 手錶充電時 adb 模擬的觸控無效（旋鈕可以）。手錶 App 可帶 `--es market US_EQUITY` 直接開到某一頁。

## 自動更新（v0.2 起）

- 版本只在 `gradle.properties` 的 `holdings.versionCode`／`holdings.versionName` 改，手機與手錶一起用；每次發版 versionCode 加 1。
- 發版：`python tools/release.py --notes "這版改了什麼"` → 產出在 `%USERPROFILE%\.holdings-build\release\<版本>\`（兩支 APK＋`update.json`），最後**只印出** `gh release create …`，自己看過再跑。
  Release 不能標 prerelease，App 讀的是 `releases/latest/download/update.json`。
- 純邏輯（清單解析、通知一次、橫幅、APK 驗證）在 `:core` 的 `Update.kt`；兩台共用的 Android 部分（自己的簽章、驗 APK、PackageInstaller）在 `:apk`。
- 手機：每天在 Wi-Fi 查一次（WorkManager `update-check-daily`）；按「更新」排 `update-apply`：先把手錶 APK 經 Data Layer 通道傳過去，再裝手機自己。
- 手錶：`UpdateListenerService` 收通道（`receiveFile`，讓 Play 服務寫檔，App 行程在背景會被凍結），驗證後 PackageInstaller 裝自己。
  通道事件的 URI 是 `wear:///<路徑>`（沒有 host），intent-filter 只能寫 scheme，寫 `host="*"` 收不到（實測）。
- **手錶要先用 adb 開一次安裝權限**，Wear OS 沒有「安裝不明應用程式」的設定頁（實測：設定頁的 intent 只開到設定首頁）：
  `adb -s <手錶> shell appops set tw.personal.holdings REQUEST_INSTALL_PACKAGES allow`。開了之後手錶不用按任何東西就會更新完（實測 0.2→0.2.1-test）。
- 手機第一次要使用者在系統設定允許「持股看板」安裝應用程式；App 會帶過去。
- Data Layer 傳檔實測約 150 KB/s：30 MB 的手錶 APK 要 3 分多鐘。
- debug 廣播（`-p tw.personal.holdings`）：`DEBUG_UPDATE_URL --es url http://127.0.0.1:8765/update.json`（不帶 url＝改回 GitHub）、
  `DEBUG_UPDATE_CHECK`（跟每天那次一樣會通知）、`DEBUG_UPDATE_APPLY`（等於按更新）；手錶 `DEBUG_INSTALL_FILE --es file t.apk --ei code <版本號>`（不經手機，裝 App 資料夾裡的 APK）。
  本機測試：`python tools/release.py --variant debug --version-code <比裝著的大> --version-name x "--download-base=" --out <資料夾>`，
  在那個資料夾 `python -m http.server 8765 --bind 127.0.0.1`，手機 `adb reverse tcp:8765 tcp:8765`。debug 建置才允許 127.0.0.1 明文。

## 資料來源（都非正式，失敗要顯示「讀不到」而不是錯的數字）

- Yahoo spark `query1.finance.yahoo.com/v7/finance/spark?symbols=…`：一個請求查多檔，台股 `.TW`／`.TWO`、美股、加密 `-USD`、匯率 `TWD=X`。
- 台灣 Yahoo 走勢圖服務 `tw.stock.yahoo.com/_td-stock/api/resource/FinanceChartService.ApacLibraCharts;symbols=[…];type=tick`：
  台指期、小台的近一／近二（`WTX&`、`WMT@`…），一個請求查多個合約，約 44 KB／合約。**沒有合約月份**，用結算日規則推算；
  夜盤的序列時間記在所屬交易日的時間軸上，報價時間用 `refreshedTs`。失敗時退回期貨頁 `tw.stock.yahoo.com/future/WTX&`（546 KB 網頁內嵌 JSON）。
- 期交所 OpenAPI `openapi.taifex.com.tw/v1/DailyMarketReportFut`：盤後，微台與個股期貨只靠它；資料日期會落後一天以上。
- 使用者不要高頻輪詢：背景約 15 分鐘一輪，收盤後補抓一次就停。

## 顏色

台灣習慣**漲紅跌綠**（正數紅、負數綠）。
