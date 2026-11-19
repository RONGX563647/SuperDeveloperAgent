#!/usr/bin/env python3
"""会话归档门禁：start / finish / validate / self-test / git hooks。

设计见 docs/session-archive-spec-v1.md。
只用 Python 标准库，Windows / Linux 均可运行。
"""
from __future__ import annotations

import argparse
import glob
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
# 多会话：每个 worktree 一份进行中的会话（key = 该 worktree git dir 的哈希）。
# 旧版是全局单例 docs/.session/active.json —— 两个 AI 会话并行时必然互相阻塞，
# 读到时自动迁移成 per-worktree 文件。
ACTIVE_LEGACY = SESSION_DIR / "active.json"
EVIDENCE_LEGACY = SESSION_DIR / "test-evidence.json"
# 会话注册表放在共享 git 目录（.git/session-archive），不能放在 docs/.session：
# 每个 worktree 是独立的工作目录，放工作区里会导致"主树看不到 worktree 的会话"。


def active_dir() -> Path:
    return shared_state_dir() / "sessions"


def evidence_dir() -> Path:
    return shared_state_dir() / "session-evidence"
# 跨 worktree 测试锁超时（per-worktree 隔离后，只在同 worktree 内排队）。
LOCK_TIMEOUT = 45 * 60
LOCK_STALE = 90 * 60
PROCESS = ROOT / "docs" / "process"
SUMMARY = ROOT / "docs" / "summary"
CATEGORIES = ("feat", "bug", "chore", "docs", "deploy")
TEST_CATEGORIES = ("feat", "bug")
FOLDER_RE = re.compile(r"^(\d{4}-\d{2}-\d{2}_\d{4})_([a-z0-9]+(?:-[a-z0-9]+)*)$")
TEST_PATH_RE = re.compile(r"(/src/test/|Test\.java$|Tests\.java$|\.test\.tsx?$|\.spec\.tsx?$)")
# 用例计数：Java 用注解，vitest 用 it()/test()，而本仓库的前端自检脚本是零依赖的
# `check('名字', 条件)` 形式——漏掉它会让"确实写了 20 个断言"的提交被判成没有新增用例。
CASE_LINE_RE = re.compile(r"^\+\s*(@Test\b|@ParameterizedTest\b|it\(|test\(|check\()")
SHA_RE = re.compile(r"^[0-9a-f]{7,40}$")
MODULE_RE = re.compile(r"^platform/([^/]+)/")
# 根 pom：唯一"改了就必须全 reactor 重新解析"的文件。
ROOT_POM = "platform/pom.xml"
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


def existing_commits(shas: list[str]) -> set[str]:
    """批量校验提交存在性：一次 git cat-file --batch-check 取代逐 sha 子进程。"""
    uniq = sorted(set(shas))
    if not uniq:
        return set()
    query = "\n".join(f"{sha}^{{commit}}" for sha in uniq) + "\n"
    proc = subprocess.run(["git", "cat-file", "--batch-check"], cwd=ROOT,
                          input=query.encode("utf-8"), capture_output=True,
                          env=clean_env())
    if proc.returncode != 0:
        raise GateError(
            "git cat-file --batch-check 失败："
            + proc.stderr.decode("utf-8", "replace").strip())
    existing: set[str] = set()
    for line in proc.stdout.decode("utf-8", "replace").splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "commit":
            existing.add(parts[0])
    return existing


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


# ------------------------------------------------------------ 会话文件（per-worktree）

def worktree_key() -> str:
    """当前 worktree 的稳定标识：git dir 路径的短哈希。

    <p>主树与 {@code .agent/worktrees/*} 各有自己的 git dir，因此各自能开一个
    进行中的会话语义；同一个 worktree 内仍然只有一个（避免同一处并发写归档）。
    """
    digest = hashlib.sha256(str(worktree_git_dir()).encode("utf-8")).hexdigest()
    return digest[:12]


def active_path() -> Path:
    return active_dir() / f"{worktree_key()}.json"


def evidence_path() -> Path:
    return evidence_dir() / f"{worktree_key()}.json"


def _migrate_legacy_active() -> None:
    """旧单例 active.json → 本 worktree 的分片文件（仅当没有任何分片时）。"""
    target_dir = active_dir()
    legacy_shards = SESSION_DIR / "active"
    # 中间版本把分片写在 docs/.session/active（工作区内），同样搬到共享目录。
    if legacy_shards.is_dir():
        target_dir.mkdir(parents=True, exist_ok=True)
        for shard in legacy_shards.glob("*.json"):
            try:
                shard.replace(target_dir / shard.name)
            except OSError:
                pass
        try:
            legacy_shards.rmdir()
        except OSError:
            pass
    if not ACTIVE_LEGACY.is_file():
        return
    if any(target_dir.glob("*.json")):
        return
    target_dir.mkdir(parents=True, exist_ok=True)
    try:
        ACTIVE_LEGACY.replace(active_path())
        out("已把旧的单会话 docs/.session/active.json 迁移为 per-worktree 会话")
    except OSError:
        pass


def active_session() -> dict | None:
    """本 worktree 进行中的会话；没有返回 None。"""
    _migrate_legacy_active()
    path = active_path()
    if not path.is_file():
        return None
    try:
        return load_json(path)
    except (OSError, json.JSONDecodeError):
        return None


def save_active(active: dict) -> None:
    write_json(active_path(), active)


def clear_active() -> None:
    try:
        active_path().unlink()
    except OSError:
        pass


def clear_evidence() -> None:
    try:
        evidence_path().unlink()
    except OSError:
        pass
    try:
        EVIDENCE_LEGACY.unlink()
    except OSError:
        pass


def all_active_sessions() -> list[tuple[Path, dict]]:
    """所有 worktree 进行中的会话（含旧单例），供 status / doctor 展示。"""
    _migrate_legacy_active()
    rows: list[tuple[Path, dict]] = []
    directory = active_dir()
    for path in sorted(directory.glob("*.json")) if directory.is_dir() else []:
        try:
            rows.append((path, load_json(path)))
        except (OSError, json.JSONDecodeError):
            continue
    return rows

def folder_slug(slug: str, task_code: str) -> str:
    """归档目录的 slug：给了任务编码就用「编码-slug」，否则退回纯 slug。

    <p>「存档用任务编码作标题」要求归档名里带编号（bug/2026-11-19_0122_bug-12-xxx），
    否则只能靠时间戳在几十份归档里翻。
    """
    code = (task_code or "").strip().lower()
    return f"{code}-{slug}" if code else slug


def cmd_start(args) -> int:
    if args.category not in CATEGORIES:
        return fail(f"category 必须是 {CATEGORIES}")
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", args.slug or ""):
        return fail("slug 必须是 kebab-case 英文（小写字母/数字/短横线）")
    existing = active_session()
    if existing:
        return fail(f"本 worktree 已有进行中的会话：{existing.get('id')}；"
                    f"先执行 finish（文件：{active_path()}）")
    # 任务编码（如 BUG-12 / FEAT-3）：给了就让它成为归档名与标题的标识，
    # 「存档用任务编码作标题」依赖这条；没给则退回"时间_slug"（历史行为不变）。
    code = (args.task_code or "").strip()
    if code and not re.fullmatch(r"(?i)(bug|feat)-\d{1,4}", code):
        return fail("task-code 必须形如 BUG-12 / FEAT-3")
    code_slug = folder_slug(args.slug, code)
    stamp = datetime.now().astimezone().strftime("%Y-%m-%d_%H%M")
    folder = f"{stamp}_{code_slug}"
    seq = 2
    while (PROCESS / args.category / folder).exists():
        folder = f"{stamp}_{code_slug}-{seq}"
        seq += 1
    # 标题带上编码：任务页、归档列表、总结文档标题三处因此能对得上同一个编号
    title = f"{code.upper()} {args.title}".strip() if code else args.title
    active = {
        "schema": "session-active/v1",
        "id": f"{args.category}/{folder}",
        "category": args.category,
        "slug": code_slug,
        "taskCode": code.upper(),
        "folder": folder,
        "title": title,        "startedAt": now_iso(),
        "baseHead": git("rev-parse", "HEAD"),
        "agentModel": args.model,
        "toolCallCount": args.tool_calls,
        "promptSummary": args.prompt or title,
        "followUps": args.follow_up or [],
        "worktree": str(ROOT),
    }
    save_active(active)
    out(f"会话已开始：{active['id']}（worktree {worktree_key()}）")
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
    ensure_jdk()
    started = datetime.now().timestamp()
    begun = time.time()
    proc = subprocess.run(command, cwd=ROOT / "platform", shell=True, env=build_env())
    stats = surefire_stats(started)
    record_metric("pre-commit", time.time() - begun, command=command,
                  testsRun=stats["tests"], result="PASSED" if proc.returncode == 0 else "FAILED")
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


JDK_MIN_MAJOR = 21
# 常见 JDK 安装位置探测（模式扫描，不写死任何单一执行路径）。
JDK_SCAN_GLOBS = (
    "F:/dev/tools/jdk*",
    "C:/Program Files/Java/jdk*",
    "C:/Program Files/Eclipse Adoptium/jdk-*",
    "C:/Program Files/BellSoft/*",
    "C:/Program Files/Zulu/zulu*",
    str(Path.home() / ".jdks" / "*"),
)

_java_home_cache: str | None = None


def _java_major(home: str) -> int:
    exe = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not exe.is_file():
        return 0
    try:
        proc = subprocess.run([str(exe), "-version"], capture_output=True, timeout=15)
    except (OSError, subprocess.TimeoutExpired):
        return 0
    match = re.search(r'version "(\d+)', proc.stderr.decode("utf-8", "replace"))
    return int(match.group(1)) if match else 0


def _jdk_cache_path() -> Path | None:
    try:
        return shared_state_dir() / "jdk.json"
    except (GateError, OSError):
        return None


def _read_jdk_cache() -> str:
    """磁盘缓存：候选列表未变时复用上次探测结果（探测本身约 3.7s，每次 hook 都要付）。"""
    path = _jdk_cache_path()
    if path is None or not path.is_file():
        return ""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return ""
    home = data.get("home") or ""
    if not home or not Path(home).is_dir():
        return ""
    if data.get("candidates") != _jdk_candidates():
        return ""
    return home


def _write_jdk_cache(home: str) -> None:
    path = _jdk_cache_path()
    if path is None:
        return
    try:
        write_json(path, {"schema": "jdk-cache/v1", "home": home,
                          "candidates": _jdk_candidates()})
    except OSError:
        pass


def _jdk_candidates() -> list[str]:
    """探测候选：环境 JAVA_HOME + 常见安装位。只做 glob，不启动 JVM。"""
    candidates: list[str] = []
    env_home = os.environ.get("JAVA_HOME", "")
    if env_home:
        candidates.append(env_home)
    for pattern in JDK_SCAN_GLOBS:
        try:
            candidates.extend(sorted(glob.glob(pattern)))
        except OSError:
            pass
    return candidates


def detect_jdk(env_home: str) -> str:
    """探测满足 JDK21+ 的 JAVA_HOME：环境变量优先，其次扫描常见安装目录。

    <p>门禁不继承"shell 恰好是什么 JDK"——本机默认 JAVA_HOME 可能是 17，
    surefire fork 出旧 JVM 去加载新编译的类必然崩（class file 65.0 vs 61.0）。
    环境里已有 ≥21 就直接用；否则在常见安装位里挑 major 最高的一个。
    找不到返回空串，由调用方决定如何报错。

    <p><b>性能</b>：全量扫描要对每个候选起一次 {@code java -version}，实测约 3.7s，
    是门禁里最贵的纯 Python 开销。因此按三级短路：
    环境 JAVA_HOME 达标（最常见）→ 直接返回，零扫描；
    上次探测结果仍有效（候选列表未变）→ 读磁盘缓存；
    都不行才真正扫描。
    """
    if env_home and _java_major(env_home) >= JDK_MIN_MAJOR:
        return env_home
    cached = _read_jdk_cache()
    if cached:
        return cached
    # 多个版本满足时选最低的（最贴近项目基线 release=21，避免引入超前版本差异）。
    best, best_major = "", JDK_MIN_MAJOR + 1000
    for cand in _jdk_candidates():
        major = _java_major(cand)
        if JDK_MIN_MAJOR <= major < best_major:
            best, best_major = cand, major
    if best:
        _write_jdk_cache(best)
    return best


def ensure_jdk() -> None:
    if not java_home():
        raise GateError(
            f"未找到 JDK{JDK_MIN_MAJOR}+：环境 JAVA_HOME 版本过低或缺失，"
            "常见安装目录也没有可用 JDK。请安装 JDK21+ 或将 JAVA_HOME 指向它。")


def java_home() -> str:
    global _java_home_cache
    if _java_home_cache is None:
        _java_home_cache = detect_jdk(os.environ.get("JAVA_HOME", ""))
    return _java_home_cache


def build_env() -> dict:
    """门禁子进程环境：注入探测到的 JDK（JAVA_HOME + PATH 前置 bin）。"""
    env = clean_env()
    home = java_home()
    if home:
        env["JAVA_HOME"] = home
        env["PATH"] = str(Path(home) / "bin") + os.pathsep + env.get("PATH", "")
    return env


def maven_binary() -> str:
    """优先 mvnd（daemon 常驻，重复构建快），没有则回退 mvn。"""
    for exe in ("mvnd", "mvn"):
        if shutil.which(exe):
            return exe
    return "mvn"


# 测试类并行度：pom 默认 2（小规格 CI 不过载），核数够时门禁提到 4。
# 实测 16 核全 reactor：1 fork 167s / 2 forks 126s / 4 forks 90s。
PARALLEL_FORK_COUNT = 4
PARALLEL_MIN_CORES = 8

# 提交时跳过的测试分组：真实进程 / 真实 git / Spring 上下文 / BCrypt 迭代这类
# 集成型用例打 @Tag("slow")。pre-commit 排除它们求快，pre-push 全量兜底与 CI 全跑。
# 实测 gateway：forkCount=2 时 40.3s→27.9s；forkCount=4 时只差 2.5s（并行已掩盖）。
SLOW_GROUP_FLAG = "-DexcludedGroups=slow"


def surefire_parallel_flag() -> str:
    """核数足够时给 surefire 提高 fork 数；否则返回空串用 pom 默认值。"""
    cores = os.cpu_count() or 1
    if cores >= PARALLEL_MIN_CORES:
        return f"-Dsurefire.forkCount={PARALLEL_FORK_COUNT}"
    return ""


def changed_modules(staged_files: list[str]) -> tuple[list[str], bool]:
    """本次提交涉及的 Maven 模块，以及是否含前端改动。

    <p>返回二元组而不是单个列表：前端（{@code platform/web}）与后端是两条完全独立的
    测试链，混在一起会让"只改了 .vue"也去跑后端全量。历史上就是这样漏的——
    {@code changed_modules} 把 web 过滤掉后返回空列表，而空列表的语义被当成
    "不知道影响什么 ⇒ 全跑"，于是**任何前端改动都要跑 775 个后端用例（实测 ~120s）**。

    @return (Maven 模块列表, 是否含前端改动)
    """
    mods: set[str] = set()
    web = False
    for path in staged_files:
        match = MODULE_RE.match(path)
        if not match:
            continue
        name = match.group(1)
        if name == "web":
            web = True
        else:
            mods.add(name)
    return sorted(mods), web


def incremental_test_command(staged_files: list[str]) -> str:
    """pre-commit 增量测试：受影响模块先编译，再只跑变更模块的测试。

    <p><b>为什么不再用 {@code -pl <mods> -amd test}</b>：{@code -amd} 会把全部下游模块
    的<b>测试</b>一起跑起来。改 agent-domain 这种被所有模块依赖的基座时，命令退化成
    全 reactor 测试（实测 166.9s），"增量"名存实亡。

    <p>拆成两步后语义不变、成本骤降：
    <ol>
      <li>{@code -pl <mods> -amd test-compile -DskipTests}：编译全部受影响模块（含下游），
          编译期破坏仍然当场发现；实测 4.2s。</li>
      <li>{@code -pl <mods> test}：只跑变更模块自己的测试；domain 实测 8.4s。</li>
    </ol>
    下游模块的测试交给 pre-push（{@code SESSION_ARCHIVE_FULL_TEST=1}）或 CI 全量兜底：
    提交时求快、推送/CI 求全，两层职责分开。

    <p>spec-checks（ArchUnit 架构 + 契约检查）是全量扫描，改动业务模块时排除它，
    移到 pre-push/CI；只有改 spec-checks 本身时才跑它验证检查规则。

    <p><b>返回空命令表示不需要跑 Maven</b>，由 {@code cmd_gate_pre_commit} 处理：
    <ul>
      <li>只有前端改动 → 跑前端自检（&lt;1s），不碰 Maven；</li>
      <li>只改了根 {@code platform/pom.xml} → 编译全 reactor（不跑测试，验证依赖可解析）；</li>
      <li>其它（工具脚本、CI 配置等）→ 什么都不跑。</li>
    </ul>
    判定"全跑"的唯一正当理由是根 pom 变了，而不是"我没认出这些文件"。
    """
    mvn = maven_binary()
    mods, web = changed_modules(staged_files)
    if not mods:
        if web:
            return ""  # 前端自检是 Python 函数不是 shell 命令，由 pre-commit 直接调用
        if ROOT_POM in staged_files:
            # 根 pom 改的是依赖/插件版本，全 reactor 都要重新解析：编译即可，
            # 测试交给 CI（与"提交求快、推送/CI 求全"的分层一致）。
            return (f"{mvn} -B -nsu -pl !spec-checks -DskipTests "
                    f"-Djacoco.skip=true test-compile")
        return ""
    if "spec-checks" in mods:
        return f"{mvn} -B -nsu -pl spec-checks test {surefire_parallel_flag()}".strip()
    pl = ",".join(mods)
    compile_step = (f"{mvn} -B -nsu -pl {pl},!spec-checks -amd "
                    f"-DskipTests -Djacoco.skip=true test-compile")
    test_step = (f"{mvn} -B -nsu -pl {pl} test {surefire_parallel_flag()} "
                 f"{SLOW_GROUP_FLAG}".strip())
    return f"{compile_step} && {test_step}"

# 说明（为什么不按模块并行）：不同模块各起一个 mvn 并行跑过一版，实测
# 「executor(12s) + tools(11s)」并行 28.2s、串行 25.3s —— Maven 启动与 reactor
# 校验占大头，两个小模块并行反而更慢；只有"两个都很重的模块"（gateway 28.8s +
# infra 19.4s）才可能省 ~15s。收益不稳，且并行会与 IDE 里同时跑的构建抢锁/抢
# target 目录，所以保持串行，改用预算校准（见 LAYER_BUDGET_SECONDS）。


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


def staged_files() -> list[str]:
    """读取"本次提交实际内容"清单：保留 hook 注入的 GIT_INDEX_FILE。

    <p>git commit --only（或并行会话共享主 index 时）git 会构建只含指定路径的
    临时 index 注入 hook；用主 index 读 staged 会把别的进程暂存的内容误当成
    本次提交，导致 --only 提交被无关文件拦截。仅读 staged 时保留
    GIT_INDEX_FILE，其余 git 子进程仍走 clean_env。
    """
    env = dict(os.environ)
    for key in GIT_ENV_KEYS:
        if key != "GIT_INDEX_FILE":
            env.pop(key, None)
    proc = subprocess.run(["git", "diff", "--cached", "--name-only"], cwd=ROOT,
                          capture_output=True, env=env)
    if proc.returncode != 0:
        raise GateError(
            "git diff --cached 失败，无法读取暂存清单："
            + proc.stderr.decode("utf-8", "replace").strip())
    return [line for line in proc.stdout.decode("utf-8", "replace").splitlines() if line]


def cmd_gate_pre_commit(args) -> int:
    staged = staged_files()
    code = [p for p in staged if not is_exempt(p)]
    if not code:
        out("仅文档/工具改动，跳过会话归档测试门禁")
        return 0
    if not active_session():
        return fail(
            "代码提交被拦截：没有进行中的会话归档。\n"
            "先执行：python tools/session-archive/gate.py start "
            "--category feat|bug|chore|deploy --slug <英文短名> --title <中文标题>\n"
            "（docs 类纯文档提交免此限制）"
        )
    _, web = changed_modules(staged)
    command = (os.environ.get("SESSION_ARCHIVE_TEST_CMD") or incremental_test_command(staged)).strip()
    if not command:
        # 没有任何 Maven 模块受影响：前端跑轻量自检，其余直接放行。
        # 关键是不能退化成"全 reactor 775 个用例"——纯前端或纯工具改动跑后端全量
        # 是纯浪费（实测每次 ~120s），而且它挡不住任何真实回归。
        if web and web_tests() != 0:
            return fail("前端自检未通过；禁止提交")
        note = "(前端自检)" if web else "(无需后端测试：本次改动不涉及 Maven 模块)"
        evidence = synth_evidence(note)
        write_json(evidence_path(), evidence)
        out(f"跳过 Maven 测试：{note}")
        return 0
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
    write_json(evidence_path(), evidence)
    if evidence["result"] != "PASSED":
        return fail(
            f"测试未通过，禁止提交（tests={evidence['testsRun']} "
            f"failures={evidence['failures']} errors={evidence['errors']}）"
        )
    out(
        f"测试通过：{evidence['testsRun']} 个用例，失败 {evidence['failures']}，"
        f"错误 {evidence['errors']}，跳过 {evidence['skipped']}"
    )
    # 记下"这些模块在当前闭包哈希下已跑绿"，供 pre-push 全量兜底按模块跳过。
    try:
        record_module_green(changed_modules(staged)[0])
    except (GateError, OSError):
        pass
    # 前后端混合改动：Maven 那半已经跑过，前端自检还差一次（<1s，不做"要不要跑"的
    # 判断反而更省心——漏跑的代价是一次坏提交，多跑的代价是一次亚秒级脚本）。
    if web and web_tests() != 0:
        return fail("前端自检未通过；禁止提交")
    return 0


def synth_evidence(note: str, tests_run: int = 0) -> dict:
    """构造"不跑 Maven 但结论有效"的测试证据。

    <p>{@code cmd_finish} 要求 feat/bug 会话有 PASSED 证据且 tree 与最后一次代码提交
    一致。纯前端或纯工具改动确实没有 JUnit 用例，但归档链仍需一份可核对的证据，
    否则要么被迫跑无意义的后端全量，要么归档失败。这里把 tree 记成当前索引树，
    语义是"这个树上的轻量门禁是绿的"。
    """
    return {
        "schema": "test-evidence/v1",
        "command": note,
        "ranAt": now_iso(),
        "result": "PASSED",
        "testsRun": tests_run,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "tree": write_tree(),
    }


def is_exempt(path: str) -> bool:
    return (
        path.startswith("docs/")
        or path.startswith("tools/session-archive/")
        or path.startswith("tools/ci/")          # CI/钩子侧静态检查脚本：与 .github/ 同类，
                                                 # 由 CI 自己执行，本地门禁不跑它
        or path.startswith(".githooks/")
        or path.startswith(".github/")          # CI 配置：本地不跑测试，由 CI 自己跑
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
    active = active_session()
    if not active:
        return fail("本 worktree 没有进行中的会话；先执行 start")
    category = active["category"]
    head = git("rev-parse", "HEAD")
    head_tree = git("rev-parse", "HEAD^{tree}")
    evidence = load_json(evidence_path()) if evidence_path().exists() else {
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
    clear_active()
    clear_evidence()
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
    # 提交存在性批量校验。blobHash 逐文件复核不再做：它在 finish 写入时已经
    # 验证过，且 git 对象库按内容寻址（SHA-1）自身不可篡改，这里逐文件
    # git show 只会拖慢校验（68 份归档即上千次子进程），防篡改交给 git 历史。
    existing: set[str] = set()
    if not skip_git:
        try:
            existing = existing_commits(
                [c["sha"] for c in commits if SHA_RE.fullmatch(c.get("sha", ""))])
        except GateError as exc:
            errors.append(f"{rel}: {exc}")
    for commit in commits:
        sha = commit.get("sha", "")
        if not SHA_RE.fullmatch(sha):
            errors.append(f"{rel}: commit sha 非法 {sha}")
            continue
        if skip_git:
            continue
        if sha not in existing:
            errors.append(f"{rel}: commit {sha} 不存在")

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


# ------------------------------------------------------- summary / plan 契约（§4.2、§4.6）

PLAN_SCHEMA = "session-plan/v1"
PLAN_STATUS = ("draft", "reviewing", "approved", "executing", "done", "dropped", "superseded")
PLAN_VERDICTS = ("PASS", "WARN", "FAIL", "N/A")
PLAN_SECTIONS = ("概述", "现状与证据", "缺口清单", "优化方案", "验收契约", "执行状态", "变更记录", "链接")
SLUG_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
PERIOD_RE = re.compile(r"^\d{4}-\d{2}$")
ISO_CN_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+08:00$")


def frontmatter(text: str) -> str | None:
    """frontmatter 文本块；不以 `---` 开头的文件返回 None（= 缺 frontmatter）。"""
    if not text.startswith("---\n"):
        return None
    parts = text.split("---", 2)
    return parts[1] if len(parts) >= 3 else None


def frontmatter_scalars(front: str) -> dict[str, str]:
    """只取顶层标量（`key: value`），跳过嵌套块、列表项与注释。"""
    scalars: dict[str, str] = {}
    for line in front.splitlines():
        if not line or line.startswith((" ", "\t", "#")) or ":" not in line:
            continue
        key, _, value = line.partition(":")
        value = value.strip()
        if len(value) >= 2 and value.startswith('"') and value.endswith('"'):
            value = value[1:-1]
        scalars[key.strip()] = value
    return scalars


def summary_refs(front: str) -> list[str]:
    """frontmatter 里 refs 列表（缩进的 `- id` 行）。"""
    refs: list[str] = []
    in_refs = False
    for line in front.splitlines():
        if line.startswith("refs:"):
            in_refs = True
            continue
        if in_refs and line.strip().startswith("- "):
            refs.append(line.strip()[2:].strip())
        elif in_refs and line and not line.startswith(" "):
            in_refs = False
    return refs


def validate_plan_doc(path: Path, root: Path, text: str, front: str) -> list[str]:
    """plan 类契约（spec §4.6）：字段、章节、双索引副本一致性。

    <p>plan 不进 process 归档，所以这里只校验"文档形状"：能定位（slug/period 与文件名对齐）、
    有状态语义（status/updatedAt 留痕）、有可执行口径（验收契约等 8 个章节）、
    双索引两处说得上是同一份东西（canonical 指回主文件且 slug/period 一致）。
    """
    rel = path.relative_to(root) if path.is_relative_to(root) else path
    meta = frontmatter_scalars(front)
    errors: list[str] = []

    if meta.get("schema") != PLAN_SCHEMA:
        errors.append(f"{rel}: schema 必须是 {PLAN_SCHEMA}")
    if meta.get("kind") != "plan":
        errors.append(f"{rel}: kind 必须是 plan")
    slug = meta.get("slug", "")
    period = meta.get("period", "")
    if not SLUG_RE.fullmatch(slug):
        errors.append(f"{rel}: slug 必须为 kebab-case（实际 {slug or '缺失'}）")
    elif slug not in path.stem:
        errors.append(f"{rel}: 文件名必须包含 slug（{slug}）")
    if not PERIOD_RE.fullmatch(period):
        errors.append(f"{rel}: period 必须为 YYYY-MM（实际 {period or '缺失'}）")
    elif period not in path.stem:
        errors.append(f"{rel}: 文件名必须包含 period（{period}）")
    created = meta.get("createdAt", "")
    updated = meta.get("updatedAt", "")
    for key, value in (("createdAt", created), ("updatedAt", updated)):
        if not ISO_CN_RE.fullmatch(value):
            errors.append(f"{rel}: {key} 必须为 +08:00 ISO 时间")
    if ISO_CN_RE.fullmatch(created) and ISO_CN_RE.fullmatch(updated) and updated < created:
        errors.append(f"{rel}: updatedAt 早于 createdAt（改了正文没留痕？）")
    status = meta.get("status", "")
    if status not in PLAN_STATUS:
        errors.append(f"{rel}: status 非法（{status or '缺失'}；可选 {'/'.join(PLAN_STATUS)}）")
    if status == "superseded" and meta.get("supersededBy", "null") in ("", "null"):
        errors.append(f"{rel}: status=superseded 必须填 supersededBy")
    verdict = meta.get("verdict", "")
    if verdict not in PLAN_VERDICTS:
        errors.append(f"{rel}: verdict 必须是 {'/'.join(PLAN_VERDICTS)}（实际 {verdict or '缺失'}）")
    if not meta.get("owner"):
        errors.append(f"{rel}: 缺少 owner")

    body = text.split("---", 2)[2] if len(text.split("---", 2)) >= 3 else text
    headings = {m.group(1).strip() for m in re.finditer(r"^##\s+(.+?)\s*$", body, re.M)}
    for section in PLAN_SECTIONS:
        if not any(h == section or h.startswith(section) for h in headings):
            errors.append(f"{rel}: 缺少必需章节「## {section}」")

    if path.parent.name == "plan":
        canonical = meta.get("canonical", "")
        if not canonical or canonical == "null":
            errors.append(f"{rel}: 方案轨副本必须填 canonical 指向时间维主文件")
        else:
            target = root / canonical
            if not target.is_file():
                errors.append(f"{rel}: canonical 指向的文件不存在（{canonical}）")
            else:
                twin = frontmatter(
                    target.read_text(encoding="utf-8", errors="replace")) or ""
                twin_meta = frontmatter_scalars(twin)
                if twin_meta.get("slug") != slug or twin_meta.get("period") != period:
                    errors.append(f"{rel}: canonical 主文件的 slug/period 与本文件不一致")
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
            front = frontmatter(text)
            if front is None:
                errors.append(f"{path.relative_to(root)}: 缺少 frontmatter")
                continue
            for ref in summary_refs(front):
                if ref not in ids:
                    errors.append(f"{path.relative_to(root)}: 引用不存在的归档 {ref}")
            meta = frontmatter_scalars(front)
            if meta.get("kind") == "plan" or path.parent == summary_root / "plan":
                errors.extend(validate_plan_doc(path, root, text, front))
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
    """start..end 之间未归档、且含非豁免改动的提交。

    <p>一次 git log 拿全部提交与文件清单，取代逐 sha 的 diff-tree 子进程
    （数百次子进程在 Windows 上要数秒，批量化后亚秒级）。merge 提交的
    组合 diff 默认为空，与逐 sha diff-tree 行为一致。
    """
    missing: list[str] = []
    seen: set[str] = set()
    sha = ""
    for line in git("log", "--format=%H", "--name-only", "-M",
                    f"{start}..{end}").splitlines():
        if SHA_RE.fullmatch(line) and len(line) == 40:
            sha = line
            continue
        if not line.strip() or not sha or sha in recorded or sha in seen:
            continue
        if not is_exempt(line):
            seen.add(sha)
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

    <p>两步走：先编译全 reactor（跳过测试），再单跑 spec-checks 的架构测试——
    避免 -am 连带执行上游业务测试。不用 -T 并行：并行 reactor 下传递依赖的
    classpath 解析存在竞争（agent-gateway 曾因此编译期找不到 domain 包）。
    结果按工作区 tree 指纹缓存，代码没变直接复用绿色结果。与业务测试共用
    TestLock，防止多轮 mvn 并发写同一 target 目录（编译产物被并发覆盖曾导致
    类版本错配假失败）。
    """
    tree = write_tree()
    cache = shared_state_dir() / "spec-checks-cache.json"
    if cache.is_file():
        try:
            data = json.loads(cache.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            data = {}
        if data.get("tree") == tree and data.get("result") == "PASSED":
            out(f"复用 spec-checks 绿色缓存（{data.get('ranAt')}，tree 一致）")
            return 0
    mvn = maven_binary()
    ensure_jdk()
    spec_begun = time.time()
    lock_dir = worktree_git_dir() / "session-archive"
    with TestLock(lock_dir / "test.lock", "规约检查"):
        # 只编译主代码：ArchUnit 读 target/classes，不需要任何模块的测试类。
        # -DskipTests 仍会编译测试源码（155 个测试文件，实测多 15.2s），
        # 必须用 -Dmaven.test.skip=true 才是真正跳过测试编译。
        compile_cmd = (f"{mvn} -B -nsu -pl spec-checks -am "
                       f"-Dmaven.test.skip=true -Djacoco.skip=true install")
        proc = subprocess.run(compile_cmd, cwd=ROOT / "platform", shell=True, env=build_env())
        if proc.returncode != 0:
            out(f"spec-checks 编译失败（{compile_cmd}）")
            return proc.returncode
        proc = subprocess.run(f"{mvn} -B -nsu -pl spec-checks test {surefire_parallel_flag()}".strip(),
                              cwd=ROOT / "platform", shell=True, env=build_env())
    record_metric("spec-checks", time.time() - spec_begun,
                  result="PASSED" if proc.returncode == 0 else "FAILED")
    if proc.returncode == 0:
        write_json(cache, {"schema": "spec-checks-cache/v1", "tree": tree,
                           "result": "PASSED", "ranAt": now_iso()})
    return proc.returncode



# ------------------------------------------------------------------ 模块级绿色证据
# pre-push 全量兜底（SESSION_ARCHIVE_FULL_TEST=1）默认要跑整个 reactor。
# 这里按「模块源码 + 它依赖的全部上游模块」算一个闭包哈希：闭包没变说明该模块的
# 编译输入与可观察行为都不变，可直接跳过。改动只落在一个叶模块时，推送能从全量 167s
# 降到只跑那一个模块。

MODULE_EVIDENCE_DIR = "module-evidence"


def module_dir_names() -> list[str]:
    """platform/ 下的业务模块目录名（排除前端 web 与构建产物）。"""
    platform = ROOT / "platform"
    if not platform.is_dir():
        return []
    names = []
    for child in sorted(platform.iterdir()):
        if not child.is_dir() or child.name in ("web", "target"):
            continue
        if (child / "pom.xml").is_file():
            names.append(child.name)
    return names


def module_source_hash(module: str) -> str:
    """模块自身输入指纹：pom.xml + src/**（含测试源码），用 stat 而非内容，够快也够准。"""
    digest = hashlib.sha256()
    base = ROOT / "platform" / module
    targets = [base / "pom.xml"] + sorted((base / "src").rglob("*")) if (base / "src").is_dir() else [base / "pom.xml"]
    for path in targets:
        if not path.is_file():
            continue
        try:
            stat = path.stat()
        except OSError:
            continue
        rel = path.relative_to(ROOT).as_posix()
        digest.update(f"{rel}:{stat.st_size}:{stat.st_mtime_ns}\n".encode("utf-8"))
    return digest.hexdigest()


_ARTIFACT_RE = re.compile(r"<artifactId>([a-z0-9-]+)</artifactId>")


def module_dependencies() -> dict[str, set[str]]:
    """模块依赖图：解析各模块 pom 里的 <artifactId>agent-…</artifactId>（含 test 依赖）。"""
    modules = module_dir_names()
    known = set(modules)
    deps: dict[str, set[str]] = {}
    for module in modules:
        text = (ROOT / "platform" / module / "pom.xml").read_text(encoding="utf-8", errors="replace")
        found = {m for m in _ARTIFACT_RE.findall(text) if m in known and m != module}
        deps[module] = found
    return deps


def module_closure_hashes() -> dict[str, str]:
    """每个模块的闭包哈希 = 自身指纹 + 全部上游模块闭包哈希（递归）。"""
    deps = module_dependencies()
    own = {m: module_source_hash(m) for m in deps}
    memo: dict[str, str] = {}
    visiting: set[str] = set()

    def closure(module: str) -> str:
        if module in memo:
            return memo[module]
        if module in visiting:  # 依赖环：按自身指纹断开，不递归
            return own.get(module, "")
        visiting.add(module)
        digest = hashlib.sha256()
        digest.update(own.get(module, "").encode("utf-8"))
        for dep in sorted(deps.get(module, ())):
            digest.update(b"|")
            digest.update(closure(dep).encode("utf-8"))
        visiting.discard(module)
        memo[module] = digest.hexdigest()
        return memo[module]

    for module in deps:
        closure(module)
    return memo


def module_evidence_path(module: str) -> Path:
    return shared_state_dir() / MODULE_EVIDENCE_DIR / f"{module}.json"


def record_module_green(modules: list[str]) -> None:
    """记录"这些模块在当前闭包哈希下已跑绿"。"""
    if not modules:
        return
    closures = module_closure_hashes()
    stamp = now_iso()
    for module in modules:
        closure = closures.get(module)
        if not closure:
            continue
        try:
            write_json(module_evidence_path(module),
                       {"schema": "module-evidence/v1", "module": module,
                        "closure": closure, "result": "PASSED", "ranAt": stamp})
        except OSError:
            pass


def green_modules(modules: list[str]) -> list[str]:
    """闭包哈希与上次跑绿一致（可跳过）的模块。"""
    closures = module_closure_hashes()
    green = []
    for module in modules:
        closure = closures.get(module)
        path = module_evidence_path(module)
        if not closure or not path.is_file():
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if data.get("result") == "PASSED" and data.get("closure") == closure:
            green.append(module)
    return green

def full_test_backstop() -> int:
    """pre-push 可选的全量测试兜底（{@code SESSION_ARCHIVE_FULL_TEST=1} 开启）。

    <p>定位：pre-commit 已把"受影响模块编译 + 变更模块测试"做完（秒级），
    但下游模块的<b>测试</b>没跑。CI 存在时这层由 CI 承担；CI 缺失或改动风险高时
    在这里补一次全 reactor 测试（实测 166.9s）。结果按 tree 缓存，同 tree 重推不重跑。
    """
    if os.environ.get("SESSION_ARCHIVE_FULL_TEST", "") not in ("1", "true", "yes"):
        return 0
    tree = write_tree()
    cache = shared_state_dir() / "full-test-cache.json"
    if cache.is_file():
        try:
            data = json.loads(cache.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            data = {}
        if data.get("tree") == tree and data.get("result") == "PASSED":
            out(f"复用全量测试绿色缓存（{data.get('ranAt')}，tree 一致）")
            return 0
    mvn = maven_binary()
    ensure_jdk()
    full_begun = time.time()
    modules = module_dir_names()
    green = green_modules(modules)
    pending = [m for m in modules if m not in green]
    if modules and not pending:
        out(f"全量测试兜底：{len(modules)} 个模块闭包哈希全部有绿色证据，跳过")
        write_json(cache, {"schema": "full-test-cache/v1", "tree": tree,
                           "result": "PASSED", "ranAt": now_iso()})
        return 0
    scope = ",".join(f"!{m}" for m in green) if green else ""
    command = f"{mvn} -B -nsu -pl {scope or '!spec-checks'} test {surefire_parallel_flag()}".strip()
    with TestLock(worktree_git_dir() / "session-archive" / "test.lock"):
        if green:
            out(f"全量测试兜底：跳过 {len(green)} 个闭包未变的模块（{', '.join(green)}），"
                f"只跑 {', '.join(pending)}")
        out(f"运行全量测试兜底：{command}")
        proc = subprocess.run(command, cwd=ROOT / "platform", shell=True, env=build_env())
    if proc.returncode != 0:
        return proc.returncode
    record_metric("full-test", time.time() - full_begun,
                  modules=",".join(pending), result="PASSED")
    if pending:
        record_module_green(pending)
    write_json(cache, {"schema": "full-test-cache/v1", "tree": tree,
                       "result": "PASSED", "ranAt": now_iso()})
    return 0




def web_tests() -> int:
    """前端零依赖自检（platform/web 下的三个 .test.ts 脚本）。

    <p>这些脚本不依赖 vitest：Node 22 的 `--experimental-strip-types` 直接跑 TS，
    `tools/ts-resolve.mjs` 负责把无扩展名的相对导入补成 .ts。整套跑完 < 1s，
    所以不做"只在改动 web 时才跑"的判断——省下的逻辑成本比它省的时间更贵。

    <p>没有 node 时跳过（与 PythonScripts 找不到 python 时的处理一致：不能因为
    环境缺工具就把门禁判红）。
    """
    node = shutil.which("node")
    if not node:
        out("未找到 node，跳过前端自检")
        return 0
    web = ROOT / "platform" / "web"
    if not web.is_dir():
        return 0
    scripts = [
        "src/state/agentEventReducer.test.ts",
        "src/components/chat/capabilityOrder.test.ts",
        "src/views/pipelineLayout.test.ts",
        # 工具入参表达口径：区分「工具无参」「还没到」「后端拿不到」
        "src/utils/toolArgs.test.ts",
    ]
    begun = time.time()
    for script in scripts:
        proc = subprocess.run(
            [node, "--experimental-strip-types", "--import", "./tools/ts-resolve.mjs", script],
            cwd=web, env=clean_env(), capture_output=True, text=True)
        if proc.returncode != 0:
            record_metric("web-test", time.time() - begun, result="FAILED")
            out(proc.stdout)
            out(proc.stderr)
            return fail(f"前端自检失败：{script}")
    record_metric("web-test", time.time() - begun, result="PASSED")
    out(f"前端自检通过（{len(scripts)} 个脚本）")
    return 0



def cmd_red_team(args) -> int:
    """门禁红队自审：故意注入违规，确认门禁真的会红。

    <p>解决的问题：规则/扫描器一旦写坏（正则失效、路径剪枝写错、作用域标错），
    门禁会静默变成"永远绿"，而没有人会去验一条通过的规则。这里每个季度（或每次
    改 spec-checks 之后）跑一次，用真实违规样本反证检查器有效。

    <p>做法：在临时目录造一个最小的 platform 树（{@code .../src/main/java/...}），
    用 {@code -Dspec.root} 指过去跑 {@code ForbiddenPatternScanTest}：
    <b>必须失败</b>。它要是通过，说明禁令扫描已经失效。
    """
    with tempfile.TemporaryDirectory(prefix="gate-red-team-") as tmp:
        root = Path(tmp)
        java_file = (root / "platform" / "demo" / "src" / "main" / "java"
                     / "top" / "rongx" / "agent" / "demo" / "RedTeamSample.java")
        java_file.parent.mkdir(parents=True, exist_ok=True)
        java_file.write_text(
            "package top.rongx.agent.demo;\n\n"
            "/** 红队样本：故意违反 R11（System.out）与 R04（new Thread）。 */\n"
            "class RedTeamSample {\n"
            "    void a() { System.out.println(\"red-team\"); }\n"
            "    void b() { new Thread(() -> { }).start(); }\n"
            "}\n",
            encoding="utf-8")
        xml_file = (root / "platform" / "demo" / "src" / "main" / "resources" / "mapper" / "RedTeam.xml")
        xml_file.parent.mkdir(parents=True, exist_ok=True)
        xml_file.write_text(
            "<mapper namespace=\"demo\">\n"
            "  <select id=\"x\">SELECT * FROM demo WHERE k = '${k}'</select>\n"
            "</mapper>\n",
            encoding="utf-8")

        mvn = maven_binary()
        ensure_jdk()
        command = (f"{mvn} -B -nsu -pl spec-checks -am -Dmaven.test.skip=true "
                   f"-Djacoco.skip=true install")
        proc = subprocess.run(command, cwd=ROOT / "platform", shell=True, env=build_env())
        if proc.returncode != 0:
            return fail("红队自审无法运行：spec-checks 编译失败")

        probe = (f"{mvn} -B -nsu -pl spec-checks test -Dtest=ForbiddenPatternScanTest "
                 f"-Dspec.root=\"{root}\" -Djacoco.skip=true")
        out(f"注入违规样本并执行：{probe}")
        # 输出重定向到临时文件再读回：比进程内管道 capture 稳（实测本机某些情况下
        # subprocess.run(capture_output=True) 会返回 stdout=None，拿不到断言依据）。
        log_file = root / "probe.log"
        with log_file.open("w", encoding="utf-8", errors="replace") as log:
            proc = subprocess.run(probe, cwd=ROOT / "platform", shell=True, env=build_env(),
                                  stdout=log, stderr=subprocess.STDOUT)
        text = log_file.read_text(encoding="utf-8", errors="replace")
        if proc.returncode == 0:
            out(text[-2000:])
            return fail("红队自审失败：注入的违规没有被检出，禁令扫描已失效")

        hits = {rid for rid in ("R04", "R08", "R11", "R03") if rid in text}
        if not hits:
            return fail("红队自审失败：扫描红了，但没报出预期规则编号（R03/R04/R08/R11）")
        out(f"红队自审通过：注入的违规被检出，规则编号 {sorted(hits)}")
        record_metric("red-team", 0.0, result="PASSED", rules=",".join(sorted(hits)))
        return 0


def cmd_gate_pre_push(args) -> int:
    session = active_session()
    if session:
        return fail(f"本 worktree 的会话未归档：{session.get('id')}。"
                    f"先执行 python tools/session-archive/gate.py finish")
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
    if web_tests() != 0:
        return fail("前端自检未通过（platform/web 下的自检脚本）；禁止推送")
    if spec_checks() != 0:
        return fail("spec-checks 规约检查未通过（架构 + 契约）；禁止推送")
    if full_test_backstop() != 0:
        return fail("全量测试兜底未通过（SESSION_ARCHIVE_FULL_TEST=1）；禁止推送")
    return 0



# ------------------------------------------------------------------ 门禁耗时度量
# 每层耗时落到公共 .git/session-archive/gate-metrics.json，供 `gate.py metrics`
# 出趋势与分位数。预算（spec §3.3.1）：pre-commit ≤ 60s、pre-push ≤ 180s、CI ≤ 600s。

GATE_METRICS = "gate-metrics.json"
METRICS_MAX_ENTRIES = 200
# pre-commit 预算按真实构成校准：单模块 13~47s；同时改两个重模块（gateway 28.8s +
# infra 19.4s + 编译）实测 70.3s，属于正常工作量而非退化。预算的作用是抓"又退化成
# 全量跑"（那会是 167s+），不是逼着把正常路径压进 60s。
LAYER_BUDGET_SECONDS = {"pre-commit": 90, "spec-checks": 180, "full-test": 600,
                        "web-test": 10}


def record_metric(layer: str, seconds: float, **extra) -> None:
    """追加一条耗时记录；失败只告警，绝不影响门禁结论。"""
    try:
        path = shared_state_dir() / GATE_METRICS
        entries: list[dict] = []
        if path.is_file():
            try:
                entries = json.loads(path.read_text(encoding="utf-8")).get("entries", [])
            except (OSError, json.JSONDecodeError):
                entries = []
        entry = {"layer": layer, "seconds": round(seconds, 1), "at": now_iso()}
        entry.update({k: v for k, v in extra.items() if v is not None})
        entries.append(entry)
        write_json(path, {"schema": "gate-metrics/v1",
                          "entries": entries[-METRICS_MAX_ENTRIES:]})
    except (GateError, OSError):
        pass


def read_metrics() -> list[dict]:
    try:
        path = shared_state_dir() / GATE_METRICS
        if not path.is_file():
            return []
        return json.loads(path.read_text(encoding="utf-8")).get("entries", [])
    except (GateError, OSError, json.JSONDecodeError):
        return []


def cmd_metrics(args) -> int:
    """打印各层耗时（最近 N 条 + 分位数 + 预算判定）。"""
    entries = read_metrics()
    if not entries:
        out("暂无耗时记录：跑一次 pre-commit / pre-push 后就有了")
        return 0
    limit = max(1, args.limit)
    layers = sorted({e.get("layer", "?") for e in entries})
    out(f"门禁耗时（最近 {limit} 条/层，共 {len(entries)} 条记录）")
    out("")
    out(f"{'层':<14}{'次数':>5}{'最近':>8}{'p50':>8}{'p95':>8}{'预算':>8}  判定")
    for layer in layers:
        rows = [e for e in entries if e.get("layer") == layer][-limit:]
        values = sorted(float(e.get("seconds", 0)) for e in rows)
        if not values:
            continue
        latest = float(rows[-1].get("seconds", 0))
        p50 = values[len(values) // 2]
        p95 = values[min(len(values) - 1, int(len(values) * 0.95))]
        budget = LAYER_BUDGET_SECONDS.get(layer)
        verdict = "-" if budget is None else ("OK" if p95 <= budget else "超预算")
        out(f"{layer:<14}{len(rows):>5}{latest:>7.1f}s{p50:>7.1f}s{p95:>7.1f}s"
            f"{(str(budget) + 's') if budget else '-':>8}  {verdict}")
    out("")
    out("最近 5 条明细：")
    for entry in entries[-5:]:
        extra = " ".join(f"{k}={v}" for k, v in entry.items()
                         if k not in ("layer", "seconds", "at"))
        out(f"  {entry.get('at', '?')}  {entry.get('layer'):<12}"
            f"{float(entry.get('seconds', 0)):>7.1f}s  {extra}")
    return 0


# ------------------------------------------------------------------ status / doctor

def cmd_status(args) -> int:
    """列出所有 worktree 进行中的会话（多会话并行时用）。"""
    rows = all_active_sessions()
    if not rows:
        out("没有进行中的会话（本 worktree 可直接 start）")
        return 0
    current = worktree_key()
    out(f"进行中的会话 {len(rows)} 个（当前 worktree key={current}）")
    for path, active in rows:
        marker = "*" if path.stem == current else " "
        out(f" {marker} {active.get('id')}")
        out(f"   worktree: {active.get('worktree') or '（旧版未记录）'}")
        out(f"   started : {active.get('startedAt')}  base={str(active.get('baseHead'))[:7]}")
    return 0


def cmd_doctor(args) -> int:
    """环境自检：门禁跑不起来时先看这个。"""
    out("== 会话归档门禁 doctor ==")
    out(f"python      : {sys.version.split()[0]} ({sys.executable})")
    out(f"git         : {git('--version')}")

    env_home = os.environ.get("JAVA_HOME", "")
    detected = java_home()
    out(f"JAVA_HOME   : {env_home or '（未设置）'}")
    out(f"JDK 实际使用: {detected or '（未找到 JDK21+）'}"
        f"{'' if not detected else '  major=' + str(_java_major(detected))}")
    if not detected:
        out("  ↑ 门禁会因此拒绝运行；装 JDK21+ 或把 JAVA_HOME 指过去")

    mvn = shutil.which(maven_binary())
    out(f"maven       : {mvn or '（未找到）'}{'  [mvnd]' if maven_binary() == 'mvnd' else ''}")
    out(f"surefire 并行: {surefire_parallel_flag() or '（pom 默认）'}  cores={os.cpu_count()}")

    hooks = ""
    try:
        hooks = git("config", "--get", "core.hooksPath", check=False).strip()
    except GateError:
        hooks = ""
    legacy_hook = (Path(git("rev-parse", "--git-path", "hooks").strip()) / "pre-commit")
    out(f"core.hooksPath: {hooks or '（未设置）'}")
    out(f"pre-commit 钩子: {'已安装 ' + str(legacy_hook) if legacy_hook.is_file() else '未安装'}")
    encoding_hook = legacy_hook.parent / "commit-msg"
    out(f"commit-msg 钩子: {'已安装 ' + str(encoding_hook) if encoding_hook.is_file() else '未安装（提交信息编码门禁不在位）'}")
    if hooks != ".githooks":
        out("  ↑ 建议执行 python tools/session-archive/gate.py install-hooks（钩子随仓库走）")

    out("")
    rows = all_active_sessions()
    out(f"进行中会话  : {len(rows)} 个" + ("" if not rows else "（" + ", ".join(
        a.get("id", "?") for _, a in rows) + "）"))
    out(f"本 worktree : {ROOT}  key={worktree_key()}")
    try:
        out(f"分支/HEAD   : {git('rev-parse', '--abbrev-ref', 'HEAD')} "
            f"{git('rev-parse', '--short', 'HEAD')}")
    except GateError:
        pass

    evidence_dir = shared_state_dir() / "evidence"
    module_dir = shared_state_dir() / MODULE_EVIDENCE_DIR
    out(f"证据缓存    : {len(list(evidence_dir.glob('*.json'))) if evidence_dir.is_dir() else 0} 份"
        f"（{evidence_dir}）")
    out(f"模块证据    : {len(list(module_dir.glob('*.json'))) if module_dir.is_dir() else 0} 个"
        f"（{module_dir}）")
    entries = read_metrics()
    if entries:
        out(f"耗时记录    : {len(entries)} 条（python tools/session-archive/gate.py metrics）")
    return 0


def cmd_install_hooks(args) -> int:
    """把仓库内的 .githooks 设为唯一钩子来源（取代复制到 .git/hooks）。"""
    source = ROOT / ".githooks"
    if not source.is_dir():
        return fail("仓库内缺少 .githooks 目录")
    git("config", "core.hooksPath", ".githooks")
    if os.name != "nt":
        for hook in source.iterdir():
            if hook.is_file():
                try:
                    hook.chmod(0o755)
                except OSError:
                    pass
    # 升级路径：旧版把钩子复制到 <git-dir>/hooks，留着会与 .githooks 双份生效/漂移。
    # 注意不能用 `git rev-parse --git-path hooks`：设置 core.hooksPath 之后它返回
    # .githooks 本身，会把仓库里的钩子脚本删掉（本实现踩过这个坑）。
    legacy_dir = Path(git("rev-parse", "--absolute-git-dir")) / "hooks"
    for name in ("pre-commit", "pre-push"):
        stale = legacy_dir / name
        if stale.is_file():
            try:
                stale.unlink()
                out(f"已移除旧副本：{stale}")
            except OSError:
                pass
    out("已设置 core.hooksPath=.githooks（钩子随仓库走，新克隆执行同一条命令即可）")
    out("自检：python tools/session-archive/gate.py doctor")
    return 0

# ------------------------------------------------------------------ self-test

def cmd_self_test(args) -> int:
    # 归档命名契约：给了任务编码就必须进目录名（「存档用任务编码作标题」的落地点）
    if folder_slug("patch-guard", "BUG-12") != "bug-12-patch-guard":
        return fail("self-test: 任务编码应进入归档目录 slug")
    if folder_slug("patch-guard", "  ") != "patch-guard":
        return fail("self-test: 未给任务编码时应退回纯 slug")
    # 增量测试的选择契约：纯前端/纯工具改动绝不能退化成后端全量。
    # 历史事故：changed_modules 把 web 过滤掉后返回空列表，空列表被当成
    # "不知道影响什么 ⇒ 跑全部业务模块"，于是每个前端提交都白跑 775 个后端用例。
    cases = [
        # (标签, staged 文件, 期望的 mods, 期望 web, 期望命令类别)
        ("纯前端", ["platform/web/src/x.vue"], [], True, "empty"),
        ("前端测试", ["platform/web/src/x.test.ts"], [], True, "empty"),
        ("工具脚本", ["tools/handbook/build.py"], [], False, "empty"),
        ("CI 配置", [".github/workflows/ci.yml"], [], False, "empty"),
        ("根 pom", [ROOT_POM], [], False, "compile"),
        ("单后端模块", ["platform/agent-gateway/a.java"], ["agent-gateway"], False, "test"),
        ("后端 + 前端", ["platform/agent-gateway/a.java", "platform/web/x.vue"],
         ["agent-gateway"], True, "test"),
    ]
    for label, staged, want_mods, want_web, want_kind in cases:
        mods, web = changed_modules(staged)
        if mods != want_mods or web != want_web:
            return fail(f"self-test: {label} 的 changed_modules 判定错误 "
                        f"(mods={mods} web={web})")
        cmd = incremental_test_command(staged)
        kind = ("empty" if not cmd
                else "compile" if "test-compile" in cmd and "&&" not in cmd
                else "test")
        if kind != want_kind:
            return fail(f"self-test: {label} 应选择 {want_kind} 类命令，实际 {kind}")
        if not cmd and not web and ROOT_POM not in staged:
            # 空命令的非前端场景必须是"确实没有 Maven 影响"，不能是漏判
            if any(MODULE_RE.match(p) for p in staged):
                return fail(f"self-test: {label} 被漏判成无需测试")
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

        # plan 类契约（spec §4.6）：双索引 + 字段 + 必需章节，缺一即红
        def plan_doc(canonical: str = "", drop: str = "", **over: str) -> str:
            fields = {
                "schema": PLAN_SCHEMA,
                "kind": "plan",
                "slug": "demo-plan",
                "period": "2026-11",
                "createdAt": "2026-11-18T20:00:00+08:00",
                "updatedAt": "2026-11-18T20:10:00+08:00",
                "status": "reviewing",
                "owner": "test",
                "verdict": "WARN",
                "supersedes": "null",
                "supersededBy": "null",
                "refs": "[]",
            }
            if canonical:
                fields["canonical"] = canonical
            fields.update(over)
            head = ["---", *[f"{key}: {value}" for key, value in fields.items()], "---", ""]
            sections = [f"## {name}\n内容\n" for name in PLAN_SECTIONS if name != drop]
            return "\n".join(head + ["# 示例方案", "", *sections])

        primary = root / "docs" / "summary" / "2026-11" / "2026-11-18_demo-plan.md"
        plan = root / "docs" / "summary" / "plan" / "2026-11_demo-plan.md"
        canonical = "docs/summary/2026-11/2026-11-18_demo-plan.md"
        primary.parent.mkdir(parents=True, exist_ok=True)
        plan.parent.mkdir(parents=True, exist_ok=True)
        primary.write_text(plan_doc(), encoding="utf-8")
        plan.write_text(plan_doc(canonical=canonical), encoding="utf-8")
        errors = validate_root(root, skip_git=True)
        if errors:
            return fail(f"self-test: 合法 plan 不应报错：{errors}")

        plan.write_text(plan_doc(canonical=canonical, status="wip"), encoding="utf-8")
        if not any("status 非法" in e for e in validate_root(root, skip_git=True)):
            return fail("self-test: plan status 非法应报错")

        plan.write_text(plan_doc(canonical=canonical, drop="验收契约"), encoding="utf-8")
        if not any("验收契约" in e for e in validate_root(root, skip_git=True)):
            return fail("self-test: plan 缺必需章节应报错")

        plan.write_text(plan_doc(canonical="docs/summary/2026-11/2026-11-18_nope.md"),
                        encoding="utf-8")
        if not any("canonical 指向的文件不存在" in e for e in validate_root(root, skip_git=True)):
            return fail("self-test: plan canonical 悬空应报错")

        plan.write_text(plan_doc(canonical=canonical, updatedAt="2026-11-18T19:00:00+08:00"),
                        encoding="utf-8")
        if not any("updatedAt 早于 createdAt" in e for e in validate_root(root, skip_git=True)):
            return fail("self-test: plan updatedAt 倒退应报错")

        plan.unlink()
        primary.unlink()
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
    # 任务编码（BUG-12 / FEAT-3）：给了就进归档目录名与标题，让存档与任务页对得上同一个编号
    p.add_argument("--task-code", default="")
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
    sub.add_parser("status").set_defaults(func=cmd_status)
    sub.add_parser("doctor").set_defaults(func=cmd_doctor)
    sub.add_parser("install-hooks").set_defaults(func=cmd_install_hooks)
    sub.add_parser("red-team").set_defaults(func=cmd_red_team)
    p = sub.add_parser("metrics")
    p.add_argument("--limit", type=int, default=20, help="每层取最近多少条（默认 20）")
    p.set_defaults(func=cmd_metrics)

    args = parser.parse_args()
    try:
        return args.func(args)
    except GateError as exc:
        return fail(str(exc))


if __name__ == "__main__":
    sys.exit(main())
