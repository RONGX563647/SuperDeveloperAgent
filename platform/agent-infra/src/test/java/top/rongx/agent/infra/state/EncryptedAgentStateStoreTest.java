package top.rongx.agent.infra.state;

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
        Path legacy = temp.resolve("state/u1/s1/agent.state.enc");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "{\"text\":\"legacy\"}", StandardCharsets.UTF_8);

        assertTrue(store.get("u1", "s1", "agent", SampleState.class).isEmpty());
    }

    @Test
    void legacyEncryptedStateIsMigrated() throws Exception {
        SecretBox box = new SecretBox(temp.toString(), "");
        EncryptedAgentStateStore store = new EncryptedAgentStateStore(temp.resolve("state"), box);
        Path legacy = temp.resolve("state/u1/s1/agent.state.enc");
        Files.createDirectories(legacy.getParent());
        SecretBox.Sealed sealed = box.encrypt("{\"text\":\"旧会话\"}");
        Files.writeString(legacy, sealed.cipherB64() + ":" + sealed.ivB64(), StandardCharsets.UTF_8);

        assertEquals("旧会话",
                store.get("u1", "s1", "agent", SampleState.class).orElseThrow().text());
        assertTrue(Files.notExists(legacy), "旧格式应在迁移后清理");
        // 再读一次走新格式
        assertEquals("旧会话",
                store.get("u1", "s1", "agent", SampleState.class).orElseThrow().text());
    }

    @Test
    void anonymousSlotIsIgnored() {
        EncryptedAgentStateStore store = newStore();

        // AgentScope 在绑定会话槽位前会用 userId=null + 默认 sessionId（agent 名）探一次状态：
        // 必须静默返回"没有状态"，而不是抛 "userId 不能为空" 让框架刷 WARN。
        assertTrue(store.get(null, "devops-agent", "agent_state", SampleState.class).isEmpty());
        assertTrue(store.getList(null, "devops-agent", "memory", SampleState.class).isEmpty());
        assertFalse(store.exists(null, "devops-agent"));
        assertTrue(store.listSessionIds(null).isEmpty());

        store.save(null, "devops-agent", "agent_state", new SampleState("x"));
        store.delete(null, "devops-agent");
        // 匿名槽位不落盘
        assertFalse(Files.exists(temp.resolve("state")));
    }

    private EncryptedAgentStateStore newStore() {
        SecretBox box = new SecretBox(temp.toString(), "");
        return new EncryptedAgentStateStore(temp.resolve("state"), box);
    }
}
