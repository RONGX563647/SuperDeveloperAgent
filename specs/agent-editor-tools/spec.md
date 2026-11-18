# 批次 4：Agent 写权限（会话独立 worktree + 分支级一次确认）

## 1. 背景与现状（已核实）

前三批次已落地并提交（`c969d20`、`5b33449`）：token 三层治理、服务端时间线落盘 + 任务票据化、
Thread Subscription 断线续传 + Watchdog/Fast Path。

Agent 目前唯一能改仓库的入口是 `propose_patch`（`agent-tools/.../PatchTools.java`），
白名单仅限 ci/、构建配置、测试代码；业务代码无法由 Agent 编辑。本批次补上“受控写文件”能力。

## 2. 目标（验收标准）

1. 非生产会话可在**会话独立 worktree** 内列出/读取/写入/删除文件、查看 diff、提交；
   不会改动用户当前分支或其它会话的工作树。
2. 首次写操作（写/删/提交）必须由用户在确认卡片上确认（分支级一次授权，后续写不再重复确认）；
   未授权时工具返回 `NEEDS_CONFIRMATION` + `changeId`，且**不写任何文件**。
3. 生产环境所有编辑工具直接拒绝；未选择专属工作树（`ctx.worktree() == null`）直接拒绝。
4. 路径逃逸（绝对路径、`..`、`.git` 段）与敏感文件（`.env*`、`*.pem`、`*.key`、`id_rsa*`、`.ssh/`）被拒绝。
5. 提交使用仓库自身 hook 校验（不加 `--no-verify`），失败原样返回给模型。
6. 现有行为不回归：全量 `spec-checks -am test` 绿；`ToolSchemaBudgetTest` 通过。

## 3. 改动明细

### 3.1 domain 新端口

**新增** `platform/agent-domain/src/main/java/top/rongx/agent/domain/port/SessionWorkspaceEditor.java`

```java
public interface SessionWorkspaceEditor {
    record Session(String worktree, String branch, String head, boolean created) {}
    record Diff(List<String> changes, String stat) {}
    record Commit(String sha, String branch, List<String> files) {}

    Session open(String projectId, String sessionKey);
    List<String> list(String projectId, String sessionKey, String dir, int depth);
    String read(String projectId, String sessionKey, String path);
    void write(String projectId, String sessionKey, String path, String content);
    boolean delete(String projectId, String sessionKey, String path);
    Diff diff(String projectId, String sessionKey);
    Commit commit(String projectId, String sessionKey, String message);
}
```

分层约束：tools 只能依赖 domain（ArchUnit A1），文件与进程操作全部在 infra。

### 3.2 infra 实现

**新增** `platform/agent-infra/src/main/java/top/rongx/agent/infra/workspace/SessionWorktreeEditor.java`（`@Component`）

- 构造器注入 `GitWorktreeManager` + `ProjectRepoLocator`。
- 命名（`sessionKey` 即 `ctx.worktree()`）：worktree 名 `wt-session-<sanitized>`（超 40 字符截断 + 8 位 hash，整体 ≤64）；
  分支 `agent/session-<sanitized>`（截断到 48）。
- `ensure`：已存在且含 `.git` 直接复用；否则按 DIRECT/MANAGED 布局清理残留后从 HEAD 新建，再确保切到会话分支。
- 路径校验：拒绝空值、绝对路径、含 `:` 的前缀、`..` 段、`.git` 段；归一化后必须落在工作树内。
- 写操作拒绝凭证文件：`.env*`、`*.pem`、`*.key`、`id_rsa*`、`.ssh/`。
- read/write 上限 200_000 字符；delete 只删文件；list 深度 1..3、跳过 `.git/.agent/node_modules/target/dist`、上限 500 条。
- diff = `statusPorcelain` + `diffStatWorking`；commit 用 `commitAllChecked`（保留 hook）。

**修改** `platform/agent-infra/.../git/GitWorktreeManager.java`：新增 `diffStatWorking` 与 `commitAllChecked`。

### 3.3 变更单：按 kind 前缀查最近一条

- `ChangeStore` 新增 `Optional<ChangePlan> findLatest(String projectId, String kindPrefix)`；
  `ChangeRegistry` 用 `kind LIKE prefix% ORDER BY created_at DESC LIMIT 1` 实现。
- `ChangePlan.executable()` 支持 `editor_branch:` 前缀（否则确认卡不会推送到对话）。

### 3.4 tools 层编辑工具

**新增** `platform/agent-tools/.../EditorTools.java`：`workspace_open/write/read/list/delete/diff/commit`。
生产环境与无工作树直接 `err`；写/删/提交走 `editor_branch:<branch>` 分支级授权
（`CONFIRMED/APPLIED` 放行、`PENDING` 复用单号、否则登记并返回 `NEEDS_CONFIRMATION`）。

### 3.5 确认卡闭环

- `ChangeController.apply` 对 `editor_branch:` 不解析 ActionId，直接返回成功并置 `APPLIED`
  （确认卡点应用 = 授权完成）。
- `ChatController.CHANGE_TOOLS` 增加 `workspace_write/workspace_delete/workspace_commit`。

### 3.6 装配与插件目录

- `ToolCatalog.agentToolkit` / `deployToolkit` 注册 `EditorTools`；只读 Toolkit 不注册。
- `ToolPlugins.change` 收录 7 个编辑工具名。

### 3.7 不改动

- 前端复用现有确认卡；不新增表；不做跨会话写同一 worktree 的互斥锁；
- 不重启后端/前端（热部署）。

## 4. 测试与验证

- `EditorToolsTest`：生产拒绝 / 无工作树 / 未授权不写 / 复用单号 / 确认后可写 / 超限 / 路径逃逸。
- `SessionWorktreeEditorTest`：临时 git 仓库 open→write→diff→commit 闭环、复用、逃逸与凭证拒绝。
- `ChangePlanTest` 增 `editor_branch` 断言；`ToolChainTest` 增装配断言。

```powershell
cd platform; mvn -B -pl agent-tools,agent-infra -am test
cd platform; mvn -B -pl spec-checks -am test
python tools/handbook/build.py sync
```

## 5. 归档

`gate.py start` → `git commit -F` → `gate.py finish` → 提交 docs。
