---
name: frontend-settings
description: 设置页与前端约定：面板组织、API 层、零依赖测试、界面回归脚本、类型生成
covers: [SettingsView, SandboxImagePanel, api/settings.ts, imageReadiness.ts, run-tests.mjs]
when_to_read: 加/改设置页面板/前端接口封装/前端测试/vite 代理/样式约定/界面截图验证
budget: 110
---

# 设置页与前端约定（frontend-settings）

## 关键文件

| 文件 | 角色 |
|---|---|
| `platform/web/src/views/SettingsView.vue` | 分区导航（BASIC/ADVANCED 分组），面板懒挂载 |
| `platform/web/src/components/settings/*.vue` | 各面板；`onMounted(load)` + `defineExpose({ load })` |
| `platform/web/src/api/settings.ts` | 接口封装（`apiFetch`，Bearer token） |
| `platform/web/tools/run-tests.mjs` | 零依赖测试运行器（发现 `src/**/*.test.ts`） |
| `platform/web/scripts/*-check.mjs` | 界面回归脚本（登录→断言→截图） |

## 关键事实

- 新面板三步：`components/settings/XPanel.vue` → `SettingsView.vue` 的 SECTIONS/模板/reloadSection →
  `api/settings.ts` 加接口。分区默认收起的放 `ADVANCED`。
- **纯展示逻辑抽 `.ts`**（如 `imageReadiness.ts`），才能脱离 Vue 单测；组件里只留装配。
- 测试是 `check(name, cond)` 零依赖脚本，不是 vitest；`npm test` 全量跑，门禁"前端自检"就是它。
- 改了后端接口要 `npm run gen:api-types` 同步 `schema.d.ts`（CI 会 `check:api-types` 卡差异）。
- 界面脚本句式统一：`node scripts/<name>.mjs [base] [截图路径]`，需后端 + dev server；
  凭据默认种子账号 `devops/admin-2026`，用 `CHECK_USER`/`CHECK_PASS` 覆盖。
- el-input 取值要用原生 setter + `input` 事件（puppeteer 下 triple-click 选不中组件值）。
- 提交前：`npm run typecheck` + `npm test`；构建 `npm run build`（vue-tsc strict）。

## 找历史

`python tools/docs/find.py --module web`；`docs/code-wiki/09-frontend.md` 有完整架构。
