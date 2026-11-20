#!/usr/bin/env python3
"""docs 检索：回答「改这个该读哪份」，而不是把整棵树灌进上下文。

用法：
    python tools/docs/find.py "docker pull 超时"      # 检索留痕 + skills（按相关度）
    python tools/docs/find.py --recent 10             # 最近 N 个会话
    python tools/docs/find.py --module agent-executor # 某模块的会话
    python tools/docs/find.py --impact DockerSandbox  # 改过该文件/类的会话（影响面）
    python tools/docs/find.py --stale                 # 30 天没更新的手写文档

检索面：docs/ledger/*.jsonl 的 标题/问题/方案/提交主题 与 docs/skills/ 全文。
设计取舍：不建索引文件——165 行 JSONL 线性扫描 <100ms，索引本身才是漂移源。
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "session-archive"))
import gate  # noqa: E402

SKILLS = ROOT / "docs" / "skills"


def haystack(manifest: dict) -> str:
    commits = " ".join(c.get("subject", "") for c in manifest.get("commits", []))
    return " ".join([manifest.get("title", ""), manifest.get("problem", ""),
                     manifest.get("solution", ""), commits])


def search(query: str, limit: int = 10) -> int:
    terms = [t for t in query.split() if t]
    hits = []
    for _, m in gate.manifests():
        text = haystack(m)
        score = sum(1 for t in terms if t.lower() in text.lower())
        if score:
            hits.append((score, m))
    hits.sort(key=lambda h: (h[0], h[1].get("createdAt", "")), reverse=True)
    if hits:
        print(f"== 留痕命中（{min(len(hits), limit)}/{len(hits)}）==")
        for score, m in hits[:limit]:
            print(f"[{score}] {m.get('createdAt', '')[:10]} {m.get('id', '')} "
                  f"— {m.get('title', '')}")
    skill_hits = []
    if SKILLS.is_dir():
        for path in sorted(SKILLS.rglob("*.md")):
            text = path.read_text(encoding="utf-8", errors="replace")
            score = sum(1 for t in terms if t.lower() in text.lower())
            if score:
                skill_hits.append((score, path))
    if skill_hits:
        print(f"== skills 命中（{len(skill_hits)}）==")
        for score, path in sorted(skill_hits, reverse=True):
            print(f"[{score}] {path.relative_to(ROOT).as_posix()}")
    if not hits and not skill_hits:
        print("无命中；换关键词，或用 --module / --impact 按维度查")
        return 1
    return 0


def recent(count: int) -> int:
    rows = sorted(gate.manifests(),
                  key=lambda r: (r[1].get("createdAt", ""), r[1].get("id", "")), reverse=True)
    for _, m in rows[:count]:
        print(f"{m.get('createdAt', '')[:10]} {m.get('id', '')} — {m.get('title', '')}")
    return 0


def by_module(module: str) -> int:
    found = [m for _, m in gate.manifests()
             if module in ((m.get("impact") or {}).get("modules") or [])]
    for m in sorted(found, key=lambda m: m.get("createdAt", ""), reverse=True):
        print(f"{m.get('createdAt', '')[:10]} {m.get('id', '')} — {m.get('title', '')}")
    print(f"（{len(found)} 个会话）")
    return 0 if found else 1


def impact(file_part: str) -> int:
    """影响面：哪些会话的提交碰过这个文件/类（子串匹配）。"""
    found = []
    for _, m in gate.manifests():
        files = [f.get("path", "") for c in m.get("commits", []) for f in c.get("files", [])]
        if any(file_part in f for f in files):
            found.append(m)
    for m in sorted(found, key=lambda m: m.get("createdAt", ""), reverse=True):
        print(f"{m.get('createdAt', '')[:10]} {m.get('id', '')} — {m.get('title', '')}")
    print(f"（{len(found)} 个会话碰过 {file_part}）")
    return 0 if found else 1


def stale(days: int = 30) -> int:
    """手写文档里超过 N 天没动过的（skills / code-wiki / 根规约）。"""
    targets = (list(SKILLS.rglob("*.md")) if SKILLS.is_dir() else []) \
        + list((ROOT / "docs" / "code-wiki").glob("*.md")) \
        + list((ROOT / "docs").glob("*.md"))
    cutoff = time.time() - days * 86400
    found = 0
    for path in targets:
        proc = subprocess.run(["git", "log", "-1", "--format=%ct", "--", str(path)],
                              cwd=ROOT, capture_output=True, text=True)
        ts = int(proc.stdout.strip() or 0)
        if ts and ts < cutoff:
            age = int((time.time() - ts) / 86400)
            print(f"{age} 天未更新：{path.relative_to(ROOT).as_posix()}")
            found += 1
    if not found:
        print(f"手写文档都在 {days} 天内更新过")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(prog="docs-find")
    parser.add_argument("query", nargs="?", help="检索关键词（空格分词）")
    parser.add_argument("--recent", type=int, metavar="N", help="最近 N 个会话")
    parser.add_argument("--module", metavar="M", help="按模块查会话")
    parser.add_argument("--impact", metavar="FILE", help="影响面：改过该文件/类的会话")
    parser.add_argument("--stale", action="store_true", help="列出 30 天没更新的手写文档")
    args = parser.parse_args()
    if args.impact:
        return impact(args.impact)
    if args.module:
        return by_module(args.module)
    if args.recent:
        return recent(args.recent)
    if args.stale:
        return stale()
    if args.query:
        return search(args.query)
    parser.print_help()
    return 1


if __name__ == "__main__":
    sys.exit(main())
