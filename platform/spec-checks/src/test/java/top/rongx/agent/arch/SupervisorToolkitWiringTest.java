package top.rongx.agent.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 调度 Agent（Router）必须保持「零写工具」：它只负责派发与只读查询。
 *
 * <p>这条护栏从装配源头校验：{@code supervisorToolkit} 只允许复用只读注册方法，
 * 并额外注册 {@code supervisorTools} 本身；一旦有人把命令类/补丁类/部署类工具塞进来，
 * 测试立即失败（防回退）。
 */
class SupervisorToolkitWiringTest {

    private static final Pattern REGISTER = Pattern.compile("toolkit\\.registerTool\\(\\s*(\\w+)");

    @Test
    @DisplayName("supervisorToolkit 只注册只读面 + supervisorTools，无任何写工具")
    void supervisorToolkitHasNoWriteTools() throws IOException {
        String body = supervisorToolkitBody();

        List<String> registered = new ArrayList<>();
        Matcher matcher = REGISTER.matcher(body);
        while (matcher.find()) {
            registered.add(matcher.group(1));
        }
        assertEquals(List.of("supervisorTools"), registered,
                "调度 Agent 只能额外注册 supervisorTools，实际：" + registered);

        for (String forbidden : List.of("CommandTools", "PatchTools", "EditorTools",
                "DeployTools", "OpsAgentTools", "CapabilityMarketTools", "EnvCommandTools")) {
            assertFalse(body.contains(forbidden),
                    "调度 Agent 工具面混入了写能力：" + forbidden);
        }
    }

    @Test
    @DisplayName("supervisorToolkit 复用只读注册方法")
    void reusesReadOnlyRegistration() throws IOException {
        String body = supervisorToolkitBody();
        assertTrue(body.contains("registerReadOnlyTools("),
                "调度 Agent 应复用只读工具注册方法，避免工具面漂移");
    }

    private static String supervisorToolkitBody() throws IOException {
        Path file = Path.of(System.getProperty("spec.root", ".."), "agent-tools", "src", "main",
                "java", "top", "rongx", "agent", "tools", "ToolCatalog.java");
        String source = Files.readString(file);
        int start = source.indexOf("public Toolkit supervisorToolkit(");
        assertTrue(start > 0, "未找到 supervisorToolkit 装配方法");
        int end = source.indexOf("\n    }", source.indexOf("return toolkit;", start));
        assertTrue(end > start, "未找到 supervisorToolkit 方法体结尾");
        return source.substring(start, end);
    }
}
