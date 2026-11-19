"""给 CI 编码门禁的归档补一条更强的证据（只改现有归档的 problem/solution 追加）。

新证据：主干上损坏提交已达 13 条，全部是平台合并提交、非平台损坏 0 条；
并且修复后用 CI 原位范围重放 PR #22 那次失败（1d03365..58c383a）已 exit 0。
"""

import copy
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = (ROOT / "docs" / "process" / "chore"
            / "2026-11-19_2026_ci-platform-merge-encoding" / "manifest.json")

EXTRA = """

【复核补充 2026-11-19 20:30】
主干损坏提交已达 13 条，其中平台合并（subject 结尾 (PR #n)）13 条、非平台损坏 0 条——
规律稳定成立，不是巧合。
另用 CI 原位范围重放 PR #22 那次失败（--base 1d03365 --head 58c383a）：
修复后 exit 0，58c383a 被正确识别为平台合并提交并给出提示，不再阻断。
"""


def canonical(obj: dict) -> str:
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def manifest_self(manifest: dict) -> str:
    body = copy.deepcopy(manifest)
    body.get("chain", {}).pop("self", None)
    return hashlib.sha256(canonical(body).encode("utf-8")).hexdigest()


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if EXTRA.strip() in (manifest.get("solution") or ""):
        print("已补充过，跳过")
        return 0
    manifest["solution"] = (manifest.get("solution") or "").rstrip() + EXTRA
    manifest.setdefault("chain", {})["self"] = manifest_self(manifest)
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    print("已补证据并重算 chain.self")
    return 0


if __name__ == "__main__":
    sys.exit(main())
