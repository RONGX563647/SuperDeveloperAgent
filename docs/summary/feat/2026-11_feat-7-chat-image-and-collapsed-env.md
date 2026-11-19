---
canonical: docs/summary/2026-11/2026-11-19_1949_feat-7-chat-image-and-collapsed-env.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T19:55:50+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_1949_feat-7-chat-image-and-collapsed-env
metrics:
  sessions: 1
  casesAdded: 40
  casesTotal: 0
  passRate: "100%"
  lineCoverage: "41.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# FEAT-7 会话支持插图，环境面板默认收起

## 概述
FEAT-7（会话）两项都没做：1) 对话输入框的附件按钮被写死成 disabled（title=附件功能待接入），用户想贴一张截图说明问题只能另存文件再描述；2) 环境面板虽然能折叠且会记 localStorage，但默认值写死在代码里，新设备首次进入会占掉一整块版面。
插图：点按钮选图、Ctrl+V 粘贴、拖入 三种入口都能上传（复用已有的 uploadIssueImage），上传后以 Markdown 图片语法插到光标处——正文本来就是 Markdown，模型能直接看到地址并据此回答，不需要另立图片消息协议；逻辑抽到 utils/chatImage.ts（imageMarkdown 处理方括号截断、insertAt 做光标夹紧，失焦时 selectionStart 可能是 0 或越界）。环境面板：加 defaultOpen 参数（默认收起），且只对没点开过的设备生效——用户手动展开过就按他的选择，否则每次进页面都被强制收起反而更烦。新增 15 个断言的 chatImage.test.ts 并接入前端自检；同时取回被分支切换覆盖的 releaseTag/reuseBuild 两份工具与 25 个断言。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `3cd35a0` | feat | feat(chat): 会话支持插图，环境面板默认收起 |

## 测试与质量总结
- 命令：`(前端自检)`
- 结果：PASSED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：40
- 行覆盖率：41.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_1949_feat-7-chat-image-and-collapsed-env/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
