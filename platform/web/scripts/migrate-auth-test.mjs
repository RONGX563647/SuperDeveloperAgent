// 一次性迁移脚本：把 ActionUsabilityTest 从「X-User-Id 参数」改为「Sa-Token 会话」。
// 运行：node scripts/migrate-auth-test.mjs （在 platform/web 下运行，路径按 ../ 相对定位）
import { readFileSync, writeFileSync } from 'node:fs'

const file = '../agent-gateway/src/test/java/top/rongx/agent/gateway/ActionUsabilityTest.java'
let src = readFileSync(file, 'utf8')

// 1) @BeforeEach 里登录（setUp 已存在，追加登录行）
src = src.replace(
  /@BeforeEach\n(\s+)void setUp\(\) throws Exception \{\n(\s+)writeRecipes\(ACTIONS_YML\);/,
  '@BeforeEach\n$1void setUp() throws Exception {\n$2writeRecipes(ACTIONS_YML);\n$2// 以 devops 身份建立会话（L2/L3 均有权）；用例内如需其它角色再单独 login\n$2cn.dev33.satoken.stp.StpUtil.login("devops");'
)

// 2) @AfterEach 登出（新增）
src = src.replace(
  /(@BeforeEach\n\s+void setUp\(\) throws Exception \{[\s\S]*?\n\s+\})\n/,
  '$1\n\n    @AfterEach\n    void tearDown() {\n        cn.dev33.satoken.stp.StpUtil.logout();\n    }\n'
)

// 3) controller 调用去掉 USER 参数
src = src.replaceAll(', USER)', ')')
src = src.replaceAll('USER, ', '')

// 4) U5 的未认证断言 → viewer 无权限断言
src = src.replace(
  /var noUser = assertThrows\(IllegalArgumentException\.class,\s*\(\) -> actionController\.list\("RY", "test", null\)\);\s*assertTrue\(noUser\.getMessage\(\)\.contains\("未认证"\)\);/m,
  'var noPerm = assertThrows(Exception.class, () -> actionController.run("backend_build", "RY", "test"));\n' +
  '        assertTrue(noPerm.getMessage().contains("权限") || noPerm.getMessage().contains("Permission"),\n' +
  '                "viewer 应被拒：" + noPerm.getMessage());'
)

// 5) import AfterEach
if (!src.includes('import org.junit.jupiter.api.AfterEach;')) {
  src = src.replace('import org.junit.jupiter.api.BeforeEach;', 'import org.junit.jupiter.api.AfterEach;\nimport org.junit.jupiter.api.BeforeEach;')
}

writeFileSync(file, src)
console.log('migrated. 剩余 USER 引用数:', (src.match(/USER/g) ?? []).length)
console.log('loginAs/AfterEach:', src.includes('AfterEach'), src.includes('StpUtil.login'))
