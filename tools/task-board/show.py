"""按编码/标题关键词查看某条任务的完整内容（标题、描述、版本、标签）。

用法：python tools/task-board/show.py BUG-17
"""
from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

DEFAULT_DB = Path("data/workspaces/_platform/platform.db")


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("用法：show.py <编码或 id 片段>", file=sys.stderr)
        return 2
    needle = argv[1].strip()
    db = Path(argv[2]) if len(argv) > 2 else DEFAULT_DB
    con = sqlite3.connect(f"file:{db.as_posix()}?mode=ro", uri=True)
    rows = list(con.execute(
        "select id, code, kind, priority, state, title, body, version, labels, "
        "sync_status, created_at, updated_at from git_issues "
        "where code = ? or id = ? or id like ? or title like ?",
        (needle.upper(), needle, f"%{needle}%", f"%{needle}%")))
    if not rows:
        print("没有匹配的任务")
        return 1
    for row in rows:
        print("=" * 70)
        print(f"{row[1]}  [{row[2]}/{row[3]}/{row[4]}]  id={row[0]}")
        print(f"标题：{row[5]}")
        print(f"版本：{row[7] or '-'}   标签：{row[8] or '-'}   同步：{row[9]}")
        print("-" * 70)
        print(row[6] or "（无描述）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
