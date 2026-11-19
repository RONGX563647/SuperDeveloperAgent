"""只读检查：真实仓库上「进程后于产物」这条判据是否会命中。

对应 BuildFreshnessCheck.inspect(module, classes, processStartedAt) 的第二条判据。
用运行中网关进程的启动时间做样本（进程 63064），不改任何文件。
"""
import os
import sys
from datetime import datetime
from pathlib import Path

CLASSES = Path(r"E:\code\SuperDeveloperAgent\platform\agent-gateway\target\classes")


def newest_class_time(root: Path):
    newest = None
    for path in root.rglob("*.class"):
        try:
            stamp = os.path.getmtime(path)
        except OSError:
            continue
        if newest is None or stamp > newest:
            newest = stamp
    return newest


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("用法：freshness-probe.py <进程启动 ISO 时间>", file=sys.stderr)
        return 2
    started = datetime.fromisoformat(argv[1]).timestamp()
    if not CLASSES.is_dir():
        print("没有编译产物目录")
        return 1
    newest = newest_class_time(CLASSES)
    if newest is None:
        print("产物目录里没有 .class")
        return 1
    print(f"最新产物：{datetime.fromtimestamp(newest):%Y-%m-%d %H:%M:%S}")
    print(f"进程启动：{datetime.fromtimestamp(started):%Y-%m-%d %H:%M:%S}")
    stale = newest > started
    print("结论：" + ("过期（产物在进程启动后又被编译 → 需重启）" if stale
                    else "进程不比产物旧"))
    return 0 if stale else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
