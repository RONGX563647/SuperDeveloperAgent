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
| R01–R15 | §5/§7/§10 | 禁令扫描（见下表） | 正则 |
| EC1–EC5 | §1.1 / §5.3 | 异常码契约：业务异常分组、禁止通用异常携带错误码、通用 throw 清零 | 正则 + 字节码 |
| C1–C5 | §1.1 A1 | 决策面配置不得含写凭证 | 正则 |
| M1 | §5.2 | 已发布的迁移文件不得修改 | sha256 基线 |

**R 规则**：R01 `Runtime.exec` · R02 `sh -c`/`bash -c`/`cmd /c` · R03 MyBatis `${}` · R04 `new Thread(` · R05 字段注入 · R06 `double` 金额 · R07 日志打凭证 · R08 `SELECT *` · R09 `java.util.Date` · R10 空 `catch {}` · R11 `System.out` · R12 `ScriptEngine`/`eval` · R13 `is_deleted` · R14 错误码字面量（`"E-xxx"`） · R15 通用 `throw new` 未配业务异常（已清零，新增即失败）。

> 只扫描**生产代码**（`src/main/java`、`src/main/resources`），测试代码不受 R01–R15 约束。

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

## 异常码契约

业务异常必须按业务分组，禁止把错误码拼进通用异常：

```java
// 违规
throw new IllegalArgumentException(ErrorCode.DRY_RUN_FAILED.code() + " dry-run 未通过");
// 合规
throw new EnvironmentException(ErrorCode.DRY_RUN_FAILED, "dry-run 未通过");
```

`ErrorCodeContractTest` 的四条硬约束：

| 编号 | 约束 |
|---|---|
| EC1 | 错误码码段（E-1xx…E-8xx）与业务分组一致，每个分组都有异常类 |
| EC2 | 禁止 `new IllegalArgumentException/IllegalStateException/RuntimeException(ErrorCode…)`，零容忍 |
| EC3 | 业务异常只能位于 `domain/error`（策略异常允许 `domain/policy`） |
| EC5 | 生产代码通用 `throw new` 已清零；基线必须保持为空，新增即失败 |

存量已全部迁移：参数校验、状态断言、内部失败分别映射到各组的
`*_INVALID_INPUT` / `*_INVALID_STATE` / `*_INTERNAL` 错误码，并由 `ErrorKind`
保持原 HTTP 语义（400 / 409 / 500）。

基线更新（仅允许收紧，正常应为空）：

```bash
mvn -q -pl spec-checks -Dtest=ErrorCodeContractTest -Derror.code.baseline.update=true test
```

只有错误码契约自身的守卫（`ErrorGroup.of`、`PlatformException` 构造器）保留通用异常；
确需豁免时在 `throw` 上一行写 `// spec-ignore: R15 <原因>`。

---

## 已知限制

| 限制 | 说明 |
|---|---|
| 无 fixture 自测 | 规则本身写错（正则失效）不会被发现。**待补**：`fixtures/` + 用 `-Dspec.root=fixtures` 跑一遍，断言每条规则都能命中样例 |
| R07 覆盖有限 | 只能抓"日志语句里出现 password/token 等词"的常见写法 |
| A1 依赖字节码 | 依赖模块必须是普通 jar；`agent-gateway` 已用 `-exec` 分类器避免 fat jar 遮住类 |
