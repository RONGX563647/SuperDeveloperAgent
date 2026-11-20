#!/usr/bin/env python3
"""docs/views 生成器：从 docs/ledger/*.jsonl 投影出人类与 agent 共用的视图。

用法：
    python tools/docs/build.py sync     # 重建 docs/views/（index/changelog/modules）
    python tools/docs/build.py check    # 校验 views 与 ledger 一致（pre-push/CI 用）
    python tools/docs/build.py list     # 打印 ledger 统计

为什么是生成而不是手写：视图是留痕（ledger）的投影，手改必然漂移；
单一写入点是 ledger（gate.py finish 追加一行），这里只做「读 ledger → 写 Markdown」。
生成物不带时间戳，同样的 ledger 必然生成同样的视图，check 才可比对。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "session-archive"))
import gate  # noqa: E402  （与 sweep.py 同款：直接复用 gate 的 ledger 读取）

VIEWS = ROOT / "docs" / "views"
PERIOD_ORDER = "倒序"


def rows() -> list[dict]:
    return [manifest for _, manifest in gate.manifests()]


def first_sentence(text: str, limit: int = 72) -> str:
    """问题/方案的一句话摘要（视图里别倒整段）。"""
    line = (text or "").strip().splitlines()[0] if (text or "").strip() else ""
    for sep in ("。", "；", ";"):
        idx = line.find(sep)
        if idx > 0:
            line = line[:idx + 1]
            break
    return line if len(line) <= limit else line[:limit] + "…"


def commit_shas(manifest: dict) -> str:
    return " ".join(f"`{c.get('sha', '')[:7]}`" for c in manifest.get("commits", []))


def module_names(manifest: dict) -> list[str]:
    mods = (manifest.get("impact") or {}).get("modules") or []
    return mods or ["other"]


def module_file(name: str) -> str:
    safe = "".join(ch if ch.isalnum() or ch in "-_" else "-" for ch in name).strip("-") or "other"
    return f"modules/{safe}.md"


def sorted_rows(items: list[dict]) -> list[dict]:
    return sorted(items, key=lambda m: (m.get("createdAt", ""), m.get("id", "")), reverse=True)


def render(all_rows: list[dict]) -> dict[str, str]:
    """返回 {相对 docs/views/ 的路径: 文件文本}。"""
    ordered = sorted_rows(all_rows)
    newest = ordered[0].get("createdAt", "")[:10] if ordered else ""
    out: dict[str, str] = {}

    # ---- index.md：近期 + 模块索引（agent 的变更入口）
    recent = ordered[:10]
    lines = [
        "<!-- 由 tools/docs/build.py 生成，勿手改；文档地图见 ../README.md -->",
        "# 近期变更索引",
        "",
        f"留痕数据：`docs/ledger/*.jsonl`（{len(ordered)} 个会话，截至 {newest}）。"
        "查历史用 `python tools/docs/find.py \"<关键词>\"`，不要整树通读。",
        "",
        f"## 最近 {len(recent)} 个会话",
        "",
        "| 日期 | 类别 | 标题 | 模块 | 提交 |",
        "|---|---|---|---|---|",
    ]
    for m in recent:
        lines.append(
            f"| {m.get('createdAt', '')[:10]} | {m.get('category', '')} "
            f"| {m.get('title', '')} | {', '.join(module_names(m))} | {commit_shas(m)} |")

    by_module: dict[str, list[dict]] = {}
    for m in ordered:
        for mod in module_names(m):
            by_module.setdefault(mod, []).append(m)
    lines += ["", "## 模块索引", "",
              "| 模块 | 会话数 | 最近变更 | 详见 |", "|---|---|---|---|"]
    for mod, items in sorted(by_module.items(),
                             key=lambda kv: kv[1][0].get("createdAt", ""), reverse=True):
        last = items[0]
        lines.append(f"| {mod} | {len(items)} | {last.get('createdAt', '')[:10]} "
                     f"{last.get('title', '')} | [{module_file(mod)}]({module_file(mod)}) |")
    out["index.md"] = "\n".join(lines) + "\n"

    # ---- changelog.md：按日期倒序的完整变更日志
    cl = ["<!-- 由 tools/docs/build.py 生成，勿手改 -->", "# 变更日志", "",
          f"每行一个会话（{len(ordered)} 个），完整数据在 `docs/ledger/`。", ""]
    current_day = None
    for m in ordered:
        day = m.get("createdAt", "")[:10]
        if day != current_day:
            cl += [f"## {day}", ""]
            current_day = day
        cl.append(f"- **{m.get('category', '')}** {m.get('title', '')}"
                  f"（{commit_shas(m)}）— {first_sentence(m.get('problem', ''))}")
    out["changelog.md"] = "\n".join(cl) + "\n"

    # ---- modules/<m>.md：按模块的会话档案（问题/方案/验证）
    for mod, items in by_module.items():
        ml = ["<!-- 由 tools/docs/build.py 生成，勿手改 -->",
              f"# 模块：{mod}", "",
              f"{len(items)} 个会话；检索用 "
              f"`python tools/docs/find.py --module {mod}`。", ""]
        for m in items:
            ml += [f"## {m.get('createdAt', '')[:10]} · {m.get('title', '')}"
                   f"（{m.get('category', '')} {commit_shas(m)}）",
                   f"- 问题：{first_sentence(m.get('problem', ''))}",
                   f"- 方案：{first_sentence(m.get('solution', ''))}",
                   f"- 验证：{first_sentence((m.get('impact') or {}).get('verification', ''))}",
                   ""]
        out[module_file(mod)] = "\n".join(ml) + "\n"

    return out


def sync() -> int:
    files = render(rows())
    VIEWS.mkdir(parents=True, exist_ok=True)
    for rel, text in files.items():
        path = VIEWS / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    # 清掉不再生成的模块视图（模块只会增不会减，防御性删除）
    for stale in VIEWS.rglob("*.md"):
        rel = stale.relative_to(VIEWS).as_posix()
        if rel not in files:
            stale.unlink()
    print(f"views 已生成：{len(files)} 个文件 → docs/views/")
    return 0


def check() -> int:
    expected = render(rows())
    stale = [p.relative_to(VIEWS).as_posix() for p in VIEWS.rglob("*.md")
             if p.relative_to(VIEWS).as_posix() not in expected] if VIEWS.is_dir() else []
    drift = []
    for rel, text in expected.items():
        path = VIEWS / rel
        if not path.is_file() or path.read_text(encoding="utf-8") != text:
            drift.append(rel)
    if drift or stale:
        for name in drift:
            print(f"- 缺失或过期：docs/views/{name}", file=sys.stderr)
        for name in stale:
            print(f"- 多余文件：docs/views/{name}", file=sys.stderr)
        print("views 与 ledger 不一致；运行 python tools/docs/build.py sync 并提交",
              file=sys.stderr)
        return 1
    print(f"views 与 ledger 一致（{len(expected)} 个文件）")
    return 0


def stats() -> int:
    all_rows = rows()
    by_cat: dict[str, int] = {}
    for m in all_rows:
        by_cat[m.get("category", "?")] = by_cat.get(m.get("category", "?"), 0) + 1
    print(f"ledger 会话数：{len(all_rows)}")
    for cat, count in sorted(by_cat.items()):
        print(f"  {cat}: {count}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(prog="docs-build")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("sync", help="重建 docs/views/")
    sub.add_parser("check", help="校验 views 与 ledger 一致")
    sub.add_parser("list", help="打印 ledger 统计")
    args = parser.parse_args()
    return {"sync": sync, "check": check, "list": stats}[args.cmd]()


if __name__ == "__main__":
    sys.exit(main())
