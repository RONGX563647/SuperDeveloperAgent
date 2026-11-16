#!/usr/bin/env python3
"""Harness Handbook 生成器 / 漂移校验器。

L3 的"行为 → 源码位置"表由代码里的锚点注释生成，避免行号手工维护：
    // @handbook runtime-chat-service/stream | 聊天主流程：校验输入 → 建会话 → 事件流

用法：
    python tools/handbook/build.py list            # 打印锚点清单
    python tools/handbook/build.py sync            # 生成/刷新 L3 文档与 manifest.json
    python tools/handbook/build.py check           # 锚点、行号、生成区、文档是否漂移

`check` 只做校验，不改文件；`sync` 只改 generated 区段，人工段落不动。
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HANDBOOK = ROOT / "docs" / "handbook"
L3_DIR = HANDBOOK / "l3"
MANIFEST = HANDBOOK / "manifest.json"
SCAN_ROOT = ROOT / "platform"

ANCHOR_RE = re.compile(
    r"^\s*//\s*@handbook\s+([a-z0-9-]+)/([a-z0-9-]+)(?:\s*\|\s*(.+?))?\s*$")
TYPE_RE = re.compile(r"\b(class|interface|enum|record)\s+([A-Za-z_$][\w$]*)")
TYPE_DECL_RE = re.compile(
    r"^\s*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed)\s+)*"
    r"(class|interface|enum|record)\s+([A-Za-z_$][\w$]*)", re.M)
NAME_RE = re.compile(r"([A-Za-z_$][\w$]*)\s*\(")
BEGIN = "<!-- generated:begin（由 tools/handbook/build.py 生成，勿手改） -->"
END = "<!-- generated:end -->"
BLOCK_RE = re.compile(re.escape(BEGIN) + r".*?" + re.escape(END), re.S)


class HandbookError(Exception):
    pass


def fail(msg: str) -> int:
    print(f"ERROR: {msg}", file=sys.stderr)
    return 1


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def module_of(path: Path) -> str:
    parts = path.relative_to(ROOT).parts
    try:
        idx = parts.index("platform")
        return parts[idx + 1] if len(parts) > idx + 1 else "platform"
    except ValueError:
        return "-"


def java_files() -> list[Path]:
    files = []
    for path in SCAN_ROOT.rglob("*.java"):
        if "target" in path.parts:
            continue
        files.append(path)
    return sorted(files)


def brace_end(text: str, brace_at: int) -> int:
    """从 '{' 起做括号匹配，跳过注释/字符串/字符/文本块。"""
    depth = 0
    i = brace_at
    size = len(text)
    while i < size:
        ch = text[i]
        if ch == "/" and i + 1 < size:
            nxt = text[i + 1]
            if nxt == "/":
                j = text.find("\n", i)
                i = size if j < 0 else j + 1
                continue
            if nxt == "*":
                j = text.find("*/", i + 2)
                i = size if j < 0 else j + 2
                continue
        if ch == '"':
            if text.startswith('"""', i):
                j = text.find('"""', i + 3)
                i = size if j < 0 else j + 3
                continue
            i += 1
            while i < size:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if ch == "'":
            i += 1
            while i < size:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == "'":
                    i += 1
                    break
                i += 1
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return size - 1


def type_spans(text: str) -> list[tuple[str, int, int]]:
    """文件中所有类型声明及其行区间（用于确定方法所属的类，取最内层）。"""
    spans = []
    for match in TYPE_DECL_RE.finditer(text):
        brace = text.find("{", match.end())
        if brace < 0:
            continue
        start = text.count("\n", 0, match.start()) + 1
        end = text.count("\n", 0, brace_end(text, brace)) + 1
        spans.append((match.group(2), start, end))
    return spans


def declaration(text: str, lines: list[str], start: int,
                spans: list[tuple[str, int, int]]) -> tuple[str, int]:
    """返回 (symbol, 结束行下标)；symbol 形如 Foo#bar 或 Foo。"""
    head = ""
    cursor = start
    while cursor < len(lines):
        head += lines[cursor] + "\n"
        if "{" in lines[cursor] or ";" in lines[cursor]:
            break
        cursor += 1
    type_match = TYPE_RE.search(head)
    paren = head.find("(")
    if type_match and (paren < 0 or type_match.start() < paren):
        symbol = type_match.group(2)
    else:
        name_match = NAME_RE.search(head)
        if not name_match:
            raise HandbookError("无法解析声明（锚点后应紧跟方法/类型声明）")
        line_no = start + 1
        owners = [span for span in spans if span[1] <= line_no <= span[2]]
        if not owners:
            raise HandbookError(f"找不到方法所属的类型声明（第 {line_no} 行）")
        innermost = min(owners, key=lambda span: span[2] - span[1])
        symbol = f"{innermost[0]}#{name_match.group(1)}"
    offset = sum(len(line) + 1 for line in lines[:start])
    brace = text.find("{", offset)
    semi = text.find(";", offset)
    if brace < 0 or (0 <= semi < brace):
        return symbol, start
    end_at = brace_end(text, brace)
    return symbol, text.count("\n", 0, end_at)


def scan(path: Path) -> list[dict]:
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    spans = type_spans(text)
    anchors = []
    for index, line in enumerate(lines):
        match = ANCHOR_RE.match(line)
        if not match:
            continue
        slug, name, label = match.group(1), match.group(2), (match.group(3) or "").strip()
        cursor = index + 1
        while cursor < len(lines) and not lines[cursor].strip():
            cursor += 1
        if cursor >= len(lines):
            raise HandbookError(f"{rel(path)}:{index + 1} 锚点后没有声明")
        try:
            symbol, end = declaration(text, lines, cursor, spans)
        except HandbookError as exc:
            raise HandbookError(f"{rel(path)}:{index + 1} {exc}") from exc
        anchors.append({
            "id": f"{slug}/{name}",
            "doc": slug,
            "label": label or name,
            "symbol": symbol,
            "source": rel(path),
            "module": module_of(path),
            "start": cursor + 1,
            "end": end + 1,
        })
    return anchors


def collect() -> list[dict]:
    anchors: list[dict] = []
    seen: dict[str, str] = {}
    for path in java_files():
        for anchor in scan(path):
            if anchor["id"] in seen:
                raise HandbookError(
                    f"锚点重复：{anchor['id']}（{seen[anchor['id']]} 与 {anchor['source']}）")
            seen[anchor["id"]] = anchor["source"]
            anchors.append(anchor)
    return sorted(anchors, key=lambda a: (a["doc"], a["start"], a["id"]))


def render_block(anchors: list[dict]) -> str:
    rows = [
        "| 行为单元 | 入口 | 源码位置 | 模块 |",
        "|---|---|---|---|",
    ]
    for anchor in anchors:
        location = f"{anchor['source']}:{anchor['start']}-{anchor['end']}"
        rows.append(
            f"| {anchor['label']} | `{anchor['symbol']}` | `{location}` | {anchor['module']} |")
    return "\n".join([BEGIN, *rows, END])


def new_doc(slug: str, anchors: list[dict]) -> str:
    modules = sorted({anchor["module"] for anchor in anchors})
    return f"""---
type: handbook-l3
level: L3
module: {modules[0] if modules else '-'}
title: {slug}
summary: 待补充（一句话说明这份实现单元覆盖的行为）
---

# {slug}

{render_block(anchors)}

## 行为拆解

## 状态与副作用

## 失败与降级

## 相关测试
"""


def write_docs(anchors: list[dict]) -> list[str]:
    changed = []
    by_doc: dict[str, list[dict]] = {}
    for anchor in anchors:
        by_doc.setdefault(anchor["doc"], []).append(anchor)
    L3_DIR.mkdir(parents=True, exist_ok=True)
    for slug, group in sorted(by_doc.items()):
        path = L3_DIR / f"{slug}.md"
        block = render_block(group)
        if not path.exists():
            path.write_text(new_doc(slug, group), encoding="utf-8")
            changed.append(rel(path))
            continue
        text = path.read_text(encoding="utf-8")
        if BEGIN in text and END in text:
            updated = BLOCK_RE.sub(lambda _: block, text, count=1)
        else:
            updated = text.rstrip() + "\n\n" + block + "\n"
        if updated != text:
            path.write_text(updated, encoding="utf-8")
            changed.append(rel(path))
    return changed


def git(*args: str) -> str:
    proc = subprocess.run(["git", *args], cwd=ROOT, capture_output=True)
    if proc.returncode != 0:
        raise HandbookError(proc.stderr.decode("utf-8", "replace").strip())
    return proc.stdout.decode("utf-8", "replace").strip()


def write_manifest(anchors: list[dict]) -> None:
    docs = []
    for folder, level in (("l1", "L1"), ("l2", "L2"), ("l3", "L3")):
        for path in sorted((HANDBOOK / folder).glob("*.md")):
            docs.append({"level": level, "path": rel(path)})
    manifest = {
        "schema": "harness-handbook/v1",
        "generatedAt": datetime.now().astimezone().replace(microsecond=0).isoformat(),
        "sourceCommit": git("rev-parse", "HEAD"),
        "docs": docs,
        "anchors": anchors,
    }
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def load_manifest() -> dict:
    if not MANIFEST.is_file():
        raise HandbookError("docs/handbook/manifest.json 不存在；先运行 build.py sync")
    try:
        return json.loads(MANIFEST.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise HandbookError(f"manifest.json 解析失败：{exc}") from exc


def check() -> int:
    anchors = collect()
    manifest = load_manifest()
    recorded = {anchor["id"]: anchor for anchor in manifest.get("anchors", [])}
    errors = []
    for anchor in anchors:
        old = recorded.get(anchor["id"])
        if old is None:
            errors.append(f"新增锚点未同步：{anchor['id']}（{anchor['source']}:{anchor['start']}）")
            continue
        if old.get("symbol") != anchor["symbol"]:
            errors.append(f"{anchor['id']}：符号由 {old.get('symbol')} 变为 {anchor['symbol']}")
        if old.get("source") != anchor["source"]:
            errors.append(f"{anchor['id']}：源文件由 {old.get('source')} 变为 {anchor['source']}")
        if (old.get("start"), old.get("end")) != (anchor["start"], anchor["end"]):
            errors.append(
                f"{anchor['id']}：行号由 {old.get('start')}-{old.get('end')} 变为 "
                f"{anchor['start']}-{anchor['end']}")
    for anchor_id in sorted(set(recorded) - {anchor["id"] for anchor in anchors}):
        errors.append(f"锚点已从代码删除：{anchor_id}（{recorded[anchor_id].get('source')}）")

    by_doc: dict[str, list[dict]] = {}
    for anchor in anchors:
        by_doc.setdefault(anchor["doc"], []).append(anchor)
    for slug, group in sorted(by_doc.items()):
        path = L3_DIR / f"{slug}.md"
        if not path.is_file():
            errors.append(f"缺少 L3 文档：{rel(path)}")
            continue
        text = path.read_text(encoding="utf-8")
        if BEGIN not in text or END not in text:
            errors.append(f"{rel(path)} 缺少 generated 区段")
        elif BLOCK_RE.search(text).group(0) != render_block(group):
            errors.append(f"{rel(path)} 生成区与代码不一致（运行 build.py sync）")
    for path in sorted(L3_DIR.glob("*.md")):
        if path.stem not in by_doc:
            errors.append(f"{rel(path)} 没有对应锚点（文档或锚点已废弃）")

    if errors:
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return fail(f"Harness Handbook 校验失败：{len(errors)} 项")
    print(f"Harness Handbook 校验通过（{len(anchors)} 个锚点 / {len(by_doc)} 份 L3）")
    return 0


def sync() -> int:
    anchors = collect()
    changed = write_docs(anchors)
    write_manifest(anchors)
    if changed:
        print("已刷新：" + "、".join(changed))
    else:
        print("L3 生成区无变化")
    print(f"锚点 {len(anchors)} 个，manifest 已更新")
    return 0


def list_anchors() -> int:
    for anchor in collect():
        print(f"{anchor['id']:<48} {anchor['symbol']:<40} "
              f"{anchor['source']}:{anchor['start']}-{anchor['end']}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(prog="handbook")
    parser.add_argument("command", choices=("sync", "check", "list"))
    args = parser.parse_args()
    try:
        if args.command == "sync":
            return sync()
        if args.command == "check":
            return check()
        return list_anchors()
    except HandbookError as exc:
        return fail(str(exc))


if __name__ == "__main__":
    sys.exit(main())
