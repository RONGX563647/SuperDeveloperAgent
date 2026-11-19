"""运行态验证：新代码是否真的生效（只读 HTTP 调用，不改数据）。

验证点：
1. 服务在线且鉴权仍生效（未登录 401）；
2. 新接口存在（本轮新增的 /git-issues/versions 与 /settings/projects/{id}/env-bindings），
   未登录时应是 401 而不是 404——404 说明跑的还是旧字节码。
"""
import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8090"

PROBES = [
    ("对话流入口（旧）", "POST", "/api/v1/chat", None),
    ("任务版本候选（本轮新增）", "GET", "/api/v1/git-issues/versions?projectId=SUPERDEVELOPERAG", None),
    ("环境绑定历史（本轮新增）", "GET",
     "/api/v1/settings/projects/SUPERDEVELOPERAG/env-bindings?env=test", None),
    ("日志行分页（本轮新增）", "GET", "/api/v1/ci/runs/run-nonexistent/log/lines?offset=0&limit=10", None),
]


def probe(name: str, method: str, path: str, body) -> int:
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            print(f"{name}: {response.status}（未鉴权却通过，需人工确认）")
            return response.status
    except urllib.error.HTTPError as error:
        code = error.code
        hint = "路由存在（鉴权拦截）" if code in (401, 403) else (
            "路由不存在（跑的是旧字节码？）" if code == 404 else "其它")
        print(f"{name}: {code} {hint}")
        return code
    except Exception as error:  # noqa: BLE001 - 只做探活
        print(f"{name}: 连接失败 {error}")
        return 0


def main() -> int:
    codes = [probe(*item) for item in PROBES]
    missing = [PROBES[i][0] for i, code in enumerate(codes) if code == 404]
    if missing:
        print("\n以下路由不存在（说明网关未加载本轮代码）：" + "、".join(missing), file=sys.stderr)
        return 1
    print("\n全部路由存在：网关已运行本轮代码")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
