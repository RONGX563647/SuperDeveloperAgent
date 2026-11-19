"""关闭 FEAT-4：两项需求都已完成，写入证据。

FEAT-4 原文：
  1. 新建任务标题需要带任务编码，git 提交和存档使用此编码作为标题；
  2. 任务 bug/feat 需要选择版本，默认根据当前工作区的测试环境的版本号选择或手动指定。
"""

import sqlite3
import sys
import time

DB = r"data/workspaces/_platform/platform.db"
TASK = "task-b19674d242"

NOTE = """## 完成记录

### 1. 标题带任务编码（已完成）

- 存储层（028d277）：`SqliteGitIssueStore.save` 创建时把分配到的编码写进标题
  （`withCodePrefix`），读回与返回值都带；更新路径不动标题，避免叠前缀。
  幂等：已写「BUG-7 」「BUG-7: 」不重复加，「BUG-7xx」这种仍补真编码。
- 提交信息与存档（批次 12）：`gate.py start --task-code BUG-12` 让归档目录名与
  归档标题都带编码，于是「标题 / 提交 / 存档」三处对得上同一个编号。
- 复核：`GitIssueTitleCodeTest` 5 例。

### 2. 任务需选择版本（已完成）

- 后端（ff496d1）：`GET /git-issues/versions` 返回当前分支、按版本线推导的建议版本
  与仓库标签候选；`PUT` 改为按 key 局部更新，只改版本不会清空标题与标签。
- 界面（同批）：任务编辑器版本号下拉取自该接口，默认值按当前分支版本线推导，
  可手输；并显示「当前分支 xxx」说明默认值来源。
- 复核：`GitIssueControllerFieldContractTest` 8 例 + `GitIssueVersionSuggestionTest` 2 例。

### 说明

版本候选取自「测试环境的版本线」这一点，是按当前分支（`0.1.0-snapshot` 这类版本线分支）
推导实现的；若将来需要严格区分「工作区测试环境的版本」与「分支版本」，可在
`GitIssueServiceImpl.suggestVersion` 里按 env 再分一层，接口形状不用变。
"""


def main() -> int:
    con = sqlite3.connect(DB)
    cur = con.cursor()
    row = cur.execute("select body from git_issues where id = ?", (TASK,)).fetchone()
    if row is None:
        print("任务不存在", file=sys.stderr)
        return 1
    body = (row[0] or "").rstrip()
    if "## 完成记录" not in body:
        body = (body + "\n\n" + NOTE).strip() if body else NOTE.strip()
    cur.execute("update git_issues set state='CLOSED', body=?, updated_at=? where id=?",
                (body, int(time.time() * 1000), TASK))
    con.commit()
    print("已关闭 FEAT-4（task-b19674d242）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
