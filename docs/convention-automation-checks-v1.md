---
type: convention
summary: "规约自动化检查规则与覆盖"
code_refs:
  - platform/spec-checks/src/test/java/top/rongx/agent/arch
---
# 规约自动化检查 v1.0

> 日期：2026-11-07 ｜ 上游：[项目规约 v1.0](project-conventions-v1.md)
> 目标：把规约里**可机械判定**的条款，变成 `mvn verify` 会红的测试

---

## 0. 三条设计原则

1. **不引入新服务**：检查器就是 JUnit 测试，随 `mvn verify` 跑。不装 SonarQube、不配 PMD 版本、不依赖 shell。
2. **能自动的自动，不能自动的进清单**：语义类判断（"描述是否说明什么时候用"）不硬凑规则，进 PR 评审清单。
3. **规则可豁免但不能静默**：行内 `// spec-ignore: <规则号>` + 原因，且必须同步登记到项目规约 §13。

---

## 1. 三层防线

| 层 | 触发 | 跑什么 | 目的 |
|---|---|---|---|
| **提交前** | 本地 `mvn -q verify` | 全部检查器 | 尽早发现红线 |
| **构建** | CI 每次构建 | 同上 + 单元测试 | 阻断合入 |
| **评审** | PR | §14 的 20 条人工清单 + 豁免审核 | 覆盖语义类条款 |

---

## 2. 条款 → 检查映射

| 规约条款 | 检查器 | 规则号 | 自动化程度 |
|---|---|---|---|
| §1.1 A1 AI 不持写凭证 | `ConfigCredentialScanTest` + `ArchitectureRulesTest` | C1–C5、A4 | ✅ 全自动 |
| §1.1 A3 执行面不含 LLM | `ArchitectureRulesTest` | A3 | ✅ 全自动 |
| §2.1 依赖方向单向 | `ArchitectureRulesTest` | A1 | ✅ 全自动 |
| §2.2 禁止 util/common 包 | `ArchitectureRulesTest` | A6 | ✅ 全自动 |
| §2.3 domain 不依赖框架 | `ArchitectureRulesTest` | A2 | ✅ 全自动 |
| §4 工具不暴露 shell / confirm | `ArchitectureRulesTest` + 人工 | A7 + 评审 | 🟡 半自动 |
| §5.1 金额不用 double | `ForbiddenPatternScanTest` | R06 | ✅ 全自动 |
| §5.1 软删用 deleted_at | `ForbiddenPatternScanTest` | R13 | ✅ 全自动 |
| §5.2 迁移不可修改 | `MigrationGuardTest` | M1 | ✅ 全自动 |
| §5.3 禁 `SELECT *` | `ForbiddenPatternScanTest` | R08 | ✅ 全自动 |
| §6 S5–S6 沙箱与镜像 | 评审 / 部署配置检查 | — | ❌ 人工（无对应规则） |
| §10.1 禁止字段注入 | `ForbiddenPatternScanTest` + `ArchitectureRulesTest` | R05、A9 | ✅ 全自动 |
| §10.2 禁止裸 Thread | `ForbiddenPatternScanTest` | R04 | ✅ 全自动 |
| §10.3 禁止吞异常 | `ForbiddenPatternScanTest` | R10 | ✅ 全自动 |
| §10.4 禁止拼 shell | `ForbiddenPatternScanTest` | R01–R02 | ✅ 全自动 |
| §10.4 禁止 MyBatis `${}` | `ForbiddenPatternScanTest` | R03 | ✅ 全自动 |
| §10.4 时间用 java.time | `ForbiddenPatternScanTest` | R09 | ✅ 全自动 |
| §7 日志不打凭证 | `ForbiddenPatternScanTest` | R07 | 🟡 半自动（正则覆盖常见写法） |
| §7 结构化日志（禁 `System.out`） | `ForbiddenPatternScanTest` | R11 | ✅ 全自动 |
| §10.5 禁止动态脚本 / eval | `ForbiddenPatternScanTest` | R12 | ✅ 全自动 |
| §4 工具描述质量 | 评审清单 | — | ❌ 人工 |
| §4 返回值带 evidence | 评审清单 | — | ❌ 人工 |
| §12.2 PR 只做一件事 | 评审清单 | — | ❌ 人工 |

**覆盖率**：映射表 23 条中，**17 条全自动、2 条半自动、4 条转人工**。

---

## 3. 四个检查器

| 检查器 | 位置 | 机制 |
|---|---|---|
| `ArchitectureRulesTest` | `platform/spec-checks/src/test/java/top/rongx/agent/arch/` | **ArchUnit** 7 条规则：A1 依赖方向、A2 domain 框架无关、A3 执行面隔离、A4 决策面无容器客户端、A6 禁 util/common 包、A7 tools 不启动进程、A9 禁字段注入 |
| `ForbiddenPatternScanTest` | 同上 | 表驱动正则扫描 `src/main/java`、`src/main/resources`，13 条规则（R01–R13） |
| `ConfigCredentialScanTest` | 同上 | 扫描决策面 `*.yml/*.properties/*.conf`，5 条凭证规则（C1–C5） |
| `MigrationGuardTest` | 同上 | 比对迁移文件 sha256 与基线清单（M1；基线缺失时跳过） |

> 已作为 Maven 模块接入 `platform/pom.xml`：**`mvn verify` 即执行，违规即构建失败**。
> 全部依赖 JUnit 5 + ArchUnit（只需引入 `archunit-junit5`，约 2MB）。

---

## 4. 落地顺序

| 优先级 | 规则 | 理由 |
|---|---|---|
| **P0（第一天）** | A1（依赖方向）、R01–R03（shell/SQL）、C1–C5（凭证）、M1（迁移锁） | 这几条违反一次就是事故 |
| P1（第一迭代） | A2–A4、A6、A7（架构边界）、R04–R06、R08、R10 | 一致性问题，越早越省 |
| P2（第二迭代） | R07、R09、R11–R13、A9 | 锦上添花，但能持续防退化 |

> **不要一次上全**。P0 四类规则能拦住 80% 的严重问题；一次上满 20+ 条会导致大量误报，然后团队集体关掉检查。

---

## 5. 误报与豁免

```java
// spec-ignore: R06 该字段是比率（0–1），不是金额，用 double 是有意为之
private double thresholdRatio;
```

| 规则 | 说明 |
|---|---|
| 豁免 MUST 带规则号 + 原因，写在同一行或上一行 | 无原因视为无效豁免 |
| 同一规则在同一模块被豁免 > 5 次 | 触发评审：是规则有问题还是代码有问题 |
| 豁免 MUST 登记到项目规约 §13 | 否则下次重构又出现 |

---

## 6. CI 集成（无额外服务）

```yaml
# 自研流水线 / 任意 CI 都适用
- name: verify
  run: ["mvn", "-B", "-q", "verify"]
```

产物：

| 文件 | 内容 |
|---|---|
| `target/surefire-reports/*.txt` | 逐条规则结果 |
| 失败信息格式 | `[R06] src/main/java/.../OrderBo.java:42 金额字段使用了 double` |

> 失败信息 MUST 含 **规则号 + 文件:行 + 规约条款**，否则开发者不知道去改什么。

---

## 7. 检查器自身也要被检查

> 现状（2026-11-15）：`platform/spec-checks/README.md` 的「已知限制」明确标注 **fixtures 自测待补**；当前只有真实代码扫描，规则正则失效不会被自动发现。下表为待落地要求，不是现状。

| 规则 | 做法 | 状态 |
|---|---|---|
| 每个检查规则 MUST 有"能抓到"的用例 | `src/test/resources/fixtures/` 放**故意违规**的样例文件，断言检查器能命中 | 待补 |
| 防止规则失效（正则写错、路径改了） | fixtures 测试失败即报警 | 待补 |
| 规则新增 MUST 同步更新本文 §2 映射表 | 评审时核对 | 执行中 |
