#!/usr/bin/env python3
"""逐条查看任务看板：编码、优先级、分类、状态、标题、版本与描述摘要。

和 verify.py 的分工：verify 只回答「结构对不对」，dump 用来看「内容是什么」。
复核重排结果、或排查"这条到底标成什么了"时用它。

用法：python tools/task-board/dump.py [db 路径] [--full]
  --full 时不截断标题与描述
"""
from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

DEFAULT_DB = Path("data/workspaces/_platform/platform.db")


def main(argv: list[str]) -> int:
    args = [item for item in argv[1:] if not item.startswith("--")]
    full = "--full" in argv
    db = Path(args[0]) if args else DEFAULT_DB
    if not db.is_file():
        print(f"库文件不存在：{db}", file=sys.stderr)
        return 1

    con = sqlite3.connect(f"file:{db.as_posix()}?mode=ro", uri=True)
    rows = list(con.execute(
        "select id, code, kind, priority, state, title, body, version from git_issues "
        "order by kind, priority, code"))
    print(f"total {len(rows)}")
    for row in rows:
        body = (row[6] or "").replace("\n", " ")
        title = row[5] or ""
        if not full:
            body = body[:60]
            title = title[:30]
        version = row[7] if row[7] else "-"
        print(f"{row[1]:>8} {row[3]} {row[2]:<4} {row[4]:<6} {title:<32} v={version} "
              f"| {body}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
