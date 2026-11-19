"""把「主干 CI 已变绿」的最终验收补进归档，并重算链。

这是最关键的证据：不是本地重放通过，而是远端主干 CI 真的绿了。
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

【最终验收 2026-11-19 20:41】
PR #23 五项检查全绿（backend / contract / docs / frontend / security），已合并进 0.1.0-snapshot；
合并提交 06bb27d 触发的主干 CI 运行 44525721043 结论 success。
对照：同一 job 在前两次主干推送（PR #21 的 44521071492、PR #22 的 44523852465）均为 failure。
即「失败 → 失败 → 成功」的转折点正是本修复。
"""


def canonical(obj: dict) -> str:
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def manifest_self(manifest: dict) -> str:
    body = copy.deepcopy(manifest)
    body.get("chain", {}).pop("self", None)
    return hashlib.sha256(canonical(body).encode("utf-8")).hexdigest()


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if "【最终验收" in (manifest.get("solution") or ""):
        print("已补充过，跳过")
        return 0
    manifest["solution"] = (manifest.get("solution") or "").rstrip() + EXTRA
    manifest.setdefault("chain", {})["self"] = manifest_self(manifest)
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    print("已补最终验收并重算 chain.self")
    return 0


if __name__ == "__main__":
    sys.exit(main())
