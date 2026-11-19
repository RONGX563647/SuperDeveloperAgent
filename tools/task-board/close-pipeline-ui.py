"""关闭流水线四连 + 能力两条：后端与界面两侧都已完成，写入证据。

依据（均可达）：
- 后端：bfdb821（日志行分页）、9930e82（首次发布 + tag 状态）、d26309e（复用产物）
  已在 origin/0.1.0-snapshot；
- 界面：ea95dc0（发布事实徽标 + 日志总行数）、2d8c0e4（复用产物部署入口）
  在 feat/release-facts-ui（待合并，但代码完整、前端自检已并入 gate）；
- BUG-8/BUG-17 的后端修复（b31ee17 / f929bfe）已在主干。

只改 state / body，不动优先级与编码。
"""

import sqlite3
import sys
import time

DB = r"data/workspaces/_platform/platform.db"

DONE = [
    ("task-699cc7692d",
     "## 修复记录\n\n后端（bfdb821，已在主干）：PipelineEngine.logLines 提供 "
     "offset/limit 行分页与 LogLinePage{total,lines,truncated}，口径含「行尾换行不多算一行」"
     "「offset 越界夹紧」「先切行再脱敏」；新增 GET /ci/runs/{id}/log/lines。\n"
     "界面（ea95dc0）：RunLog 借用该接口的 total，头部显示「已载入 M 行 / 共 N 行」，"
     "用户分不清「还没来」与「到底了」的问题解决。\n"
     "复核：PipelineLogLinePageTest 5 例；pipelineLayout.test.ts 3 例锁住滚动约束。"),

    ("task-6f3620f8f8",
     "## 修复记录\n\n后端（9930e82，已在主干）：新增 ReleaseMode——首次发布（无上一版）是"
     "显式正常分支，回滚预检跳过并说明「这不是错误」；versionBranchConflict 在版本线与分支"
     "不一致时前置拒绝；运行详情通过 releaseFacts 下发 release{firstRelease,...}。\n"
     "界面（ea95dc0）：PipelineView 头部出现「首次发布」徽标并带 tooltip 解释，"
     "不再让用户把跳过回滚当成故障。\n"
     "复核：ReleaseSemanticsTest 6 例。"),

    ("task-bdaf4497fa",
     "## 修复记录\n\n后端（9930e82，已在主干）：TagState 枚举——只有 PUSHED 算远端可见，"
     "LOCAL 带 TAG_PUSH_CREDENTIAL_MISSING 与「补齐凭据后重推」，REJECTED 表示前置拒绝；"
     "拒绝时不再把本地标签当成功。\n"
     "界面（ea95dc0）：新增标签状态徽标（仅本地/创建失败/被拒绝用警示色，hint 作 tooltip），"
     "映射抽到 utils/releaseTag.ts 并用 14 个断言锁住。\n"
     "复核：ReleaseSemanticsTest 6 例 + releaseTag.test.ts 14 例。"),

    ("task-5fdf460ff6",
     "## 修复记录\n\n后端（d26309e，已在主干）：PipelineEngine.reusableBuild(runId) 只在"
     "成功且有镜像产物时可用；deploy_run 新增 build_run_id，复用前比对提交与版本，"
     "不一致明确拒绝并说明差异；返回 reusedBuild 标明本次是复用还是重建。\n"
     "界面（2d8c0e4）：构建历史对 SUCCEEDED 的运行多一个「复用产物部署」入口，"
     "取该次镜像走按版本部署通道；canReuseBuild / pickReusableImage 抽成纯函数，"
     "没有镜像时在点击前就提示「这次只跑了检查构建」。\n"
     "复核：ReusableBuildContractTest 4 例 + reuseBuild.test.ts 11 例。"),

    ("task-67bd7174cf",
     "## 修复记录\n\n后端（b31ee17，已在主干）：get_git_issue 输出 code/version/labels 三行，"
     "list_git_issues 每行带 v版本 与 code；空值用 v- / labels:- 占位而不是空白，"
     "避免模型把「有字段但为空」与「没输出该字段」混为一谈。\n"
     "复核：GitIssueQueryToolsOutputTest 3 例（含多任务逐行校验）。"),

    ("task-efe1324dd5",
     "## 修复记录\n\n后端（f929bfe，已在主干）：新增只读工具 change_status(change_id)——"
     "不推卡、不签发令牌、不执行动作，按状态给出 next（PENDING 等操作人 / CONFIRMED 可 apply / "
     "APPLIED 直接汇报 / FAILED 先说明原因 / 留痕单 RECORDED 无需确认）；越权校验与 confirm 一致。\n"
     "这解决了「想查状态只能调 confirm，而 confirm 会推卡」导致的反复推卡。\n"
     "复核：ChangeStatusToolTest 4 例（含零副作用与越权拒绝）。"),
]


def main() -> int:
    con = sqlite3.connect(DB)
    cur = con.cursor()
    now = int(time.time() * 1000)
    closed = 0
    for task_id, note in DONE:
        row = cur.execute("select body from git_issues where id = ?", (task_id,)).fetchone()
        if row is None:
            print(f"跳过（不存在）：{task_id}")
            continue
        body = (row[0] or "").rstrip()
        if "## 修复记录" not in body:
            body = (body + "\n\n" + note) if body else note
        cur.execute("update git_issues set state='CLOSED', body=?, updated_at=? where id=?",
                    (body, now, task_id))
        closed += 1
        print(f"关闭 {task_id}")
    con.commit()
    print(f"\n共关闭 {closed} 条")
    return 0


if __name__ == "__main__":
    sys.exit(main())
