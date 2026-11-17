---
canonical: docs/summary/2026-11/2026-11-16_1641_model-chat-ux.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T10:23:35+08:00
extends: tool-schema-token-budget
refs:
  - feat/2026-11-16_1641_model-chat-ux
metrics:
  sessions: 1
  casesAdded: 16
  casesTotal: 478
  passRate: "100%"
  lineCoverage: "37.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 会话级模型选择、确认卡续跑与多版本部署

## 概述
会话不能选模型且无用量统计；工具确认后需手动继续；部署只能靠 git hash 覆盖，旧版本无法保留；无 Dockerfile 时静默降级 HOST 产生孤儿进程
会话级模型/网关选择、token 统计与会话时间线入库；确认卡内嵌对话并自动续跑；市场分页懒加载并兼容系统根证书；部署支持版本号+tag 多版本与单容器卷挂载；无 Dockerfile 显式阻断并提供 HOST 进程排障；前端侧栏折叠、环境 Tab 与部署矩阵

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `89cb898` | feat | feat(workspace): 任务看板上移工作区并强化调度编排 |
| `8da8302` | feat | feat(chat): 会话级模型选择、token 用量统计与会话时间线入库 |
| `886e7a3` | feat | feat(chat): 确认卡内嵌当前对话并支持变更应用后自动续跑 |
| `44e4394` | feat | feat(settings): 模型目录同步与用量统计面板 |
| `200d1d2` | feat | feat(tools): 子 Agent 变更单回填与补丁白名单补充 |
| `f2e6ac5` | bug | fix(deploy): HOST 部署进程占用与构建现场清理 |
| `8d3fb5a` | feat | feat(market): 能力市场分页懒加载、技能包安装与信任库兼容 |
| `05621c2` | bug | fix(security): 主密钥文件权限收紧兼容 Windows ACL |
| `0b21f44` | chore | chore(build): JDK 24+ native-access 放行与运行日志配置 |
| `249a2a0` | feat | feat(ui): 侧栏可拖拽折叠与全局布局密度优化 |
| `844dff0` | feat | feat(workspace): 新增环境 Tab 与版本×环境部署矩阵 |
| `26741f8` | feat | feat(deploy): 版本号与打 tag 多版本部署，单容器支持卷挂载 |
| `bddd38a` | bug | fix(deploy): 无 Dockerfile 显式阻断、HOST 进程排障与 read_file 行窗口 |
| `c6d150d` | feat | feat(chat): 状态栏增加本会话 Skill/MCP 快捷开关 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 478, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：16
- 行覆盖率：37.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 开发环境固定单槽部署，忽略 tag

## 链接
- 归档：`docs/process/feat/2026-11-16_1641_model-chat-ux/manifest.json`
- 上一份同域总结：tool-schema-token-budget
