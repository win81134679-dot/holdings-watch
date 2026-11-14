"""產生手機 App 內建的股票名單（phone/src/main/assets/symbols/）。

    python tools/build_symbols.py            # 每個來源打一次，原始回應存在 tools/.raw/，再產生名單
    python tools/build_symbols.py --offline  # 不連網，用 tools/.raw/ 裡上次存的原始回應重產

APK 只內建手工表 us_zh_names.csv 裡的美股（Nasdaq 符號目錄的條款只允許個人非商業使用，不跟 APK 一起散布）；
完整美股名單由 App 第一次連上 Wi-Fi 時、之後每週自己下載。台股與加密是全份內建。

App 每週更新名單時用的是 phone 的 SymbolLists.kt，規則跟這支一樣；兩邊對同一份樣本的輸出由
phone 的 SymbolListsTest 比對 tools/testdata/ 的期望檔，改規則時兩邊一起改，再跑 --testdata 重產期望檔。
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import io
import json
import re
import sys
import unicodedata
import urllib.error
import urllib.request
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
OUT_DIR = ROOT / "phone" / "src" / "main" / "assets" / "symbols"
RAW_DIR = TOOLS / ".raw"
TESTDATA = TOOLS / "testdata"

SOURCES = {
    "twse_companies.json": "https://openapi.twse.com.tw/v1/opendata/t187ap03_L",
    "twse_day_all.json": "https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL",
    "tpex_companies.json": "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap03_O",
    "tpex_daily.json": "https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes",
    "nasdaqlisted.txt": "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt",
    "otherlisted.txt": "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt",
}
OPTIONAL = {"tpex_daily.json"}
BROWSER_UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

TW_CODE = re.compile(r"^[0-9]{4}[0-9A-Z]{0,2}$")
US_TICKER = re.compile(r"^[A-Z][A-Z0-9.\-]{0,9}$")
# 只留普通股、ADR 與 ETF：名稱裡出現這些字的是權證、單位、特別股、債券或權利。
US_EXCLUDE = re.compile(
    r"\b(warrants?|rights?|units?|preferred|preference|notes?|debentures?|depositary shares? representing|"
    r"subordinated|perpetual|trust preferred|when issued|contingent value)\b|%",
    re.IGNORECASE,
)
US_SUFFIX = re.compile(
    r"\s*[-,]?\s*(class [a-z] )?(common stock|capital stock|ordinary shares?|common shares?|american depositary shares?|"
    r"american depository shares?|shares of beneficial interest|beneficial interest)\b.*$",
    re.IGNORECASE,
)


def fetch(url: str) -> bytes:
    """一次請求；只有 403 才帶瀏覽器 User-Agent 再試一次。"""
    for headers in ({}, {"User-Agent": BROWSER_UA}):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code != 403 or headers:
                raise
            print(f"  403，帶 User-Agent 再試一次：{url}")
    raise AssertionError("unreachable")


def decode(raw: bytes) -> str:
    """TPEx 的回應可能是 UTF-8（含 BOM）也可能是 Big5；UTF-8 解不開才用 cp950。"""
    try:
        return raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        return raw.decode("cp950")


def clean(text: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", text or "").replace("\t", " ").split())


def volume(text: str) -> int:
    digits = (text or "").replace(",", "").strip()
    return int(digits) if digits.isdigit() else 0


def build_tw(twse_companies: str, twse_day_all: str, tpex_companies: str, tpex_daily: str | None) -> list[list[str]]:
    rows: dict[str, dict] = {}

    def put(code: str, name: str, english: str, board: str, vol: int) -> None:
        code = clean(code).upper()
        if not TW_CODE.match(code):
            return
        row = rows.setdefault(code, {"name": "", "english": "", "board": board, "volume": 0})
        row["name"] = row["name"] or clean(name)
        row["english"] = row["english"] or clean(english)
        row["volume"] = max(row["volume"], vol)

    for r in json.loads(twse_day_all):
        put(r.get("Code", ""), r.get("Name", ""), "", "TWSE", volume(r.get("TradeVolume", "")))
    for r in json.loads(twse_companies):
        put(r.get("公司代號", ""), r.get("公司簡稱", ""), r.get("英文簡稱", ""), "TWSE", 0)
    tpex_codes = set()
    for r in json.loads(tpex_companies):
        tpex_codes.add(clean(r.get("SecuritiesCompanyCode", "")).upper())
        put(r.get("SecuritiesCompanyCode", ""), r.get("CompanyAbbreviation", ""), r.get("Symbol", ""), "TPEX", 0)
    if tpex_daily is not None:
        for r in json.loads(tpex_daily):
            code = clean(r.get("SecuritiesCompanyCode", "")).upper()
            # 日行情也有可轉債（5 碼）等；只收公司名單裡有的與 00 開頭的 ETF。
            if code in tpex_codes or code.startswith("00"):
                put(code, r.get("CompanyName", ""), "", "TPEX", volume(r.get("TradingShares", "")))

    ordered = sorted(rows.items(), key=lambda kv: (-kv[1]["volume"], kv[0]))
    return [[code, r["name"] or code, r["english"], r["board"], str(r["volume"])] for code, r in ordered]


def us_name(security_name: str) -> str:
    name = US_SUFFIX.sub("", clean(security_name)).strip(" -,")
    half = name[: len(name) // 2]
    if len(name) % 2 == 1 and name == f"{half} {half}":
        name = half
    return name or clean(security_name)


def build_us(nasdaq: str, other: str, zh_names: list[tuple[str, str]]) -> list[list[str]]:
    rows: dict[str, dict] = {}

    def put(symbol: str, name: str, etf: str, test: str) -> None:
        symbol = clean(symbol).upper().replace(".", "-")
        if test.strip() == "Y" or not US_TICKER.match(symbol) or "$" in symbol:
            return
        if etf.strip() != "Y" and US_EXCLUDE.search(name):
            return
        rows.setdefault(symbol, {"name": us_name(name), "zh": "", "rank": ""})

    for line in nasdaq.splitlines()[1:]:
        f = line.split("|")
        if len(f) >= 8 and not line.startswith("File Creation Time"):
            put(f[0], f[1], f[6], f[3])
    for line in other.splitlines()[1:]:
        f = line.split("|")
        if len(f) >= 8 and not line.startswith("File Creation Time"):
            put(f[0], f[1], f[4], f[6])
    for i, (symbol, zh) in enumerate(zh_names, start=1):
        row = rows.get(symbol)
        if row is not None:
            row["zh"] = zh
            row["rank"] = str(i)

    def key(kv):
        r = kv[1]
        return (int(r["rank"]) if r["rank"] else 10**9, kv[0])

    return [[s, r["zh"], r["name"], r["rank"]] for s, r in sorted(rows.items(), key=key)]


def read_pairs(path: Path) -> list[tuple[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as f:
        return [(r[0].strip().upper(), r[1].strip()) for r in csv.reader(f) if r and not r[0].startswith("#") and r[0] != "symbol"]


def write_tsv(path: Path, header: str, rows: list[list[str]]) -> None:
    body = "".join("\t".join(r) + "\n" for r in rows)
    path.write_bytes((header + body).encode("utf-8"))


def generate(raw: dict[str, bytes], zh: list[tuple[str, str]], out: Path, stamp: str, bundle: bool = False) -> dict[str, int]:
    tpex_daily = decode(raw["tpex_daily.json"]) if "tpex_daily.json" in raw else None
    tw = build_tw(decode(raw["twse_companies.json"]), decode(raw["twse_day_all.json"]),
                  decode(raw["tpex_companies.json"]), tpex_daily)
    us = build_us(decode(raw["nasdaqlisted.txt"]), decode(raw["otherlisted.txt"]), zh)
    listed = {r[0] for r in us}
    missing = [s for s, _ in zh if s not in listed]
    if missing and stamp != "TEST":
        print(f"  us_zh_names.csv 裡這些代號不在 Nasdaq 名單（下市或改名？）：{', '.join(missing)}")
    if bundle:
        us = [r for r in us if r[3]]
    out.mkdir(parents=True, exist_ok=True)
    write_tsv(out / "tw.tsv", f"# generated {stamp}\n# code\tname\tenglish\tboard\tvolume\n", tw)
    write_tsv(out / "us.tsv", f"# generated {stamp}\n# symbol\tzh\tname\trank\n", us)
    return {"tw": len(tw), "us": len(us)}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", action="store_true", help="不連網，用 tools/.raw/ 的原始回應")
    ap.add_argument("--testdata", action="store_true", help="用 tools/testdata/ 的樣本重產給 JVM 測試比對的期望檔")
    args = ap.parse_args()

    zh = read_pairs(TOOLS / "us_zh_names.csv")
    if args.testdata:
        raw = {name: (TESTDATA / "raw" / name).read_bytes() for name in SOURCES if (TESTDATA / "raw" / name).exists()}
        print(generate(raw, zh, TESTDATA / "expected", "TEST"))
        return 0

    raw: dict[str, bytes] = {}
    RAW_DIR.mkdir(exist_ok=True)
    for name, url in SOURCES.items():
        path = RAW_DIR / name
        if args.offline:
            if path.exists():
                raw[name] = path.read_bytes()
            elif name not in OPTIONAL:
                print(f"缺原始檔 {path}，先不帶 --offline 跑一次", file=sys.stderr)
                return 1
            continue
        try:
            raw[name] = fetch(url)
        except (urllib.error.URLError, TimeoutError) as e:
            if name in OPTIONAL:
                print(f"  略過 {url}：{e}")
                continue
            print(f"抓不到 {url}：{e}", file=sys.stderr)
            return 1
        path.write_bytes(raw[name])
        print(f"  {len(raw[name]):>9,} B  {url}")

    counts = generate(raw, zh, OUT_DIR, dt.date.today().isoformat(), bundle=True)
    (OUT_DIR / "crypto.csv").write_bytes((TOOLS / "crypto.csv").read_bytes())
    for f in sorted(OUT_DIR.iterdir()):
        print(f"  {f.stat().st_size:>9,} B  {f.relative_to(ROOT)}")
    print(counts)
    return 0


if __name__ == "__main__":
    sys.exit(main())
