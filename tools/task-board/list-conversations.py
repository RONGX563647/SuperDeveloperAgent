"""列出会话与运行记录（只读）：确认调度会话是否真的派发过子任务。"""

import sqlite3
import sys
from pathlib import Path

DB = Path("data/workspaces/_platform/conversations.db")


def main() -> int:
    con = sqlite3.connect(f"file:{DB.as_posix()}?mode=ro", uri=True)
    cur = con.cursor()

    print("=== conversations ===")
    rows = list(cur.execute(
        "select id, title, kind, status, parent_conversation_id, created_at "
        "from conversations order by created_at desc limit 12"))
    for cid, title, kind, status, parent, created in rows:
        print(f"{cid[:20]:22} {kind:10} {status:12} parent={parent or '-':22} {title[:26]}")

    print("\n=== conversation_runs ===")
    try:
        runs = list(cur.execute(
            "select id, conversation_id, status, substr(goal,1,40) "
            "from conversation_runs order by created_at desc limit 10"))
        for rid, cid, status, goal in runs:
            print(f"{rid[:16]:18} conv={cid[:18]:20} {status:12} {goal}")
        if not runs:
            print("（没有运行记录）")
    except sqlite3.Error as error:
        print(f"读取失败：{error}")

    print("\n=== workspace_tasks（派发票据）===")
    try:
        tasks = list(cur.execute(
            "select id, status, substr(goal,1,40) from workspace_tasks "
            "order by created_at desc limit 10"))
        for tid, status, goal in tasks:
            print(f"{tid[:18]:20} {status:12} {goal}")
        if not tasks:
            print("（没有票据：说明从未真正派发过）")
    except sqlite3.Error as error:
        print(f"读取失败：{error}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
