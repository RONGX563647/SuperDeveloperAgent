"""查看失败运行里的错误原文（只读）：按错误码打印上下文，确认「为什么失败」。

和 run-failures.py 的分工：那个只统计频次，这个看具体是哪一句话。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

HISTORY = Path("data/workspaces/_history")


def main(argv: list[str]) -> int:
    wanted = argv[1] if len(argv) > 1 else "E-501"
    limit = int(argv[2]) if len(argv) > 2 else 8
    files = sorted(HISTORY.glob("*.yml"), key=lambda p: p.stat().st_mtime, reverse=True)
    shown = 0
    for path in files:
        text = path.read_text(encoding="utf-8", errors="replace")
        if wanted not in text:
            continue
        print("=" * 74)
        print(path.name)
        # 打印每个命中行的前后各一行，足够看清原因
        lines = text.splitlines()
        for index, line in enumerate(lines):
            if wanted in line:
                start = max(0, index - 1)
                for local in lines[start:index + 2]:
                    print("  " + local.strip()[:180])
                print("  ---")
        shown += 1
        if shown >= limit:
            break
    if shown == 0:
        print(f"没有含 {wanted} 的历史")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
