"""把 cherry-pick 后的新 sha 写进已有归档（同一份改动、换了落点）。

背景：把归档一起 cherry-pick 到主干基线后，代码改动会在新基线上生成新 sha，
而归档的 commits 仍记旧 sha。CI 的归档校验会去查 `{sha}^{commit}`，
旧 sha 在新基线上不可达，于是报「commit xxx 不存在」，docs job 直接变红。

用法：python tools/session-archive/append-cherry-pick.py <manifest 路径> <旧 sha> <新 sha> [说明]

三个踩过的坑：
  1. sha 必须写 40 位全长：gate 用 {sha}^{commit} 批量查询，
     短 sha 在某些 git 版本/配置下会报「不存在」；
  2. 改完必须重算 chain.self，否则该分类的链会断，validate 报 chain.prev 断链；
  3. 旧 sha 可能已经不可达（历史重写 / rebase 后旧提交不再是任何引用的祖先），
     此时不能 rev-parse 它，但 manifest 里记的就是这一串字符，按原样匹配即可。
"""

from __future__ import annotations

import copy
import hashlib
import json
import subprocess
import sys
from pathlib import Path


def canonical(obj: dict) -> str:
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def manifest_self(manifest: dict) -> str:
    body = copy.deepcopy(manifest)
    body.get("chain", {}).pop("self", None)
    return hashlib.sha256(canonical(body).encode("utf-8")).hexdigest()


def full_sha(rev: str) -> str:
    result = subprocess.run(["git", "rev-parse", rev], capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"git 里找不到该提交：{rev}")
    return result.stdout.strip()


def recorded_sha(rev: str) -> str:
    """旧 sha 在历史重写后可能已不可达；此时按 manifest 里记的字符串原样使用。"""
    result = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"{rev}^{{commit}}"],
        capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else rev.strip().lower()


def main(argv: list[str]) -> int:
    if len(argv) < 4:
        print("用法：append-cherry-pick.py <manifest 路径> <旧 sha> <新 sha> [说明]",
              file=sys.stderr)
        return 2
    path = Path(argv[1])
    if not path.is_file():
        print(f"归档不存在：{path}", file=sys.stderr)
        return 1
    old = recorded_sha(argv[2])
    new = full_sha(argv[3])
    note = argv[4] if len(argv) > 4 else "（cherry-pick 到主干基线）"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    commits = manifest.get("commits") or []

    replaced = 0
    for commit in commits:
        if str(commit.get("sha")) == old:
            commit["sha"] = new
            subject = (commit.get("subject") or "").lstrip("\ufeff")
            if "cherry-pick" not in subject and note not in subject:
                commit["subject"] = subject + note
            replaced += 1
    if not replaced:
        print("该归档里没有这个旧 sha，无需修改")
        return 0

    manifest["commits"] = commits
    manifest.setdefault("chain", {})["self"] = manifest_self(manifest)
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"已把 {old[:12]} → {new[:12]} 并重算 chain.self（{manifest.get('id')}）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
