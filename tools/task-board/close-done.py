"""关闭已修完的任务，并把修复证据写进描述（只改 state / body）。

依据：对应的修复提交已全部进入 origin/0.1.0-snapshot（逐条 merge-base 验证过）。
不改优先级、不改编码、不动别的任务。
"""

import sqlite3
import sys
import time

DB = r"data/workspaces/_platform/platform.db"

# (task_id, 关闭说明) —— 每条的“已修”都必须能对应到真实提交或真实文件
DONE = [
    ("task-9779e75d91",
     "已修（4e8ddfb）：propose_patch 放行 src/main/** 与 src/test/**，"
     "并新增拒绝清单（.git/ target/ build/ dist/ node_modules/ 与 .env/*.pem/*.key/id_rsa*/"
     ".npmrc）。被拒时返回结构化 PATH_DENIED + 允许清单 + next，不再笼统失败；"
     "apply 阶段二次校验。复核：WorkspaceCodePatcherTest 6 例、PatchToolsTest 5 例。"),
    ("task-a1074891b7",
     "已修（eea143c + 5f71090）：子会话运行结束自动落时间线、派发去重按会话隔离；"
     "前半的「会话丢失」由时间线兜住。后半「子 agent 无法正常工作」的根因是 "
     "ConversationManager.send 在两条分支返回 err 文案却被当 runId 用 → 子会话派发成功却永不动；"
     "已改为抛 RunException，派发器改为校验 runId 形状。复核：DispatchSendContractTest 4 例。"),
    ("task-da2bbce041",
     "已修（7bd8900 + 批次21）：后端把入参归一化扩展到 tool.started / tool.completed"
     "（插件类与 MCP 工具走这两条路），契约是有参给 args、无参给 null、拿不到给 "
     "args=null + argsUnavailable=true；界面新增 utils/toolArgs.ts 分四态展示，"
     "不再把「拿不到」显示成「无参数」。复核：AgentEventMapperTest、toolArgs.test.ts 20 例。"),
    ("task-605d5e5fcd",
     "已修（ff496d1）：新增 GET /git-issues/versions 提供分支/建议版本/标签候选；"
     "PUT 改为按 key 局部更新——只改版本号不会再清空标题与标签；工具回显 code/version/labels；"
     "前端删掉重复的版本推导。复核：GitIssueControllerFieldContractTest 8 例。"),
    ("task-46122095b6",
     "已修（1614c1e）：能力列表返回 enabled/installed/available/reasonCode/reason/missingEnv，"
     "原因码 CAPABILITY_DISABLED / NOT_INSTALLED / MISSING_ENV / TRANSPORT_UNAVAILABLE；"
     "缺变量时直接列出键名。skill 正文本就按需加载（提示词只留 name + 一句话）。"),
    ("task-4730f75e69",
     "已修（6fa64cd）：隔离参数（gVisor / cap-drop ALL / no-new-privileges / 只读根 + tmpfs / "
     "资源上限）本已就位；本轮补 SandboxDeniedException（E-707）六类规则 + 隔离快照写日志，"
     "运行时不可用绝不静默回落 runc。复核：DockerSandboxDeniedTest 7 例。"),
    ("task-2ee7375068",
     "已修（e7a886e）：GET /env-bindings 返回 currentBindingId + bindings[] 全量"
     "（草稿/已确认/过期/已解绑）；解绑只标 REVOKED 不删记录；usable() 口径统一。"
     "复核：EnvBindingHistoryTest 5 例。"),
    ("task-76c9432989",
     "已修（c2821ad）：心跳改为命名事件 heartbeat（注释帧会被前端解码器丢弃）；"
     "新增静默看门狗，业务事件超阈值无进展时主动下发 error 帧并关流；"
     "超时与静默都落终态到时间线；调度会话补时间线记录器；前端 60 秒无心跳兜底中止。"
     "复核：SseHeartbeatTest 4 例 + gate.py 前端自检。"),
    ("task-90836fe8d5",
     "已修（c2821ad + 2fd381e + ba7b231）：心跳与超时兜底让「挂住」变成「明确报错」；"
     "另加 BuildFreshnessCheck——启动时比对产物与源码时间戳、以及产物与进程启动时间，"
     "源码更新或编译后未重启都会告警「请重启网关」。本次实测已复现并验证该自检生效。"),
]

OPEN_UNFINISHED = [
    ("task-efe1324dd5", "已修（f929bfe）：新增只读工具 change_status(change_id)，"
                       "不推卡/不签发令牌/不执行动作，按状态给出 next；越权校验与 confirm 一致。"
                       "复核：ChangeStatusToolTest 4 例。"),
    ("task-67bd7174cf", "已修（ff496d1 + b31ee17）：两处输出均已带 version；"
                       "新增 GitIssueQueryToolsOutputTest 3 例锁住字段与占位符口径。"),
]


def main() -> int:
    con = sqlite3.connect(DB)
    cur = con.cursor()
    now = int(time.time() * 1000)

    closed = 0
    for task_id, note in DONE:
        row = cur.execute("select state, body from git_issues where id = ?", (task_id,)).fetchone()
        if row is None:
            print(f"跳过（不存在）：{task_id}")
            continue
        body = (row[1] or "").rstrip()
        marker = "## 修复记录\n"
        if marker not in body:
            body = (body + "\n\n" + marker + note) if body else marker + note
            cur.execute("update git_issues set state='CLOSED', body=?, updated_at=? where id=?",
                        (body, now, task_id))
        else:
            cur.execute("update git_issues set state='CLOSED', updated_at=? where id=?",
                        (now, task_id))
        closed += 1
        print(f"关闭 {task_id}")

    for task_id, note in OPEN_UNFINISHED:
        row = cur.execute("select body from git_issues where id = ?", (task_id,)).fetchone()
        if row is None:
            continue
        body = (row[0] or "").rstrip()
        if "## 代码侧修复" in body:
            continue
        body = (body + "\n\n## 代码侧修复\n" + note) if body else "## 代码侧修复\n" + note
        cur.execute("update git_issues set body=?, updated_at=? where id=?", (body, now, task_id))
        print(f"补记录（保持 OPEN，待前端/实机验收）{task_id}")

    con.commit()
    print(f"\n关闭 {closed} 条")
    return 0


if __name__ == "__main__":
    sys.exit(main())
