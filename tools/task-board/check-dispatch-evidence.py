"""查会话库里有没有真实的子会话/派发记录（只读）。

用途：判断 FEAT-1/2「super agent 编排」是否已有实机证据，
而不是只靠单元测试证明机制存在。
"""

import sqlite3
import sys
from pathlib import Path

DB = Path("data/workspaces/_platform/conversations.db")


def main() -> int:
    if not DB.is_file():
        print(f"库不存在：{DB}", file=sys.stderr)
        return 1
    con = sqlite3.connect(f"file:{DB.as_posix()}?mode=ro", uri=True)
    cur = con.cursor()
    tables = [row[0] for row in cur.execute(
        "select name from sqlite_master where type='table'")]
    print("表:", ", ".join(sorted(tables)))

    for table in ("conversations", "conversation_runs", "runs"):
        if table not in tables:
            continue
        total = cur.execute(f"select count(*) from {table}").fetchone()[0]
        print(f"{table}: {total} 行")

    if "conversations" in tables:
        # kind 区分 NORMAL / SUPERVISOR；parent 字段能看出子会话归属
        cols = [c[1] for c in cur.execute("pragma table_info(conversations)")]
        print("conversations 列:", ", ".join(cols))
        if "kind" in cols:
            for kind, n in cur.execute(
                    "select kind, count(*) from conversations group by kind"):
                print(f"  kind={kind}: {n}")
        if "parent_conversation_id" in cols:
            children = cur.execute(
                "select count(*) from conversations where parent_conversation_id is not null "
                "and parent_conversation_id != ''").fetchone()[0]
            print(f"  有父会话的子会话: {children}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
