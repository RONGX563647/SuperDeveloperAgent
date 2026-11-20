"""诊断归档断链：打印某分类最后几份归档的 prev/self，并直接判定是否断链。

用途：``gate validate`` 只会说「chain.prev 断链」，不告诉你是哪两份对不上。
这个脚本按 createdAt 排序后逐份打印指针，并把「后一份的 prev 是否等于前一份的 self」
直接算出来——排查 cherry-pick、并行会话合并后的断链时一步到位。

用法：python tools/session-archive/diag-chain.py [分类，默认 bug] [显示份数，默认 4]
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main(argv: list[str]) -> int:
    category = argv[1] if len(argv) > 1 else "bug"
    tail = int(argv[2]) if len(argv) > 2 else 4
    root = ROOT / "docs" / "process" / category
    if not root.is_dir():
        print(f"分类目录不存在：{root}", file=sys.stderr)
        return 1

    rows = []
    for path in sorted(root.glob("*/manifest.json")):
        manifest = json.loads(path.read_text(encoding="utf-8"))
        rows.append((manifest.get("createdAt", ""), path.parent.name, manifest))
    rows.sort()
    print(f"{category} 共 {len(rows)} 份")
    if not rows:
        return 0

    broken: list[str] = []
    previous_self = "GENESIS"
    for created, name, manifest in rows:
        chain = manifest.get("chain") or {}
        prev = str(chain.get("prev", ""))
        self_hash = str(chain.get("self", ""))
        if prev != previous_self:
            broken.append(f"{name}：prev={prev[:16]} 但前一份 self={previous_self[:16]}")
        previous_self = self_hash

    for created, name, manifest in rows[-tail:]:
        chain = manifest.get("chain") or {}
        print(f"{created[:16]}  {name}")
        print(f"    prev={str(chain.get('prev'))[:16]}  self={str(chain.get('self'))[:16]}")

    if broken:
        print(f"\n断链 {len(broken)} 处：")
        for line in broken:
            print("  - " + line)
        print("\n修复：python -c \"import sys;sys.path.insert(0,'tools/session-archive');"
              f"import gate;print(gate.reseal_chain('{category}', gate.PROCESS))\"")
        return 1
    print("\n链连续")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
