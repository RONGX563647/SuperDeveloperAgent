package top.rongx.agent.infra.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;
import top.rongx.agent.infra.security.SecretBox;

/**
 * 会话状态存储（AES-256-GCM 落盘）。
 *
 * <p>底层复用 AgentScope 的 {@link JsonFileAgentStateStore}：目录布局、原子写、会话列举、
 * 删除、clearAll 都由它负责；这里只在其上叠加一层“状态 JSON 加密”编解码。
 * 这样不会出现平台自研一套文件状态库、AgentScope 升级后路径语义分叉的问题。
 *
 * <p>兼容旧格式：历史版本使用 {@code *.state.enc} / {@code *.list.enc}，读取时先查新格式，
 * 再回退旧格式；回退命中后会顺手迁移到新格式。
 */
public class EncryptedAgentStateStore implements AgentStateStore {

    private static final Logger log = LoggerFactory.getLogger(EncryptedAgentStateStore.class);

    private static final String LEGACY_VALUE_SUFFIX = ".state.enc";
    private static final String LEGACY_LIST_SUFFIX = ".list.enc";
    private static final String SEPARATOR = ":";

    private final Path root;
    private final SecretBox cipher;
    private final JsonCodec codec = JsonUtils.getJsonCodec();
    /** 懒初始化：匿名槽位不应在磁盘上创建任何状态目录。 */
    private volatile JsonFileAgentStateStore delegate;

    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>
            sessionLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public EncryptedAgentStateStore(Path root, SecretBox cipher) {
        this.root = root.toAbsolutePath().normalize();
        this.cipher = cipher;
    }

    private JsonFileAgentStateStore delegate() {
        JsonFileAgentStateStore current = delegate;
        if (current == null) {
            synchronized (this) {
                current = delegate;
                if (current == null) {
                    current = new JsonFileAgentStateStore(root);
                    delegate = current;
                }
            }
        }
        return current;
    }

    /** 加密后的状态载荷；真正内容由 {@link #cipher} 保护，JsonFileAgentStateStore 只看到密文。 */
    public record EncryptedState(String token) implements State {
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        if (state == null || isAnonymous(userId)) {
            return;
        }
        withSessionLock(userId, sessionId, () -> {
            delegate().save(userId, sessionId, key,
                    new EncryptedState(encrypt(codec.toJson(state))));
            deleteLegacy(userId, sessionId, key, LEGACY_VALUE_SUFFIX);
        });
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        if (isAnonymous(userId)) {
            return;
        }
        List<EncryptedState> payload = new ArrayList<>();
        if (states != null) {
            for (State state : states) {
                if (state != null) {
                    payload.add(new EncryptedState(encrypt(codec.toJson(state))));
                }
            }
        }
        withSessionLock(userId, sessionId, () -> {
            delegate().save(userId, sessionId, key, payload);
            deleteLegacy(userId, sessionId, key, LEGACY_LIST_SUFFIX);
        });
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key,
                                             Class<T> type) {
        if (isAnonymous(userId)) {
            return Optional.empty();
        }
        return withSessionLock(userId, sessionId, () -> {
            Optional<EncryptedState> stored = delegate().get(userId, sessionId, key, EncryptedState.class);
            if (stored.isPresent()) {
                return decryptState(stored.get(), type, userId, sessionId, key);
            }
            // 旧格式回退：迁移到新格式后返回，避免升级丢会话
            String token = readLegacyToken(userId, sessionId, key, LEGACY_VALUE_SUFFIX);
            if (token == null) {
                return Optional.empty();
            }
            Optional<T> migrated = decryptState(new EncryptedState(token), type, userId, sessionId, key);
            migrated.ifPresent(value -> delegate().save(userId, sessionId, key,
                    new EncryptedState(encrypt(codec.toJson(value)))));
            if (migrated.isPresent()) {
                deleteLegacy(userId, sessionId, key, LEGACY_VALUE_SUFFIX);
            }
            return migrated;
        });
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key,
                                             Class<T> type) {
        if (isAnonymous(userId)) {
            return List.of();
        }
        return withSessionLock(userId, sessionId, () -> {
            List<EncryptedState> stored = delegate().getList(userId, sessionId, key, EncryptedState.class);
            if (!stored.isEmpty()) {
                return stored.stream()
                        .map(item -> decryptState(item, type, userId, sessionId, key))
                        .flatMap(Optional::stream)
                        .toList();
            }
            List<T> migrated = readLegacyList(userId, sessionId, key, type);
            if (!migrated.isEmpty()) {
                List<EncryptedState> payload = migrated.stream()
                        .map(value -> new EncryptedState(encrypt(codec.toJson(value))))
                        .toList();
                delegate().save(userId, sessionId, key, payload);
                deleteLegacy(userId, sessionId, key, LEGACY_LIST_SUFFIX);
            }
            return migrated;
        });
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return !isAnonymous(userId)
                && (delegate().exists(userId, sessionId) || Files.isDirectory(legacySessionDir(userId, sessionId)));
    }

    @Override
    public void delete(String userId, String sessionId) {
        if (isAnonymous(userId)) {
            return;
        }
        withSessionLock(userId, sessionId, () -> {
            delegate().delete(userId, sessionId);
            deleteRecursively(legacySessionDir(userId, sessionId));
        });
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        if (isAnonymous(userId)) {
            return;
        }
        withSessionLock(userId, sessionId, () -> {
            delegate().delete(userId, sessionId, key);
            deleteLegacy(userId, sessionId, key, LEGACY_VALUE_SUFFIX);
            deleteLegacy(userId, sessionId, key, LEGACY_LIST_SUFFIX);
        });
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        if (isAnonymous(userId)) {
            return Set.of();
        }
        java.util.Set<String> sessions = new java.util.HashSet<>(delegate().listSessionIds(userId));
        Path legacyUserDir = legacyUserDirectory(userId);
        if (Files.isDirectory(legacyUserDir)) {
            try (Stream<Path> entries = Files.list(legacyUserDir)) {
                entries.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .forEach(sessions::add);
            } catch (IOException ignored) {
                // 目录可能被并发删除
            }
        }
        return Set.copyOf(sessions);
    }

    // ---------------------------------------------------------------- 加解密

    private <T extends State> Optional<T> decryptState(EncryptedState stored, Class<T> type,
                                                       String userId, String sessionId, String key) {
        if (stored == null || stored.token() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(codec.fromJson(decrypt(stored.token()), type));
        } catch (RuntimeException e) {
            // 旧版明文 / 主密钥轮换后的历史数据：当成没有，别让整轮对话失败
            log.warn("会话状态无法解密，已忽略：user={} session={} key={}", userId, sessionId, key);
            return Optional.empty();
        }
    }

    private <T extends State> List<T> readLegacyList(String userId, String sessionId, String key,
                                                     Class<T> type) {
        Path file = legacyFile(userId, sessionId, key, LEGACY_LIST_SUFFIX);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<T> result = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    result.add(codec.fromJson(decrypt(line.trim()), type));
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("旧会话状态列表无法解密，已忽略：user={} session={} key={}", userId, sessionId, key);
            return List.of();
        }
        return result;
    }

    private String encrypt(String plaintext) {
        SecretBox.Sealed sealed = cipher.encrypt(plaintext);
        return sealed.cipherB64() + SEPARATOR + sealed.ivB64();
    }

    private String decrypt(String token) {
        int sep = token.indexOf(SEPARATOR);
        if (sep <= 0) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "状态数据不是加密格式");
        }
        return cipher.decrypt(token.substring(0, sep), token.substring(sep + 1));
    }

    // ---------------------------------------------------------------- 旧路径兼容

    private static boolean isAnonymous(String userId) {
        return userId == null || userId.isBlank();
    }

    private Path legacyUserDirectory(String userId) {
        return root.resolve(safeName(userId, "userId"));
    }

    private Path legacySessionDir(String userId, String sessionId) {
        return legacyUserDirectory(userId).resolve(safeName(sessionId, "sessionId"));
    }

    private Path legacyFile(String userId, String sessionId, String key, String suffix) {
        return legacySessionDir(userId, sessionId).resolve(safeName(key, "key") + suffix);
    }

    private static String safeName(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, label + " 不能为空");
        }
        String name = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.equals(".") || name.equals("..")) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, label + " 非法：" + raw);
        }
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private String readLegacyToken(String userId, String sessionId, String key, String suffix) {
        Path file = legacyFile(userId, sessionId, key, suffix);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
        }
    }

    private void deleteLegacy(String userId, String sessionId, String key, String suffix) {
        try {
            Files.deleteIfExists(legacyFile(userId, sessionId, key, suffix));
        } catch (IOException ignored) {
            // 删除失败不影响新格式读写
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 目录可能已被并发删除
                }
            });
        } catch (IOException ignored) {
            // 目录可能已被并发删除
        }
    }

    private <T> T withSessionLock(String userId, String sessionId,
                                  java.util.function.Supplier<T> action) {
        String key = userId + "/" + sessionId;
        java.util.concurrent.locks.ReentrantLock lock = sessionLocks.computeIfAbsent(
                key, ignored -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private void withSessionLock(String userId, String sessionId, Runnable action) {
        withSessionLock(userId, sessionId, () -> {
            action.run();
            return null;
        });
    }
}
