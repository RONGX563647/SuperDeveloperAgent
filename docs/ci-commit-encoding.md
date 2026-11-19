# CI 提交信息编码治理

两件事写在同一个文件里，因为它们是由同一次"远端 CI 告警清理"暴露出来的：
提交信息里的中文变成 `?????`（乱码），以及每个 job 顶部都刷一遍 Node 20 弃用警告。

---

## 一、提交信息里的中文变成 "?"

### 症状

GitHub 提交列表和 Actions 标题长这样：

```
chore(release): main ???? 0.1.0-snapshot(PR #18)
test(db): ? PostgreSQL ????????(PR #16)
chore(release): snapshot ?? main(PR #17)
```

### 根因：已经被替换成 0x3F，不是显示问题

```
$ git cat-file commit d173a04 | sed -n '5,6p'
chore(release): main ???? 0.1.0-snapshot
$ git log -1 --format=%s d173a04 | xxd | head -2
00000010   6d 61 69 6e 20 3f 3f 3f 3f 20 30 2e 31 2e 30 2d  main ???? 0.1.0-
```

对象里躺着的就是问号字节。中文在"拼字符串 → 传参数 → 写文件"的某一环被当成
ASCII/GBK 处理，无法表示的字符被编码器替换为 `?`，**替换不可逆**：git 里没有留下
任何原文线索，只能改写历史才能恢复。

常见触发点（本仓库的自动化提交基本都踩在这里）：

| 环节 | 问题 |
| --- | --- |
| PowerShell 5.1 | 子进程参数按控制台 ANSI 代码页（936/437）编码，`[Console]::OutputEncoding` 未改时中文先变 `?` |
| `cmd /c` / `.bat` | 默认代码页 936，`chcp 65001` 之前不接受 UTF-8 |
| Node `child_process.execSync` | 返回 Buffer 未按 utf-8 解码，或 `env` 里 `LANG` 为空 |
| Python | `PYTHONIOENCODING` 未设、`locale` 是 C → `sys.stdout.encoding` 退化为 ascii |
| Git | `i18n.commitEncoding` 未声明，日志按本地代码页解码显示成 mojibake |

### 三层闸门（本次落地）

1. **本地**：`.githooks/commit-msg` → `tools/ci/check-commit-messages.py --file`
   提交信息非法（含 U+FFFD 或连续 `??`）时直接拒绝落库，并打印修复命令。
   安装：`powershell -ExecutionPolicy Bypass -File tools/session-archive/install-hooks.ps1`。
2. **远端**：`.github/workflows/ci.yml` 的 `docs` job 里
   `提交信息编码（禁止 ? / U+FFFD 损坏）` 这一步，按 `base..head` 复查 PR / push
   引入的提交。网页提交、API 提交、Agent 脚本绕得过本地钩子，但绕不过这一层。
3. **生成侧**：写提交信息的脚本/命令统一走 UTF-8。PowerShell 里最少要这三行：

   ```powershell
   [Console]::OutputEncoding = [Text.UTF8Encoding]::new()
   $OutputEncoding = [Text.UTF8Encoding]::new()
   git config --global i18n.commitEncoding utf-8
   ```

   更稳的做法是把信息写进文件再用 `git commit -F`，彻底避开命令行参数的代码页转换：

   ```powershell
   $msg = "chore(release): 同步 main 到 0.1.0-snapshot"
   [IO.File]::WriteAllText("$PWD\.git\COMMIT_MSG_UTF8.txt", $msg, [Text.UTF8Encoding]::new($false))
   git commit -F .git/COMMIT_MSG_UTF8.txt
   ```

### 自查

```bash
# 最近 30 条（只报告，不阻断）
python tools/ci/check-commit-messages.py --history 30

# 某个区间
python tools/ci/check-commit-messages.py --base origin/main --head HEAD
```

### 历史损坏（可选，需协调）

已经落到远端主干上的 9 条脏提交（8 条在 `main`，另 1 条 `d173a04` 只在
`0.1.0-snapshot`）只能通过改写历史恢复，代价是 force-push、所有未合并 PR 需要
rebase、已推送的 commit sha 全部变化。确认要动时：

```bash
pip install git-filter-repo
# 先备份：git clone --mirror . ../backup.git
git filter-repo --message-callback '
import re
def cb(m):
    m = m.decode("utf-8")
    m = re.sub(r"\?{2,}", "（原文丢失）", m)
    return m.encode("utf-8")
'
git push --force-with-lease origin main 0.1.0-snapshot
```

`?` 是信息丢失，不是可解码的乱码，脚本无法还原中文原文，只能把问号换成可读占位符；
要恢复原意得按 PR 内容重写 subject。

---

## 二、Node 20 弃用与 Actions 升级

GitHub 已把 runner 默认运行时切到 Node 24（2026-06-02），并在 2026-09-16 从托管
runner 移除 Node 20。旧版本 action 会被强制在 Node 24 上跑并打印弃用警告。
本次把所有 action 钉到 node24 运行时的精确版本：

| 用途 | 旧 | 新 | 说明 |
| --- | --- | --- | --- |
| 检出 | `actions/checkout@v4` | `actions/checkout@v7.0.1` | v5 起 `runs.using: node24`；v5/v6/v7 都加了 `allow-unsafe-pr-checkout`，默认值是安全的，本仓库不用 `pull_request_target`，不受影响 |
| Java | `actions/setup-java@v4` | `actions/setup-java@v6.3.0` | v6 把 `jdkFile` 改名 `jdk-file`（未用）、移除 legacy Adopt 发行版（用 temurin）；`cache: maven` 不变 |
| Python | `actions/setup-python@v5` | `actions/setup-python@v7.1.0` | v7 移除 `pip-install` 输入（未用）与 EOL 版本；`python-version` 不变 |
| Node | `actions/setup-node@v4` | `actions/setup-node@v7.1.0` | v7 迁 ESM 并移除 dummy `NODE_AUTH_TOKEN`（不用 registry-url，无影响） |
| pnpm | `pnpm/action-setup@v4` | `pnpm/action-setup@v6.1.1` | 输入未变：`version` 仍生效 |
| 密钥扫描 | `gitleaks/gitleaks-action@v2` | `gitleaks/gitleaks-action@v3.0.0` | 官方声明仅迁移运行时，inputs / outputs / 行为不变 |
| 产物上传 | `actions/upload-artifact@v4` | `actions/upload-artifact@v7.0.2` | v5 起 node24；v6 起要求 runner ≥ 2.327.1（托管 runner 满足） |
| 依赖漏洞 | `google/osv-scanner-action@v1.9.0` | 不变 | composite action，无 Node 运行时，不在弃用名单 |

配套约束：

- **self-hosted runner** 必须 ≥ `v2.327.1`，否则 node24 action 起不来。本仓库全部用
  `ubuntu-latest`，无需处理。
- 升级后不要再用浮动大版本 tag（`@v4` 这种），移动 tag 会让"同一 commit 跑出不同
  结果"。精确版本号 = 可复现。
- 弃用警告清空后，CI 顶部应该只剩 `ci` 的 job 名称，不再有 "Node.js 20 has been
  deprecated" 与 "Setup-Java v4 has been deprecated" 两段提示。
