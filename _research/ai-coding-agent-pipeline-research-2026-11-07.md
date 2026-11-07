# AI 编程 Agent 与「人类审核 + CI/CD」自动化流水线 · 市场调研

**2026-11-07**，链接均为当日抓取的公开页面。

## 0. 方法与口径

`web_search` 未配置密钥，通用搜索引擎被拦截，Bing 长尾查询返回噪声，`github.com` 不可达。故改用**官方一手页面直取**。标注：**✅事实**、**🔶推断**、**⚠️缺口**（不做数字断言）。

## 1. AI 编程 Agent 现状对比

| 产品 | 形态 | 自主度 | 定价 | 基准 |
|---|---|---|---|---|
| **Claude Code** | CLI+IDE+Web/Slack+Actions | 最高：跑 Bash/测试、worktree 隔离、后台 subagent、agent teams | Pro/Max/**Ultra**/Team/Ent；企业实测≈**$13/人/活跃日** | Opus 5 居 SWE-bench Verified 首（97.00%） |
| **Cursor**（Anysphere，**2026-08-14 被 SpaceX 收购**） | IDE+Cloud Agents+CLI+Bugbot+Agent SDK | 高：云 agent、自托管机器、Rollouts/Security Review | $0/$8/$30/$100/$200 | 无官方分 |
| **GitHub Copilot** | 插件+**cloud agent**（异步出 bot PR）+code review | 中高：提 PR、跑 CI | ⚠️缺口 | 无；转向评审采纳率指标 |
| **OpenAI Codex** | 云端并行 agent（2025-05-16 首发 codex-1） | 中高 | 首发限 Pro/Business/Ent；⚠️现档位未核实 | GPT-5 Codex 58.75% |
| **Devin**（Cognition） | Desktop+Cloud+API+DeepWiki；自研 **SWE-2** | 高：并发 10→不限 | Free/**$20**/**$200**/Teams **$80+$40/席** | 未取得分数页 |
| **Google Jules** | 异步 agent（GitHub 集成） | 中高 | ⚠️缺口 | ⚠️缺口 |
| **通义灵码** | 插件+**Lingma IDE**+编程智能体（自主规划/终端执行） | 中高 | **个人与企业均免费** | 满意率>87%；Gartner 挑战者象限唯一中国厂商 |
| **Trae**（字节） | AI IDE+TraeWork 云任务 | 中高：并发 2/10/15/20 | Free/**$20**/**$60**/**$200** | 无披露 |
| **CodeGeeX / Qoder / 文心快码** | 插件；Qoder 为 Agentic 平台（Projects/Automations） | 中–中高 | ⚠️缺口（空渲染） | 无 |

**三条可判定的事实：**

1. **经典基准已失效。** Vals AI 因饱和归档 SWE-bench Verified（2026-09-01）：Opus 5 **97.00%**、DeepSeek V4 Pro 0813 **96.40%**、GPT-5.6 Sol **96.20%**（[榜单](https://www.vals.ai/benchmarks/swebench)）。✅
2. **Terminal-Bench 1.0 同样饱和，但留下"单任务成本"这一列**（[榜单](https://www.vals.ai/benchmarks/terminal-bench)，2026-01-12）：GPT-5.2 63.75%/**$1.40**、Sonnet 4.5 61.25%/$0.66、Gemini 3 Flash 60.00%/**$0.13**、**DeepSeek V3.2 50.00%/$0.02**、GLM 4.7 50%/$0.22、Qwen3 Max 36.25%/$0.48。✅ →🔶**对高频、可回滚的流水线任务，国产/开源模型性价比差 10–70 倍，这是最关键的成本事实。**
3. **前沿能力收敛**：Vals Index（2026-11-05）前三 Sonnet 5.5 70.85%、Gemini 4 Carbon 70.45%、Opus 5.5 70.18%。✅ →🔶**差异化不再来自模型，而来自 harness 与集成深度。**

**机制样本**（Claude Code 文档）：子代理默认最多 3 层、并发上限 20、`isolation: worktree`；hooks 覆盖 30+ 事件，`exit 2` 可硬阻断；`allowManagedHooksOnly` 限定只有受管 hooks 可运行（[sub-agents](https://code.claude.com/docs/en/sub-agents.md)、[hooks](https://code.claude.com/docs/en/hooks.md)）。✅ →**"人类审核"正从"人看 diff"下沉为"策略即代码"。**

## 2. 流水线真实落地情况

**（1）评审负担第一次被量化。** GitHub 2026-11-05 在 usage metrics API 增加 `copilot_code_review`：addressed rate、按 severity 分布，以及 **`total_merged_no_human_change_request_after_review`**——"经 Copilot 评审后无人再要求修改即合并"的 PR 数（[changelog](https://github.blog/changelog/2026-11-05-usage-metrics-api-adds-code-review-details-and-addressed-rate)）；2026-11-06 把采纳度面板扩到 9 种体验（[changelog](https://github.blog/changelog/2026-11-06-copilot-impact-dashboard-adds-chat-and-vs-code-agent-usage)）。✅ →🔶**接受率、无人工打回合并率已成可采购、可写进合同的运营指标。**

**（2）企业侧两例。** **Stripe**：Claude Code 铺到 **1,370 名工程师**、零配置；一团队 4 天迁 **10,000 行** Scala→Java（估 10 工程周）。关键细节：因"随机 JS 包不可接受"，与 Anthropic 联合做**签名企业二进制**绕开 npm 链；且同时启用 6 种助手，明确"尚未归因到任何单一助手"（[案例](https://claude.com/customers/stripe)）。✅ **Nokia Core Networks**：2 人 2 周分析 **5000 万+ 行**（原估 12+ 专家、数月），内部工具自动化约 **80%** 的原 6–10 人 PM 工作流，根因分析"数周→数天"（[cursor.com/blog/nokia](https://cursor.com/blog/nokia)）。✅（厂商口径）

**（3）反直觉但最关键的一条结论。** Cursor 公开"自驱动代码库"实验：单机峰值数百并发 agent、一周 **~1,000 commits/小时**、**1,000 万次工具调用**、无人工干预。他们明确写道：**要求"每次提交 100% 正确"会造成严重串行化与吞吐坍塌**（一个 typo 就让系统停摆、多 agent 越界互踩），故主动接受"小而稳定的错误率"，但**必须保留一条最终"绿色分支"：agent 定期打快照、跑 quick fixup pass 再发布**（[2026-02-05](https://cursor.com/blog/self-driving-codebases)）。✅

> 🔶**核心推断：** 这定义了"人类审核+CI/CD"的正确位置——**不是逐 commit 的强制门禁，而是"分批收敛 + 发布前绿灯"**。把 100% 正确性压到每个 AI 提交上，并发度一升就是瓶颈；取消绿分支，错误率直接进生产。

**（4）已公开的失败案例。** **Cursor 插件市场事件（2026-10-29）**：`Anthropic.claude-code` **2.1.312/2.1.313** 被篡改，运行后收集主机信息、向 `authorized_keys` 追加 `svc-telemetry` 公钥、开反向 SSH 隧道，并以计划任务 `SysMon`（尝试 SYSTEM）持久化、约 60 秒重试。Cursor 移除版本并**整体关停市场**、轮换密钥、重建市场（[公告](https://cursor.com/blog/claude-code-extension-security-notice)）。✅ →🔶**攻击面不是模型，是插件市场与自动更新通道。**

**（5）⚠️缺口：** Google/Meta/微软/Shopify/阿里/字节关于"AI 生成 PR 占比、自动合并率、事故率"的一手量化披露未取得，不作断言。

## 3. 代码生成器 + LLM

- **JHipster 未见 AI 集成。** 官网与 Marketplace 在 2026-11-07 **无任何 LLM/AI 表述**（[jhipster.tech](https://www.jhipster.tech/)）；**JHipster Lite 已移交 Seed4J 并改名**（[页面](https://www.jhipster.tech/jhipster-lite/)，最后更新 2022-09-14）。✅ →🔶**"JHipster AI"在一手资料中不存在。**
- **RuoYi 生成器是"表驱动 CRUD"**：在线配置表信息，一键生成含增删改查/排序/导出/权限的模块（[doc.ruoyi.vip](http://doc.ruoyi.vip/)）。✅

**组合的正确形态（🔶）**：生成器输入**结构化元数据**、输出**确定性模板代码**；LLM 弱在长程一致性，强在语义理解。所以不是"让 LLM 直接写 CRUD"，而是 **LLM 把需求变 spec → 生成器把 spec 变代码 → LLM 只补生成器覆盖不到的 20%**。这把不确定性关进 spec 层。

## 4. RuoYi 生态 AI 化盘点（Gitee，2026-11-07）

| 项目 | 规模 | 做了什么 | 评价 |
|---|---|---|---|
| **RuoYi** | **49.0K star / 27.3K fork**/v4.8.3/MIT | 官方**无 AI 模块**，仅代码生成器；生态位在卖低代码（含 AI 低代码 JNPF） | 底座最稳、AI 为零 |
| **ruoyi-vue-pro（芋道）** | **111.9K star / 20.5K fork**/v2026.10 | 仓内已有 **`yudao-module-ai`** 与 **`yudao-module-ai1-springai`**（Spring AI） | star 为官方仓 2.3 倍；AI 是"业务模块"非"研发助手" |
| **ruoyi-ai（ageerle）** | **5.1K star / 2.5K fork**/v3.1.0/MIT/GVP | "企业级 AI 应用开发框架"：多厂商接入、知识库、流程编排、多智能体、**兼容主流 Agent Skill 协议**；含 `scripts/sandbox` | 生态内 AI 化最完整，但是"用若依做 AI 应用" |
| **RuoYi-Vue-Plus** | 已迁 **dromara**（旧仓仅跳转） | 无 AI 模块 | 治理升级 |
| **RuoYi Office**（商业） | 称服务 **100+** 企业 | 平台 + **企业管理智能体**（在身份/权限/流程约束下查数据、生成单据） | 唯一把 Agent 嵌进权限与流程的商业样本 |

**共性缺陷（🔶）：** ①"给系统加 AI"多，"给开发者加 agent"无——没有一个在做能读代码库、跑测试、提 PR 的助手；②**元数据资产被浪费**，把"表→模块→权限→菜单"当普通文本喂模型；③ star 极端不均（111.9K vs 5.1K），说明**需求规模尚未验证**。⚠️ NocoBase/jeecg/Halo 的 AI 模块未取得可核验页面，不列数据。

## 5. 合规与风控

1. **供应链安全是第一风险且已有实战案例**（§2-4）；Stripe 的答案是**放弃公共包管理器、改用签名二进制**。🔶**若依若做技能市场，签名校验、版本钉死、最小权限沙箱、一键回滚缺一不可，否则一次投毒即摧毁生态信任。**
2. **许可证/版权**：RuoYi 与 ruoyi-ai 均为 **MIT**（LICENSE 实读），但 MIT 只约束软件分发，**不解决 AI 生成代码的权属与第三方片段复现**。🔶**采购真正会问的是"归谁、能否闭源商用、有无 IP 赔付"——合同问题。**
3. **代码出境**：Claude Code 存在 **1.1× data residency 费率**，并提供 Bedrock/Foundry/自托管形态（[costs.md](https://code.claude.com/docs/en/costs.md)）。✅ →🔶**对政企金融这是硬约束，选项被压缩为"私有化 + 国产模型"。**
4. **审计留痕已可工程化**：hooks + managed settings + OTel，"每次工具调用可记录、可阻断、可归因到人"（[hooks](https://code.claude.com/docs/en/hooks.md)）。✅ →🔶**审计将从"日志留存"升级为"决策可复现"。**
5. **采购顾虑排序（🔶）**：①代码/数据出境 →②插件投毒 →③权属与赔付 →④审计可追溯 →⑤ROI 归因（Stripe 直言无法归因，这本身就是摩擦点）。

## 6. 市场规模与渗透率

**可核验渗透率**（Anthropic 委托 Material 调研 500+ 技术负责人，2025-12-09，[链接](https://claude.com/resources/articles/how-enterprises-are-building-ai-agents-in-2026)）：近 **90%** 组织用 AI 辅助开发；**86%** 已将 agent 用于生产代码；**57%** 用于多阶段工作流；时间节省：编码/文档/**评审与测试均为 59%**；**80%** 称已获可量化回报；障碍：集成 46%、数据质量 42%、变更管理 39%。✅（厂商委托）

**价格带四锚点（✅）**：免费层（通义灵码、Copilot Free）；**$20** 个人主力（Devin Pro、Trae Pro）；**$60–100** 重度（Trae Pro+、Cursor Super）；**$200** 顶配（Cursor Ultra、Devin Max、Trae Ultra）。企业侧席位+用量双计。**规模信号**：Cursor ARR 三个月翻倍至 **$20 亿**（Bloomberg 2026-03-02，经官方 press 引用）。✅ →🔶**平台级市场，但通用层已被资本与模型能力双重挤压，机会只在垂直、私有化、流程嵌入侧。**

⚠️ **缺口：** Gartner、IDC 规模预测与 Octoverse 采用数据未取得（Cloudflare 校验/域名不可达），拒绝以记忆数字充数。

## 7. 结论：RuoYi 类框架的差异化机会与最大风险

### 机会（🔶）

1. **把元数据变成别人拿不到的上下文。** 通用 agent 看到的是**文件树**，RuoYi 助手能看到**业务语义图**（表→实体→Mapper→Service→Controller→菜单→权限点）。同一句"订单加审批流"，前者靠猜，后者可定位确定的 6 个文件并复用既有权限模型。**这是唯一不会被模型厂商下次发版抹平的优势。**
2. **spec-first、codegen-second，而非 prompt-to-code。** 副产品是天然可审计：spec 人可读可审、diff 模板化、回归可自动。**审 spec 比审 2000 行 diff 便宜一个数量级。**
3. **把"发布前绿灯"做成产品而非流程**：worktree 隔离、按模块跑测试、生成器回归、依赖白名单、健康分支自动收敛——正是通用工具"给原语不给流水线"的空档。
4. **私有化 + 国产模型 + 成本结构**：$0.02 vs $1.40/任务的差距叠加"代码不出境"，使"国产模型+私有化+框架内上下文"成为政企/金融/制造的唯一可交付组合；100+ 商业客户是现成分发渠道。

### 风险（按严重度）

1. **（致命）需求未被验证。** 若依用户画像是"快速交付后台系统的实施/外包/中小企业团队"，痛点是**交付速度与人力成本**，未必是"评审负担"。生态 star 最高的 AI 尝试是"AI 应用框架"而非"AI 研发助手"——**生态用脚投票投给了"用若依卖 AI"**。不先验证，极易做出"开发者叫好、付费者不买单"的产品。
2. **（结构性）上游降维打击。** Cursor/Claude Code 正从编辑器变平台（Cloud Agents、Agent SDK、Rollouts、Security Review、自托管），并已瞄准"合并 PR、管理发布"。**一旦"框架感知"被做成可配置 skill，结构化优势会被压缩成一个插件的工作量。** 唯一防御是把优势沉到**生成器与运行时**，而非提示词。
3. **（信任）插件市场是自杀式攻击面**：签名、钉版本、最小权限、回滚缺一不可；**没有基建就开放市场＝一次性抵押生态信任。**
4. **（合规）MIT 不等于免责**：私有化、模型可替换、审计留痕须进架构而非事后补丁。

### 最小可行路线（🔶）

① 不做通用 agent，先做**"若依专用 reviewer + 生成器前端"**（需求→spec/表设计+diff 预览；对 AI 改动跑生成器回归+单测并出 spec 级摘要）；② 把"绿分支"做成内置流水线，用数据证明"评审时间下降"而非"代码量上升"；③ 用四个指标自证：spec 采纳率、AI 变更 CI 通过率、无人工打回合并率、单 PR 评审时长——**没有这四个数进不了采购流程。**

---

**核查状态**：Claude Code 机制、Cursor 归属/定价/投毒、Copilot 指标、两个基准榜单、Stripe/Nokia 案例、RuoYi 系五仓、JHipster 现状、通义灵码/Trae/Devin 定价均已官方一手直取。**未取得**：Google/Meta/微软/Shopify 的 AI-PR 数据；Gartner/IDC/Octoverse；Jules、Codex 现档位、Copilot 官方定价；AI 生成代码版权判例与 EU AI Act 原文（故未做法条断言）。
