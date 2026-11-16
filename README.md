# 研发运维智能体平台（RuoYi × AgentScope Java）

一个**内网部署的独立 Agent 平台**：面向开发者与运维，提供「读 Issue 改 Bug」「AI 自动化运维」「自动化部署」三类能力。
技术栈：**AgentScope Java 2.0** + Spring Boot 4 + JDK 21 + Vue 3 + 单机 Docker。

> 安全边界：AI 负责决策与受控执行——dev/test 的 L2 动作可直接执行，prod 会话只读；生产写操作与 L3 动作必须人工确认。模型不持有生产写凭证。

---

## 目录

```
docs/          文档树（doc-marshal 管理；入口 docs/overview.md）
docs/specs/    功能规格与任务清单（dev 本机部署 / 环境注入健康 / 环境一致性）
platform/      后端 8 个 Maven 模块 + Vue3 前端
docs/research/ 市场调研与源码能力盘点（只读证据，含采集脚本）
llms.txt       给 LLM / RAG 的文档入口
.workflow/     Gitee Go CI（doc-marshal check + cd platform && mvn -B verify）
```

## 文档阅读顺序

> 文档类型、摘要、状态与代码锚点：`doc-marshal index`；人工导航入口：**[docs/overview.md](docs/overview.md)**。
> 机器可读入口：**[llms.txt](llms.txt)**；文档门禁：`doc-marshal check --all`（类型、链接、代码锚点）。
> 文档索引与代码对应关系：**`doc-marshal index`**；漂移检查：**`doc-marshal drifted`**。

### 核心必读（先看这四篇）

| # | 文档 | 作用 |
|---|---|---|
| 1 | [PRD v1.6（Draft 产品稿）](docs/prd-devops-agent-v1.md) | 产品目标与边界；现状以 Code-Wiki / code_refs 为准 |
| 2 | [项目规约 v1.0](docs/project-conventions-v1.md) | 代码与流程的强制约束（MUST / MUST NOT） |
| 3 | [规约自动化检查](docs/convention-automation-checks-v1.md) | 规约如何落到 `mvn verify` |
| 4 | [Code Wiki](docs/code-wiki.md) | 面向新成员与评审：模块、关键类、HTTP API、工具、前端 |

> 为什么这么设计：见 [ADR 索引](docs/adr/overview.md)（7 篇：独立平台、CI 形态、环境三层、运行账本、安全边界、事件驱动、DAG 并行调度）。

### 设计与实现（按文档状态阅读）

| # | 文档 | 作用 |
|---|---|---|
| 5 | [流水线设计 · Java+Vue 双栈](docs/pipeline-design-java-vue.md) | 双栈设计稿/参考，现行 stage 以 Code-Wiki 为准 |
| 6 | [规约：流水线执行器](docs/pipeline-executor-spec-v1.md) | stage 执行器契约（参考） |
| 7 | [发布编排 + 平台部署器 v1](docs/release-orchestration-deployer.md) | HOST / DOCKER / SERVER 三种部署语义 |
| 8 | [项目接入设计 v2](docs/project-intake-v2.md) | DIRECT 本地引用 / MANAGED bare + worktree |
| 9 | [环境模板与中间件自动注入 v2](docs/env-template-design-v2.md) | 环境变量 / 中间件 / 部署注入的现行决策 |
| 10 | [环境配置事实层与分环境门控 · 方案 v1](docs/env-config-facts-gating-v1.md) | 配置解析、`secret://`、分环境策略（部分落地，observe/草案审核未实现） |
| 11 | [设置：服务器与模型连接 v1](docs/settings-server-model-v1.md) | 设置页数据模型、密钥加密与连通性探测 |
| 12 | [多项目托管 v4](docs/multi-project-hosting-v4.md) | 项目作为作用域边界（部分实现：成员/Issue 桥/配额未落地） |
| 13 | [流水线工作台 · 使用指南](docs/pipeline-workbench-guide-v1.md) | 接入项目、写配方、触发/重跑/回滚、看懂错误码 |

### 评估与决策（结论已定，代码可能只实现部分）

| # | 文档 | 作用 |
|---|---|---|
| 14 | [CI/CD 方案替代与自创 · 决策](docs/cicd-decision-v1.md) | 不造 DSL，改用「7 个固定动作 + 仓库内配方」；决策时点为 5 个 |
| 15 | [窄控制器](docs/narrow-controller-rolling-deployer.md) | 单机 compose 滚动发布（**设计稿，未实施**） |

> 验收报告、Jenkins 规划、开源 CI 调研、执行面设计等历史文档已按废弃流程删除，原因与替代物见[废弃登记](docs/deprecated/registry.md)。

### 背景与推演（决策依据见 ADR）

平台从若依嵌入式模块演进到独立多项目平台；现行方案见[多项目托管设计 v4](docs/multi-project-hosting-v4.md)，为什么走独立平台见 [ADR-0001](docs/adr/0001-independent-platform.md)。

## 快速开始

```bash
cd platform
# 模型 API Key 不是启动前置：启动后进「设置 → 模型连接」配置即可（加密落库、热切换）。
# 若想用环境变量兜底（或写入 platform/.env，启动自动加载、真实环境变量优先）：
# export AGENT_MODEL_API_KEY=sk-xxx    # Windows: $env:AGENT_MODEL_API_KEY="sk-xxx"

mvn -q verify                          # 编译 + 规约检查 + 单测
# JDK 24+ 建议带上 native-access：sqlite-jdbc 通过 System.load 加载本地库
java --enable-native-access=ALL-UNNAMED \
  -jar agent-gateway/target/agent-gateway-0.1.0-SNAPSHOT-exec.jar
# 健康检查：http://127.0.0.1:8090/actuator/health

cd web && npm install && npm run dev    # http://localhost:5173
```

> 首次启动无需任何模型密钥；未配置密钥时服务照常启动，只有发起对话才会提示「请在设置 → 模型连接中配置」。
> 本机无 Docker 时需另设 `AGENT_SANDBOX_MODE=process`（沙箱默认 docker 且 fail-closed，与密钥无关）。
> 登录种子账号：`admin` / `devops` / `viewer`，**三者初始密码统一为 `admin-2026`**（固定值，每次启动都一样）；
> 登录后到「设置 → 账号安全」改成自己的密码。共享/生产部署可用
> `AGENT_SEED_PASSWORDS=admin=xxx,devops=yyy,viewer=zzz` 覆盖初始值（未指定的账号仍用默认值）。
> 前端只监听 `localhost`：用 `127.0.0.1` 打不开是正常的（Vite 默认绑 `::1`）。
> 要让 IPv4 可达，用 `npm run dev -- --host 127.0.0.1`；回归脚本也按 `localhost` 取址。

详见 [platform/README.md](platform/README.md)。

## 代码托管与流水线

| 项 | 说明 |
|---|---|
| 代码托管 | **Gitee**（Maven groupId `top.rongx`，Java 根包 `top.rongx.agent`） |
| 流水线 | [`.workflow/build.yml`](.workflow/build.yml)（Gitee Go）：`doc-marshal check --all` + `cd platform && mvn -B verify` |
| 门禁 | 分支保护 + doc-marshal（类型/链接/代码锚点）+ `mvn verify` 必须绿 |

```bash
git remote add origin https://gitee.com/<你的账号>/agentscope-ruoyi.git
git push -u origin main --tags
```

> Gitee Go 的字段名以平台文档为准（首次接入核对 `triggers` / `step` / `artifacts`）。
> 若该套餐不含 Gitee Go，把 `doc-marshal check --all` + `mvn verify` 接到任意 runner（自建 / 云效 / Jenkins）即可，效果一致。

## 提交规范

遵循 [项目规约 §12](docs/project-conventions-v1.md)：

| 项 | 约定 |
|---|---|
| 提交信息 | `type(scope): 摘要`，type ∈ `feat` / `fix` / `refactor` / `test` / `docs` / `chore` / `ci` |
| scope | 模块名（`platform` / `web` / `spec-checks` / `docs`）或省略 |
| 分支 | `feat/<issue>-<slug>`、`fix/<issue>-<slug>`、`chore/...`；**禁止直推 main** |
| 粒度 | 一个提交只做一件事；文档与代码分开提交；生成物（jar/pptx 源）与源文件分开 |
| 语言 | 摘要用中文，type/scope 用英文 |

示例：

```
feat(platform): 增加治理模块（动作门禁与确认令牌）
docs: 流水线执行器契约规约
test(platform): 接入规约自动化检查（ArchUnit + 扫描）
```

## 关于外部源码引用

`docs/research/` 中的调研文档引用了 `../RuoYi/`、`../agentscope-java/`、`../RuoYi-Vue-Plus/` 下的源码路径。
这些是**分析当时的源码快照，已从本仓库移除**（见 [.gitignore](.gitignore) 中的克隆命令），因此那些链接不可点击，但结论与引用的类名/行号仍可对照上游仓库复核。

## 当前状态

| 项 | 状态 |
|---|---|
| 设计文档 | ✅ `docs/` 由 doc-marshal 管理，根目录 15 份（现行/规划）；`docs/specs` 3 组规格与任务清单 |
| 后端 | ✅ 8 个 Maven 模块（7 个运行时模块 + `spec-checks`），`mvn -B verify` 全绿 |
| 前端 | ✅ Vue3 + Vite + Element Plus；7 个页面；`npm run build`（vue-tsc strict）通过 |
| 认证与授权 | ✅ Sa-Token 会话、3 个种子账号、角色权限码；高危操作需二级密码 |
| 项目接入 | ✅ 本地 DIRECT（不复制仓库）/ 远程 clone（bare + worktree）；配方自检 + 按仓库结构在**平台侧**生成定义 |
| 流水线 | ✅ 检出 → 构建/测试 → 镜像 → 部署 → 健康检查 → 失败回滚；命令级预览、失败续跑、产物下载、历史删除 |
| dev 本机部署 | ✅ dev 固定本机运行（后端 jar + 前端 dist），忽略 `deployTarget=docker`；可一键停止交还 IDE |
| 环境子系统 | ✅ 模板 / 中间件资源 / 项目覆盖 / 有效视图；发现、按需供给、健康检查；部署前 E-509 / E-701 / E-702 预检与端口租约 |
| 安全门禁 | ✅ L2/L3 动作门禁、一次性确认令牌（绑定 planHash）；补丁只提交到 `patch/<projectId>` 分支；明文回显单独授权并写审计 |
| 取证与恢复 | ✅ `env_runtime_status` / `app_logs` / `port_check`；失败恢复建议；一键自动部署 Agent（仅 dev/test） |
| 统一运行账本 | ✅ 对话/修复/流水线/发布四类运行落 `_platform/run_ledger.db`；重启标记孤儿 RUNNING，终态保留 30 天 |
| 并发与频率 | ✅ 单实例信号量或 SQLite 租约（`agent.chat.limiter=jdbc`）；破坏性 L2 强制人工确认，同动作 10 分钟最多 3 次 |
| 回归脚本 | ✅ `platform/web/scripts/*.mjs`（布局 / 登录 / 外壳隔离 / 交互 / 流水线 / 项目 ID），需本地起前端 dev server |
| 未做 | ⬜ 多租户与用户管理界面、`GET /api/v1/fs/read`、AgentScope 事件稳定枚举、跨引擎统一取消/重试 |
