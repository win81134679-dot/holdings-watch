# 測試樣本

`nasdaqlisted.txt`、`otherlisted.txt` 是照 Nasdaq Trader 符號目錄的欄位格式**手寫的**樣本（不是下載的檔），
涵蓋普通股、ETF、名稱重複的 ADR、測試代號、權證、單位、權利、特別股、`$` 與 `.` 代號這幾種情況。
其餘四個 `*.json` 是證交所、櫃買中心 OpenAPI 回應（政府資料開放授權）的節錄。

改了 `build_symbols.py` 或 `SymbolLists.kt` 的規則：`python tools/build_symbols.py --testdata` 重產 `../expected/`，
再跑 phone 的 `SymbolListsTest`。
