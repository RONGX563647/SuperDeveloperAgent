#!/usr/bin/env python3
"""会话归档门禁：start / finish / validate / self-test / git hooks。

设计见 docs/session-archive-spec-v1.md。
只用 Python 标准库，Windows / Linux 均可运行。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SESSION_DIR = ROOT / "docs" / ".session"
ACTIVE = SESSION_DIR / "active.json"
EVIDENCE = SESSION_DIR / "test-evidence.json"
# 跨 worktree 测试锁超时（per-worktree 隔离后，只在同 worktree 内排队）。
LOCK_TIMEOUT = 45 * 60
LOCK_STALE = 90 * 60
PROCESS = ROOT / "docs" / "process"
SUMMARY = ROOT / "docs" / "summary"
CATEGORIES = ("feat", "bug", "chore", "docs", "deploy")
TEST_CATEGORIES = ("feat", "bug")
FOLDER_RE = re.compile(r"^(\d{4}-\d{2}-\d{2}_\d{4})_([a-z0-9]+(?:-[a-z0-9]+)*)$")
TEST_PATH_RE = re.compile(r"(/src/test/|Test\.java$|Tests\.java$|\.test\.tsx?$|\.spec\.tsx?$)")
CASE_LINE_RE = re.compile(r"^\+\s*(@Test\b|@ParameterizedTest\b|it\(|test\()")
SHA_RE = re.compile(r"^[0-9a-f]{7,40}$")
MODULE_RE = re.compile(r"^platform/([^/]+)/")
# hook 会把这些变量注入子进程（GIT_INDEX_FILE=.git/index 是相对路径），
# 测试里再跑 git 时会按错误的目录解析，导致 worktree/index 建到别处。
GIT_ENV_KEYS = (
    "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR",
    "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_PREFIX", "GIT_QUARANTINE_PATH",
)


class GateError(Exception):
    pass


def out(msg: str = "") -> None:
    print(msg)


def fail(msg: str) -> int:
    print(f"ERROR: {msg}", file=sys.stderr)
    return 1


def clean_env() -> dict:
    env = dict(os.environ)
    for key in GIT_ENV_KEYS:
        env.pop(key, None)
    return env


def git(*args: str, check: bool = True, binary: bool = False):
    proc = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, env=clean_env())
    if check and proc.returncode != 0:
        detail = proc.stderr.decode("utf-8", "replace").strip()
        raise GateError(f"git {' '.join(args)} 失败：{detail}")
    return proc.stdout if binary else proc.stdout.decode("utf-8", "replace").strip()


def now_iso() -> str:
    return datetime.now().astimezone().replace(microsecond=0).isoformat()


def canonical(obj: dict) -> str:
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def manifest_self(manifest: dict) -> str:
    body = json.loads(json.dumps(manifest, ensure_ascii=False))
    body.get("chain", {}).pop("self", None)
    return hashlib.sha256(canonical(body).encode("utf-8")).hexdigest()


def blob_hash(data: bytes) -> str:
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def commit_blob(sha: str, path: str) -> bytes:
    return git("show", f"{sha}:{path}", binary=True)


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise GateError(f"{path.relative_to(ROOT)} 读取失败：{exc}") from exc


def manifests(root: Path = PROCESS) -> list[tuple[Path, dict]]:
    if not root.is_dir():
        return []
    rows = []
    for path in sorted(root.glob("*/*/manifest.json")):
        rows.append((path, load_json(path)))
    return rows


def reseal_chain(category: str, root: Path = PROCESS) -> list[str]:
    """按 createdAt 重排某个分类的归档链，重算每份的 prev/self。

    <p>并行会话各自 finish 时看到的链尾是同一个（旧的）末端，合并后必然出现
    多条记录指向同一 prev 的断链。链本身由 createdAt 完全决定，所以落盘后
    整体重算一遍即可恢复：prev 依次串起来，self 随内容重算。只改真正变化的文件。
    """
    rows = [(path, manifest) for path, manifest in manifests(root)
            if manifest.get("category") == category]
    rows.sort(key=lambda row: (row[1].get("createdAt", ""), row[1].get("id", "")))
    changed: list[str] = []
    prev = "GENESIS"
    for path, manifest in rows:
        chain = manifest.setdefault("chain", {})
        before = (chain.get("prev"), chain.get("self"))
        chain["prev"] = prev
        chain["self"] = manifest_self(manifest)
        if before != (chain["prev"], chain["self"]):
            write_json(path, manifest)
            changed.append(str(manifest.get("id")))
        prev = chain["self"]
    return changed


def classify(subject: str) -> str:
    low = subject.lower()
    if low.startswith("feat"):
        return "feat"
    if low.startswith("fix"):
        return "bug"
    if low.startswith("docs"):
        return "docs"
    if low.startswith(("deploy", "ops")):
        return "deploy"
    return "chore"


# ------------------------------------------------------------------ start

def cmd_start(args) -> int:
    if args.category not in CATEGORIES:
        return fail(f"category 必须是 {CATEGORIES}")
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", args.slug or ""):
        return fail("slug 必须是 kebab-case 英文（小写字母/数字/短横线）")
    if ACTIVE.exists():
        return fail("已有进行中的会话；先执行 finish 或删除 docs/.session/active.json")
    stamp = datetime.now().astimezone().strftime("%Y-%m-%d_%H%M")
    folder = f"{stamp}_{args.slug}"
    seq = 2
    while (PROCESS / args.category / folder).exists():
        folder = f"{stamp}_{args.slug}-{seq}"
        seq += 1
    active = {
        "schema": "session-active/v1",
        "id": f"{args.category}/{folder}",
        "category": args.category,
        "slug": args.slug,
        "folder": folder,
        "title": args.title,
        "startedAt": now_iso(),
        "baseHead": git("rev-parse", "HEAD"),
        "agentModel": args.model,
        "toolCallCount": args.tool_calls,
        "promptSummary": args.prompt or args.title,
        "followUps": args.follow_up or [],
    }
    write_json(ACTIVE, active)
    out(f"会话已开始：{active['id']}")
    out("下一步：写实现 + 单元测试，然后 git commit（pre-commit 会自动跑测试并留存证据）")
    return 0


# ------------------------------------------------------------------ tests

def write_tree() -> str:
    """快照当前索引树。

    <p>IDE 的 SCM 刷新、并行会话的 git 操作都会短暂创建 {@code .git/index.lock}，
    撞上就会让本该通过的提交失败。这里对瞬时锁做几次短重试，测试结论本身不受影响。
    """
    last = ""
    for attempt in range(10):
        proc = subprocess.run(["git", "write-tree"], cwd=ROOT, capture_output=True,
                              env=clean_env())
        if proc.returncode == 0:
            return proc.stdout.decode("utf-8", "replace").strip()
        last = proc.stderr.decode("utf-8", "replace").strip()
        if "index.lock" not in last:
            break
        if attempt == 0:
            out("索引被其它 git 进程占用，等待锁释放后重试…")
        time.sleep(0.5)
    raise GateError(f"git write-tree 失败：{last}")


def surefire_stats(started: float) -> dict:
    tests = failures = errors = skipped = 0
    reports = ROOT / "platform"
    for xml in reports.glob("*/target/surefire-reports/TEST-*.xml"):
        if xml.stat().st_mtime < started - 5:
            continue
        text = xml.read_text(encoding="utf-8", errors="replace")
        head = text[:2000]

        def attr(name: str) -> int:
            match = re.search(rf'\b{name}="(\d+)"', head)
            return int(match.group(1)) if match else 0

        tests += attr("tests")
        failures += attr("failures")
        errors += attr("errors")
        skipped += attr("skipped")
    return {"tests": tests, "failures": failures, "errors": errors, "skipped": skipped}


def run_tests(command: str) -> dict:
    started = datetime.now().timestamp()
    proc = subprocess.run(command, cwd=ROOT / "platform", shell=True, env=clean_env())
    stats = surefire_stats(started)
    result = "PASSED" if proc.returncode == 0 and stats["failures"] == 0 and stats["errors"] == 0 else "FAILED"
    return {
        "schema": "test-evidence/v1",
        "command": command,
        "ranAt": now_iso(),
        "result": result,
        "testsRun": stats["tests"],
        "failures": stats["failures"],
        "errors": stats["errors"],
        "skipped": stats["skipped"],
        "tree": write_tree(),
    }


def shared_state_dir() -> Path:
    """跨 worktree 共享目录：主仓库公共 .git 下的 session-archive。

    <p>gate 的 ROOT 是当前 worktree，测试锁与证据缓存必须放到公共 .git，
    否则主树和 .agent/worktrees/* 会各跑一套全量测试。
    """
    proc = subprocess.run(["git", "rev-parse", "--git-common-dir"], cwd=ROOT,
                          capture_output=True, env=clean_env())
    if proc.returncode != 0:
        raise GateError("git rev-parse --git-common-dir 失败，无法定位共享目录")
    common = Path(proc.stdout.decode("utf-8", "replace").strip())
    if not common.is_absolute():
        common = (ROOT / common).resolve()
    return common / "session-archive"


def java_home() -> str:
    return os.environ.get("JAVA_HOME", "")


def maven_binary() -> str:
    """优先 mvnd（daemon 常驻，重复构建快），没有则回退 mvn。"""
    for exe in ("mvnd", "mvn"):
        if shutil.which(exe):
            return exe
    return "mvn"


def changed_modules(staged_files: list[str]) -> list[str]:
    """本次提交涉及的 Maven 模块（platform/<模块>/ 前缀，排除前端 web）。"""
    mods = set()
    for path in staged_files:
        match = MODULE_RE.match(path)
        if match and match.group(1) != "web":
            mods.add(match.group(1))
    return sorted(mods)


def incremental_test_command(staged_files: list[str]) -> str:
    """pre-commit 增量测试命令：只跑受影响模块及其下游（-amd），排除 spec-checks。

    <p>spec-checks（ArchUnit 架构 + 契约检查）是全量扫描，改动业务模块时排除它、
    移到 pre-push/CI；只有改动 spec-checks 本身时才跑它验证检查规则。
    无 Maven 业务模块改动（如根 pom、tools/handbook）时跑全部业务模块、排除 spec-checks。
    """
    mvn = maven_binary()
    mods = changed_modules(staged_files)
    if not mods:
        return f"{mvn} -B -pl !spec-checks test"
    if "spec-checks" in mods:
        return f"{mvn} -B -pl spec-checks test"
    pl = ",".join(mods)
    return f"{mvn} -B -pl {pl},!spec-checks -amd test"


def worktree_git_dir() -> Path:
    """当前 worktree 自己的 git dir（per-worktree 测试锁就放这里）。"""
    proc = subprocess.run(["git", "rev-parse", "--absolute-git-dir"], cwd=ROOT,
                          capture_output=True, env=clean_env())
    if proc.returncode != 0:
        raise GateError("git rev-parse --absolute-git-dir 失败，无法定位 worktree 锁目录")
    return Path(proc.stdout.decode("utf-8", "replace").strip())


class TestLock:
    """同 worktree 内的互斥锁（O_CREAT|O_EXCL），拿不到就排队。

    <p>锁文件放在当前 worktree 自己的 git dir 下（per-worktree 隔离），
    不同 worktree 可并行跑各自的测试、互不阻塞；同一 worktree 内
    （IDE 与 CLI 同时提交）仍串行，避免并发写 surefire 报告互相覆盖。
    """

    def __init__(self, path: Path, label: str = "测试"):
        self.path = path
        self.label = label
        self.fd: int | None = None

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        deadline = time.time() + LOCK_TIMEOUT
        waiting = False
        last_note = time.time()
        while True:
            try:
                self.fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                os.write(self.fd, f"{os.getpid()} {now_iso()}\n".encode("utf-8"))
                return self
            except FileExistsError:
                if self._stale():
                    try:
                        self.path.unlink()
                    except OSError:
                        pass
                    continue
                if time.time() > deadline:
                    raise GateError(
                        f"等待其它 worktree 的{self.label}锁超时"
                        f"（{LOCK_TIMEOUT // 60} 分钟）：{self.path}"
                    )
                if not waiting:
                    out(f"另一个 worktree 正在{self.label}，排队等待…")
                    waiting = True
                    last_note = time.time()
                elif time.time() - last_note > 60:
                    out(f"仍在等待{self.label}锁（{self.path.name}）…")
                    last_note = time.time()
                time.sleep(1)

    def _stale(self) -> bool:
        try:
            return time.time() - self.path.stat().st_mtime > LOCK_STALE
        except OSError:
            return False

    def __exit__(self, *exc):
        if self.fd is not None:
            try:
                os.close(self.fd)
            except OSError:
                pass
            self.fd = None
        try:
            self.path.unlink()
        except OSError:
            pass


def worktree_fingerprint() -> str:
    """当前工作区指纹：索引树 + 未暂存改动 + 未跟踪文件元数据。

    测试实际跑在工作区而不是索引上，只按 tree 复用会把“同样暂存、不同未提交改动”
    误判成等价，所以要把未暂存 diff 和未跟踪文件也算进来。
    """
    tree = write_tree()
    diff = subprocess.run(["git", "diff", "--no-ext-diff", "--no-color"], cwd=ROOT,
                          capture_output=True, env=clean_env())
    if diff.returncode != 0:
        raise GateError("git diff 失败，无法计算工作区指纹")
    digest = hashlib.sha256()
    digest.update(tree.encode("utf-8"))
    digest.update(b"\0")
    digest.update(diff.stdout)
    for name in sorted(line for line in git("ls-files", "--others", "--exclude-standard").splitlines() if line):
        path = ROOT / name
        try:
            stat = path.stat()
            digest.update(f"\0{name}:{stat.st_size}:{stat.st_mtime_ns}".encode("utf-8"))
        except OSError:
            digest.update(f"\0{name}:missing".encode("utf-8"))
    return digest.hexdigest()


def evidence_cache_path(fingerprint: str) -> Path:
    return shared_state_dir() / "evidence" / f"{fingerprint}.json"


def cached_evidence(command: str, fingerprint: str) -> dict | None:
    path = evidence_cache_path(fingerprint)
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if data.get("result") != "PASSED" or data.get("command") != command:
        return None
    if data.get("javaHome", java_home()) != java_home():
        return None
    data["reused"] = True
    return data


def cache_evidence(evidence: dict, fingerprint: str) -> None:
    if evidence.get("result") != "PASSED":
        return
    payload = dict(evidence)
    payload["javaHome"] = java_home()
    write_json(evidence_cache_path(fingerprint), payload)


def cmd_gate_pre_commit(args) -> int:
    staged = [line for line in git("diff", "--cached", "--name-only").splitlines() if line]
    code = [p for p in staged if not is_exempt(p)]
    if not code:
        out("仅文档/工具改动，跳过会话归档测试门禁")
        return 0
    if not ACTIVE.exists():
        return fail(
            "代码提交被拦截：没有进行中的会话归档。\n"
            "先执行：python tools/session-archive/gate.py start "
            "--category feat|bug|chore|deploy --slug <英文短名> --title <中文标题>\n"
            "（docs 类纯文档提交免此限制）"
        )
    command = (os.environ.get("SESSION_ARCHIVE_TEST_CMD") or incremental_test_command(staged)).strip()
    try:
        with TestLock(worktree_git_dir() / "session-archive" / "test.lock"):
            fingerprint = ""
            try:
                fingerprint = worktree_fingerprint()
            except GateError as exc:
                out(f"无法计算工作区指纹，跳过证据复用：{exc}")
            evidence = cached_evidence(command, fingerprint) if fingerprint else None
            if evidence:
                out(f"复用同一工作区的绿色测试证据（{evidence['ranAt']}，"
                    f"{evidence['testsRun']} 个用例）")
            else:
                out(f"运行测试门禁：{command}")
                evidence = run_tests(command)
                if fingerprint:
                    cache_evidence(evidence, fingerprint)
    except GateError as exc:
        return fail(str(exc))
    write_json(EVIDENCE, evidence)
    if evidence["result"] != "PASSED":
        return fail(
            f"测试未通过，禁止提交（tests={evidence['testsRun']} "
            f"failures={evidence['failures']} errors={evidence['errors']}）"
        )
    out(
        f"测试通过：{evidence['testsRun']} 个用例，失败 {evidence['failures']}，"
        f"错误 {evidence['errors']}，跳过 {evidence['skipped']}"
    )
    return 0


def is_exempt(path: str) -> bool:
    return (
        path.startswith("docs/")
        or path.startswith("tools/session-archive/")
        or path.startswith(".githooks/")
        or path.startswith(".trae-html-share-packages/")  # 分享产物，非源码
        or path in {".gitignore", ".pre-commit-config.yaml", "agent.md"}
        or path.endswith(".md")
    )


# ------------------------------------------------------------------ finish

def commit_files(sha: str) -> list[dict]:
    status_lines = git("diff-tree", "--no-commit-id", "--name-status", "-r", "-M", sha).splitlines()
    num_lines = git("diff-tree", "--no-commit-id", "--numstat", "-r", "-M", sha).splitlines()
    nums = {}
    for line in num_lines:
        parts = line.split("\t")
        if len(parts) >= 3:
            nums[parts[-1]] = (parts[0], parts[1])
    files = []
    for line in status_lines:
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        status = parts[0][0]
        path = parts[-1]
        add, delete = nums.get(path, ("0", "0"))
        files.append({
            "path": path,
            "status": "R" if status == "R" else status,
            "additions": int(add) if add.isdigit() else 0,
            "deletions": int(delete) if delete.isdigit() else 0,
            "blobHash": "-" if status == "D" else blob_hash(commit_blob(sha, path)),
        })
    return files


def cases_added(sha: str, tests: list[dict]) -> int:
    """统计某次提交新增的用例行数。

    必须按「引入测试的提交」逐次统计：会话内多次提交时，新增的测试文件往往不在
    HEAD 这一次提交里，用 HEAD 去 show 该文件拿不到任何 diff，用例数会被误判成 0。
    """
    count = 0
    for test in tests:
        if test["status"] == "D":
            continue
        patch = git("show", "--format=", "--unified=0", sha, "--", test["path"])
        count += sum(1 for line in patch.splitlines() if CASE_LINE_RE.match(line))
    return count


def cmd_finish(args) -> int:
    if not ACTIVE.exists():
        return fail("没有进行中的会话；先执行 start")
    active = load_json(ACTIVE)
    category = active["category"]
    head = git("rev-parse", "HEAD")
    head_tree = git("rev-parse", "HEAD^{tree}")
    evidence = load_json(EVIDENCE) if EVIDENCE.exists() else {
        "command": "未执行", "result": "SKIPPED", "testsRun": 0,
        "failures": 0, "errors": 0, "skipped": 0, "tree": "",
    }
    sha_list = [s for s in git("log", "--reverse", "--no-merges",
                               "--format=%H", f"{active['baseHead']}..{head}").splitlines() if s]
    # 纯问答/分析会话可以没有代码提交：docs 类允许空 commits，
    # 由 sweep 对账或用户手动 finish 生成"只有摘要"的归档。
    if not sha_list and category != "docs":
        return fail("会话内没有提交，无需归档（docs 类除外）")
    commits = []
    all_tests: list[dict] = []
    modules = set()
    code_trees: list[str] = []
    added_cases = 0
    for sha in sha_list:
        subject = git("log", "-1", "--format=%s", sha)
        files = commit_files(sha)
        if any(not is_exempt(f["path"]) for f in files):
            code_trees.append(git("rev-parse", f"{sha}^{{tree}}"))
        tests = [f for f in files if TEST_PATH_RE.search(f["path"])]
        all_tests.extend(tests)
        added_cases += cases_added(sha, tests)
        commits.append({
            "sha": sha, "subject": subject,
            "category": classify(subject), "files": files,
        })
        for f in files:
            match = re.match(r"platform/([^/]+)/", f["path"])
            if match and match.group(1) != "web":
                modules.add(match.group(1))
            if f["path"].startswith("platform/web/"):
                modules.add("web")

    if category in TEST_CATEGORIES:
        if not code_trees:
            return fail("feat/bug 归档要求会话内有代码改动")
        if evidence["result"] != "PASSED":
            return fail("feat/bug 归档要求测试通过；当前证据不是 PASSED")
        if evidence.get("tree") != code_trees[-1]:
            return fail("测试后代码又变了（tree 不一致）；请重新提交并让 pre-commit 重跑测试")

    tests_section = {
        "required": category in TEST_CATEGORIES,
        "files": all_tests,
        "casesAdded": added_cases,
        "command": evidence["command"],
        "result": evidence["result"],
        "evidence": (
            f"Tests run: {evidence['testsRun']}, Failures: {evidence['failures']}, "
            f"Errors: {evidence['errors']}, Skipped: {evidence['skipped']}"
        ),
    }
    if category == "chore":
        has_code = any(not is_exempt(f["path"]) for c in commits for f in c["files"])
        if has_code and not args.verification:
            return fail("chore 含代码改动，必须提供 --verification")
    if category == "deploy" and not args.verification:
        return fail("deploy 归档必须提供 --verification")
    if category in TEST_CATEGORIES and tests_section["casesAdded"] < 1:
        return fail("feat/bug 归档至少要有 1 个新增测试用例")

    period = active["folder"][:7]
    summary_ref = f"{period}/{active['folder']}"
    manifest = {
        "schema": "process-manifest/v2",
        "id": active["id"],
        "category": category,
        "title": active["title"],
        "createdAt": active["startedAt"],
        "session": {
            "agentModel": active["agentModel"],
            "promptSummary": active["promptSummary"],
            "toolCallCount": active["toolCallCount"],
        },
        "problem": args.problem or active["title"],
        "solution": args.solution or (commits[-1]["subject"] if commits else active["title"]),
        "commits": commits,
        "tests": tests_section,
        "impact": {
            "modules": sorted(modules),
            "verification": args.verification or tests_section["evidence"],
        },
        "followUps": active.get("followUps") or [],
        "links": {"relatedIds": [], "summaryRefs": [summary_ref]},
        "chain": {"prev": "GENESIS", "self": ""},
    }

    # 落盘 + 重排整条链：不再用全局互斥锁，改为乐观并发——链由 createdAt 完全决定，
    # 并行 finish 各自接在旧链尾后，reseal_chain 会整体重排收敛，不产生断链。
    manifest["chain"]["self"] = manifest_self(manifest)
    folder_dir = PROCESS / category / active["folder"]
    write_json(folder_dir / "manifest.json", manifest)
    resealed = reseal_chain(category)

    write_summary(manifest, active, period)
    ACTIVE.unlink(missing_ok=True)
    EVIDENCE.unlink(missing_ok=True)
    out(f"归档完成：docs/process/{category}/{active['folder']}/manifest.json")
    if resealed:
        out(f"归档链已重排（{len(resealed)} 份）：{', '.join(resealed)}")
    out(f"总结：docs/summary/{period}/{active['folder']}.md")
    out("请提交 docs/ 归档后再推送")
    return 0


# ------------------------------------------------------------------ summary

def jacoco_coverage() -> str:
    covered = missed = 0
    for xml in (ROOT / "platform").glob("*/target/site/jacoco/jacoco.xml"):
        text = xml.read_text(encoding="utf-8", errors="replace")
        for match in re.finditer(r'<counter type="LINE" missed="(\d+)" covered="(\d+)"/>', text):
            missed += int(match.group(1))
            covered += int(match.group(2))
    total = covered + missed
    return f"{covered * 100.0 / total:.1f}%" if total else "N/A"


def write_summary(manifest: dict, active: dict, period: str) -> None:
    folder = active["folder"]
    primary = SUMMARY / period / f"{folder}.md"
    track_dir = SUMMARY / manifest["category"]
    secondary = track_dir / f"{period}_{active['slug']}.md"
    previous = sorted(track_dir.glob("*.md")) if track_dir.is_dir() else []
    extends = previous[-1].stem.split("_", 1)[-1] if previous else "null"
    coverage = jacoco_coverage()
    gate = "PASS" if manifest["tests"]["result"] == "PASSED" else "WARN"
    tests = manifest["tests"]
    match = re.search(r"Tests run: (\d+)", tests.get("evidence", ""))
    cases_total = int(match.group(1)) if match else 0
    commits = "\n".join(
        f"| `{c['sha'][:7]}` | {c['category']} | {c['subject']} |" for c in manifest["commits"])
    body = f"""---
schema: session-summary/v1
period: {period}
createdAt: {now_iso()}
extends: {extends}
refs:
  - {manifest['id']}
metrics:
  sessions: 1
  casesAdded: {tests['casesAdded']}
  casesTotal: {cases_total}
  passRate: "{'100%' if tests['result'] == 'PASSED' else '0%'}"
  lineCoverage: "{coverage}"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: {gate}
---

# {manifest['title']}

## 概述
{manifest['problem']}
{manifest['solution']}

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
{commits}

## 测试与质量总结
- 命令：`{tests['command']}`
- 结果：{tests['result']}；{tests['evidence']}
- 新增用例：{tests['casesAdded']}
- 行覆盖率：{coverage}（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**{gate}**

## 风险敞口
- 未覆盖的新增文件：{'无' if tests['result'] == 'PASSED' else '见失败测试'}
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
{chr(10).join('- ' + f for f in manifest.get('followUps', [])) or '- 无'}

## 链接
- 归档：`docs/process/{manifest['category']}/{folder}/manifest.json`
- 上一份同域总结：{extends}
"""
    for path, canonical_path in ((primary, None), (secondary, primary)):
        path.parent.mkdir(parents=True, exist_ok=True)
        text = body
        if canonical_path is not None:
            text = text.replace("---\n", f"---\ncanonical: {canonical_path.relative_to(ROOT).as_posix()}\n", 1)
        path.write_text(text, encoding="utf-8")


# ------------------------------------------------------------------ validate

REQUIRED = ("schema", "id", "category", "title", "createdAt", "session", "problem",
            "solution", "commits", "tests", "impact", "links", "chain")


def validate_manifest(path: Path, manifest: dict, skip_git: bool) -> list[str]:
    errors: list[str] = []
    rel = path.relative_to(path.parents[2]) if len(path.parents) > 2 else path
    for key in REQUIRED:
        if key not in manifest:
            errors.append(f"{rel}: 缺少字段 {key}")
    if manifest.get("schema") != "process-manifest/v2":
        errors.append(f"{rel}: schema 必须是 process-manifest/v2")
    category = manifest.get("category")
    if category not in CATEGORIES:
        errors.append(f"{rel}: category 非法")
    ident = manifest.get("id", "")
    folder = path.parent.name
    if not FOLDER_RE.fullmatch(folder):
        errors.append(f"{rel}: 目录名不符合 YYYY-MM-DD_HHMM_<slug>")
    if ident != f"{category}/{folder}":
        errors.append(f"{rel}: id 与目录不一致（应为 {category}/{folder}）")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+08:00",
                         manifest.get("createdAt", "")):
        errors.append(f"{rel}: createdAt 必须为 +08:00 ISO 时间")
    ide_session = (manifest.get("session") or {}).get("ideSessionId")
    if ide_session and not re.fullmatch(r"[A-Za-z0-9_-]{6,64}", str(ide_session)):
        errors.append(f"{rel}: ideSessionId 非法 {ide_session}")

    commits = manifest.get("commits")
    if commits is None:
        errors.append(f"{rel}: 缺少 commits 字段")
        commits = []
    if not commits and category not in ("docs", "chore"):
        errors.append(f"{rel}: commits 不能为空（docs/chore 类可无提交）")
    for commit in commits:
        sha = commit.get("sha", "")
        if not SHA_RE.fullmatch(sha):
            errors.append(f"{rel}: commit sha 非法 {sha}")
            continue
        if skip_git:
            continue
        check = subprocess.run(["git", "cat-file", "-e", f"{sha}^{{commit}}"], cwd=ROOT,
                               capture_output=True, env=clean_env())
        if check.returncode != 0:
            errors.append(f"{rel}: commit {sha} 不存在")
            continue
        for f in commit.get("files", []):
            if f.get("status") == "D":
                continue
            try:
                actual = blob_hash(commit_blob(sha, f["path"]))
            except GateError:
                errors.append(f"{rel}: {sha} 中不存在文件 {f['path']}")
                continue
            if actual != f.get("blobHash"):
                errors.append(f"{rel}: {f['path']} blobHash 不匹配")

    tests = manifest.get("tests") or {}
    if category in TEST_CATEGORIES:
        if tests.get("required") is not True:
            errors.append(f"{rel}: feat/bug 必须 tests.required=true")
        if tests.get("result") != "PASSED":
            errors.append(f"{rel}: feat/bug 必须 tests.result=PASSED")
        if int(tests.get("casesAdded", 0)) < 1:
            errors.append(f"{rel}: feat/bug 至少新增 1 个用例")
        if not tests.get("files"):
            errors.append(f"{rel}: feat/bug 必须登记测试文件")
    if category == "deploy" and not (manifest.get("impact") or {}).get("verification"):
        errors.append(f"{rel}: deploy 必须填写 impact.verification")

    expected = manifest_self(manifest)
    if manifest.get("chain", {}).get("self") != expected:
        errors.append(f"{rel}: chain.self 与内容不符（历史被改动？）")
    return errors


def validate_root(root: Path, skip_git: bool = False) -> list[str]:
    errors: list[str] = []
    process_root = root / "docs" / "process"
    rows = []
    if process_root.is_dir():
        for path in sorted(process_root.glob("*/*/manifest.json")):
            try:
                rows.append((path, load_json(path)))
            except GateError as exc:
                errors.append(str(exc))
    for path, manifest in rows:
        errors.extend(validate_manifest(path, manifest, skip_git))
    # 一个 AI 开发会话只能有一份归档：ideSessionId 全局唯一。
    seen_ide: dict[str, str] = {}
    for path, manifest in rows:
        ide = (manifest.get("session") or {}).get("ideSessionId")
        if not ide:
            continue
        rel = str(path.relative_to(path.parents[2])) if len(path.parents) > 2 else str(path)
        if ide in seen_ide:
            errors.append(f"{rel}: ideSessionId 重复 {ide}（已在 {seen_ide[ide]}）")
        else:
            seen_ide[ide] = rel
    for category in CATEGORIES:
        chain = sorted([m for _, m in rows if m.get("category") == category],
                       key=lambda m: (m.get("createdAt", ""), m.get("id", "")))
        prev = "GENESIS"
        for manifest in chain:
            if manifest.get("chain", {}).get("prev") != prev:
                errors.append(f"{manifest.get('id')}: chain.prev 断链")
            prev = manifest.get("chain", {}).get("self", "")

    summary_root = root / "docs" / "summary"
    ids = {m.get("id") for _, m in rows}
    if summary_root.is_dir():
        for path in sorted(summary_root.glob("**/*.md")):
            text = path.read_text(encoding="utf-8", errors="replace")
            if not text.startswith("---\n"):
                errors.append(f"{path.relative_to(root)}: 缺少 frontmatter")
                continue
            front = text.split("---", 2)[1]
            refs = []
            in_refs = False
            for line in front.splitlines():
                if line.startswith("refs:"):
                    in_refs = True
                    continue
                if in_refs and line.strip().startswith("- "):
                    refs.append(line.strip()[2:].strip())
                elif in_refs and line and not line.startswith(" "):
                    in_refs = False
            for ref in refs:
                if ref not in ids:
                    errors.append(f"{path.relative_to(root)}: 引用不存在的归档 {ref}")
    return errors


def cmd_validate(args) -> int:
    errors = validate_root(ROOT)
    gaps = archive_gap_commits()
    if gaps:
        errors.append(
            "已归档锚点之前存在未归档的代码提交（归档不能跳着做）："
            + ", ".join(sha[:7] for sha in gaps)
            + "；必须补录归档，每个代码提交都要被某份 manifest 覆盖")
    if errors:
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return fail(f"会话归档校验失败：{len(errors)} 项")
    count = len(manifests())
    out(f"会话归档校验通过（{count} 份）")
    return 0


def archive_baseline() -> str | None:
    """归档体系上线的那次提交：最早新增 docs/process 文件的提交。

    <p>基线之前的提交属于归档制度建立前的历史，不做回溯要求；基线之后的所有代码提交
    必须被某份归档覆盖，否则就是"跳过归档"。
    """
    shas = git("log", "--reverse", "--format=%H", "--diff-filter=A",
               "--", "docs/process").splitlines()
    return shas[0] if shas else None


def code_commits_between(start: str, end: str, recorded: set) -> list[str]:
    """start..end 之间未归档、且含非豁免改动的提交。"""
    missing = []
    for sha in git("rev-list", f"{start}..{end}").splitlines():
        if sha in recorded:
            continue
        files = git("diff-tree", "--no-commit-id", "--name-only", "-r", "-M", sha).splitlines()
        if any(not is_exempt(path) for path in files):
            missing.append(sha)
    return missing


def archive_gap_commits() -> list[str]:
    """已归档锚点之前的漏归档（历史缺口）。

    <p>锚点取最新归档里最后一个提交；锚点之后属于"本轮会话未完成"，由 pre-push 兜底，
    这里只查锚点之前——这样多提交会话在提交过程中不会被误报，但事后想"只归档新提交、
    跳过中间提交"会立刻失败。
    """
    rows = sorted((m for _, m in manifests() if m.get("commits")),
                  key=lambda m: (m.get("createdAt", ""), m.get("id", "")))
    if not rows:
        return []
    recorded = {c.get("sha") for m in rows for c in m.get("commits", [])}
    anchor = rows[-1]["commits"][-1]["sha"]
    probe = subprocess.run(["git", "merge-base", "--is-ancestor", anchor, "HEAD"],
                           cwd=ROOT, capture_output=True, env=clean_env())
    if probe.returncode != 0:
        return []
    baseline = archive_baseline()
    start = baseline or anchor
    return code_commits_between(start, anchor, recorded)


def unarchived_code_commits() -> list[str]:
    """pre-push 契约：每个代码提交都必须被某份归档覆盖，一个都不能漏。"""
    rows = sorted((m for _, m in manifests() if m.get("commits")),
                  key=lambda m: (m.get("createdAt", ""), m.get("id", "")))
    if not rows:
        return []
    recorded = {c.get("sha") for m in rows for c in m.get("commits", [])}
    anchor = rows[-1]["commits"][-1]["sha"]
    probe = subprocess.run(["git", "merge-base", "--is-ancestor", anchor, "HEAD"],
                           cwd=ROOT, capture_output=True, env=clean_env())
    if probe.returncode != 0:
        return []
    return code_commits_between(anchor, "HEAD", recorded)


def handbook_check() -> int:
    """Handbook 漂移检查：与 spec-checks 的 HandbookDriftTest 同一入口。"""
    script = ROOT / "tools" / "handbook" / "build.py"
    if not script.is_file():
        return 0
    proc = subprocess.run([sys.executable, str(script), "check"], cwd=ROOT, env=clean_env())
    return proc.returncode


def spec_checks() -> int:
    """spec-checks 全量（ArchUnit 架构 + 契约检查），pre-push 兜底。

    <p>pre-commit 为秒级反馈只跑受影响模块单测、排除架构规则；这里补跑全量
    spec-checks（含 ArchitectureRulesTest 的 ArchUnit 规则），推送前兜底。
    """
    command = f"{maven_binary()} -B -pl spec-checks -am test"
    proc = subprocess.run(command, cwd=ROOT / "platform", shell=True, env=clean_env())
    return proc.returncode


def cmd_gate_pre_push(args) -> int:
    if ACTIVE.exists():
        active = load_json(ACTIVE)
        return fail(f"会话未归档：{active.get('id')}。先执行 python tools/session-archive/gate.py finish")
    dirty = git("status", "--porcelain", "docs/process", "docs/summary")
    if dirty:
        return fail("存在未提交的归档/总结文件，请先提交 docs/ 后再推送")
    missing = unarchived_code_commits()
    if missing:
        return fail(
            "存在未归档的代码提交，禁止推送：\n  "
            + "\n  ".join(sha[:7] for sha in missing)
            + "\n每个代码提交都必须 start → commit → finish 归档；禁止 --no-verify 绕过"
        )
    result = cmd_validate(args)
    if result != 0:
        return result
    if handbook_check() != 0:
        return fail("Harness Handbook 已漂移：运行 python tools/handbook/build.py sync 并提交 docs/handbook")
    if spec_checks() != 0:
        return fail("spec-checks 规约检查未通过（架构 + 契约）；禁止推送")
    return 0


# ------------------------------------------------------------------ self-test

def cmd_self_test(args) -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        base = {
            "schema": "process-manifest/v2",
            "id": "feat/2026-11-17_1000_sample",
            "category": "feat",
            "title": "示例特性",
            "createdAt": "2026-11-17T10:00:00+08:00",
            "session": {"agentModel": "test", "promptSummary": "示例", "toolCallCount": 1},
            "problem": "问题描述示例",
            "solution": "方案描述示例",
            "commits": [{"sha": "0123456", "subject": "feat: x", "category": "feat",
                         "files": [{"path": "a.java", "status": "A", "additions": 1,
                                    "deletions": 0, "blobHash": "0" * 40}]}],
            "tests": {"required": True, "files": [{"path": "ATest.java", "status": "A",
                       "additions": 1, "deletions": 0, "blobHash": "0" * 40}],
                      "casesAdded": 1, "command": "mvn test", "result": "PASSED",
                      "evidence": "Tests run: 1"},
            "impact": {"modules": ["x"], "verification": "ok"},
            "links": {"relatedIds": [], "summaryRefs": []},
            "chain": {"prev": "GENESIS", "self": ""},
        }
        base["chain"]["self"] = manifest_self(base)
        target = root / "docs" / "process" / "feat" / "2026-11-17_1000_sample" / "manifest.json"
        write_json(target, base)
        if validate_root(root, skip_git=True):
            return fail("self-test: 合法样本不应报错")

        broken = json.loads(json.dumps(base))
        broken["tests"]["casesAdded"] = 0
        write_json(target, broken)
        if not validate_root(root, skip_git=True):
            return fail("self-test: 缺用例应报错")

        tampered = json.loads(json.dumps(base))
        tampered["chain"]["self"] = "f" * 64
        write_json(target, tampered)
        if not validate_root(root, skip_git=True):
            return fail("self-test: 篡改 self 应报错")

        # 纯问答会话：docs 类允许空 commits
        docs = {
            "schema": "process-manifest/v2",
            "id": "docs/2026-11-17_0930_chat-summary",
            "category": "docs",
            "title": "纯问答会话归档",
            "createdAt": "2026-11-17T09:30:00+08:00",
            "session": {"agentModel": "unknown", "promptSummary": "分析",
                        "toolCallCount": 0, "ideSessionId": "61e6c0a1c3d4e5f6"},
            "problem": "对话式分析，无代码改动",
            "solution": "记录结论与证据位置",
            "commits": [],
            "tests": {"required": False, "files": [], "casesAdded": 0,
                      "command": "n/a", "result": "NOT_RUN", "evidence": "无代码改动"},
            "impact": {"modules": [], "verification": "无代码改动"},
            "links": {"relatedIds": [], "summaryRefs": []},
            "chain": {"prev": "GENESIS", "self": ""},
        }
        docs["chain"]["self"] = manifest_self(docs)
        docs_path = (root / "docs" / "process" / "docs"
                     / "2026-11-17_0930_chat-summary" / "manifest.json")
        write_json(docs_path, docs)
        write_json(target, base)
        if validate_root(root, skip_git=True):
            return fail("self-test: docs 空提交归档应被接受")

        dup = json.loads(json.dumps(base))
        dup["session"]["ideSessionId"] = docs["session"]["ideSessionId"]
        dup["chain"]["self"] = manifest_self(dup)
        write_json(target, dup)
        if not validate_root(root, skip_git=True):
            return fail("self-test: ideSessionId 重复应报错")

        # 并行会话各接旧链尾 → 合并后断链；reseal_chain 应把链重排回一致
        write_json(target, base)
        later = json.loads(json.dumps(base))
        later["id"] = "feat/2026-11-17_1100_sample-later"
        later["createdAt"] = "2026-11-17T11:00:00+08:00"
        later["chain"] = {"prev": "GENESIS", "self": ""}
        later["chain"]["self"] = manifest_self(later)
        later_path = (root / "docs" / "process" / "feat"
                      / "2026-11-17_1100_sample-later" / "manifest.json")
        write_json(later_path, later)
        if not any("chain.prev 断链" in e for e in validate_root(root, skip_git=True)):
            return fail("self-test: 并联接链应被判为断链")
        resealed = reseal_chain("feat", root / "docs" / "process")
        if resealed != ["feat/2026-11-17_1100_sample-later"]:
            return fail(f"self-test: 只应重排后一份归档，实际 {resealed}")
        if validate_root(root, skip_git=True):
            return fail("self-test: 重排后链应恢复一致")
        if reseal_chain("feat", root / "docs" / "process"):
            return fail("self-test: 链已一致时重排不应再改文件")
    out("self-test OK")
    return 0


# ------------------------------------------------------------------ main

def main() -> int:
    parser = argparse.ArgumentParser(prog="session-archive")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("start")
    p.add_argument("--category", required=True)
    p.add_argument("--slug", required=True)
    p.add_argument("--title", required=True)
    p.add_argument("--model", default="unknown")
    p.add_argument("--prompt", default="")
    p.add_argument("--tool-calls", type=int, default=0)
    p.add_argument("--follow-up", action="append")
    p.set_defaults(func=cmd_start)

    p = sub.add_parser("finish")
    p.add_argument("--problem")
    p.add_argument("--solution")
    p.add_argument("--verification")
    p.set_defaults(func=cmd_finish)

    sub.add_parser("validate").set_defaults(func=cmd_validate)
    sub.add_parser("self-test").set_defaults(func=cmd_self_test)
    sub.add_parser("gate-pre-commit").set_defaults(func=cmd_gate_pre_commit)
    sub.add_parser("gate-pre-push").set_defaults(func=cmd_gate_pre_push)

    args = parser.parse_args()
    try:
        return args.func(args)
    except GateError as exc:
        return fail(str(exc))


if __name__ == "__main__":
    sys.exit(main())
