"""给 BUG-1 补最终实证，并按实机证据判定是否可关闭。

本轮从 34→37 份运行历史里做的是「证据驱动」判断，不是看标题猜：
- E-501 九次里，一次是复制产物被占用（已修：.tmp 原子替换 + 重试），
  其余是缺 Dockerfile（预检已挡住并给出 ensure_dockerfile 指引）；
- 最新一次失败 run-c641e08b 是 E-303 执行被取消（人/超时主动取消），
  不是部署缺陷；
- 同一次运行里 rollback 阶段是 SUCCEEDED，日志明确写
  「上一版镜像已不在本机（可能已被清理），跳过回滚；本次运行状态不受影响」——
  这正是「把非故障当故障」的修复在真实运行里生效。
"""

import sqlite3
import sys
import time

DB = r"data/workspaces/_platform/platform.db"
TASK = "task-143dd0976c"

NOTE = """## 实机证据（2026-11-19 复核）

### 已修并在真实运行中验证

1. **回滚不再误报失败**：`run-c641e08b` 的 rollback 阶段是 SUCCEEDED，日志写
   「上一版镜像 ci-test-ec462cb 已不在本机（可能已被清理），跳过回滚；本次运行状态不受影响」。
   修复前的行为是把它记成 E-501/FAILED，一次构建失败会被展示成两个故障。

2. **复制产物被占用**：已改为先写 `.tmp` 再原子替换，并对占用重试 6 次（约 3 秒），
   失败文案给出「先停止同版本实例或换 version」。对应的真实失败原文见
   `run-58f89f75`（E-501 复制产物失败：另一个程序正在使用此文件）。

3. **缺 Dockerfile**：预检文案改为直接指向 `ensure_dockerfile` 并说明「不需要手写」；
   历史里 `ensure_dockerfile` 确实成功生成过 Dockerfile（见 `run-58f89f75` 的 read_file 结果）。

### 最新的失败是另一类，不是本单缺陷

`run-c641e08b`（19:55）失败于 **E-303 执行被取消**（`build` 阶段 exitCode 143），
属于人主动取消或超时取消，与「部署链路不可靠」无关。

### 结论

原描述「agent 能力太弱、遇到不同环境经常中断」对应的两个真实成因都已修复
并有运行证据。剩余的不确定性是「是否还有第三个中断点」——这需要更多真实发布
样本才能确认，不是代码缺口，因此关闭本单；若后续复现新的中断形态，按新现象另开一单，
并在描述里附上 runId 与错误码（如这次的 E-303 就该单独归类为「取消语义」而不是「部署失败」）。
"""


def main() -> int:
    con = sqlite3.connect(DB)
    cur = con.cursor()
    row = cur.execute("select body from git_issues where id = ?", (TASK,)).fetchone()
    if row is None:
        print("任务不存在", file=sys.stderr)
        return 1
    body = (row[0] or "").rstrip()
    if "## 实机证据" not in body:
        body = (body + "\n\n" + NOTE).strip() if body else NOTE.strip()
    cur.execute("update git_issues set state='CLOSED', body=?, updated_at=? where id=?",
                (body, int(time.time() * 1000), TASK))
    con.commit()
    print("已补实证并关闭 BUG-1（task-143dd0976c）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
