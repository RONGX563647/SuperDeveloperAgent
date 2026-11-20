package top.rongx.agent.governance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.policy.DangerousActionPolicy;
import top.rongx.agent.domain.policy.PolicyDeniedException;
import top.rongx.agent.domain.project.ProjectContext;

/** 非生产破坏性动作防护：命令识别 + 频次上限。 */
class DangerousActionPolicyTest {

    private final ProjectContext ctx =
            new ProjectContext("p1", "dev", "u1", "git@example.com:a/b.git", null);

    @Test
    void normalBuildDoesNotNeedConfirmation() {
        DangerousActionPolicy policy = new DangerousActionPolicy(3, 600);
        assertFalse(policy.requiresConfirmation("backend_build", "mvn -B -DskipTests package"));
        assertDoesNotThrow(() -> policy.checkFrequency(ctx, "backend_build", "mvn -B test"));
    }

    @Test
    void destructiveCommandNeedsConfirmation() {
        DangerousActionPolicy policy = new DangerousActionPolicy(3, 600);
        assertTrue(policy.requiresConfirmation("backend_test", "rm -rf target && mvn test"));
        assertTrue(policy.requiresConfirmation("db_reset", null));
        assertFalse(policy.requiresConfirmation("backend_build", null));
    }

    @Test
    void sameDestructiveActionIsLimitedPerWindow() {
        DangerousActionPolicy policy = new DangerousActionPolicy(2, 600);
        policy.checkFrequency(ctx, "db_reset", null);
        policy.checkFrequency(ctx, "db_reset", null);
        assertThrows(PolicyDeniedException.class,
                () -> policy.checkFrequency(ctx, "db_reset", null));
        // 换个动作不受影响
        assertDoesNotThrow(() -> policy.checkFrequency(ctx, "backend_build", null));
    }

    // ================================================================ BUG-51 回归

    /** BUG-51 绕过样例：每个等价写法都必须命中（归一化后匹配）。 */
    @Test
    void bypassVariantsAreAllDestructive() {
        DangerousActionPolicy policy = new DangerousActionPolicy(3, 600);
        // 旗标拆分：rm -r -f
        assertTrue(policy.isDestructiveCommand("rm -r -f /tmp/x"),
                "rm -r -f 必须命中（BUG-51 绕过样例 1）");
        // git push --force / -f / -uf
        assertTrue(policy.isDestructiveCommand("sh -c git push --force"),
                "git push --force 必须命中（BUG-51 绕过样例 2）");
        assertTrue(policy.isDestructiveCommand("git push -f origin main"));
        assertTrue(policy.isDestructiveCommand("git push -uf origin main"));
        // find -delete
        assertTrue(policy.isDestructiveCommand("find . -name *.log -delete"),
                "find -delete 必须命中（BUG-51 绕过样例 3）");
        // SQL 多空格 / 大小写
        assertTrue(policy.isDestructiveCommand("mysql -e \"DROP  TABLE t\""),
                "DROP  TABLE（多空格）必须命中（BUG-51 绕过样例 4）");
        assertTrue(policy.isDestructiveCommand("DROP TABLE users"));
        assertTrue(policy.isDestructiveCommand("drop   database prod"));
        // rm 长短旗标 / 顺序
        assertTrue(policy.isDestructiveCommand("rm -f -r /tmp/x"));
        assertTrue(policy.isDestructiveCommand("rm --force --recursive /tmp/x"));
        assertTrue(policy.isDestructiveCommand("rm --recursive -f /tmp/x"));
        assertTrue(policy.isDestructiveCommand("rm -fr /tmp/x"));
        // git clean -f
        assertTrue(policy.isDestructiveCommand("git clean -f"));
        assertTrue(policy.isDestructiveCommand("git clean --force"));
        // docker 变体
        assertTrue(policy.isDestructiveCommand("docker rm --force web"));
        assertTrue(policy.isDestructiveCommand("docker rm $(docker ps -aq)"));
        assertTrue(policy.isDestructiveCommand("docker rmi nginx:latest"));
        // 权限 / 磁盘
        assertTrue(policy.isDestructiveCommand("chmod -R 777 /"));
        assertTrue(policy.isDestructiveCommand("mkfs.ext4 /dev/sdb1"));
        // find -exec rm
        assertTrue(policy.isDestructiveCommand("find . -name *.log -exec rm {} \\;"));
    }

    @Test
    void normalCommandsStillAllowed() {
        DangerousActionPolicy policy = new DangerousActionPolicy(3, 600);
        assertFalse(policy.isDestructiveCommand("npm run build"));
        assertFalse(policy.isDestructiveCommand("mvn -B package"));
        assertFalse(policy.isDestructiveCommand("git status"));
        assertFalse(policy.isDestructiveCommand("git push origin main"));
        assertFalse(policy.isDestructiveCommand("git push --force-with-lease origin main"),
                "--force-with-lease 不是 --force，不应误伤");
        assertFalse(policy.isDestructiveCommand("rm -v build.log"),
                "无 -r/-f 的 rm 不需要确认");
        assertFalse(policy.isDestructiveCommand("find . -name *.log -print"));
    }

    @Test
    void keywordMatchingUsesWordBoundaries() {
        DangerousActionPolicy policy = new DangerousActionPolicy(3, 600);
        // 原有行为保留：下划线分隔的破坏性动作名照样命中
        assertTrue(policy.isDestructive("db_reset"));
        assertTrue(policy.isDestructive("clean_workspace"));
        assertTrue(policy.isDestructive("migrate_up"));
        // 新增：关键词是更长单词一部分时不再误报
        assertFalse(policy.isDestructive("deleted_flag_check"));
        assertFalse(policy.isDestructive("restoredmode"));
        assertFalse(policy.isDestructive("forcemajor"));
    }
}
