# Token 治理批次 1：KV 命中率可观测 + 历史折叠摘要

## 1. 背景与现状（已核实）

原始方案中的「Token 三层治理」在仓库里已经落地了两层半，本批次只补缺口，不重复造轮子：

| 能力 | 现状 | 位置 |
| --- | --- | --- |
| 工具 schema 渐进披露 | 已实现，首轮最多自动加载 2 个插件，工具面稳定以保 KV 前缀缓存 | `agent-runtime/.../ProgressiveToolMiddleware.java` |
| 单工具输出限流 | 已实现，头尾保留 + 省略量标注，按工具预算覆盖 | `agent-tools/.../cache/CachingAgentTool.java#budget`、`ToolResultCache#maxOutputChars` |
| 会话上下文预算 | 已实现，25% 余量触发、一次裁到 60%，系统提示不计预算 | `agent-infra/.../state/ConversationContextTrimmer.java` |
| KV 命中率展示 | 前端设置页已展示 `cached/input` | `web/src/components/settings/UsagePanel.vue#hitRate` |

真实缺口只有两个：

1. **命中率只有前端算，后端日志与接口没有统一口径**，`logs/token-usage.log` 无法直接看出缓存是否生效，线上排查要进设置页。
2. **上下文裁剪是"直接丢弃最旧历史"**，长会话中被裁掉的 `runId` / `changeId` / 文件路径等关键标识一起消失，后续追问会退化成重新取证（多花 token）。

## 2. 目标（批次 1 验收标准）

- `logs/token-usage.log` 每行包含单次调用的 `hit=xx.x%`。
- `GET /api/v1/usage/session/{sessionId}` 响应包含 `cacheHitRate`（0–100 的整数百分比，输入为 0 时返回 0）。
- 会话上下文裁剪时，被裁掉的旧历史折叠成 **一条** synthetic 摘要消息（≤4000 字符），保留角色、工具名与关键标识；重复裁剪不会重复叠加摘要消息。
- 现有行为不回归：裁剪窗口不以 TOOL 开头、原状态不被原地修改、未超预算时不改动。

## 3. 改动明细

### 3.1 KV 命中率（后端统一口径）

**文件**：`platform/agent-domain/src/main/java/top/rongx/agent/domain/settings/TokenUsageRepository.java`

- 在 `UsageTotals` record 中新增默认方法：

```java
/** 缓存命中率（0–100 的整数百分比）；没有输入 token 时为 0。 */
public int cacheHitRate() {
    return inputTokens <= 0 ? 0
            : (int) Math.round(cachedTokens * 100.0 / inputTokens);
}
```

Jackson 序列化 record 只输出 components，新增方法不会改变 `/usage/summary` 的 JSON 形状，前端无需改动。

**文件**：`platform/agent-runtime/src/main/java/top/rongx/agent/runtime/TokenUsageRecorder.java`

- `usageLog.info(...)` 末尾追加 `hit={}%`，值用 `input <= 0 ? 0 : Math.round(cached * 100.0 / input)`。

**文件**：`platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/UsageController.java`

- `session()` 方法在 `totals` 旁增加 `out.put("cacheHitRate", totals.cacheHitRate())`。
- 注意：`usage.sessionTotals(sessionId)` 只调用一次并复用，避免重复查询。

### 3.2 历史折叠摘要

**文件**：`platform/agent-infra/src/main/java/top/rongx/agent/infra/state/ConversationContextTrimmer.java`

- 保持现有 `trim(state, maxChars, keepMessages)` 签名不变（调用方与测试不破），新增重载：
  `trim(state, maxChars, keepMessages, summaryChars)`。
- 在确定裁剪起点 `start > 0` 之后：
  - 取 `dropped = rest.subList(0, start)`；
  - 生成一条 `MsgRole.USER` 的 synthetic 消息：
    - `metadata`: `Msg.METADATA_SYNTHETIC=true`、`Msg.METADATA_REMINDER_KIND="compacted_history"`；
    - `name`: `"compaction"`；
    - 文本格式（确定性提取，不调用模型）：
      ```
      <compacted-history>
      Earlier turns were compacted. Keep these facts; re-query only if details are needed.
      - [user] <前 180 字>
      - [assistant] <前 180 字>
      - [tool:<name>] <前 180 字> …(<N> chars omitted)
      </compacted-history>
      ```
    - 工具结果额外抽取并保留标识符，每行最多 3 个：
      `(?i)(runId|run_id|changeId|change_id|sha|traceId)\s*[=:]\s*([A-Za-z0-9_-]{4,})`。
  - 总长超过 `summaryChars`（默认 4000）时，从**最早的行**开始丢弃，保证最近的事实优先。
  - 若 `dropped` 中已存在同类 synthetic 消息（上一次裁剪的摘要），把它的正文行并入本次摘要开头，而不是再套一层。
- 结果列表：`system + [摘要消息] + rest.subList(start, end)`。
- 原方法 `trim(state, maxChars, keepMessages)` 委托为 `trim(state, maxChars, keepMessages, 4000)`。

**文件**：`platform/agent-infra/src/main/java/top/rongx/agent/infra/state/EncryptedAgentStateStore.java`

- 构造器新增 `contextSummaryChars` 字段，`save()` 调 4 参 `trim`。

**文件**：`platform/agent-infra/src/main/java/top/rongx/agent/infra/state/AgentStateConfig.java`

- `agentStateStore(...)` 新增 `@Value("${agent.context.summary-chars:4000}") int contextSummaryChars` 并透传。

**文件**：`platform/agent-gateway/src/main/resources/application.yml`

- `agent.context` 下新增：
  ```yaml
  # 被裁掉的旧历史折叠成一条摘要消息的字符上限（0 = 不生成摘要，退回直接丢弃）
  summary-chars: ${AGENT_CONTEXT_SUMMARY_CHARS:4000}
  ```

## 4. 测试

新增 `platform/agent-infra/src/test/java/top/rongx/agent/infra/state/ConversationContextSummaryTest.java`：

1. 超预算裁剪后，保留区第一条非 SYSTEM 消息是 synthetic 摘要，且包含被裁掉的 `runId`。
2. 摘要长度不超过 `summary-chars`。
3. 连续两次裁剪，摘要消息最多一条（不重复叠加）。
4. `summary-chars=0` 时不生成摘要（与旧行为一致）。

新增 `platform/agent-runtime/src/test/java/top/rongx/agent/runtime/TokenUsageRecorderTest.java`：

1. `cached > input` 时落库被夹紧到 `input`。
2. `usage == null` 或 `sessionId` 为空时不落库。
3. 仓储抛异常时不向上抛（对话不中断）。

新增 `platform/agent-domain/src/test/java/top/rongx/agent/domain/settings/UsageTotalsTest.java`：

1. `cached=500, input=1000` → 50。
2. `input=0` → 0。

同时更新 `ConversationContextTrimmerTest.trimIsIdempotent` 的断言：改为"第二次裁剪后摘要消息数量仍为 1"，而不是简单比较 size（折叠会改变 size 语义）。

## 5. 验证步骤

```powershell
cd platform
mvn -q -pl agent-domain,agent-infra,agent-runtime,agent-gateway -am test
```

- 不重启后端/前端；已有进程继续用 Spring Boot 热部署。
- 验收：上述测试全绿；`docs/.session` 走 gate.py start → commit → finish → commit。

## 6. 风险与回滚

- 摘要消息是 synthetic 且只在落盘状态里出现，不会进入工具执行链；渲染给模型只是普通 USER 文本。
- `summary-chars=0` 可一键回退到"直接丢弃"的旧行为。
- 若摘要行里含敏感值，沿用现有的工具结果脱敏链路（`SecretRedactor` 在工具输出阶段已处理），摘要不再二次展开原始内容。

## 7. 明确不做（留给后续批次）

- 批次 2：服务端完整时间线落盘、子 Agent 任务规格/Ticket 化。
- 批次 3：Thread Subscription、Watchdog/Fast Path。
- 批次 4：Agent 写权限（EditorTools + 会话 worktree 分支 + 分支级一次确认）。
- 本批次不动 `createSupervisor` 的工具面（supervisor 无 `tool_load`，贸然渐进披露会让它更缺工具）。
