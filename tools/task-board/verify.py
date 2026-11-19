#!/usr/bin/env python3
"""任务看板一致性校验：编码唯一、前缀与分类一致、同类编码连续。

为什么单独一个脚本：任务编码是「口头引用一个任务」的唯一手段，
一旦出现重复（历史上真的出现过两条 BUG-1）或与分类不一致，
复核时就会指错任务。这些约束用一句 SQL 说不清，做成可反复执行的检查。

用法：python tools/task-board/verify.py [db 路径]
退出码：0 = 全部通过；1 = 至少一项不通过（输出会指出具体编码）。
"""
from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

DEFAULT_DB = Path("data/workspaces/_platform/platform.db")


def main(argv: list[str]) -> int:
    db = Path(argv[1]) if len(argv) > 1 else DEFAULT_DB
    if not db.is_file():
        print(f"库文件不存在：{db}", file=sys.stderr)
        return 1

    con = sqlite3.connect(f"file:{db.as_posix()}?mode=ro", uri=True)
    cur = con.cursor()
    problems: list[str] = []

    duplicates = list(cur.execute(
        "select user_id, project_id, code, count(*) from git_issues "
        "group by 1,2,3 having count(*) > 1"))
    print(f"重复编码：{duplicates or '无'}")
    if duplicates:
        problems.append("存在重复编码")

    mismatched = list(cur.execute(
        "select id, code, kind from git_issues where "
        "(kind = 'BUG' and code not like 'BUG-%') or "
        "(kind = 'FEAT' and code not like 'FEAT-%')"))
    print(f"分类与编码前缀不一致：{mismatched or '无'}")
    if mismatched:
        problems.append("编码前缀与分类不一致")

    for kind in ("BUG", "FEAT"):
        codes = [row[0] for row in cur.execute(
            "select code from git_issues where kind = ? order by code", (kind,))]
        numbers = sorted(int(code.split("-")[1]) for code in codes)
        expected = list(range(1, len(numbers) + 1))
        intact = numbers == expected
        print(f"{kind} 编码连续性：{'连续' if intact else '有空洞 ' + str(numbers)}")
        if not intact:
            problems.append(f"{kind} 编码不连续")

    for kind in ("BUG", "FEAT"):
        for priority in ("P0", "P1", "P2"):
            codes = [row[0] for row in cur.execute(
                "select code from git_issues where kind = ? and priority = ? order by code",
                (kind, priority))]
            print(f"{kind} {priority}: {len(codes):>2}  {','.join(codes)}")

    if problems:
        print("\n不通过：" + "；".join(problems), file=sys.stderr)
        return 1
    print("\n全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
