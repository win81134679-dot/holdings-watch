"""Build both APKs, write update.json, and print (never run) the `gh release create` command.

    python tools/release.py --notes "這版改了什麼"
    python tools/release.py --skip-build            # reuse the APKs already built

Version comes from gradle.properties (holdings.versionCode / holdings.versionName); bump it there first.
Output: %USERPROFILE%\\.holdings-build\\release\\<versionName>\\ (APKs renamed, update.json, notes.md).

For a local update test, --variant debug --version-code 3 --version-name 0.2.1-test --download-base ""
builds a higher-numbered debug release whose update.json uses relative URLs (served from the same folder).

Signing is done by Gradle from ..\\signing\\keystore.properties; this script never opens that file.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = "win81134679-dot/holdings-watch"
MANIFEST_FORMAT = 1


def read_version(props: Path) -> tuple[int, str]:
    text = props.read_text(encoding="utf-8")
    code = re.search(r"^holdings\.versionCode=(\d+)\s*$", text, re.M)
    name = re.search(r"^holdings\.versionName=(\S+)\s*$", text, re.M)
    if not code or not name:
        sys.exit("gradle.properties 沒有 holdings.versionCode / holdings.versionName")
    return int(code.group(1)), name.group(1)


def build_dir(module: str) -> Path:
    # Root build.gradle.kts moves build output out of non-ASCII project paths.
    if any(ord(c) > 127 for c in str(ROOT)):
        return Path.home() / ".holdings-build" / module
    return ROOT / module / "build"


def gradle_env() -> dict[str, str]:
    env = dict(os.environ)
    env.setdefault("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr")
    env.setdefault("ANDROID_HOME", str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"))
    return env


def run_gradle(variant: str, overrides: list[str]) -> None:
    task = "assembleRelease" if variant == "release" else "assembleDebug"
    cmd = [str(ROOT / "gradlew.bat"), f":phone:{task}", f":wear:{task}", "-q", *overrides]
    print("執行：", " ".join(cmd[1:]))
    proc = subprocess.run(cmd, cwd=ROOT, env=gradle_env(), capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        tail = "\n".join((proc.stdout + proc.stderr).splitlines()[-40:])
        sys.exit(f"Gradle 失敗（結束碼 {proc.returncode}）：\n{tail}")


def built_apk(module: str, variant: str) -> Path:
    folder = build_dir(module) / "outputs" / "apk" / variant
    signed = folder / f"{module}-{variant}.apk"
    if signed.is_file():
        return signed
    if (folder / f"{module}-{variant}-unsigned.apk").is_file():
        sys.exit(f"{module} 的 {variant} APK 沒有簽章：找不到 ..\\signing\\keystore.properties？")
    sys.exit(f"找不到 {signed}；先不加 --skip-build 跑一次")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def asset(path: Path, base: str) -> dict:
    return {"url": base + path.name, "sha256": sha256(path), "size": path.stat().st_size}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--notes", default="", help="這版的說明，App 的更新通知與橫幅會顯示")
    ap.add_argument("--skip-build", action="store_true")
    ap.add_argument("--variant", choices=["release", "debug"], default="release")
    ap.add_argument("--version-code", type=int, help="只給本機測試：蓋過 gradle.properties")
    ap.add_argument("--version-name", help="只給本機測試：蓋過 gradle.properties")
    ap.add_argument("--download-base", help='APK 網址前綴；預設是這版 GitHub Release 的下載網址，"" 表示相對網址')
    ap.add_argument("--out", type=Path, help="輸出資料夾，預設 %%USERPROFILE%%\\.holdings-build\\release\\<版本>")
    args = ap.parse_args()

    code, name = read_version(ROOT / "gradle.properties")
    overrides = []
    if args.version_code is not None:
        code = args.version_code
        overrides.append(f"-Pholdings.versionCode={code}")
    if args.version_name:
        name = args.version_name
        overrides.append(f"-Pholdings.versionName={name}")

    if not args.skip_build:
        run_gradle(args.variant, overrides)

    out = args.out or Path.home() / ".holdings-build" / "release" / name
    out.mkdir(parents=True, exist_ok=True)
    phone = out / f"holdings-phone-{name}.apk"
    wear = out / f"holdings-wear-{name}.apk"
    shutil.copyfile(built_apk("phone", args.variant), phone)
    shutil.copyfile(built_apk("wear", args.variant), wear)

    tag = f"v{name}"
    base = f"https://github.com/{REPO}/releases/download/{tag}/" if args.download_base is None else args.download_base
    manifest = {
        "format": MANIFEST_FORMAT,
        "versionCode": code,
        "versionName": name,
        "notes": args.notes,
        "phone": asset(phone, base),
        "wear": asset(wear, base),
    }
    manifest_path = out / "update.json"
    manifest_path.write_bytes((json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    notes_path = out / "notes.md"
    notes_path.write_bytes(
        (
            f"{args.notes or '持股看板 ' + name}\n\n"
            f"- 手機：{phone.name}（sha256 {manifest['phone']['sha256']}）\n"
            f"- 手錶：{wear.name}（sha256 {manifest['wear']['sha256']}）\n"
        ).encode("utf-8")
    )

    print(f"版本 {name}（versionCode {code}，{args.variant}）")
    for p in (phone, wear, manifest_path):
        print(f"  {p}  {p.stat().st_size:,} bytes")
    print("\n確認沒問題後，自己執行（這個腳本不會發佈）：")
    print(
        f'gh release create {tag} --repo {REPO} --title "持股看板 {name}" --notes-file "{notes_path}" '
        f'"{phone}" "{wear}" "{manifest_path}"'
    )


if __name__ == "__main__":
    main()
