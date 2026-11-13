package top.rongx.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.State;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.infra.security.SecretBox;

/** 会话状态必须密文落盘，但读写语义与 AgentScope 的 JSON 存储保持一致。 */
class EncryptedAgentStateStoreTest {

    record SampleState(String text) implements State {
    }

    @TempDir
    Path temp;

    @Test
    void savesCiphertextAndReadsBack() throws Exception {
        EncryptedAgentStateStore store = newStore();

        store.save("u1", "s1", "agent", new SampleState("对话里的敏感内容"));
        assertTrue(store.exists("u1", "s1"));
        assertEquals("对话里的敏感内容",
                store.get("u1", "s1", "agent", SampleState.class).orElseThrow().text());

        // 磁盘上不得出现明文
        try (var walk = Files.walk(temp.resolve("state"))) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                assertFalse(raw.contains("对话里的敏感内容"), "状态文件仍含明文：" + file);
                assertNotEquals("", raw.trim());
            }
        }
    }

    @Test
    void listSaveAndRead() {
        EncryptedAgentStateStore store = newStore();
        store.save("u1", "s1", "memory", List.of(new SampleState("a"), new SampleState("b")));

        List<SampleState> loaded = store.getList("u1", "s1", "memory", SampleState.class);
        assertEquals(List.of("a", "b"), loaded.stream().map(SampleState::text).toList());
    }

    @Test
    void deleteRemovesSession() {
        EncryptedAgentStateStore store = newStore();
        store.save("u1", "s1", "agent", new SampleState("x"));
        store.delete("u1", "s1");
        assertFalse(store.exists("u1", "s1"));
        assertTrue(store.get("u1", "s1", "agent", SampleState.class).isEmpty());
    }

    @Test
    void legacyPlaintextIsIgnored() throws Exception {
        EncryptedAgentStateStore store = newStore();
        store.save("u1", "s1", "agent", new SampleState("x"));
        Path file;
        try (var walk = Files.walk(temp.resolve("state"))) {
            file = walk.filter(p -> p.getFileName().toString().endsWith(".state.enc")).findFirst().orElseThrow();
        }
        Files.writeString(file, "{\"text\":\"legacy\"}", StandardCharsets.UTF_8);

        assertTrue(store.get("u1", "s1", "agent", SampleState.class).isEmpty());
    }

    private EncryptedAgentStateStore newStore() {
        SecretBox box = new SecretBox(temp.toString(), "");
        return new EncryptedAgentStateStore(temp.resolve("state"), box);
    }
}
