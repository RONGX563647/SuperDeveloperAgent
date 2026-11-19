"""取一次 GitHub Actions 失败日志里的关键行（避免在 PowerShell 里解析日志）。"""

import re
import subprocess
import sys

run_id = sys.argv[1] if len(sys.argv) > 1 else ""
if not run_id:
    raise SystemExit("用法：fetch-ci-failure.py <runId>")

proc = subprocess.run(["gh", "run", "view", run_id, "--log-failed"],
                      capture_output=True, text=True, encoding="utf-8", errors="replace")
text = proc.stdout + proc.stderr

# 只保留有信息量的行：错误、失败断言、缺失文件、退出码
PATTERNS = re.compile(
    r"(error|Error|ERROR|✗|FAIL|failed|not found|missing|Cannot find|Type error|"
    r"error TS|exit code|AssertionFailed)", re.IGNORECASE)

seen = 0
for line in text.splitlines():
    # 去掉 "job\tstep\t2026-...Z " 前缀
    match = re.search(r"Z\s(.*)$", line)
    body = match.group(1) if match else line
    if not PATTERNS.search(body):
        continue
    print(body.strip()[:220])
    seen += 1
    if seen >= 40:
        break

if seen == 0:
    print("（没有匹配到关键行，下面是原始尾部）")
    for line in text.splitlines()[-20:]:
        print(line[:220])
