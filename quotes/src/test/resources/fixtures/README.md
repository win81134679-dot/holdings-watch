# 測試樣本（2026-11-14 凌晨，週六，各抓一次）

| 檔 | 來源 | 說明 |
|---|---|---|
| `yahoo_chart_*.json` | `https://query1.finance.yahoo.com/v8/finance/chart/<代號>?range=1d&interval=1m` | 台股上市 2330.TW、上櫃 6488.TWO、美股 AAPL、加密 BTC-USD、匯率 TWD=X。`chart.result[0].meta` 有 `currency`、`symbol`、`regularMarketPrice`、`chartPreviousClose`、`regularMarketTime`（epoch 秒）、`regularMarketDayHigh/Low`；`timestamp[]` 與 `indicators.quote[0].low/high/close[]` 是 1 分鐘序列（有 null）。台股的 `regularMarketTime` 是 11/13 13:30 收盤。 |
| `yahoo_chart_NOTFOUND.json` | 同上，代號 `ZZZZ9999.TW` | HTTP 404，`chart.error.code = "Not Found"`。 |
| `yahoo_tw_future_WTX&.html` | `https://tw.stock.yahoo.com/future/WTX&` | 台指期近一，夜盤 01:41 抓。報價在頁面內嵌 JSON：找 `"symbol":"WTX&","symbolName":"台指期近一"`，同一個物件**前面**有 `price.raw`、`change.raw`、`changePercent`、`regularMarketPreviousClose.raw`、`regularMarketOpen/DayHigh/DayLow.raw`、`regularMarketTime`（ISO、UTC）；後面有 `currency`、`exchangeDataDelayedBy`。**頁面上沒有合約月份欄位**：近一是哪個月要用最後交易日規則推算。原頁 546 KB；為了公開 repo，用 Python 照 `parseFuturePage` 的找法只留下四個合約的報價物件（約 13 KB），網頁其他內容拿掉了。 |
| `yahoo_tw_future_WMT@.html` | `https://tw.stock.yahoo.com/future/WMT@` | 小型台指近二。同樣縮過。 |
| `taifex_DailyMarketReportFut.json` | `https://openapi.taifex.com.tw/v1/DailyMarketReportFut` | 期交所盤後，陣列 2284 列。欄位 `Date`（`20261112`）、`Contract`（`TX`、`MTX`、`TMF`、個股期如 `CDF`）、`ContractMonth(Week)`（`202611`；週契約另有格式）、`Last`、`SettlementPrice`（夜盤列是字串 `NULL`）、`TradingSession`（`一般` 或 `盤後`）。檔頭可能有 UTF-8 BOM。**資料日期落後**：週六凌晨抓到的是 11/12，不是 11/13。 |

## v0.2 補的樣本（2026-11-14 03:44–03:50，週六，各抓一次）

| 檔 | 來源 | 說明 |
|---|---|---|
| `yahoo_spark_1m.json` | `https://query1.finance.yahoo.com/v7/finance/spark?symbols=2330.TW,6488.TWO,AAPL,BTC-USD,TWD=X&range=1d&interval=1m` | 現在用的端點，一個請求 5 檔。`spark.result[]` 每檔 `{symbol, response:[{meta, timestamp[], indicators.quote[0].close[]}]}`；`meta` 跟 chart 一樣有 `currency`、`symbol`、`regularMarketPrice`、`chartPreviousClose`、`regularMarketTime`、`regularMarketDayLow/High`。**只有收盤序列，沒有每根 K 的高低**；數字是乾淨的十進位（沒有 chart 那種 float32 尾數）。**回應順序不照請求**（AAPL 排第一）。不用 cookie／crumb。內容 58,126 B，伺服器 gzip 後 12,284 B。BTC 的序列低點 67881.53 比 `meta.regularMarketDayLow` 68052.44 還低：加密與匯率的 meta 當日高低跟序列不是同一段時間。 |
| `yahoo_spark_5m.json` | 同上，`interval=5m` | 給 `DailyTrafficTest` 比較 1 分與 5 分的流量。內容 17,717 B，gzip 3,844 B。 |
| `yahoo_spark_unknown.json` | 同上，`symbols=2330.TW,ZZZZ9999.TW&interval=5m` | 不存在的代號**直接從結果裡消失**，HTTP 200、`error: null`，其他代號照常回。 |

`yahoo_chart_*.json` 已不是程式用的來源，留著當 v0.1 的基準與「1 分 K 高低」的真值（`CloseSeriesMissTest`）。
期貨頁的兩個 `.html` 各自內嵌 `WTX&`、`WTX@`、`WMT&`、`WMT@` 四個代號的完整報價物件（成交時間都在抓取前幾分鐘內），所以一頁就夠四個合約用。

## 期貨走勢圖服務（2026-11-14 04:21，週六凌晨夜盤中，抓一次）

| 檔 | 來源 | 說明 |
|---|---|---|
| `yahoo_tw_libra_tick.json` | `https://tw.stock.yahoo.com/_td-stock/api/resource/FinanceChartService.ApacLibraCharts;symbols=%5B%22WTX%26%22%2C%22WTX%40%22%2C%22WMT%26%22%2C%22WMT%40%22%5D;type=tick` | 現在期貨用的端點。頂層是陣列，每個合約 `{symbol, chart:{meta, timestamp[1140], indicators.quote[0]{open,high,low,close,volume}, quote}}`。`chart.quote` 的數字是字串（`"43624.00"`），`refreshedTs` 是實際時間（`2026-11-13T20:20:23Z`）；`meta.regularMarketTime` 與 `timestamp` 是所屬交易日的時間軸，**週五夜盤被記成週日**（比實際晚 172,800 秒）。還沒到的分鐘是 null。176,441 B，gzip 約 38 KB。 |
