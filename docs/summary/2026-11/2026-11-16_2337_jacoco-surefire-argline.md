---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-16T23:41:04+08:00
extends: null
refs:
  - chore/2026-11-16_2337_jacoco-surefire-argline
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 506
  passRate: "100%"
  lineCoverage: "37.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 修复 JaCoCo 覆盖率未生成

## 概述
summary 的 lineCoverage 为 N/A：父 pom 的 surefire argLine 写死，覆盖了 jacoco prepare-agent 注入的参数，agent 从未挂载
surefire argLine 改为 @{argLine} --enable-native-access=ALL-UNNAMED，保留 JDK 受限方法参数并让 JaCoCo agent 生效

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `7acfa87` | chore | chore: 修复 surefire argLine 覆盖导致 JaCoCo 无数据 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 506, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：0
- 行覆盖率：37.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-16_2337_jacoco-surefire-argline/manifest.json`
- 上一份同域总结：null
