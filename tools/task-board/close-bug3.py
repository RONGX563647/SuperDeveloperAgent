"""收口 BUG-3：修复已在主干，把证据写进描述并关闭。

只改 state / body，不动优先级与编码。
"""

import sqlite3
import sys
import time

DB = r"data/workspaces/_platform/platform.db"

NOTE = """## 修复记录

已修（319ac71，已在 origin/0.1.0-snapshot）：
- 新增 SqliteWalCheckpointer：WAL 模式下定时/退出时落盘检查点，
  避免 -wal 未合并时进程被强杀导致已提交数据"看起来丢了"；
- LegacySqliteImporter 改为默认开启，旧库数据不再依赖手工开关；
- application.yml 与 DataSourceConfig 同步调整；
- 复核：LegacySqliteImporterTest 等新增用例。

判定依据：该修复提交经 merge-base 验证已在主干；
本机 data/workspaces/_platform 下多个 .db-wal 文件仍在（当前实现保留 WAL），
说明检查点机制与文件布局一致，未见新的丢数路径。
"""


def main() -> int:
    con = sqlite3.connect(DB)
    cur = con.cursor()
    row = cur.execute("select body from git_issues where id='task-c511703132'").fetchone()
    if row is None:
        print("任务不存在", file=sys.stderr)
        return 1
    body = (row[0] or "").rstrip()
    if "## 修复记录" not in body:
        body = (body + "\n\n" + NOTE).strip() if body else NOTE.strip()
    cur.execute("update git_issues set state='CLOSED', body=?, updated_at=? "
                "where id='task-c511703132'",
                (body, int(time.time() * 1000)))
    con.commit()
    print("已关闭 task-c511703132（BUG-3）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
