package top.rongx.agent.gateway.apidocs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * OpenAPI 快照新鲜度（golden file）。
 *
 * <p>规范由控制器实时生成，仓库里的 {@code src/main/resources/api/openapi.json}
 * 是它的可审阅副本（Agent 工具与外部 MCP 读它）。新增/修改接口后必须同步：
 * <pre>mvn -pl agent-gateway -Dtest=ApiDocsSnapshotTest -Dapi.docs.update=true test</pre>
 * 未同步时本测试失败，避免"文档与代码两套事实"。
 */
@SpringBootTest
class ApiDocsSnapshotTest {

    /** 相对模块目录（surefire 的工作目录 = 模块根）。 */
    private static final Path SNAPSHOT = Path.of("src", "main", "resources", "api", "openapi.json");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path tmp = Files.createTempDirectory("api-docs-test");
        // 测试不写仓库 data 目录
        registry.add("agent.api-docs.snapshot", () -> tmp.resolve("openapi.json").toString());
        registry.add("agent.tools.catalog-path", () -> tmp.resolve("agent-tools.json").toString());
        registry.add("agent.sandbox.mode", () -> "process");
    }

    @Autowired
    private ApiDocsSnapshotService snapshots;

    @Test
    @DisplayName("OpenAPI 快照与控制器一致")
    void snapshotIsFresh() throws IOException {
        String generated = snapshots.refresh() + "\n";

        if (Boolean.getBoolean("api.docs.update")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, generated, StandardCharsets.UTF_8);
            return;
        }
        if (!Files.isRegularFile(SNAPSHOT)) {
            fail("缺少 OpenAPI 快照：" + SNAPSHOT.toAbsolutePath()
                    + "；执行 mvn -pl agent-gateway -Dtest=ApiDocsSnapshotTest -Dapi.docs.update=true test 生成");
        }
        String committed = Files.readString(SNAPSHOT, StandardCharsets.UTF_8);
        if (!committed.equals(generated)) {
            fail("OpenAPI 快照已过期（控制器变了但快照没更新）。首个差异："
                    + firstDifference(committed, generated) + "\n执行 mvn -pl agent-gateway "
                    + "-Dtest=ApiDocsSnapshotTest -Dapi.docs.update=true test 重新生成并提交。");
        }
    }

    private static String firstDifference(String a, String b) {
        String[] left = a.split("\n", -1);
        String[] right = b.split("\n", -1);
        int max = Math.max(left.length, right.length);
        for (int i = 0; i < max; i++) {
            String l = i < left.length ? left[i] : "<EOF>";
            String r = i < right.length ? right[i] : "<EOF>";
            if (!l.equals(r)) {
                return "第 " + (i + 1) + " 行\n  committed: " + l + "\n  generated: " + r;
            }
        }
        return "（无行级差异，可能是行尾）";
    }
}
