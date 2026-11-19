---
canonical: docs/summary/2026-11/2026-11-19_1249_workdir-mount-escape.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T12:52:53+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-19_1249_workdir-mount-escape
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 194
  passRate: "100%"
  lineCoverage: "39.8%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 拦截跨平台绝对路径，堵住 stage.workdir 挂载逃逸

## 概述
resolveWorkdir 只靠 resolved.startsWith(base) 判断越界，而它依赖当前平台是否把该写法认作绝对路径：workdir: E:/Windows 在 Windows 上被拒，在 Linux 上被当作相对名拼成 <base>/E:/Windows 后放行，被原样拼进 docker run -v 的宿主路径。stage.workdir 可被模型或补丁改动，构成绕过沙箱隔离的挂载逃逸面。原用例硬编码 Windows 盘符路径，Linux 上断言虽过但语义跑偏，被误以为已覆盖。
resolveWorkdir 增加 isAbsoluteAnyPlatform，在 resolve 之前显式拒绝任一端认为绝对的写法：POSIX 根与 UNC、Windows 盘符（E:/ 与 C:\\）、盘符相对形式 E:foo。用例改用 java.io.tmpdir 构造绝对目录，拆成三条：相对路径上跳被拒、合法相对路径不被误拦、绝对路径全拒，且全部跨平台执行，不再用 @EnabledOnOs 跳过。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `538f0b8` | bug | fix(executor): 拦截跨平台绝对路径，堵住 stage.workdir 挂载逃逸 |
| `5f0e4cf` | chore | test(executor): workdir 越界用例改为真跨平台，不再靠禁用掩盖差异 |
| `bd0245b` | chore | test(executor): 补 workdir 相对路径的跨平台一致性用例 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-executor,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-executor test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 194, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：1
- 行覆盖率：39.8%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-19_1249_workdir-mount-escape/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
