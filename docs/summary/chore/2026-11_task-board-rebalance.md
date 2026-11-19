# 任务看板重排口径（2026-11-19）

> 重排对象：`data/workspaces/_platform/platform.db` 的 `git_issues` 表（32 条）。
> 执行前备份：`platform.db.bak-before-task-reorder`。
> 判定依据是你给的三条结构性判断，不是"现象刺眼程度"。

## 一、为什么能直接改库

全部 32 条任务 `provider` / `repo` 为空、`sync_status=LOCAL`，**从不推送远端**
（`.tmp-read-binding.py` 的输出可复核）。因此走服务层与直接改库结果等价：
不会漏掉任何远端同步，也不会把本地状态写成"已同步"。

## 二、三处分类纠正

| 任务 | 原标题 | 原分类 | 新分类 | 依据 |
|---|---|---|---|---|
| `task-de956c65b7` | 工作区助手 super agent 的搭建 | BUG P0 | **FEAT-1** P0 | 要搭的是"调度能力"，不是修缺陷 |
| `task-143dd0976c` | 流水线部署问题 | FEAT P0 | **BUG-1** P0 | 现象是部署失败，属缺陷 |
| `task-67bd7174cf` | 输出丢失 version | FEAT P1 | **BUG-8** P1 | 字段写读不回环，属缺陷 |

## 三、P0 只留"能一票否决"的七条

| 新编码 | 任务 | 原优先级 | 为什么是 P0 |
|---|---|---|---|
| BUG-2 | agent 无法写入 `src/main` | P0 | 写不进业务代码，所有"让 agent 自己改代码"的 FEAT 全部空转 |
| BUG-3 | sqlite 数据丢失 | P0 | 数据丢失不可逆 |
| BUG-4 | 轨迹工具详情参数缺失 | **P2 → P0** | 影响所有调试的判断依据，缺参数就无法定位根因 |
| BUG-5 | 工作区会话发送按钮失效 | **P2 → P0** | 请求挂住既不返回也不报错，用户只能重启 |
| BUG-6 | 对话流中断 | P0 | 同上：SSE 无心跳、无终态兜底 |
| BUG-7 | 工作区会话丢失 + 子 agent 无记录 | P0 | 会话是工作区的基本单位 |
| BUG-1 | 流水线部署问题 | P0 | 部署链路不可用 |

## 四、P0 → P1 的两条

| 新编码 | 任务 | 原优先级 | 降级理由 |
|---|---|---|---|
| BUG-9 | 任务栏（版本号太丑难用） | P0 | 纯观感；后端 `version`/`labels` 已能写读（见 batch-2 归档） |
| BUG-16 | 流水线沙箱"脆弱且不安全" | P0 | gVisor / cap-drop / 只读根已落地，剩余是结构化拒绝码 |

## 五、编码重排

原库存在 **重复编码 BUG-1**（`task-c511703132` 与 `task-94491a7adc` 共用），
且 FEAT 内混进了三条实为 BUG 的记录。重排后：

- BUG-1 … BUG-25 连续无空洞；FEAT-1 … FEAT-7 连续无空洞；
- 同类 `code` 前缀与 `kind` 完全一致（校验脚本输出"分类与编码前缀不一致：无"）。

**旧编码不能当稳定引用**：`BUG-21` 原来是"写不进 src/main"，现在是"部署完后的环境需要区分"。
需要跨时间引用时用 `task-xxxx` id（重排不动 id）。

## 六、补建的两条缺失记录

用户报告里有两个 id 在库中不存在（复核会对不上号），按其原文标题与结论补建：

| id | 标题 | 对应已完成的修复 |
|---|---|---|
| `task-76c9432989` | 对话流中断 | 命名心跳 + 静默看门狗（batch-3 归档） |
| `task-2ee7375068` | 工作区环境（历史环境卡片看不到） | `GET /env-bindings` + 解绑留痕（batch-4 归档） |

## 七、复核方法

```powershell
cd E:\code\SuperDeveloperAgent
python .tmp-verify-issues.py     # 重复编码 / 前缀一致性 / 优先级分布 / 编码连续性
python .tmp-read-issues.py       # 逐条看编码、优先级、标题、描述摘要
```

回滚：

```powershell
copy data\workspaces\_platform\platform.db.bak-before-task-reorder `
     data\workspaces\_platform\platform.db
```
