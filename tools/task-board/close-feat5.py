"""关闭 FEAT-5（token 爆炸）：三项诉求逐条有代码证据。

不凭印象关单：每项都指向可检索的实现与测试。
"""

import sqlite3
import sys
import time

DB = r"data/workspaces/_platform/platform.db"
TASK = "task-5ac93d7d53"

NOTE = """## 完成记录

原文三项诉求，逐条核实如下。

### 1. 每次对话都插 tool → 渐进 + 插件化（已完成）

- `ProgressiveToolMiddleware` + `ToolPluginIndex`：常驻工具只有 8 个发现类
  （`project_info` / `list_envs` / `tool_search` / `tool_get` / `tool_load` /
  `tool_plugins` / `skill_load` / `api_search`），其余按插件激活后才进入 schema。
- `ToolSchemaBudgetTest` 把「核心只有 8 个、单个 description ≤40 字符、总预算 ≤4000」
  固化成测试，防止后续又塞回长描述。

### 2. prompt / skill / mcp 同样压缩（已完成）

- skill 索引只注入「名称 + 一句话」（`AgentFactory#skillIndex`），
  正文由 `skill_load(name)` 按需拉取。
- 插件索引文案固定顺序、无时间戳（`ToolPluginIndex` 注释明确为缓存稳定而设计）。

### 3. 提升 KV cache 命中率（已完成 + 有度量）

- `ConversationContextTrimmer`：留 25% 余量才触发、一次裁到 60%——
  高频小裁会让模型侧前缀缓存反复失效，这个阈值就是为此设的。
- 度量侧本轮补齐：`/usage/summary` 返回总体与三个维度的 `cacheHitRate`，
  `UsagePanel` 顶部与按模型/按天两张表都显示命中率。

### 复核用例

`UsageSummaryHitRateTest`（5）、`UsageHitRateSerializationTest`（5）、
`UsageTotalsTest`（2）、`ToolSchemaBudgetTest`（2）。

### 说明

后续若命中率数字不理想，要调的是压缩策略参数（余量比例、裁剪深度、
哪些内容进系统提示），不是再补实现——三项机制都已就位。
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
    print("已关闭 FEAT-5（task-5ac93d7d53）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
