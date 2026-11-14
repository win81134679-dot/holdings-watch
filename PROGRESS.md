# 進度

## 2026-11-14 v0.2 進行中（接手先看這段）

決定在上一層 `持股看板-決策紀錄.md`〈v0.2 決定〉V1–V9；事實在上一層 `v0.2研究.md`。

- **已完成**：
  - 正式簽章金鑰在專案外 `..\signing\`（不進 repo，遺失就不能發更新）；debug／release 都用它簽。
  - 圖示 A「錶框K線」：手錶、手機（含通知小圖示）都已套用並裝上。
  - 搜尋與新增流程（unit A）：`phone\...\symbols\`、`tools\build_symbols.py`。美股只內建 174 檔手工表，完整名單 App 第一次連 Wi-Fi 時下載（決策 V10）。
  - 省電（unit B＋V11）：Yahoo 改 spark 一次多檔；期貨改用台灣 Yahoo 走勢圖服務 `FinanceChartService.ApacLibraCharts`（約 44 KB／合約，期貨頁留作後備）；
    期交所一天一次。每日 49.11 → **5.92 MB 內容／1.39 MB gzip**（`docs\battery.md`）。
  - 手機已裝上（2026-11-14 04:25，`adb install -r`，資料保留）：搜尋、新流程、新圖示。
  - 自動更新（unit C）：GitHub Releases 的 `update.json`、下載後驗大小／雜湊／套件名／版本／簽章、PackageInstaller；手機經 Data Layer 把 APK 傳給手錶，手錶自己裝。
    手錶經本機伺服器實測 0.2→0.2.1-test 全程不用按（之後已用 adb 降回 0.2）。手錶的安裝權限要用 adb 開一次（`CLAUDE.md`〈自動更新〉）。
- **還沒驗**：手機自我更新（要使用者先允許「安裝應用程式」）、手機畫面（不准截圖，等使用者看）、每週名單工作是否排進去、走勢圖服務在日盤與週一的時間位移（樣本是週六凌晨夜盤）。
- **公開**：repo `win81134679-dot/holdings-watch`，Release v0.2（2026-11-14，第一個公開版）。
- **0.2.1**：release 開 R8，APK 從約 30 MB 縮到約 3 MB（手錶更新從 3 分多鐘變幾十秒）。用它實測從 GitHub 上的一版更新到下一版。
- adb 埠會變：見 `CLAUDE.md`〈裝到裝置〉。

## 2026-11-14 第一版完成（v0.1，debug 安裝）

- 四個模組都做完並裝上真機：`:core`（51 個 JVM 測試）、`:quotes`（39 個）、`:phone`（16 個）、`:wear`（沒有單元測試，靠實機截圖）。
- 實機驗證過：手機一輪刷新（抓價、提醒、推送）、提醒通知只響一次、手錶顯示手機推來的資料、Tile 收到新資料自己更新、手錶總損益與手算一致。
- 範例資料已用 `pm clear` 清掉，手錶顯示「請在手機新增持股」。

## 等使用者

1. 打開手機 App 一次，允許通知、設為不受電池最佳化；新增真實持股。
2. 目視檢查手機的首頁、編輯頁（子 agent 不准截手機螢幕，沒看過畫面）。
3. 戴著手錶時試「點 Tile 開 App」（充電時 adb 觸控無效，沒驗到）；Tile 頂端白色圓點是否換成 App 圖示。
4. 錶面小格：自己在錶面編輯器加「今日損益 %」，或授權暫換一格來截圖。

## 下一步（還沒做）

- 交易日 9:00–13:30 實測台股 Yahoo 是即時還是延遲，決定 `:quotes` 台股標 LIVE 或 DELAYED。
- 背景每 15 分鐘那一輪實際有沒有網路：使用者打開過 App 之後看 `logcat -s HoldingsPhone:V` 有 `round start mode=BACKGROUND` 接 `http 200`。
- `:quotes` 的 `RoutedFetcher` 把來源丟的例外全收成 `Network`，解析錯誤分不出來；考慮改成 `Unparseable`。
- 持股 JSON 匯出匯入（手機重置會遺失）。
- `:wear` 加 junit，替 `pickSnapshot`、`Display.kt` 寫 JVM 測試。
- Tile 每一檔可點、直接開到該市場分頁。
