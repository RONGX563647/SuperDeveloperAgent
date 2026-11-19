"""提交信息编码门禁的自测：确认「平台合并提交放行」没有把闸门整体放松。

三条契约：
1. 本地产生的损坏提交（中文被替换成 ??、且不是平台合并）仍然阻断；
2. 平台合并提交（subject 带 `(PR #n)`）只提示不阻断；
3. 正常中文提交、正常英文问句、git status 片段都不误判。

运行：python tools/ci/check-commit-messages.py 的同目录下
    python tools/ci/test_check_commit_messages.py
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

MODULE = Path(__file__).with_name("check-commit-messages.py")


def load():
    spec = importlib.util.spec_from_file_location("check_commit_messages", MODULE)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    # 必须先注册进 sys.modules：dataclass 装饰器解析注解时会回查
    # sys.modules[cls.__module__]，模块名没登记就抛 AttributeError: 'NoneType'
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


mod = load()
passed = 0
failed = 0


def check(name: str, condition: bool, detail: str = "") -> None:
    global passed, failed
    if condition:
        passed += 1
        print(f"  OK  {name}")
    else:
        failed += 1
        print(f"  FAIL {name}" + (f" — {detail}" if detail else ""))


def commit(subject: str) -> "mod.Commit":
    return mod.Commit(sha="a" * 40, subject=subject, body=subject)


# 1. 本地损坏（无 PR 标记）必须仍被判为错误
local_bad = commit("feat(deploy): ??? jar ???????")
findings = mod.inspect(local_bad.body)
check("本地损坏产生错误", len(findings.errors) == 1, str(findings.errors))
check("本地损坏不是平台合并", mod.is_platform_merge(local_bad) is False)

# 2. 平台合并提交：仍被 inspect 判为错误，但走放行分支
platform_bad = commit("fix(deploy): ??? jar ???????(PR #21)")
check("平台合并也判为损坏（不掩盖事实）", len(mod.inspect(platform_bad.body).errors) == 1)
check("平台合并被识别", mod.is_platform_merge(platform_bad) is True)

# 3. 正常内容不误判
check("正常中文无错误", mod.inspect("feat: 新增只读变更状态查询工具").errors == [])
check("英文问句无错误", mod.inspect("fix: is this ok?").errors == [])
check("git status 片段跳过", mod.inspect("??  src/main/App.java").errors == [])
check("行内仍有中文的双问号只提示",
      len(mod.inspect("fix: 你确定??").errors) == 0
      and len(mod.inspect("fix: 你确定??").warnings) == 1)
check("U+FFFD 判为错误", len(mod.inspect("feat: \ufffd\ufffd").errors) >= 1)

# 4. PR 标记必须真的在结尾，避免把正文里提到 PR 的正常提交放过
check("正文中提及 PR 不算平台合并",
      mod.is_platform_merge(commit("fix: 修复 PR #21 引入的问题")) is False)
check("结尾 PR 标记算平台合并",
      mod.is_platform_merge(commit("fix: 修复(PR #21)")) is True)
check("结尾 PR 标记带空格也算",
      mod.is_platform_merge(commit("fix: 修复(PR #21)  ")) is True)

print(f"\n{'OK' if failed == 0 else 'FAIL'} {passed} passed, {failed} failed")
sys.exit(0 if failed == 0 else 1)
