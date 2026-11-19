/**
 * 会话插图的纯逻辑回归脚本（零依赖）。
 *
 * 背景（FEAT-7 第 2 项）：对话输入框的附件按钮原来直接 `disabled`
 * （title="附件功能待接入"）。补上之后，最容易出错的是两个细节：
 * 图片插在光标处还是末尾、中文/带括号文件名会不会破坏 Markdown 语法。
 *
 * 运行：node --experimental-strip-types --import ./tools/ts-resolve.mjs src/utils/chatImage.test.ts
 */
import { imageMarkdown, insertAt, isImageFile } from './chatImage.ts'

let passed = 0
let failed = 0

function check(name: string, condition: boolean, detail = ''): void {
  if (condition) {
    passed++
    console.log(`  ✓ ${name}`)
  } else {
    failed++
    console.error(`  ✗ ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

// 1. Markdown 语法
check('基本图片语法',
  imageMarkdown('/api/v1/git-issue-images/a.png', 'shot.png')
  === '![shot.png](/api/v1/git-issue-images/a.png)')
check('空文件名用 image 兜底',
  imageMarkdown('/u', '') === '![image](/u)')
check('方括号被替换，不截断语法',
  imageMarkdown('/u', 'a[b].png') === '![a_b_.png](/u)',
  imageMarkdown('/u', 'a[b].png'))
check('中文文件名原样保留',
  imageMarkdown('/u', '截图.png') === '![截图.png](/u)')

// 2. 插入位置：插在光标处而不是末尾
check('插在中间',
  insertAt('前半后半', '[图]', 2).text === '前半[图]后半',
  insertAt('前半后半', '[图]', 2).text)
check('光标随插入后移',
  insertAt('前半后半', '[图]', 2).caret === 5,
  String(insertAt('前半后半', '[图]', 2).caret))

// 3. 边界：光标为空/越界/负数都要夹紧，不能插到开头或抛错
check('null 光标追加到末尾',
  insertAt('abc', 'X', null).text === 'abcX')
check('越界光标夹到末尾',
  insertAt('abc', 'X', 99).text === 'abcX')
check('负数光标夹到开头',
  insertAt('abc', 'X', -5).text === 'Xabc')
check('空文本也能插',
  insertAt('', 'X', 0).text === 'X')
check('undefined 文本当空串',
  insertAt(undefined as unknown as string, 'X', null).text === 'X')

// 4. 只认图片类型
check('image/png 是图片', isImageFile('image/png') === true)
check('image/jpeg 是图片', isImageFile('image/jpeg') === true)
check('text/plain 不是图片', isImageFile('text/plain') === false)
check('undefined 不是图片', isImageFile(undefined) === false)

console.log(`\n${failed === 0 ? '✅' : '❌'} ${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
