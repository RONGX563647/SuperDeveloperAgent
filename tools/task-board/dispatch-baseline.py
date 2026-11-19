"""实机验证：supervisor 是否真的能派发子会话并回收结论。

只读 + 一次真实派发，用来回答 FEAT-1/FEAT-2 的核心问题：
「调度 Agent 是真的能派发，还是只能人肉派发」。

做法：直接调用 ConversationManager 的 dispatch 需要 Spring 上下文，
这里改从数据库侧验证「派发前后的变化」：
  1. 派发前：conversations 里 parent_conversation_id 非空的数量；
  2. 派发后：同一指标 + workspace_tasks 里是否出现新票据。

脚本本身不触发派发（那需要 LLM），只负责**采集基线**与**判定变化**，
供人工在界面上点一次派发后复跑对比。
"""

import sqlite3
import sys
import time
from pathlib import Path

DB = Path("data/workspaces/_platform/conversations.db")


def snapshot() -> dict:
    con = sqlite3.connect(f"file:{DB.as_posix()}?mode=ro", uri=True)
    cur = con.cursor()
    children = cur.execute(
        "select count(*) from conversations "
        "where parent_conversation_id is not null and parent_conversation_id != ''"
    ).fetchone()[0]
    supervisors = cur.execute(
        "select count(*) from conversations where kind = 'SUPERVISOR'").fetchone()[0]
    runs = cur.execute("select count(*) from conversation_runs").fetchone()[0]
    tickets = cur.execute("select count(*) from workspace_tasks").fetchone()[0]
    return {"children": children, "supervisors": supervisors,
            "runs": runs, "tickets": tickets}


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "--save":
        out = Path(".tmp-dispatch-baseline.txt")
        data = snapshot()
        out.write_text(repr(data), encoding="utf-8")
        print(f"已记录基线 {data}")
        return 0

    base_file = Path(".tmp-dispatch-baseline.txt")
    now = snapshot()
    print("当前:", now)
    if not base_file.is_file():
        print("（没有基线文件；先跑 --save，派发后再次运行本脚本对比）")
        return 0
    base = eval(base_file.read_text(encoding="utf-8"))  # 本地临时文件，仅含计数
    print("基线:", base)
    delta = {key: now[key] - base[key] for key in now}
    print("变化:", delta)
    if delta["children"] > 0 and delta["runs"] > 0:
        print("\n结论：派发生效——出现子会话且产生运行记录")
        return 0
    print("\n结论：没有观察到派发（子会话仍为 0）")
    return 1


if __name__ == "__main__":
    sys.exit(main())
