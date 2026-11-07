# -*- coding: utf-8 -*-
"""生成《若依 × AgentScope Java 可行性分析与市场调研》汇报演示稿。"""
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE

INK = RGBColor(0x0F, 0x2A, 0x43)
INK_SOFT = RGBColor(0x3C, 0x55, 0x6E)
ACCENT = RGBColor(0xE0, 0x6C, 0x2A)
ACCENT_SOFT = RGBColor(0xFD, 0xEE, 0xE4)
TEAL = RGBColor(0x1B, 0x7A, 0x7A)
GOOD = RGBColor(0x1E, 0x7D, 0x4F)
WARN = RGBColor(0xC2, 0x7A, 0x0E)
BAD = RGBColor(0xB3, 0x33, 0x33)
GRAY = RGBColor(0x6B, 0x7A, 0x88)
LIGHT = RGBColor(0xF2, 0xF5, 0xF7)
LINE = RGBColor(0xD8, 0xDF, 0xE6)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)

FONT = "微软雅黑"
W, H = 13.333, 7.5

prs = Presentation()
prs.slide_width = Inches(W)
prs.slide_height = Inches(H)


def tb(slide, l, t, w, h, text="", size=14, bold=False, color=INK,
       align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, spacing=1.0, font=FONT):
    box = slide.shapes.add_textbox(Inches(l), Inches(t), Inches(w), Inches(h))
    tf = box.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = 0
    tf.margin_right = 0
    tf.margin_top = 0
    tf.margin_bottom = 0
    lines = text.split("\n")
    for i, ln in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.line_spacing = spacing
        r = p.add_run()
        r.text = ln
        r.font.size = Pt(size)
        r.font.bold = bold
        r.font.color.rgb = color
        r.font.name = font
    return box


def rect(slide, l, t, w, h, fill=None, line=None, lw=1.0, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.06):
    s = slide.shapes.add_shape(shape, Inches(l), Inches(t), Inches(w), Inches(h))
    if shape == MSO_SHAPE.ROUNDED_RECTANGLE:
        try:
            s.adjustments[0] = radius
        except Exception:
            pass
    if fill is None:
        s.fill.background()
    else:
        s.fill.solid()
        s.fill.fore_color.rgb = fill
    if line is None:
        s.line.fill.background()
    else:
        s.line.color.rgb = line
        s.line.width = Pt(lw)
    s.shadow.inherit = False
    s.text_frame.word_wrap = True
    return s


def shape_text(s, text, size=12, bold=False, color=INK, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.MIDDLE):
    tf = s.text_frame
    tf.vertical_anchor = anchor
    tf.margin_left = Inches(0.12)
    tf.margin_right = Inches(0.12)
    tf.margin_top = Inches(0.05)
    tf.margin_bottom = Inches(0.05)
    for i, ln in enumerate(text.split("\n")):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        r = p.add_run()
        r.text = ln
        r.font.size = Pt(size)
        r.font.bold = bold
        r.font.color.rgb = color
        r.font.name = FONT


def blank():
    return prs.slides.add_slide(prs.slide_layouts[6])


def header(slide, kicker, title, sub=None):
    rect(slide, 0, 0, W, 0.09, fill=INK, shape=MSO_SHAPE.RECTANGLE)
    tb(slide, 0.72, 0.42, 9.0, 0.3, kicker, size=10.5, bold=True, color=ACCENT)
    tb(slide, 0.72, 0.70, 11.6, 0.6, title, size=25, bold=True, color=INK)
    if sub:
        tb(slide, 0.72, 1.30, 11.6, 0.36, sub, size=12.5, color=GRAY)
    rect(slide, 0.72, 1.72 if sub else 1.42, 0.86, 0.05, fill=ACCENT, shape=MSO_SHAPE.RECTANGLE)


def footer(slide, n, total=10):
    tb(slide, 0.72, 7.03, 6.0, 0.26, "若依 × AgentScope Java ｜ 可行性分析与市场调研 v1.0", size=9, color=GRAY)
    tb(slide, W - 1.4, 7.03, 0.7, 0.26, f"{n:02d} / {total}", size=9, color=GRAY, align=PP_ALIGN.RIGHT)


def table(slide, rows, l, t, w, col_w, row_h=0.36, head_h=0.40, size=11, head_size=11,
          head_fill=INK, head_color=WHITE, zebra=LIGHT, align_first_left=True):
    n_r, n_c = len(rows), len(rows[0])
    gt = slide.shapes.add_table(n_r, n_c, Inches(l), Inches(t), Inches(w), Inches(head_h + row_h * (n_r - 1))).table
    for i, cw in enumerate(col_w):
        gt.columns[i].width = Inches(cw)
    gt.rows[0].height = Inches(head_h)
    for r in range(1, n_r):
        gt.rows[r].height = Inches(row_h)
    for r in range(n_r):
        for c in range(n_c):
            cell = gt.cell(r, c)
            cell.text = ""
            cell.margin_left = Inches(0.10)
            cell.margin_right = Inches(0.06)
            cell.margin_top = Inches(0.02)
            cell.margin_bottom = Inches(0.02)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            if r == 0:
                cell.fill.solid()
                cell.fill.fore_color.rgb = head_fill
            else:
                cell.fill.solid()
                cell.fill.fore_color.rgb = WHITE if r % 2 == 1 else zebra
            p = cell.text_frame.paragraphs[0]
            p.alignment = PP_ALIGN.LEFT if (c == 0 and align_first_left) else PP_ALIGN.LEFT
            run = p.add_run()
            run.text = str(rows[r][c])
            run.font.size = Pt(head_size if r == 0 else size)
            run.font.bold = (r == 0)
            run.font.color.rgb = head_color if r == 0 else INK_SOFT
            run.font.name = FONT
    return gt


# ---------------------------------------------------------------- 1 封面
s = blank()
rect(s, 0, 0, W, H, fill=INK, shape=MSO_SHAPE.RECTANGLE)
rect(s, 0, 0, 0.16, H, fill=ACCENT, shape=MSO_SHAPE.RECTANGLE)
tb(s, 1.05, 1.55, 8.0, 0.3, "FEASIBILITY  &  MARKET  STUDY", size=12, bold=True, color=ACCENT)
tb(s, 1.05, 2.02, 11.0, 1.0, "给若依装一个「会干活的 Agent」", size=42, bold=True, color=WHITE)
tb(s, 1.05, 3.10, 10.6, 1.1,
   "RuoYi 4.8.3  ×  AgentScope Java 2.x\n多身份 Agent 增强模块：技术可行性、架构设计与市场机会",
   size=17, color=RGBColor(0xC6, 0xD4, 0xE0), spacing=1.35)
rect(s, 1.05, 4.55, 2.2, 0.05, fill=ACCENT, shape=MSO_SHAPE.RECTANGLE)
tb(s, 1.05, 4.78, 6.0, 0.32, "2026-11-07  ·  v1.0  ·  基于两个本地仓库的一手代码分析",
   size=11.5, color=RGBColor(0x93, 0xA8, 0xB8))
tb(s, 1.05, 6.55, 9.0, 0.3, "证据等级：A 代码可验证 ｜ B 官方文档 ｜ C 公开信息（本环境未联网复核） ｜ D 推断",
   size=9.5, color=RGBColor(0x6E, 0x86, 0x9A))

# ---------------------------------------------------------------- 2 结论先行
s = blank()
header(s, "CONCLUSION", "结论先行：能做，但要克制", "三个判断，决定这个项目该不该立项、该怎么立项")

cards = [
    ("① 技术可行", GOOD, "权限模型「结构同构」",
     "若依的 sys_menu.perms / 角色 / 数据范围 / 操作日志，\n可以近乎一一映射到 AgentScope 的\nPermissionRule + ToolGroup。\n\n改动集中在新增 ruoyi-agent 模块，\n不动既有 30 个 Controller。"),
    ("② 有 5 个必须验证的坑", WARN, "D 级风险，3 天可证伪",
     "· Spring Boot 4.1.0 vs BOM 4.0.4\n· Jackson 2 与 Jackson 3 并存\n· 本地 2.1.0-SNAPSHOT，须锁 GA\n· Shiro ThreadLocal → dataScope 失效\n· SessionLogStore 无 DB 实现"),
    ("③ 只做一件事", ACCENT, "P0 + P1，6–8 周",
     "「权限对齐的只读问答\n + 写操作人工审批」\n\n先证明 AI 能提效，\n而不是证明 AI 能干活。\n开发者场景（P3）独立立项。"),
]
x = 0.72
for title, color, tag, body in cards:
    rect(s, x, 1.92, 3.86, 1.06, fill=LIGHT, line=LINE)
    rect(s, x, 1.92, 0.07, 1.06, fill=color, shape=MSO_SHAPE.RECTANGLE)
    tb(s, x + 0.22, 2.06, 3.5, 0.34, title, size=15, bold=True, color=color)
    tb(s, x + 0.22, 2.44, 3.5, 0.3, tag, size=10.5, bold=True, color=INK_SOFT)
    rect(s, x, 3.06, 3.86, 2.62, fill=WHITE, line=LINE)
    tb(s, x + 0.22, 3.22, 3.46, 2.3, body, size=11.5, color=INK_SOFT, spacing=1.28)
    x += 4.03

rect(s, 0.72, 5.92, 11.92, 0.82, fill=ACCENT_SOFT, line=None)
tb(s, 0.95, 6.08, 11.5, 0.5,
   "一句话：难点不在 AI，而在「可执行动作的治理」——谁允许它做、做到哪一步要人点头、做完怎么查。",
   size=14, bold=True, color=RGBColor(0x9A, 0x45, 0x14))
footer(s, 2)

# ---------------------------------------------------------------- 3 三种身份
s = blank()
header(s, "SEGMENTATION", "三种身份，其实是三个产品",
       "技术栈重叠，但产品逻辑、失败代价、技术路径完全不同 —— 混在一起做必然四不像")

cols = [
    ("运营 / 业务人员", TEAL, "不改代码完成日常操作",
     [("入口", "后台悬浮对话框 / 钉钉飞书"),
      ("动作", "查数据、改单据、跑报表"),
      ("失败代价", "数据被改错（可回滚，要审计）"),
      ("技术路径", "工具调用 + HITL 审批"),
      ("绝不能", "让它自由写 SQL 直连库")]),
    ("开发者", INK, "从需求到可合并 PR 的加速",
     [("入口", "IDE / Git 平台 / 研发助手页"),
      ("动作", "建表、生成、改码、跑测试、提 PR"),
      ("失败代价", "代码带病上线（不可回滚，要门禁）"),
      ("技术路径", "Harness + 沙箱 + Skill + 外部 CI"),
      ("绝不能", "直接 push main / 直接发布")]),
    ("管理者 / 审计", ACCENT, "看得见、管得住、算得清",
     [("入口", "用量看板 / 审计报表"),
      ("动作", "看调用、查审批、控配额"),
      ("失败代价", "越权无人知（合规事故）"),
      ("技术路径", "全量工具日志 + 双写操作日志"),
      ("绝不能", "只统计 token 不记录「谁批准」")]),
]
x = 0.72
for name, color, tag, rows in cols:
    rect(s, x, 1.98, 3.86, 4.55, fill=WHITE, line=LINE)
    rect(s, x, 1.98, 3.86, 0.72, fill=color)
    tb(s, x + 0.2, 2.10, 3.5, 0.3, name, size=14.5, bold=True, color=WHITE)
    tb(s, x + 0.2, 2.40, 3.5, 0.24, tag, size=10, color=RGBColor(0xEC, 0xF1, 0xF4))
    y = 2.86
    for k, v in rows:
        tb(s, x + 0.2, y, 0.95, 0.26, k, size=10, bold=True, color=GRAY)
        tb(s, x + 1.15, y, 2.55, 0.62, v, size=10.5, color=INK_SOFT, spacing=1.18)
        y += 0.72
    x += 4.03

tb(s, 0.72, 6.66, 11.9, 0.3,
   "建议：两个 Agent 定义（ReActAgent 轻量运营 / HarnessAgent 开发者），共享同一套运行时、权限与审计。",
   size=11.5, bold=True, color=INK)
footer(s, 3)

# ---------------------------------------------------------------- 4 结构同构
s = blank()
header(s, "WHY IT FITS", "五个「恰好对上」的结构同构点",
       "不是硬凑：若依已有的四样东西，正是 AgentScope 权限体系需要的输入")
rows = [
    ["若依已有（A 级）", "AgentScope 对应（B 级）", "带来的价值"],
    ["sys_menu.perms\n例如 system:user:edit", "PermissionRule(tool, rule, ALLOW/DENY/ASK)", "权限体系零改造复用，不新建 ACL"],
    ["sys_role / sys_role_menu", "Toolkit + ToolGroup（按角色装配）", "角色决定「能看见哪些工具」"],
    ["dataScope（全部/本部门/本人）", "ToolCallInterceptor + beforeToolCall", "数据范围在参数层强制，不靠模型自觉"],
    ["@Log → sys_oper_log", "ToolExecutionCallback + 工具日志", "审计双写，满足等保留痕要求"],
    ["ruoyi-quartz 定时任务", "PermissionMode.DONT_ASK 无人值守", "「每天 8 点自动出经营日报」可直接落地"],
]
table(s, rows, 0.72, 2.02, 11.92, [3.5, 4.1, 4.32], row_h=0.66, size=11, head_size=11.5)

rect(s, 0.72, 5.86, 11.92, 0.78, fill=LIGHT, line=None)
tb(s, 0.95, 5.98, 11.4, 0.55,
   "这意味着：权限配置只有一处——运营改「角色菜单」就改完了，不用碰 Agent 代码。这是能不能长期活下去的关键。",
   size=12.5, bold=True, color=INK)
footer(s, 4)

# ---------------------------------------------------------------- 5 架构链路
s = blank()
header(s, "ARCHITECTURE", "一次对话的完整链路", "关键设计：权限判定发生在工具执行之前，且不依赖模型的判断")

boxes = [
    ("浏览器 / IM", "SSE 流式", LIGHT),
    ("AgentChat\nController", "鉴权 + 抓 LoginUser", LIGHT),
    ("权限桥\nShiroBridge", "perms → Rules", ACCENT_SOFT),
    ("ReActAgent\nstreamEvents", "工具调用循环", ACCENT_SOFT),
    ("权限引擎\nALLOW/DENY/ASK", "写操作必问人", ACCENT_SOFT),
    ("工具 = 若依\nService", "dataScope 显式注入", LIGHT),
]
x = 0.72
for i, (name, tag, fill) in enumerate(boxes):
    rect(s, x, 2.10, 1.72, 1.06, fill=fill, line=LINE)
    tb(s, x + 0.12, 2.22, 1.48, 0.5, name, size=11, bold=True, color=INK, spacing=1.1)
    tb(s, x + 0.12, 2.72, 1.48, 0.34, tag, size=8.8, color=GRAY)
    if i < len(boxes) - 1:
        tb(s, x + 1.74, 2.48, 0.24, 0.3, "›", size=17, bold=True, color=ACCENT, align=PP_ALIGN.CENTER)
    x += 1.98

# 分支
rect(s, 0.72, 3.52, 3.6, 1.42, fill=WHITE, line=GOOD, lw=1.4)
tb(s, 0.95, 3.66, 3.2, 0.3, "ALLOW · 只读", size=12, bold=True, color=GOOD)
tb(s, 0.95, 3.98, 3.2, 0.86, "直接执行 → 结果脱敏 → 回传模型\n例：查用户、查日志、查任务", size=10.5, color=INK_SOFT, spacing=1.25)

rect(s, 4.44, 3.52, 4.0, 1.42, fill=WHITE, line=WARN, lw=1.4)
tb(s, 4.67, 3.66, 3.6, 0.3, "ASK · 写操作（默认）", size=12, bold=True, color=WARN)
tb(s, 4.67, 3.98, 3.6, 0.86, "暂停 → 审批卡片显示字段级 diff\n→ 人点头 → ConfirmResult 恢复执行", size=10.5, color=INK_SOFT, spacing=1.25)

rect(s, 8.56, 3.52, 4.08, 1.42, fill=WHITE, line=BAD, lw=1.4)
tb(s, 8.79, 3.66, 3.7, 0.3, "DENY · 越权 / 批量", size=12, bold=True, color=BAD)
tb(s, 8.79, 3.98, 3.7, 0.86, "直接拒绝，结果回传模型自我纠正\n拒绝记录不可绕过（BYPASS 也生效）", size=10.5, color=INK_SOFT, spacing=1.25)

rect(s, 0.72, 5.16, 11.92, 0.62, fill=INK, line=None)
tb(s, 0.95, 5.28, 11.4, 0.4,
   "审计双写：sys_agent_tool_log（模型想了什么 / 决定了什么）  +  sys_oper_log（业务侧发生了什么）",
   size=11.5, bold=True, color=WHITE)

tb(s, 0.72, 6.06, 11.9, 0.7,
   "两个必须写死的细节：① 工具永远不读 SecurityUtils，LoginUser 从 RuntimeContext 注入；\n"
   "② 审批对象是「工具 + 参数」的结构化 diff，不是模型的一段解释。",
   size=11, color=INK_SOFT, spacing=1.3)
footer(s, 5)

# ---------------------------------------------------------------- 6 权限映射
s = blank()
header(s, "GOVERNANCE", "权限映射：一份角色配置决定一切",
       "PermissionMode 提供框架级护栏，PermissionRule 提供逐工具的精细控制")
rows = [
    ["若依角色", "PermissionMode", "装配的工具组", "写出去了会怎样"],
    ["admin", "DEFAULT", "全部", "全工具可用，写操作仍需人工审批"],
    ["ops（运营）", "DEFAULT", "system / ops / analytics", "写操作 ASK，批量操作 DENY"],
    ["auditor（审计）", "EXPLORE", "analytics + log", "框架级只读：一切写与命令被拒"],
    ["dev（开发）", "DEFAULT", "全部 + devtools", "多出生成器 / Git / CI 工具"],
    ["agent_job（定时）", "DONT_ASK", "白名单", "ASK 自动降级为 DENY，适配无人值守"],
]
table(s, rows, 0.72, 2.02, 11.92, [2.5, 2.3, 3.2, 3.92], row_h=0.50, size=11, head_size=11.5)

rect(s, 0.72, 5.10, 5.82, 1.5, fill=LIGHT, line=None)
tb(s, 0.95, 5.24, 5.4, 0.3, "红队验收（上线前必须全过）", size=12.5, bold=True, color=INK)
tb(s, 0.95, 5.58, 5.4, 0.92,
   "① 运营账号要求删 admin → 工具不存在\n② 在数据备注里植入指令 → 不触发\n③ 「导出全部手机号」→ 必触发审批",
   size=10.5, color=INK_SOFT, spacing=1.3)

rect(s, 6.82, 5.10, 5.82, 1.5, fill=ACCENT_SOFT, line=None)
tb(s, 7.05, 5.24, 5.4, 0.3, "成本护栏（别忽视）", size=12.5, bold=True, color=RGBColor(0x9A, 0x45, 0x14))
tb(s, 7.05, 5.58, 5.4, 0.92,
   "模型分级 + 结果缓存 + 按角色 token 配额\n+ 单轮 maxIters 上限 + 熔断\n（codingagent 样例已有预算中间件可借鉴）",
   size=10.5, color=RGBColor(0x7A, 0x45, 0x2C), spacing=1.3)
footer(s, 6)

# ---------------------------------------------------------------- 7 CUA 取舍
s = blank()
header(s, "COMPUTER USE", "「怎么让它操作」的四条路",
       "直接回答：不要用视觉 CUA 做主路径 —— 90% 走工具调用，10% 走 RPA 兜底")

matrix = [
    ["路径", "实现成本", "稳定性", "权限可控", "审计", "推荐"],
    ["A · 工具调用（Service/API）", "低", "●●●●●", "●●●●●", "●●●●●", "主路径 90%"],
    ["B · MCP 服务化", "低", "●●●●○", "●●●●○", "●●●●○", "对外集成"],
    ["C · RPA / Playwright", "中", "●●●○○", "●●●○○", "●●●○○", "兜底 ≤10%"],
    ["D · 视觉 CUA（坐标点击）", "高", "●●○○○", "●●○○○", "●●○○○", "不建议上生产"],
    ["E · 外部执行（人做最后一步）", "极低", "●●●●●", "●●●●●", "●●●●●", "与 A 组合"],
]
table(s, matrix, 0.72, 2.02, 8.3, [3.1, 1.0, 0.95, 0.95, 0.95, 1.35], row_h=0.52, size=10.5, head_size=10.5)

rect(s, 9.28, 2.02, 3.36, 3.66, fill=LIGHT, line=None)
tb(s, 9.5, 2.16, 2.95, 0.3, "为什么 CUA 不能做主线", size=12, bold=True, color=BAD)
tb(s, 9.5, 2.52, 2.95, 3.0,
   "· 界面一改版就全挂\n· 只知「点哪里」，不知「该不该点」\n· 审计只能录屏，过不了等保\n· 成本按「页面数 × 改版次数」增长\n\n结论：只用于销售演示，\n不进入生产链路。",
   size=10.2, color=INK_SOFT, spacing=1.35)

tb(s, 0.72, 6.02, 11.9, 0.7,
   "补充：最高性价比的「computer use」其实是 E ——让 AI 把活干到 95%，最后一步由人在真实界面上点。\n"
   "这既规避了 GUI 脆弱性，又天然满足「人工复核」的合规要求。",
   size=11.5, color=INK, spacing=1.3)
footer(s, 7)

# ---------------------------------------------------------------- 8 市场格局
s = blank()
header(s, "MARKET", "市场格局：空位在「第 5 层」",
       "五层结构里，国际大厂与国内软件商都只覆盖自己的客户；开源后台框架这一层几乎无人做闭环")

layers = [
    ("第 1 层 · 基础模型", "通义 / DeepSeek / GLM / Kimi / 文心", 3.4, RGBColor(0xDD, 0xE6, 0xEE)),
    ("第 2 层 · Agent 框架", "AgentScope Java · Spring AI Alibaba · LangChain4j", 5.0, RGBColor(0xC9, 0xD8, 0xE4)),
    ("第 3 层 · Agent 平台", "Dify · Coze · n8n · RAGFlow · FastGPT", 6.5, RGBColor(0xAF, 0xC6, 0xD8)),
    ("第 4 层 · 业务系统内嵌", "Copilot Studio · Agentforce · Joule · 金蝶 / 用友 / 泛微", 7.9, RGBColor(0x8F, 0xAD, 0xC4)),
    ("第 5 层 · 开源后台 + AI", "若依 · JeecgBoot · yudao · NocoBase  ← 本项目位置", 9.3, ACCENT_SOFT),
]
y = 1.98
for name, desc, w, fill in layers:
    ln = ACCENT if fill == ACCENT_SOFT else None
    rect(s, 0.72, y, w, 0.80, fill=fill, line=ln, lw=1.8)
    tb(s, 0.95, y + 0.12, w - 0.4, 0.28, name, size=11.5, bold=True, color=INK)
    tb(s, 0.95, y + 0.42, w - 0.4, 0.26, desc, size=9.5, color=INK_SOFT)
    y += 0.88

rect(s, 10.35, 1.98, 2.29, 3.56, fill=WHITE, line=LINE)
tb(s, 10.55, 2.12, 1.95, 0.3, "空位在哪", size=12, bold=True, color=ACCENT)
tb(s, 10.55, 2.46, 1.95, 2.9,
   "大量自研/开源派生的业务系统：\n\n· 有权限与组织模型\n· 但没有可操作它的 Agent\n· 等不起大厂改造\n· 不愿把数据交出去\n\n⚠ 钉钉 ¥19,800/组织/年\n已是「顺手买」价格",
   size=9.6, color=INK_SOFT, spacing=1.3)

tb(s, 0.72, 6.42, 11.9, 0.5,
   "真正的对手不是「另一个 AI 助手」，而是「Dify + 若依 API」的自拼方案 —— 它赢在做 demo，我们赢在上生产。",
   size=11.5, bold=True, color=INK)
footer(s, 8)

# ---------------------------------------------------------------- 9 路线图
s = blank()
header(s, "ROADMAP", "路线图：先窄后宽，每阶段都有硬指标",
       "不要用「上线了」当验收；P3 必须用对照实验度量净节省工时")

phases = [
    ("P0", "只读闭环", "15–20 人日", "50 条真实问句准确率 ≥85%\nP95 首字延迟 ≤3s", TEAL),
    ("P1", "可写可控", "25–30 人日", "写操作 100% 经审批\n审批耗时 ≤2min / 误操作 0", WARN),
    ("P2", "治理与渠道", "25–35 人日", "留痕率 100% / 越权 0\n单用户日成本 ≤ 预算", INK),
    ("P3", "开发者 Agent", "40–60 人日", "净节省工时 >0\n一次通过率 ≥50% / 回滚率不高于人工", ACCENT),
]
x = 0.72
for code, name, days, kpi, color in phases:
    rect(s, x, 2.02, 2.86, 3.5, fill=WHITE, line=LINE)
    rect(s, x, 2.02, 2.86, 0.10, fill=color, shape=MSO_SHAPE.RECTANGLE)
    tb(s, x + 0.2, 2.24, 1.0, 0.4, code, size=19, bold=True, color=color)
    tb(s, x + 0.82, 2.32, 1.9, 0.3, name, size=12.5, bold=True, color=INK)
    tb(s, x + 0.2, 2.78, 2.46, 0.3, days, size=10.5, bold=True, color=GRAY)
    rect(s, x + 0.2, 3.14, 2.46, 0.02, fill=LINE, shape=MSO_SHAPE.RECTANGLE)
    tb(s, x + 0.2, 3.26, 2.46, 0.24, "验收指标", size=9.5, bold=True, color=GRAY)
    tb(s, x + 0.2, 3.52, 2.46, 1.7, kpi, size=10, color=INK_SOFT, spacing=1.35)
    x += 3.02

rect(s, 0.72, 5.74, 5.82, 0.92, fill=LIGHT, line=None)
tb(s, 0.95, 5.86, 5.4, 0.28, "总投入", size=11, bold=True, color=GRAY)
tb(s, 0.95, 6.12, 5.4, 0.4, "105–145 人日 ≈ 2 人 3–4 个月（含踩坑缓冲）", size=12.5, bold=True, color=INK)

rect(s, 6.82, 5.74, 5.82, 0.92, fill=ACCENT_SOFT, line=None)
tb(s, 7.05, 5.86, 5.4, 0.28, "最大的坑", size=11, bold=True, color=RGBColor(0x9A, 0x45, 0x14))
tb(s, 7.05, 6.12, 5.4, 0.4, "P3 用「生成行数」证明价值 —— 必须从第一天做对照实验", size=12, bold=True,
   color=RGBColor(0x9A, 0x45, 0x14))
footer(s, 9)

# ---------------------------------------------------------------- 10 决策建议
s = blank()
header(s, "DECISION", "决策建议：Go，但有条件", "四个前置条件、三条禁令 —— 满足则立项，否则降级为技术预研")

rect(s, 0.72, 1.98, 6.1, 4.42, fill=WHITE, line=LINE)
tb(s, 0.98, 2.12, 5.6, 0.3, "GO 的四个前置条件", size=13, bold=True, color=GOOD)
conds = [
    ("01", "3 天兼容性 PoC 通过", "Boot 4.1 + AgentScope 同进程启动、SSE 出字、工具可调"),
    ("02", "一个真实且高频的首场景", "建议：运营查数据 + 改单个单据状态"),
    ("03", "有 2 名真实用户愿意每周用", "否则做出来只是「演示品」"),
    ("04", "安全负责人认可红线", "写操作默认审批、批量操作禁止"),
]
y = 2.52
for n, t, d in conds:
    tb(s, 0.98, y + 0.02, 0.42, 0.3, n, size=11, bold=True, color=GOOD)
    tb(s, 1.44, y, 5.1, 0.28, t, size=11.5, bold=True, color=INK)
    tb(s, 1.44, y + 0.28, 5.1, 0.44, d, size=9.8, color=GRAY)
    y += 0.94

rect(s, 7.10, 1.98, 5.54, 4.42, fill=INK, line=None)
tb(s, 7.36, 2.12, 5.0, 0.3, "三个不要做", size=13, bold=True, color=ACCENT)
donts = [
    ("不做通用聊天助手", "用户有钉钉和豆包，不会来你这儿聊天"),
    ("不让 AI 自由写 SQL", "只读视图 + 列白名单 + 强制 LIMIT，否则第一个事故就是数据泄露"),
    ("P0 阶段不碰开发者场景", "复杂度是运营场景的 3 倍，会把项目拖死"),
]
y = 2.52
for t, d in donts:
    rect(s, 7.36, y + 0.06, 0.05, 0.62, fill=ACCENT, shape=MSO_SHAPE.RECTANGLE)
    tb(s, 7.58, y, 4.9, 0.3, t, size=11.5, bold=True, color=WHITE)
    tb(s, 7.58, y + 0.30, 4.9, 0.5, d, size=9.8, color=RGBColor(0xB8, 0xC8, 0xD6), spacing=1.2)
    y += 1.16

rect(s, 0.72, 6.56, 11.92, 0.44, fill=LIGHT, line=None)
tb(s, 0.95, 6.62, 11.5, 0.3,
   "下一步建议交付物：① ruoyi-agent 模块骨架 + 3 个工具的可运行 PoC　② 给管理层的一页纸结论　③ 安全红队用例清单",
   size=10.5, bold=True, color=INK)
footer(s, 10)

prs.save(r"E:\CODE\Agent\agentscope-ruoyi\docs\ruoyi-agent-可行性分析-汇报.pptx")
print("saved")
