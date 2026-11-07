package org.example.agent.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目规约 §1.1 A1：AI 决策面不得持有写凭证。
 *
 * <p>只检查 {@code gateway / runtime / tools / governance} 四个模块的配置文件。
 * 允许：只读库账号、actuator Basic Auth、Git token（PR 用途）。
 * 禁止：kubeconfig / DDL 账号 / 部署令牌 / 仓库写凭证 / SSH 私钥。
 */
class ConfigCredentialScanTest {

    private static final String[] DECISION_PLANE = {"gateway", "runtime", "tools", "governance"};

    /** 编号 + 正则 + 原因。 */
    private static final Object[][] RULES = {
            {"C1", "kubeconfig|kube-config|kubectl", "K8s 写权限只属于 Deployer"},
            {"C2", "ddl[-_.]?(user|account|password)|dba[-_.]?(user|password)|schema[-_.]?owner",
                    "DDL 账号只属于 Migrator"},
            {"C3", "deploy[-_.]?(token|password|key)|release[-_.]?(token|key)", "部署令牌只属于 Deployer"},
            {"C4", "registry[-_.]?(password|token|push)|docker[-_.]?push", "镜像推送凭证只属于执行面"},
            {"C5", "ssh[-_.]?(private|identity|key[-_.]?path)", "部署用 SSH 私钥只属于执行面"},
    };

    @Test
    @DisplayName("决策面配置不得含写凭证键")
    void decisionPlaneHasNoWriteCredentials() throws IOException {
        Path root = Path.of(System.getProperty("spec.root", ".")).toAbsolutePath().normalize();
        List<String> violations = new ArrayList<>();
        int scanned = 0;

        for (Path file : collect(root)) {
            String normalized = file.toString().replace('\\', '/');
            if (!isDecisionPlane(normalized)) {
                continue;
            }
            scanned++;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    continue;
                }
                for (Object[] rule : RULES) {
                    String id = (String) rule[0];
                    Pattern pattern = Pattern.compile((String) rule[1], Pattern.CASE_INSENSITIVE);
                    if (pattern.matcher(line).find()) {
                        violations.add(String.format("[%s] %s:%d  %s%n        → %s（项目规约 §1.1 A1）",
                                id, root.relativize(file), i + 1, trimmed, rule[2]));
                    }
                }
            }
        }

        final int scannedFiles = scanned;
        assertTrue(violations.isEmpty(),
                () -> String.format("AI 决策面出现了写凭证（扫描 %d 个配置文件）：%n%s",
                        scannedFiles, String.join(System.lineSeparator(), violations)));
    }

    private static boolean isDecisionPlane(String path) {
        for (String module : DECISION_PLANE) {
            if (path.contains("/agent-" + module + "/")) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> collect(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/resources/"))
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".yml") || n.endsWith(".yaml")
                                || n.endsWith(".properties") || n.endsWith(".conf");
                    })
                    .forEach(files::add);
        }
        return files;
    }
}
