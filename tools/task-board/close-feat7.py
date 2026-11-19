"""关闭 FEAT-7（会话）并给出证据。

原文：
  1. 环境面板需要设置为默认折叠；
  2. 会话需要可以上传图片。
"""

import sqlite3
import sys
import time

DB = r"data/workspaces/_platform/platform.db"
TASK = "task-fcfbe08a03"

NOTE = """## 完成记录

### 1. 环境面板默认折叠（已完成）

`EnvironmentStatusBar` 本来就能折叠并记 `localStorage`，但默认值写死在代码里。
改为 `defaultOpen` 参数（默认收起），且只对没点开过的设备生效——
用户手动展开过就按他的选择，否则每次进页面都被强制收起反而更烦。

### 2. 会话支持上传图片（已完成）

输入框的附件按钮原来是 `disabled`（title=`附件功能待接入`）。现在三种入口都能用：
- 点按钮选图；
- 直接 Ctrl+V 粘贴（截图后最高频的用法）；
- 拖入图片。

上传复用已有的 `uploadIssueImage`，成功后以 Markdown 图片语法**插到光标处**
（用户常常写了一半再补图）。正文本来就是 Markdown，模型能直接看到地址并据此回答，
不需要另立一套图片消息协议。

逻辑抽到 `utils/chatImage.ts`：`imageMarkdown` 处理方括号截断，
`insertAt` 做光标夹紧（失焦时 `selectionStart` 可能是 0 或越界值）。
复核：`chatImage.test.ts` 15 例。

### 说明

图片只给模型一个本机地址（`/api/v1/git-issue-images/...`），
当前不会把图片内容本身编码进上下文；若将来需要多模态识别，
需要模型侧支持图片输入，本层接口形状不用变。
"""


def main() -> int:
    con = sqlite3.connect(DB)
    cur = con.cursor()
    row = cur.execute("select body from git_issues where id = ?", (TASK,)).fetchone()
    if row is None:
        print("任务不存在", file=sys.stderr)
        return 1
    body = (row[0] or "").rstrip()
    if "## 完成记录" not in body:
        body = (body + "\n\n" + NOTE).strip() if body else NOTE.strip()
    cur.execute("update git_issues set state='CLOSED', body=?, updated_at=? where id=?",
                (body, int(time.time() * 1000), TASK))
    con.commit()
    print("已关闭 FEAT-7（task-fcfbe08a03）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
