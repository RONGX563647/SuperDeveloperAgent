#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""会话自动对账：把 AI 开发会话（Trae 记忆里的 topics）补录成 docs 类归档。

背景：git hook 只能覆盖"有提交的会话"，纯问答/分析会话永远进不了归档。
本脚本读取 IDE 自动写下的会话摘要（topics.md），对照已有 manifest：

- 会话窗口内的提交都已被归档 → 只登记映射，不重复建档；
- 窗口内没有提交 → 生成 docs 类归档（commits 为空，tests.required=false）；
- 窗口内有未归档提交 → 报错并跳过（代码会话必须先走 gate start/finish）。

用法：
    python tools/session-archive/sweep.py --dry-run
    python tools/session-archive/sweep.py
    python tools/session-archive/sweep.py --memory-dir C:/Users/x/.trae-cn/memory/projects/<project>

可用环境变量 TRAE_MEMORY_ROOT 覆盖记忆根目录；定时任务默认每天跑一次。
"""
import argparse
import importlib.util
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("gate", TOOLS / "gate.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

TOPIC_RE = re.compile(
    r"\[session_id:\s*([0-9a-f]+)\s*\|\s*topic_summary_time:\s*"
    r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\](.*)")
TZ = timezone(timedelta(hours=8))
STATE = gate.ROOT / "docs" / ".session" / "ide-sessions.json"


def find_memory_dir(root: Path, explicit: str | None) -> Path | None:
    if explicit:
        path = Path(explicit)
        return path if path.is_dir() else None
    base = Path(os.environ.get("TRAE_MEMORY_ROOT",
                               str(Path.home() / ".trae-cn" / "memory" / "projects")))
    if not base.is_dir():
        return None
    slug = re.sub(r"[^0-9A-Za-z]+", "-", str(root).replace(":", "")).strip("-").lower()
    for candidate in base.iterdir():
        if candidate.is_dir() and slug in candidate.name.lower().replace("_", "-"):
            return candidate
    return None


def parse_topics(memory_dir: Path) -> list[dict]:
    topics = []
    for file in sorted(memory_dir.glob("*/topics.md")):
        for line in file.read_text(encoding="utf-8").splitlines():
            match = TOPIC_RE.search(line)
            if not match:
                continue
            topics.append({
                "sessionId": match.group(1),
                "time": datetime.strptime(match.group(2), "%Y-%m-%d %H:%M:%S").replace(tzinfo=TZ),
                "text": match.group(3).strip(),
            })
    return topics


def archived_commits() -> set[str]:
    shas = set()
    for _, manifest in gate.manifests():
        for commit in manifest.get("commits") or []:
            shas.add(commit.get("sha"))
    return shas


def archive_by_commit() -> dict[str, str]:
    mapping = {}
    for _, manifest in gate.manifests():
        for commit in manifest.get("commits") or []:
            mapping[commit.get("sha")] = manifest["id"]
    return mapping


def known_sessions() -> dict:
    state = {}
    if STATE.exists():
        state = gate.load_json(STATE).get("sessions", {})
    for _, manifest in gate.manifests():
        ide = (manifest.get("session") or {}).get("ideSessionId")
        if ide:
            state[ide] = {"archive": manifest["id"], "source": "manifest"}
    return state


def commits_in_window(start: datetime, end: datetime) -> list[str]:
    out = gate.git("log", "--no-merges", "--format=%H",
                   f"--since={start.isoformat()}", f"--until={end.isoformat()}")
    return [line for line in out.splitlines() if line]


def is_code_commit(sha: str) -> bool:
    files = gate.git("diff-tree", "--no-commit-id", "--name-only", "-r", "-M", sha).splitlines()
    return any(not gate.is_exempt(path) for path in files)


def title_of(text: str) -> str:
    cleaned = re.sub(r"^(用户要求|用户反馈|用户提出|用户澄清|完成|本次|用户)\s*", "", text)
    first = re.split(r"[。；;!?！？\n]", cleaned)[0].strip()
    return (first or text).strip()[:60]


def main() -> int:
    parser = argparse.ArgumentParser(prog="session-archive-sweep")
    parser.add_argument("--memory-dir", default=None)
    parser.add_argument("--window-hours", type=int, default=8)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    memory_dir = find_memory_dir(gate.ROOT, args.memory_dir)
    if not memory_dir:
        print("未找到 IDE 会话记忆目录，跳过（可用 --memory-dir 指定）")
        return 0

    topics = parse_topics(memory_dir)
    if not topics:
        print(f"{memory_dir} 下没有会话摘要，跳过")
        return 0

    recorded = archived_commits()
    owner = archive_by_commit()
    known = known_sessions()
    chain = gate.category_chain("docs")
    prev = chain[-1]["chain"]["self"] if chain else "GENESIS"
    base = datetime.now(TZ).replace(microsecond=0)
    created = 0
    pending = 0
    missing = 0

    for topic in topics:
        session_id = topic["sessionId"]
        if session_id in known:
            continue
        end = topic["time"]
        start = end - timedelta(hours=args.window_hours)
        candidates = commits_in_window(start, end)
        unarchived = [sha for sha in candidates
                      if sha not in recorded and is_code_commit(sha)]
        if unarchived:
            missing += 1
            print(f"[跳过] 会话 {session_id} 时间窗内有未归档提交："
                  + ", ".join(sha[:7] for sha in unarchived[:5]))
            continue
        code_commits = [sha for sha in candidates if sha in owner]
        related = owner[code_commits[-1]] if code_commits else None

        folder = f"{end:%Y-%m-%d_%H%M}_session-{session_id[:8]}"
        manifest_id = f"docs/{folder}"
        manifest = {
            "schema": "process-manifest/v2",
            "id": manifest_id,
            "category": "docs",
            "title": title_of(topic["text"]),
            "createdAt": (base + timedelta(seconds=created + pending)).isoformat(),
            "session": {
                "agentModel": "unknown",
                "promptSummary": topic["text"][:200],
                "toolCallCount": 0,
                "ideSessionId": session_id,
                "autoSwept": True,
            },
            "problem": "AI 开发会话（无代码提交）：" + title_of(topic["text"]),
            "solution": topic["text"][:800],
            "commits": [],
            "tests": {
                "required": False,
                "files": [],
                "casesAdded": 0,
                "command": "n/a",
                "result": "NOT_RUN",
                "evidence": "会话无代码改动，按会话摘要自动补录",
            },
            "impact": {"modules": [],
                       "verification": "无代码改动（自动对账生成）"
                       + (f"；代码产出见 {related}" if related else "")},
            "followUps": [],
            "links": {
                "relatedIds": [related] if related else [],
                "summaryRefs": [f"{folder[:7]}/{folder}"],
            },
            "chain": {"prev": prev, "self": ""},
        }
        manifest["chain"]["self"] = gate.manifest_self(manifest)
        prev = manifest["chain"]["self"]
        known[session_id] = {"archive": manifest_id, "source": "sweep"}
        created += 1
        if args.dry_run:
            print(f"[待补录] {manifest_id} | {manifest['title']}")
            continue
        gate.write_json(gate.PROCESS / "docs" / folder / "manifest.json", manifest)
        gate.write_summary(manifest, {"folder": folder, "slug": folder.split("_", 2)[2],
                                      "category": "docs"}, folder[:7])
        print(f"[已补录] {manifest_id} | {manifest['title']}")

    if not args.dry_run:
        gate.write_json(STATE, {"schema": "ide-sessions/v1",
                                "updatedAt": datetime.now(TZ).isoformat(),
                                "sessions": known})
    print(f"对账完成：会话 {len(topics)}，新增 {0 if args.dry_run else created}，"
          f"未归档提交待处理 {missing}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
