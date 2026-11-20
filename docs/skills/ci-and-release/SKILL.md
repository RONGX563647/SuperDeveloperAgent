---
name: ci-and-release
description: CI 流水线与发版：spec-checks 规约套件、Handbook 生成、分支模型与同步合并
covers: [ci.yml, ArchitectureRulesTest, ForbiddenPatternScanTest, ApiDocContractTest, build.py]
when_to_read: CI 红了/加禁令规则/改 API 契约/Handbook 漂移/分支合并同步/发版
budget: 100
---

# CI 与发版（ci-and-release）

## CI（`.github/workflows/ci.yml`）

- `spec-checks`（规约套件）→ 前端（typecheck / check:api-types / test）→ 文档契约
  （`gate.py validate` + `tools/docs/build.py check`）→ gitleaks（凭证）→ osv-scanner（依赖漏洞）。
- 本地对应物就是 pre-commit / pre-push 钩子；**CI 不是第一次报错的地方**。

## spec-checks 套件（`platform/spec-checks`）

| 测试 | 拦什么 |
|---|---|
| `ArchitectureRulesTest` | 依赖方向（ArchUnit） |
| `ForbiddenPatternScanTest`（R01–R13） | 禁令（如 `Runtime.exec`、明文密钥） |
| `MigrationGuardTest` | 已发布迁移不可改 |
| `HandbookDriftTest` | handbook 锚点与源码行号漂移 |
| `ApiDocContractTest` | 每个 `@GetMapping` 必须有 Javadoc + `@Operation`；OpenAPI 快照新鲜 |
| `ProcessArchiveTest` | 归档门禁（调 `gate.py validate/self-test` + `sweep.py`） |

改规则前先看 `docs/quality-gate-design-v1.md`（分层设计）。

## Handbook

`// @handbook <doc>/<anchor>` 锚点生成 L3 行为→源码表；`python tools/handbook/build.py sync/check`。
改动锚点后必须 sync 并提交 `docs/handbook/`（pre-push 有 drift 检查）。

## 分支模型

- `0.1.0-snapshot`：活跃开发线；`0.2.0-snapshot`：并行版本线；`main`：发布线。
- 同步 0.1→0.2 是 merge（不是 rebase）；冲突集中在归档链与 handbook 生成物：
  归档链按 (createdAt,id) 重排重算（`gate.reseal_chain`），handbook 用 `build.py sync` 重新生成，都不要手改。
- 提交 sha 被 rebase 后，归档里的 `commits[].sha` 要重指新 sha（见 archive-gates 卡）。

## 找历史

`python tools/docs/find.py "CI"`；`docs/ci-commit-encoding.md`（提交信息约定）。
