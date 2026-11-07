# -*- coding: utf-8 -*-
"""v2 演示稿：RuoYi-Vue-Plus 6.1.0 × AgentScope Java。"""
import sys, io
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

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
prs.slide_width, prs.slide_height = Inches(W), Inches(H)


def tb(slide, l, t, w, h, text="", size=14, bold=False, color=INK,
       align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, spacing=1.0):
    box = slide.shapes.add_textbox(Inches(l), Inches(t), Inches(w), Inches(h))
    tf = box.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    for m in ("margin_left", "margin_right", "margin_top", "margin_bottom"):
        setattr(tf, m, 0)
    for i, ln in enumerate(text.split("\n")):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.line_spacing = spacing
        r = p.add_run()
        r.text = ln
        r.font.size = Pt(size)
        r.font.bold = bold
        r.font.color.rgb = color
        r.font.name = FONT
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
    return s


def blank():
    return prs.slides.add_slide(prs.slide_layouts[6])


def header(slide, kicker, title, sub=None):
    rect(slide, 0, 0, W, 0.09, fill=INK, shape=MSO_SHAPE.RECTANGLE)
    tb(slide, 0.72, 0.42, 9.0, 0.3, kicker, size=10.5, bold=True, color=ACCENT)
    tb(slide, 0.72, 0.70, 11.8, 0.6, title, size=25, bold=True, color=INK)
    if sub:
        tb(slide, 0.72, 1.30, 11.8, 0.36, sub, size=12.5, color=GRAY)
    rect(slide, 0.72, 1.72 if sub else 1.42, 0.86, 0.05, fill=ACCENT, shape=MSO_SHAPE.RECTANGLE)


def footer(slide, n, total=9):
    tb(slide, 0.72, 7.03, 7.0, 0.26, "RuoYi-Vue-Plus 6.1.0 × AgentScope Java ｜ 可行性分析 v2", size=9, color=GRAY)
    tb(slide, W - 1.4, 7.03, 0.7, 0.26, f"{n:02d} / {total}", size=9, color=GRAY, align=PP_ALIGN.RIGHT)


def table(slide, rows, l, t, w, col_w, row_h=0.4, head_h=0.40, size=10.5, head_size=10.5):
    n_r, n_c = len(rows), len(rows[0])
    gt = slide.shapes.add_table(n_r, n_c, Inches(l), Inches(t), Inches(w),
                                Inches(head_h + row_h * (n_r - 1))).table
    for i, cw in enumerate(col_w):
        gt.columns[i].width = Inches(cw)
    gt.rows[0].height = Inches(head_h)
    for r in range(1, n_r):
        gt.rows[r].height = Inches(row_h)
    for r in range(n_r):
        for c in range(n_c):
            cell = gt.cell(r, c)
            cell.text = ""
            cell.margin_left, cell.margin_right = Inches(0.09), Inches(0.06)
            cell.margin_top, cell.margin_bottom = Inches(0.02), Inches(0.02)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            cell.fill.solid()
            cell.fill.fore_color.rgb = INK if r == 0 else (WHITE if r % 2 == 1 else LIGHT)
            p = cell.text_frame.paragraphs[0]
            run = p.add_run()
            run.text = str(rows[r][c])
            run.font.size = Pt(head_size if r == 0 else size)
            run.font.bold = (r == 0)
            run.font.color.rgb = WHITE if r == 0 else INK_SOFT
            run.font.name = FONT
    return gt


# ---------------------------------------------------------------- 1 封面
s = blank()
rect(s, 0, 0, W, H, fill=INK, shape=MSO_SHAPE.RECTANGLE)
rect(s, 0, 0, 0.16, H, fill=ACCENT, shape=MSO_SHAPE.RECTANGLE)
tb(s, 1.05, 1.45, 9.0, 0.3, "RE-ANALYSIS  ·  v2", size=12, bold=True, color=ACCENT)
tb(s, 1.05, 1.92, 11.2, 1.0, "换底座重评：RuoYi-Vue-Plus 6.1.0", size=36, bold=True, color=WHITE)
tb(s, 1.05, 2.95, 11.0, 1.2,
   "AgentScope Java 多身份 Agent 模块 · 可行性分析\n上一版基于 RuoYi 4.8.3（Thymeleaf + Shiro），本版基于 6.1.0（Sa-Token + Spring AI + MCP）",
   size=16, color=RGBColor(0xC6, 0xD4, 0xE0), spacing=1.35)
rect(s, 1.05, 4.40, 2.2, 0.05, fill=ACCENT, shape=MSO_SHAPE.RECTANGLE)
tb(s, 1.05, 4.62, 8.0, 0.32, "2026-11-07  ·  依据本地克隆 commit 835246e（v6.1.0）",
   size=11.5, color=RGBColor(0x93, 0xA8, 0xB8))
tb(s, 1.05, 6.35, 10.0, 0.3, "证据等级：A 代码可验证（本次逐文件核实） ｜ B 官方文档 ｜ C 公开信息 ｜ D 推断",
   size=9.5, color=RGBColor(0x6E, 0x86, 0x9A))

# ---------------------------------------------------------------- 2 四个判断变了
s = blank()
header(s, "WHAT CHANGED", "换底座之后，四个判断变了",
       "上版结论里有一条被彻底推翻，两条要修订，一条反而更强")
rows = [
    ["", "上一版（RuoYi 4.8.3）", "本版（Vue-Plus 6.1.0）", "为什么"],
    ["定位", "「给若依加 AI」是空白", "这个底座已经有 AI 了", "Spring AI 2.0.2 + Snail AI（23 张 sai_* 表）+ MCP"],
    ["红利", "权限模型结构同构", "同构且更强", "LoginUser 已缓存 menuPermission / rolePermission"],
    ["高危点", "生成器直写宿主磁盘", "消失了", "生成器只出 previewCode / zip，零副作用"],
    ["主路径", "工具调用 + 人工审批", "多了一条：同线程执行", "Java 21 + Jetty，数据权限天然生效"],
]
table(s, rows, 0.72, 1.98, 11.92, [1.1, 3.5, 3.3, 4.02], row_h=0.62, size=10.5)

rect(s, 0.72, 5.35, 11.92, 1.30, fill=ACCENT_SOFT, line=None)
tb(s, 0.95, 5.48, 11.4, 0.3, "新增的三个判断", size=12, bold=True, color=RGBColor(0x9A, 0x45, 0x14))
tb(s, 0.95, 5.80, 11.4, 0.78,
   "① 不要做「AI 模块」，要做「操作治理层」——对话/知识库/模型配置底座都有了\n"
   "② Snail AI 是外部平台、MCP 是无身份通道，两者都拿不到 @DataPermission，中间那层是空的\n"
   "③ 底座越强，模块越小：工作量从 105–145 人日降到 73–104 人日",
   size=11, color=RGBColor(0x7A, 0x45, 0x2C), spacing=1.35)
footer(s, 2)

# ---------------------------------------------------------------- 3 底盘差异
s = blank()
header(s, "BASELINE", "底盘对照：六个决定性差异",
       "不是「换了个 UI 框架」，而是鉴权、长连接、AI、生成器四条链路全变了")
rows = [
    ["维度", "RuoYi 4.8.3", "RuoYi-Vue-Plus 6.1.0", "对 Agent 模块的影响"],
    ["框架", "Boot 4.1.0 / JDK17 / Tomcat", "Boot 4.1.2 / JDK21 / Jetty", "虚拟线程可用，同线程方案成本更低"],
    ["鉴权", "Shiro（SecurityUtils）", "Sa-Token 1.46（SaHolder）", "上下文问题同类，但更隐蔽（见坑 1）"],
    ["权限", "@RequiresPermissions", "@SaCheckPermission", "字符串格式一致，桥接逻辑不变"],
    ["长连接", "无", "SSE + WebSocket 双通道", "流式输出不用自己造基建"],
    ["AI", "无", "Spring AI + Snail AI + MCP", "问题性质改变——从「加 AI」变成「补治理」"],
    ["生成器", "Velocity，直写磁盘", "FreeMarker，只出 zip", "最危险的技术点被底座堵死"],
]
table(s, rows, 0.72, 1.98, 11.92, [1.3, 3.2, 3.3, 4.12], row_h=0.55, size=10.5)

rect(s, 0.72, 5.72, 5.82, 1.02, fill=LIGHT, line=None)
tb(s, 0.95, 5.84, 5.4, 0.28, "现成资产 1：审批引擎", size=11.5, bold=True, color=INK)
tb(s, 0.95, 6.12, 5.4, 0.5, "warm-flow 2.1.1：任务/抄送/催办/事件等待/SPEL",
   size=10.5, color=INK_SOFT)

rect(s, 6.82, 5.72, 5.82, 1.02, fill=LIGHT, line=None)
tb(s, 7.05, 5.84, 5.4, 0.28, "现成资产 2：AI 编码规范", size=11.5, bold=True, color=INK)
tb(s, 7.05, 6.12, 5.4, 0.5, ".codex/skills/… + .claude/agents/（6 个子角色）",
   size=10.5, color=INK_SOFT)
footer(s, 3)

# ---------------------------------------------------------------- 4 差一层
s = blank()
header(s, "THE GAP", "三层能力都在，各缺一层",
       "这就是这个模块存在的唯一理由，也是最容易被客户听懂的一句话")

cards = [
    ("MCP Server", "已内建", "/mcp 端点 + @McpTool\n把若依能力暴露给外部 LLM", "缺：调用者身份\n端点只有一个服务账号，工具里取不到 LoginUser", BAD),
    ("Snail AI", "已内建", "智能体/对话/知识库/Skill\n23 张 sai_* 表 + 用量统计", "缺：进程内权限\n知道「是谁」(externalId)，不知道「他能看什么」", WARN),
    ("Spring AI", "已内建", "ChatClient / Tool Calling\nMCP 客户端与服务端", "缺：治理\n无审批、无审计、无 ToolGroup 按角色裁剪", WARN),
]
x = 0.72
for name, tag, has, lacks, color in cards:
    rect(s, x, 1.98, 3.86, 3.34, fill=WHITE, line=LINE)
    rect(s, x, 1.98, 3.86, 0.62, fill=INK)
    tb(s, x + 0.2, 2.08, 2.4, 0.28, name, size=13.5, bold=True, color=WHITE)
    tb(s, x + 2.55, 2.10, 1.1, 0.24, tag, size=9.5, bold=True, color=ACCENT)
    tb(s, x + 0.2, 2.76, 3.46, 0.7, has, size=10, color=INK_SOFT, spacing=1.25)
    rect(s, x + 0.2, 3.56, 3.46, 0.02, fill=LINE, shape=MSO_SHAPE.RECTANGLE)
    tb(s, x + 0.2, 3.66, 3.46, 0.9, lacks, size=10, bold=True, color=color, spacing=1.25)
    x += 4.03

rect(s, 0.72, 5.52, 11.92, 1.16, fill=INK, line=None)
tb(s, 0.98, 5.64, 11.4, 0.3, "空位：进程内、带用户身份、受数据权限与审批约束的工具执行层",
   size=13, bold=True, color=WHITE)
tb(s, 0.98, 5.98, 11.4, 0.55,
   "AgentScope 补的正是这层：PermissionRule（来自 @SaCheckPermission） + ToolGroup（按角色） + HITL 审批 + 工具调用拦截器（注入数据范围）",
   size=10.5, color=RGBColor(0xB8, 0xC8, 0xD6), spacing=1.25)
footer(s, 4)

# ---------------------------------------------------------------- 5 坑
s = blank()
header(s, "RISKS", "高危点消失了四个，新增四个",
       "最危险的一条从「AI 往服务器写文件」变成「数据权限静默失效」")

rect(s, 0.72, 1.98, 5.66, 3.9, fill=WHITE, line=GOOD, lw=1.4)
tb(s, 0.95, 2.10, 5.2, 0.3, "上版的高危点：已消失", size=12.5, bold=True, color=GOOD)
tb(s, 0.95, 2.48, 5.2, 3.2,
   "① 生成器直写磁盘\n     → FreeMarker 只出 previewCode / zip\n\n"
   "② CSRF / kickout 拦 SSE\n     → 前后端分离 + Token，端点独立\n\n"
   "③ 无前端流式能力\n     → 独立 Vue3 仓 + 现成 SSE 通道\n\n"
   "④ 无审批引擎\n     → warm-flow 直接承接",
   size=10.5, color=INK_SOFT, spacing=1.3)

rect(s, 6.70, 1.98, 5.94, 3.9, fill=WHITE, line=BAD, lw=1.4)
tb(s, 6.93, 2.10, 5.5, 0.3, "新增的四个坑：按危险度", size=12.5, bold=True, color=BAD)
tb(s, 6.93, 2.48, 5.5, 3.2,
   "① [最危险] Sa-Token 上下文在异步线程失效\n     SaHolder 取不到 → 抛错或跳过数据范围\n\n"
   "② MCP SDK 版本冲突\n     AgentScope 钉 2.1.0，Spring AI 也管同一个 jar\n\n"
   "③ AOP 自调用让权限注解静默失效\n     工具里自写查询逻辑 = 绕过 @DataPermission\n\n"
   "④ 前端在另一个仓库（plus-ui）",
   size=10.2, color=INK_SOFT, spacing=1.25)

tb(s, 0.72, 6.06, 11.9, 0.7,
   "对策：P0 的 5 天 PoC 专治第 1、3 条——用两个数据权限不同的账号调同一个工具，断言结果集不同。\n"
   "这条单测同时是「坑 1」和「坑 3」的回归防线，也是唯一能造成生产事故的技术点。",
   size=11, color=INK, spacing=1.3)
footer(s, 5)

# ---------------------------------------------------------------- 6 权限桥
s = blank()
header(s, "PERMISSION BRIDGE", "权限桥：80 行，全靠底座已有的东西",
       "Sa-Token 的 LoginUser 里已经缓存了权限集合，桥接时不用再查库")

rect(s, 0.72, 1.98, 6.24, 4.0, fill=WHITE, line=LINE)
tb(s, 0.95, 2.10, 5.8, 0.3, "映射关系", size=12, bold=True, color=INK)
rows = [
    ["底座已有", "AgentScope", "效果"],
    ["@SaCheckPermission\n\"system:user:edit\"", "PermissionRule\nALLOW / DENY / ASK", "越权工具直接不注册"],
    ["rolePermission", "ToolGroup + mode\nEXPLORE / DONT_ASK", "审计角色框架级只读"],
    ["@DataPermission", "ToolCallInterceptor", "数据范围在参数层强制"],
    ["warm-flow 任务", "ASK → 审批单", "写操作必过人"],
]
gt = table(s, rows, 0.95, 2.44, 5.78, [2.05, 1.9, 1.83], row_h=0.62, size=9.5, head_size=9.5)

rect(s, 7.28, 1.98, 5.36, 4.0, fill=WHITE, line=LINE)
tb(s, 7.5, 2.10, 5.0, 0.3, "三条铁律", size=12, bold=True, color=ACCENT)
tb(s, 7.5, 2.48, 4.95, 3.3,
   "① 工具永不读 LoginHelper\n     身份从 RuntimeContext 注入\n\n"
   "② 工具只做三件事\n     校验 / 调 Service / 脱敏摘要\n     ——绝不写查询逻辑\n\n"
   "③ 一律注入 Service 接口\n     不注入 Mapper（否则绕过 AOP）",
   size=10.5, color=INK_SOFT, spacing=1.35)

rect(s, 0.72, 6.10, 11.92, 0.52, fill=LIGHT, line=None)
tb(s, 0.95, 6.20, 11.5, 0.34,
   "「你的 AI 能查数，但它不知道你现在登录的是谁。」—— 这句话就是本模块的立项理由",
   size=11.5, bold=True, color=INK)
footer(s, 6)

# ---------------------------------------------------------------- 7 开发者场景
s = blank()
header(s, "DEVELOPER AGENT", "开发者场景：最难的两块，底座已经准备好了",
       "上版要从零搭；本版知识层与生成层都是现成的")

rect(s, 0.72, 1.98, 5.72, 1.55, fill=WHITE, line=LINE)
rect(s, 0.72, 1.98, 0.07, 1.55, fill=TEAL, shape=MSO_SHAPE.RECTANGLE)
tb(s, 0.98, 2.10, 5.3, 0.3, "知识层：.codex/skills/ruoyi-plus-ai-coding", size=11.5, bold=True, color=TEAL)
tb(s, 0.98, 2.46, 5.3, 1.0,
   "SKILL.md（触发条件 + 反例清单 + 6 步流程）\n+ references/backend.md / frontend.md / examples.md\n→ 整个目录可直接挂成 FileSystemSkillRepository",
   size=10, color=INK_SOFT, spacing=1.3)

rect(s, 6.86, 1.98, 5.78, 1.55, fill=WHITE, line=LINE)
rect(s, 6.86, 1.98, 0.07, 1.55, fill=ACCENT, shape=MSO_SHAPE.RECTANGLE)
tb(s, 7.12, 2.10, 5.3, 0.3, "生成层：FreeMarker 双端模板", size=11.5, bold=True, color=ACCENT)
tb(s, 7.12, 2.46, 5.3, 1.0,
   "fm/java/*.ftl + fm/react/*.ftl（含 tsx/types/api.ts）\n只出 previewCode / zip，不落地\n→ Agent 天然无副作用",
   size=10, color=INK_SOFT, spacing=1.3)

tb(s, 0.72, 3.72, 11.9, 0.3, "标准链路（沿用上版的「绿灯批次」原则）", size=11.5, bold=True, color=INK)
steps = ["一句话需求", "Agent 出 spec", "人审 spec", "previewCode", "沙箱改造", "编译/单测/SAST", "PR + AI 预评审", "warm-flow 审批", "CI 发布"]
x = 0.72
for i, st in enumerate(steps):
    fill = ACCENT_SOFT if "审" in st or "审批" in st else LIGHT
    rect(s, x, 4.06, 1.18, 0.72, fill=fill, line=LINE)
    tb(s, x + 0.06, 4.16, 1.06, 0.55, st, size=8.6, bold=True, color=INK, align=PP_ALIGN.CENTER,
       anchor=MSO_ANCHOR.MIDDLE, spacing=1.1)
    if i < len(steps) - 1:
        tb(s, x + 1.19, 4.26, 0.14, 0.3, "›", size=12, bold=True, color=ACCENT, align=PP_ALIGN.CENTER)
    x += 1.32

tb(s, 0.72, 5.02, 11.9, 1.4,
   "红线（硬编码 DENY，不开放给模型）：禁 force push · 禁直推主干 · 禁改流水线定义 · 禁读生产配置 · 禁操作生产库\n"
   "度量（写进采购口径）：spec 采纳率 · AI 变更 CI 通过率 · 无人工打回即合并率 · 单 PR 评审时长",
   size=10.5, color=INK_SOFT, spacing=1.4)
footer(s, 7)

# ---------------------------------------------------------------- 8 路线图
s = blank()
header(s, "ROADMAP", "路线图：P0 三周，整体比上版省 30%",
       "不是因为 AI 变简单了，是因为流式通道、审批引擎、生成器、技能库全是现成的")

phases = [
    ("P0", "只读闭环", "10–12 人日", "权限桥 + 8 个只读工具\n+ SSE 打通 plus-ui", TEAL),
    ("P1", "可写可控", "15–20 人日", "写工具 + 审批单\n+ warm-flow 对接", WARN),
    ("P2", "治理", "8–12 人日", "审计 + 配额\n+ 导出标识", INK),
    ("P3", "开发者 Agent", "30–45 人日", "沙箱工作区 + Skill\n+ 生成器改造 + PR", ACCENT),
]
x = 0.72
for code, name, days, kpi, color in phases:
    rect(s, x, 1.98, 2.86, 3.0, fill=WHITE, line=LINE)
    rect(s, x, 1.98, 2.86, 0.10, fill=color, shape=MSO_SHAPE.RECTANGLE)
    tb(s, x + 0.2, 2.18, 1.0, 0.4, code, size=19, bold=True, color=color)
    tb(s, x + 0.82, 2.26, 1.9, 0.3, name, size=12, bold=True, color=INK)
    tb(s, x + 0.2, 2.72, 2.46, 0.3, days, size=10.5, bold=True, color=GRAY)
    rect(s, x + 0.2, 3.06, 2.46, 0.02, fill=LINE, shape=MSO_SHAPE.RECTANGLE)
    tb(s, x + 0.2, 3.18, 2.46, 0.24, "交付", size=9.5, bold=True, color=GRAY)
    tb(s, x + 0.2, 3.44, 2.46, 1.3, kpi, size=10, color=INK_SOFT, spacing=1.35)
    x += 3.02

rect(s, 0.72, 5.24, 5.82, 1.5, fill=LIGHT, line=None)
tb(s, 0.95, 5.36, 5.4, 0.3, "5 天 PoC：只验四件事", size=11.5, bold=True, color=INK)
tb(s, 0.95, 5.70, 5.4, 0.94,
   "① MCP SDK 只有一个版本、Boot 仍 4.1.2\n② 两个账号调同一工具 → 结果必须不同\n③ SSE 能推流到 plus-ui\n④ 工具权限码能自动对齐",
   size=9.6, color=INK_SOFT, spacing=1.3)

rect(s, 6.82, 5.24, 5.82, 1.5, fill=ACCENT_SOFT, line=None)
tb(s, 7.05, 5.36, 5.4, 0.3, "最大风险不是技术", size=11.5, bold=True, color=RGBColor(0x9A, 0x45, 0x14))
tb(s, 7.05, 5.70, 5.4, 0.94,
   "定位撞车：内部已有 Snail AI\n若边界不清 → 两个聊天框抢同一批用户\n先定边界，再写代码",
   size=9.8, color=RGBColor(0x7A, 0x45, 0x2C), spacing=1.3)
footer(s, 8)

# ---------------------------------------------------------------- 9 决策
s = blank()
header(s, "DECISION", "结论：换底座是对的，但范围要更窄", "从「做一个 AI 模块」退回「做一层操作治理」")

rect(s, 0.72, 1.98, 5.9, 3.9, fill=WHITE, line=LINE)
tb(s, 0.98, 2.10, 5.4, 0.3, "值得做，且底座更合适", size=13, bold=True, color=GOOD)
tb(s, 0.98, 2.50, 5.4, 3.2,
   "· 省 30% 工作量（105–145 → 73–104 人日）\n\n"
   "· 四个高危点被底座堵死\n\n"
   "· 权限桥从 120 行降到 80 行\n\n"
   "· 开发者场景的知识层已现成\n\n"
   "· 但：只做「操作治理」，不做「AI 平台」",
   size=10.8, color=INK_SOFT, spacing=1.35)

rect(s, 6.78, 1.98, 5.86, 3.9, fill=INK, line=None)
tb(s, 7.04, 2.10, 5.3, 0.3, "四个不要做", size=13, bold=True, color=ACCENT)
donts = [
    ("不要重造对话/知识库表", "复用 sai_agent_conversation 或 AgentStateStore"),
    ("不要跨线程跑 agent", "除非先写好 Sa-Token 上下文传播器"),
    ("不要在工具里写查询逻辑", "会绕过 @DataPermission，且不报错"),
    ("P0 不碰开发者场景", "虽然成本降了，它仍是另一条产品线"),
]
y = 2.50
for t, d in donts:
    rect(s, 7.04, y + 0.05, 0.05, 0.56, fill=ACCENT, shape=MSO_SHAPE.RECTANGLE)
    tb(s, 7.26, y, 4.7, 0.28, t, size=10.8, bold=True, color=WHITE)
    tb(s, 7.26, y + 0.27, 4.7, 0.4, d, size=9.5, color=RGBColor(0xB8, 0xC8, 0xD6))
    y += 0.82

rect(s, 0.72, 6.10, 11.92, 0.56, fill=ACCENT_SOFT, line=None)
tb(s, 0.95, 6.20, 11.5, 0.36,
   "下一步：① 5 天 PoC（四项验证）　② 与 Snail AI 的边界文档（一页）　③ 工具目录 v1（8 个只读）",
   size=11, bold=True, color=RGBColor(0x9A, 0x45, 0x14))
footer(s, 9)

prs.save(r"E:\CODE\Agent\agentscope-ruoyi\docs\ruoyi-vue-plus-agent-汇报-v2.pptx")
print("saved", len(prs.slides._sldIdLst), "slides")
