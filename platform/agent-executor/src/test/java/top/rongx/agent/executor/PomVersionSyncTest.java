package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PomVersionSyncTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("正式发布写 x.y.z，跳过 parent 块只改本项目版本")
    void updatesProjectVersionNotParent() throws Exception {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.3.0</version>
                  </parent>
                  <groupId>demo</groupId>
                  <artifactId>demo-app</artifactId>
                  <version>0.1.0-SNAPSHOT</version>
                </project>
                """);

        var result = PomVersionSync.apply(tmp, "0.1.1", false).orElseThrow();
        assertTrue(result.changed());
        assertEquals("0.1.0-SNAPSHOT", result.oldVersion());
        String text = Files.readString(pom);
        assertTrue(text.contains("<version>0.1.1</version>"), text);
        assertTrue(text.contains("<version>3.3.0</version>"), "父 POM 版本不能被改：" + text);
    }

    @Test
    @DisplayName("快照构建写 x.y.z-SNAPSHOT；一致时不改动")
    void writesSnapshotSuffix() throws Exception {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, "<project><version>0.1.0</version></project>");

        var result = PomVersionSync.apply(tmp, "0.1.1", true).orElseThrow();
        assertEquals("0.1.1-SNAPSHOT", result.newVersion());
        assertTrue(Files.readString(pom).contains("<version>0.1.1-SNAPSHOT</version>"));

        assertFalse(PomVersionSync.apply(tmp, "0.1.1", true).orElseThrow().changed());
    }

    @Test
    @DisplayName("没有 pom.xml 时返回空，不影响其它项目类型")
    void noPomIsNoop() {
        assertTrue(PomVersionSync.apply(tmp, "0.1.1", false).isEmpty());
    }
}
