"""给 BUG-1 补一段进展记录（保持 OPEN），说明已修哪一半、还差什么。

只改 body / updated_at，不动 state（这条还要实机观察）。
"""

import sqlite3
import sys
import time

DB = r"data/workspaces/_platform/platform.db"
TASK = "task-143dd0976c"

NOTE = """## 修复记录（部分）

已修（fce29f9）：HOST 目标的产物复制改为先写 .tmp 再原子替换，并对「文件被占用」
重试 6 次（约 3 秒）；失败文案改为可操作（先停止同版本实例，或改用不同的 version）。

依据：扫描 data/workspaces/_history 下 34 份运行，17 份失败中 E-501 出现 9 次，
其中 run-58f89f75 的原文就是「复制产物失败：...app.jar 另一个程序正在使用此文件，
进程无法访问」——这类失败重跑一次即成功，属于把短暂占用当成永久失败。

仍 OPEN 的原因：
- DOCKER 目标下「仓库没有 Dockerfile」的场景（历史里显示为 E-101 / E-501）
  需要 ensure_dockerfile 介入；自动修复链（DockerDaemon → ImagePull →
  AgentDiagnosisRepair）已具备诊断能力，且历史上确实自动恢复成功过
  （run-0d13a007），但缺 Dockerfile 属于"要生成文件"而非"重试即可"，
  仍需人工或 Agent 确认，不适合完全自动化。
- 这条描述是「不同环境行为不一致」的笼统反馈，需要在实机上再跑一轮
  多环境发布来确认是否还有其它中断点。
"""


def main() -> int:
    con = sqlite3.connect(DB)
    cur = con.cursor()
    row = cur.execute("select body from git_issues where id = ?", (TASK,)).fetchone()
    if row is None:
        print("任务不存在", file=sys.stderr)
        return 1
    body = (row[0] or "").rstrip()
    if "## 修复记录（部分）" in body:
        print("已有进展记录，跳过")
        return 0
    body = (body + "\n\n" + NOTE).strip() if body else NOTE.strip()
    cur.execute("update git_issues set body = ?, updated_at = ? where id = ?",
                (body, int(time.time() * 1000), TASK))
    con.commit()
    print("已为 BUG-1 补进展记录（保持 OPEN）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
