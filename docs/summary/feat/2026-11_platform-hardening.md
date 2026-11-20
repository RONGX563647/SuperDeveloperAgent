---
canonical: docs/summary/2026-11/2026-11-16_0021_platform-hardening.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T10:23:21+08:00
extends: tool-schema-token-budget
refs:
  - feat/2026-11-16_0021_platform-hardening
metrics:
  sessions: 1
  casesAdded: 55
  casesTotal: 449
  passRate: "100%"
  lineCoverage: "37.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 平台加固：有界命令、部署配置与镜像自动拉取

## 概述
外部命令无界等待、JDK/镜像硬依赖、Windows 私有仓库认证失败；缺少部署配置工具且镜像不会自动拉取
引入有界命令原语与 OS 自适应解释器、按当前 JVM 解析路径；新增平台部署配置工具与镜像自动拉取；补齐稳定性回归并自动释放 SQLite 连接池

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `30be8dc` | bug | fix(web): 工作区助手切走不再中断输出，并补齐流式体验 |
| `0d1f3de` | bug | fix(executor): 消除外部命令无界等待、镜像与 JDK 硬依赖 |
| `f4f9e49` | bug | fix(gateway,infra): 接入有界原语并修 Windows 私有仓库认证 |
| `3d4a763` | chore | test: 补齐稳定性回归、修正部署夹具并自动释放 SQLite 连接池 |
| `ac4534a` | chore | chore(build): 目标 Java 版本可覆盖，不绑定 JDK 安装路径 |
| `0345fc1` | feat | feat(web): 工作台展示运行阶段进度与自动修复状态 |
| `435c17f` | feat | feat(platform): 新增部署配置工具与镜像自动拉取，并修正非法 YAML 报错契约 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 449, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：55
- 行覆盖率：37.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- JDK 安装路径仍可按环境覆盖，但默认跟随当前 JVM

## 链接
- 归档：`docs/process/feat/2026-11-16_0021_platform-hardening/manifest.json`
- 上一份同域总结：tool-schema-token-budget
