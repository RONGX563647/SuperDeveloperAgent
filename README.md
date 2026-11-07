# 研发运维智能体平台（RuoYi × AgentScope Java）

一个**内网部署的独立 Agent 平台**：面向开发者与运维，提供「读 Issue 改 Bug」「AI 自动化运维」「自动化部署」三类能力。
技术栈：**AgentScope Java 2.0** + Spring Boot 4 + JDK 21 + Vue 3 + 单机 Docker。

> 设计原则：**AI 只做决策与建议，不持有任何写凭证**；所有写操作由确定性执行器在人工确认后执行。

---

## 目录

```
docs/          设计与规约（11 份文档 + 2 份汇报稿）
  research/    —（调研材料在 ../_research/）
platform/      后端 8 模块 + spec-checks + Vue3 前端
_research/     市场调研与源码能力盘点（含采集脚本）
```

## 阅读顺序（推荐）

| # | 文档 | 作用 |
|---|---|---|
| 1 | [PRD v1.6](docs/PRD-DevOps-Agent平台-v1.0.md) | 做什么、不做什么、验收标准 |
| 2 | [项目规约 v1.0](docs/项目规约-v1.0.md) | 代码与流程的强制约束（MUST / MUST NOT） |
| 3 | [规约自动化检查](docs/规约自动化检查-v1.0.md) | 规约如何落到 `mvn verify` |
| 4 | [流水线设计 · Java+Vue 双栈](docs/流水线设计-Java与Vue双栈.md) | 构建与静态发布 |
| 5 | [规约：流水线执行器](docs/规约-流水线执行器-v1.0.md) | stage 执行器契约 |
| 6 | [窄控制器](docs/窄控制器设计-单机滚动部署器.md) | 单机 compose 滚动发布 |
| 7 | [执行面设计（K8s/Jenkins/Flyway）](docs/devops-agent-执行面设计-k8s-jenkins-flyway.md) | 「拥有类似能力」的三条路线 |
| 8 | [多项目托管 v4](docs/多项目托管设计-v4.md) | 项目作为作用域边界 |
| 9 | [Jenkins 能力复刻与复用 · 规划](docs/Jenkins能力复刻与复用-规划-v1.md) | CI 引擎该复刻什么、复用什么、明确不做什么 |
| 10 | [开源 CI 集成复用 · 调研](docs/开源CI集成复用-调研-v1.md) | 有没有现成开源 CI 能直接用（结论：SCM 绑死 Gitee） |
| 11 | [CI/CD 方案替代与自创 · 决策](docs/CICD方案替代与自创-决策-v1.md) | 不造 DSL，改用「5 个动作 + 仓库内配方」 |

**背景与推演**（按时间顺序，记录结论如何演化）：

[v1 若依 4.8.3](docs/ruoyi-agent-可行性分析与市场调研.md) → [v2 Vue-Plus 重评](docs/ruoyi-vue-plus-agent-可行性分析-v2.md) → [v3 独立平台](docs/devops-agent-独立平台-可行性分析-v3.md) → [v4 多项目](docs/多项目托管设计-v4.md)；
另有 [ruoyi-ai 编程助手拆解](docs/ruoyi-ai-编程助手拆解.md) 与 [脚手架完善分析](docs/脚手架完善分析-v1.md)。

## 快速开始

```bash
cd platform
export AGENT_MODEL_API_KEY=sk-xxx      # Windows: $env:AGENT_MODEL_API_KEY="sk-xxx"
mvn -q verify                          # 编译 + 规约检查 + 单测
java -jar agent-gateway/target/agent-gateway-0.1.0-SNAPSHOT-exec.jar

cd web && npm install && npm run dev    # http://127.0.0.1:5173
```

详见 [platform/README.md](platform/README.md)。

## 代码托管与流水线

| 项 | 说明 |
|---|---|
| 代码托管 | **Gitee**（Maven groupId `top.rongx`，Java 根包 `top.rongx.agent`） |
| 流水线 | [`.workflow/build.yml`](.workflow/build.yml)（Gitee Go）。本质只有一条：`cd platform && mvn -B verify` |
| 门禁 | 分支保护 + `mvn verify` 必须绿（规约检查已并入构建） |

```bash
git remote add origin https://gitee.com/<你的账号>/agentscope-ruoyi.git
git push -u origin main --tags
```

> Gitee Go 的字段名以平台文档为准（首次接入核对 `triggers` / `step` / `artifacts`）。
> 若该套餐不含 Gitee Go，把上面那条命令接到任意 runner（自建 / 云效 / Jenkins）即可，效果一致。

## 提交规范

遵循 [项目规约 §12](docs/项目规约-v1.0.md)：

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
`docs/` 中 v1、v2 两份报告的「证据索引」引用了 `../RuoYi/`、`../agentscope-java/`、`../RuoYi-Vue-Plus/` 下的源码路径。
这些是**分析当时的源码快照，已从本仓库移除**（见 [.gitignore](.gitignore) 中的克隆命令），因此那些链接不可点击，但结论与引用的类名/行号仍可对照上游仓库复核。

## 当前状态

| 项 | 状态 |
|---|---|
| 设计文档 | ✅ 11 份（PRD / 规约 / 契约 / 设计 / 分析） |
| 后端骨架 | ✅ 8 模块编译通过，`mvn verify` 16 个测试全绿 |
| 前端骨架 | ✅ Vue3 + Vite + Element Plus（未在本机构建验证） |
| 端到端能力 | 🟡 对话 + SSE + 只读工具 + 确认令牌；**执行（apply）与流水线实现待补** |
