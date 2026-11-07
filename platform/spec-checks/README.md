# spec-checks · 规约自动化检查

把《[项目规约 v1.0](../../docs/项目规约-v1.0.md)》里可机械判定的条款，变成 `mvn verify` 会红的测试。
**不引入新服务**、不装 PMD/Sonar、不写 shell——就是 JUnit 测试。

已接入构建：在 `platform/` 下执行 `mvn verify` 即会运行（模块排在最后，任一违规即构建失败）。

---

## 规则清单

| 编号 | 对应规约 | 检查内容 | 机制 |
|---|---|---|---|
| A1 | §2.1 | 依赖单向：Gateway→Runtime→Tools/Governance→Domain←Executor；Infra 只依赖 Domain | ArchUnit layeredArchitecture |
| A2 | §2.3 | `domain` 不依赖 Spring / MyBatis / AgentScope | ArchUnit |
| A3 | §1.1 A3 | `executor` 不含 LLM SDK | ArchUnit |
| A4 | §1.1 A1 | 决策面（gateway/runtime/tools/governance）不含 Docker/K8s 客户端 | ArchUnit |
| A6 | §2.2 | 禁止 `util` / `common` / `helper` 包 | ArchUnit |
| A7 | §4 | `tools` 不得依赖 `ProcessBuilder` | ArchUnit |
| A9 | §10.1 | 禁止字段注入 `@Autowired` | ArchUnit |
| R01–R13 | §5/§7/§10 | 禁令扫描（见下表） | 正则 |
| C1–C5 | §1.1 A1 | 决策面配置不得含写凭证 | 正则 |
| M1 | §5.2 | 已发布的迁移文件不得修改 | sha256 基线 |

**R 规则**：R01 `Runtime.exec` · R02 `sh -c`/`bash -c`/`cmd /c` · R03 MyBatis `${}` · R04 `new Thread(` · R05 字段注入 · R06 `double` 金额 · R07 日志打凭证 · R08 `SELECT *` · R09 `java.util.Date` · R10 空 `catch {}` · R11 `System.out` · R12 `ScriptEngine`/`eval` · R13 `is_deleted`。

> 只扫描**生产代码**（`src/main/java`、`src/main/resources`），测试代码不受 R01–R13 约束。

---

## 运行

```bash
# 全量（推荐：与 CI 一致）
mvn -q verify

# 只跑检查
mvn -q -pl spec-checks -am test -Dtest=ArchitectureRulesTest
mvn -q -pl spec-checks -am test -Dtest=ForbiddenPatternScanTest

# 指定扫描根（默认 = platform/）
mvn -q -pl spec-checks test -Dspec.root=/path/to/platform
```

---

## 豁免

```java
// spec-ignore: R06 该字段是比率 0-1，不是金额
private double thresholdRatio;
```

三条约束：**必须带原因**（不足 3 字视为无效，仍会失败）；同一规则同模块豁免 >5 次触发评审；**必须登记到项目规约 §13**。

---

## 迁移基线

`spec-checks/migration-baseline.txt`，每行 `<相对路径> <sha256>`：

```
agent-infra/src/main/resources/db/migration/V1__init.sql  9f3c…
```

发布后追加并提交；已在基线里的文件不允许再改（改了 M1 会红）。

---

## 已知限制

| 限制 | 说明 |
|---|---|
| 无 fixture 自测 | 规则本身写错（正则失效）不会被发现。**待补**：`fixtures/` + 用 `-Dspec.root=fixtures` 跑一遍，断言每条规则都能命中样例 |
| R07 覆盖有限 | 只能抓"日志语句里出现 password/token 等词"的常见写法 |
| A1 依赖字节码 | 依赖模块必须是普通 jar；`agent-gateway` 已用 `-exec` 分类器避免 fat jar 遮住类 |
