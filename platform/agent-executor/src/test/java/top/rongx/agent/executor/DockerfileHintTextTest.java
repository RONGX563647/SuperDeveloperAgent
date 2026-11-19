package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 缺 Dockerfile 的阻塞文案：必须在预检阶段就告诉用户「用哪个工具」。
 *
 * <p>回归背景（BUG-1「遇到不同环境经常中断」）：历史 17 份失败运行里 E-501 出现 9 次，
 * 其中一部分是「DOCKER 目标但仓库没有 Dockerfile」。预检确实挡住了，但文案只写
 * 「先生成 Dockerfile」——用户不知道平台已能按仓库结构自动生成，只能手写一个，
 * 手写的又常常和镜像阶段/目录结构对不上，于是又失败一次。
 *
 * <p>这条契约很小但很关键：<b>挡住了不等于解决了</b>，文案必须给出可执行的下一步。
 */
class DockerfileHintTextTest {

    @Test
    @DisplayName("文案给出工具名与「不需要手写」，而不是只说缺文件")
    void mentionsToolAndDiscouragesManualWrite() {
        String text = BuiltInPipelineEngine.missingDockerfileBlocker("Dockerfile");

        assertTrue(text.contains("ensure_dockerfile"),
                "必须出现工具名，否则用户不知道该用什么：" + text);
        assertTrue(text.contains("不需要手写 Dockerfile"),
                "必须明确说明不需要手写，否则用户会先写一个错的：" + text);
        assertTrue(text.contains("确认后自动用新提交重跑"),
                "要说清确认后会自动重跑，否则用户会以为还要手动触发：" + text);
    }

    @Test
    @DisplayName("文案带上具体缺失路径与错误码，便于对号入座")
    void carriesPathAndErrorCode() {
        String text = BuiltInPipelineEngine.missingDockerfileBlocker("deploy/Dockerfile");

        assertTrue(text.contains("deploy/Dockerfile"), text);
        assertTrue(text.startsWith("E-501") || text.contains("E-501"), text);
    }

    @Test
    @DisplayName("不要写成命令式指令让用户去 shell 里干活")
    void doesNotAskUserToRunCommands() {
        String text = BuiltInPipelineEngine.missingDockerfileBlocker("Dockerfile");

        assertFalse(text.contains("docker build"),
                "不该让用户自己跑 docker build——那是平台的事：" + text);
        assertFalse(text.contains("vim ") || text.contains("cat >"),
                "不该引导用户手写文件：" + text);
    }
}
