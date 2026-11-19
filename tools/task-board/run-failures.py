"""最近失败运行的失败原因汇总（只读）：按错误码/阶段归类，找出真正的模式。

用法：python tools/task-board/run-failures.py [limit]
数据源：data/workspaces/_history/*.yml（每次运行一份）。
"""
from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path

HISTORY = Path("data/workspaces/_history")


def main(argv: list[str]) -> int:
    limit = int(argv[1]) if len(argv) > 1 else 40
    files = sorted(HISTORY.glob("*.yml"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not files:
        print("没有运行历史")
        return 1
    codes: Counter[str] = Counter()
    stages: Counter[str] = Counter()
    examples: dict[str, str] = {}
    failed = 0
    for path in files[:limit]:
        text = path.read_text(encoding="utf-8", errors="replace")
        if not re.search(r"^status:\s*FAILED", text, re.M) and "FAILED" not in text[:2000]:
            continue
        failed += 1
        for match in re.finditer(r"errorCode:\s*([A-Z]-\d{3})", text):
            code = match.group(1)
            codes[code] += 1
        for match in re.finditer(r"stageId:\s*([\w-]+)", text):
            stages[match.group(1)] += 1
        for match in re.finditer(r"errorSummary:\s*(.+)", text):
            summary = match.group(1).strip()[:110]
            for code_match in re.finditer(r"([A-Z]-\d{3})", summary):
                examples.setdefault(code_match.group(1), summary)
    print(f"扫描 {min(limit, len(files))} 份历史，其中失败 {failed} 份\n")
    print("按错误码：")
    for code, count in codes.most_common(12):
        print(f"  {code}  x{count}  {examples.get(code, '')}")
    print("\n按阶段：")
    for stage, count in stages.most_common(12):
        print(f"  {stage}  x{count}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
