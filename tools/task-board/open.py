"""任务看板全景：按状态与优先级分组，直接看「还剩什么没做」。

只读。和 dump.py 的区别：dump 是平铺全量，这个按「该先看哪一列」排好，
用于每天开工时确认优先队列。
"""
from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

DEFAULT_DB = Path("data/workspaces/_platform/platform.db")


def main(argv: list[str]) -> int:
    db = Path(argv[1]) if len(argv) > 1 else DEFAULT_DB
    if not db.is_file():
        print(f"库文件不存在：{db}", file=sys.stderr)
        return 1
    con = sqlite3.connect(f"file:{db.as_posix()}?mode=ro", uri=True)
    cur = con.cursor()

    total = cur.execute("select count(*) from git_issues").fetchone()[0]
    opened = cur.execute(
        "select count(*) from git_issues where state='OPEN'").fetchone()[0]
    print(f"共 {total} 条，其中 OPEN {opened} 条\n")

    for kind in ("BUG", "FEAT"):
        for priority in ("P0", "P1", "P2"):
            rows = list(cur.execute(
                "select code, title, version from git_issues "
                "where kind=? and priority=? and state='OPEN' order by code",
                (kind, priority)))
            if not rows:
                continue
            print(f"[{kind} {priority}] {len(rows)} 条待办")
            for code, title, version in rows:
                tag = f"  v{version}" if version else ""
                print(f"  {code:<8} {title[:38]:<40}{tag}")
            print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
