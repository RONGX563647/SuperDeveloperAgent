#!/usr/bin/env python3
"""提交信息编码门禁：拦住已经损坏成 "?" / U+FFFD 的 commit message。

为什么需要它
------------
中文提交信息一旦在「生成 → 传参 → 落盘」的某一环被按 ASCII/GBK 重新编码，字符
会被替换成问号（0x3F）再写进 git 对象。这个替换是**不可逆**的：git 里躺着的就是
`chore(release): main ???? 0.1.0-snapshot`，GitHub 上也只能显示问号，和"提交本身
不存在"一样无从修复，只能改历史重写。

本地 `.githooks/commit-msg` 拦得住 `git commit`，但网页提交、API 提交、自动化脚本
（Agent 用 PowerShell 拼字符串最常见）都绕得过钩子，所以远端 CI 要再兜一层。

用法
----
    # CI：只查本次变更引入的提交（base 为空/全零/已失效时自动退回单提交）
    python tools/ci/check-commit-messages.py --base "$BASE_SHA" --head "$GITHUB_SHA" \
        --allow-file tools/ci/known-bad-commit-messages.txt

    # 本地自查最近 30 条（历史已有的坏提交只报告，不阻断）
    python tools/ci/check-commit-messages.py --history 30

判定规则
--------
1. 出现 U+FFFD（REPLACEMENT CHARACTER）→ 非 UTF-8 字节被吞，判定为损坏。
2. 出现连续两个及以上的半角问号 `??`：
   - 行首 `??  path`（git status 的未跟踪标记）→ 跳过，那是粘贴的输出不是损坏；
   - 该行仍有中文 → 判为**警告**不阻断（"你确定??" 这类正常双问号不该卡人）；
   - 该行一个中文都不剩 → 判定为损坏：中文被整段替换成问号正是本门禁要抓的形态。
3. 单个问号不判（"is this ok?" 正常）。

历史遗留的坏提交用 `--allow-file` 登记在 `tools/ci/known-bad-commit-messages.txt`，
否则主干同步类 PR（会把陈年提交一起带进范围）永远红。

退出码：0 通过；1 有损坏提交；2 用法/git 调用失败。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

# U+FFFD 与环境无关；`??+` 覆盖 "????" 这种整词被吃掉的情况。
REPLACEMENT_CHAR = re.compile("\ufffd")
DOUBLE_QMARK = re.compile(r"\?{2,}")
# 中文（含扩展 A 与兼容区）：用来区分"正常双问号"与"整段中文被吃掉"。
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
# git status 的未跟踪/忽略标记，粘贴进提交信息时不该算损坏。
GIT_STATUS_PREFIX = re.compile(r"^\s*(?:\?\?|!!)\s+\S")

ZERO_SHA = "0" * 40

REMEDY = (
    "\n修复方式（下次提交起生效，历史只能改写）：\n"
    "  1. 让生成提交信息的脚本/命令用 UTF-8：PowerShell 里先执行\n"
    "     [Console]::OutputEncoding = [Text.UTF8Encoding]::new()\n"
    "     $OutputEncoding = [Text.UTF8Encoding]::new()\n"
    "  2. 提交信息写文件再提交，避免命令行参数二次转码：\n"
    "     git commit -F .git/COMMIT_MSG_UTF8.txt\n"
    "  3. 确认 git 侧声明：git config --global i18n.commitEncoding utf-8\n"
    "详见 docs/ci-commit-encoding.md"
)

DEFAULT_ALLOW_FILE = "tools/ci/known-bad-commit-messages.txt"


@dataclass(frozen=True)
class Commit:
    sha: str
    subject: str
    body: str


@dataclass(frozen=True)
class Findings:
    errors: list[str]
    warnings: list[str]


def git(*args: str, check: bool = True) -> str:
    proc = subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and proc.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} 失败: {proc.stderr.strip()}")
    return proc.stdout


def commit_exists(rev: str) -> bool:
    if not rev or set(rev) <= {"0"}:
        return False
    proc = subprocess.run(
        ["git", "cat-file", "-e", f"{rev}^{{commit}}"],
        capture_output=True,
    )
    return proc.returncode == 0


def read_commits(rev_range: str) -> list[Commit]:
    """按 rev-list 顺序读取提交；用记录分隔符避免被 message 里的换行打乱解析。"""
    raw = git(
        "-c",
        "core.quotepath=false",
        "log",
        "--encoding=utf-8",
        "--format=%H%x1f%B%x1e",
        rev_range,
    )
    commits: list[Commit] = []
    for chunk in raw.split("\x1e"):
        chunk = chunk.strip("\n")
        if not chunk:
            continue
        sha, _, message = chunk.partition("\x1f")
        lines = message.strip("\n").splitlines()
        commits.append(
            Commit(
                sha=sha.strip(),
                subject=lines[0] if lines else "",
                body=message,
            )
        )
    return commits


def inspect(body: str) -> Findings:
    errors: list[str] = []
    warnings: list[str] = []
    for line in body.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if REPLACEMENT_CHAR.search(line):
            errors.append(f"U+FFFD: {stripped}")
            continue
        if not DOUBLE_QMARK.search(line):
            continue
        if GIT_STATUS_PREFIX.match(line):
            continue
        if CJK.search(line):
            warnings.append(f"连续问号（行内仍有中文，按正常双问号放过）: {stripped}")
            continue
        errors.append(f"连续问号且无中文残留（中文已被替换）: {stripped}")
    return Findings(errors=errors, warnings=warnings)


def load_allow(paths: list[str]) -> set[str]:
    """读取历史遗留坏提交白名单：一行一个 sha 前缀，`#` 起始为注释。"""
    allowed: set[str] = set()
    for raw in paths:
        if not raw:
            continue
        path = Path(raw)
        if not path.is_file():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            item = line.split("#", 1)[0].strip()
            if item:
                allowed.add(item.lower())
    return allowed


def is_allowed(sha: str, allowed: set[str]) -> bool:
    sha = sha.lower()
    return any(sha.startswith(prefix) for prefix in allowed)


def check_file(path: Path) -> int:
    """commit-msg 钩子入口：只看这一次要落库的 message。

    钩子拿到的是未提交的编辑文件，按严格 UTF-8 解码；解不开说明上游已经用错编码写盘。
    """
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        print(f"ERROR: 找不到提交信息文件 {path}", file=sys.stderr)
        return 2
    except UnicodeDecodeError as exc:
        print(f"提交信息编码：{path} 不是合法 UTF-8（{exc.reason} @ byte {exc.start}）", file=sys.stderr)
        print(REMEDY, file=sys.stderr)
        return 1

    # 注释行（以 # 开头）不会进 git，跳过以免误报。
    body = "\n".join(line for line in text.splitlines() if not line.startswith("#"))
    findings = inspect(body)
    for warning in findings.warnings:
        print(f"  · 提示：{warning}", file=sys.stderr)
    if not findings.errors:
        return 0

    print("提交信息编码：中文已被替换成 ? / U+FFFD，这条提交不能落库", file=sys.stderr)
    for error in findings.errors:
        print(f"  - {error}", file=sys.stderr)
    print(REMEDY, file=sys.stderr)
    return 1


def resolve_range(base: str, head: str, history: int | None) -> tuple[str, str]:
    """返回 (rev_range, 说明)。

    - base 有效：base..head（只查本次引入的提交）
    - base 为空 / 全零 / 已被 force-push 掉：退回单提交 head
    - --history N：最近 N 条
    """
    if history is not None:
        return f"-{history}", f"最近 {history} 条提交"
    head = head or "HEAD"
    if commit_exists(base):
        return f"{base}..{head}", f"{base[:7]}..{head[:7] if head != 'HEAD' else 'HEAD'}"
    if commit_exists(head):
        # `head^!` 表示"该提交自身、不含父提交"：git log 需要单个 revision 参数，
        # 不能把 `-1` 和 sha 拼成一个字符串。
        return f"{head}^!", f"单提交 {head[:7]}（base 不可用：新分支或 force-push）"
    raise RuntimeError(f"既没有可用的 base（{base or '空'}）也没有可用的 head（{head}）")


def main(argv: list[str] | None = None) -> int:
    # Windows 控制台默认 GBK，中文提示会二次炸成 "?"，这里强制 UTF-8。
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
        except (AttributeError, ValueError):
            pass

    parser = argparse.ArgumentParser(description="检查提交信息是否被编码损坏")
    scope = parser.add_mutually_exclusive_group()
    scope.add_argument("--history", type=int, default=None, help="改为检查最近 N 条")
    scope.add_argument(
        "--file",
        default="",
        help="检查单个提交信息文件（commit-msg 钩子传入 .git/COMMIT_EDITMSG）",
    )
    parser.add_argument("--base", default="", help="范围起点（PR base / push before）")
    parser.add_argument("--head", default="HEAD", help="范围终点")
    parser.add_argument(
        "--strict-legacy",
        action="store_true",
        help="历史模式也返回非零（默认历史模式只报告，便于先清理再收紧）",
    )
    parser.add_argument(
        "--allow",
        action="append",
        default=[],
        help=f"历史遗留坏提交的 sha 前缀文件（可重复；默认读 {DEFAULT_ALLOW_FILE}）",
    )
    args = parser.parse_args(argv)

    if args.history is not None and (args.base or args.head != "HEAD"):
        parser.error("--history 与 --base/--head 是两种范围，不能同时指定")

    try:
        if args.file:
            return check_file(Path(args.file))
        rev_range, scope_desc = resolve_range(args.base, args.head, args.history)
        commits = read_commits(rev_range)
    except RuntimeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    if not commits:
        print(f"提交信息编码：{scope_desc} 内没有提交，跳过")
        return 0

    allow_files = args.allow or [DEFAULT_ALLOW_FILE]
    allowed = load_allow(allow_files)

    bad: list[tuple[Commit, Findings]] = []
    warned: list[tuple[Commit, Findings]] = []
    skipped: list[str] = []
    for commit in commits:
        findings = inspect(commit.body)
        if not findings.errors and not findings.warnings:
            continue
        if is_allowed(commit.sha, allowed):
            skipped.append(f"{commit.sha[:7]}  {commit.subject}")
            continue
        if findings.errors:
            bad.append((commit, findings))
        else:
            warned.append((commit, findings))

    for commit, findings in warned:
        print(f"  · 提示 {commit.sha[:7]}: {findings.warnings[0]}")

    if skipped:
        print(f"提交信息编码：{len(skipped)} 条历史遗留坏提交在 allow-file 白名单里，跳过")

    if not bad:
        print(f"提交信息编码：{scope_desc} 共 {len(commits)} 条，未发现 ? / U+FFFD 损坏")
        if args.history is None:
            return 0
        return 0

    print(
        f"提交信息编码：{scope_desc} 共 {len(commits)} 条，{len(bad)} 条已损坏（? 不可逆）",
        file=sys.stderr,
    )
    for commit, findings in bad:
        print(f"\n  {commit.sha[:7]}  {commit.subject}", file=sys.stderr)
        for error in findings.errors:
            print(f"    - {error}", file=sys.stderr)

    print(REMEDY, file=sys.stderr)

    if args.history is not None and not args.strict_legacy:
        print("（历史模式：仅报告，不阻断）", file=sys.stderr)
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
