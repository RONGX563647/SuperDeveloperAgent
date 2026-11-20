#!/usr/bin/env python3
"""一次性迁移：会话归档从「每会话一个 manifest.json + 两份总结 md」收敛为 ledger JSONL。

- docs/process/<category>/<folder>/manifest.json → docs/ledger/<YYYY-MM>.jsonl（每行一份，
  内容原样，chain 不变——self 只哈希内容，路径搬家不影响链）
- docs/summary/**/*.md 里 schema: session-summary/v1 的散文副本删除（内容 100% 可由
  ledger 重建，views 生成物已取代）；session-plan/v1 的方案双份保留
- 最后刷新 docs/views/

用法：python tools/session-archive/migrate-to-ledger.py [--dry-run]
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "session-archive"))
import gate  # noqa: E402

PROCESS = ROOT / "docs" / "process"
LEDGER = ROOT / "docs" / "ledger"
SUMMARY = ROOT / "docs" / "summary"


def main() -> int:
    parser = argparse.ArgumentParser(prog="migrate-to-ledger")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    moved = deleted = 0

    # 1) manifests → ledger 行
    by_period: dict[str, list[dict]] = {}
    for path in sorted(PROCESS.glob("*/*/manifest.json")):
        row = json.loads(path.read_text(encoding="utf-8"))
        by_period.setdefault(row["createdAt"][:7], []).append(row)
        moved += 1
    for period, items in sorted(by_period.items()):
        items.sort(key=lambda m: (m.get("createdAt", ""), m.get("id", "")))
        target = LEDGER / f"{period}.jsonl"
        print(f"ledger/{period}.jsonl ← {len(items)} 份")
        if not args.dry_run:
            gate.write_ledger(target, items)

    # 2) 链自检（内容未变应零改动；有改动说明历史 manifest 本来就没对齐，顺手修）
    if not args.dry_run:
        for category in gate.CATEGORIES:
            resealed = gate.reseal_chain(category)
            if resealed:
                print(f"链重排（{category}）：{len(resealed)} 份")

    # 3) 删除 process/ 与 session-summary 散文副本（plan 双份保留）
    if not args.dry_run:
        shutil.rmtree(PROCESS)
    print(f"删除 docs/process/（{moved} 份 manifest 已入 ledger）")
    for path in sorted(SUMMARY.glob("**/*.md")):
        text = path.read_text(encoding="utf-8", errors="replace")
        if text.startswith("---\n") and "schema: session-summary/v1" in text.split("---", 2)[1]:
            if not args.dry_run:
                path.unlink()
            deleted += 1
    print(f"删除 {deleted} 份 session-summary 散文副本（views 已取代）")
    if not args.dry_run:
        for empty in sorted(SUMMARY.glob("*"), reverse=True):
            if empty.is_dir() and not any(empty.iterdir()):
                empty.rmdir()
        subprocess.run([sys.executable, str(ROOT / "tools" / "docs" / "build.py"), "sync"],
                       cwd=ROOT, check=True)
    print("完成；请运行 python tools/session-archive/gate.py validate 复核")
    return 0


if __name__ == "__main__":
    sys.exit(main())
