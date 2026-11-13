package top.rongx.agent.runtime;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.infra.security.SecretBox;

/**
 * 会话状态存储（AES-256-GCM 落盘）。
 *
 * <p>AgentScope 自带的 {@code JsonFileAgentStateStore} 只写明文 JSON，对话内容（可能含
 * 日志片段、环境变量摘要）会直接躺在磁盘上。这里用平台主密钥
 * （{@link SecretBox}，同一把 {@code AGENT_SECURITY_MASTER_KEY}）加密后再落盘：
 *
 * <ul>
 *   <li>单值：一个文件一行密文（Base64(cipher):Base64(iv)）；</li>
 *   <li>列表：一行一个元素密文，追加语义由整体重写替代（会话状态量很小）；</li>
 *   <li>写入用临时文件 + 原子移动，避免读到半截；目录 700、文件 600。</li>
 * </ul>
 *
 * <p>兼容性：早期明文 JSON 文件无法解密，按"不存在"处理（会话从下一轮重新开始），
 * 不会因为升级而让对话接口报错。
 */
public class EncryptedAgentStateStore implements AgentStateStore {

    private static final Logger log = LoggerFactory.getLogger(EncryptedAgentStateStore.class);

    private static final String VALUE_SUFFIX = ".state.enc";
    private static final String LIST_SUFFIX = ".list.enc";
    private static final String SEPARATOR = ":";

    private final Path root;
    private final SecretBox cipher;
    private final JsonCodec codec = JsonUtils.getJsonCodec();

    public EncryptedAgentStateStore(Path root, SecretBox cipher) {
        this.root = root.toAbsolutePath().normalize();
        this.cipher = cipher;
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        if (state == null) {
            return;
        }
        write(file(userId, sessionId, key, VALUE_SUFFIX),
                encrypt(codec.toJson(state)) + System.lineSeparator());
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        StringBuilder body = new StringBuilder();
        if (states != null) {
            for (State state : states) {
                if (state != null) {
                    body.append(encrypt(codec.toJson(state))).append(System.lineSeparator());
                }
            }
        }
        write(file(userId, sessionId, key, LIST_SUFFIX), body.toString());
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key,
                                             Class<T> type) {
        String token = readToken(file(userId, sessionId, key, VALUE_SUFFIX));
        if (token == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(codec.fromJson(decrypt(token), type));
        } catch (RuntimeException e) {
            // 旧版明文 / 主密钥轮换后的历史数据：当成没有，别让整轮对话失败
            log.warn("会话状态无法解密，已忽略：user={} session={} key={}", userId, sessionId, key);
            return Optional.empty();
        }
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key,
                                             Class<T> type) {
        Path file = file(userId, sessionId, key, LIST_SUFFIX);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<T> result = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                result.add(codec.fromJson(decrypt(line.trim()), type));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("会话状态列表无法解密，已忽略：user={} session={} key={}", userId, sessionId, key);
            return List.of();
        }
        return result;
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return Files.isDirectory(sessionDirectory(userId, sessionId));
    }

    @Override
    public void delete(String userId, String sessionId) {
        deleteRecursively(sessionDirectory(userId, sessionId));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        deleteIfExists(file(userId, sessionId, key, VALUE_SUFFIX));
        deleteIfExists(file(userId, sessionId, key, LIST_SUFFIX));
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        Path dir = userDirectory(userId);
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            return Set.of();
        }
    }

    // ---------------------------------------------------------------- 路径与 IO

    private Path userDirectory(String userId) {
        return root.resolve(safeName(userId, "userId"));
    }

    private Path sessionDirectory(String userId, String sessionId) {
        return userDirectory(userId).resolve(safeName(sessionId, "sessionId"));
    }

    private Path file(String userId, String sessionId, String key, String suffix) {
        return sessionDirectory(userId, sessionId).resolve(safeName(key, "key") + suffix);
    }

    /** 只允许安全字符，且拒绝 . / ..，避免状态目录被路径穿越。 */
    private static String safeName(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        String name = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException(label + " 非法：" + raw);
        }
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private String encrypt(String plaintext) {
        SecretBox.Sealed sealed = cipher.encrypt(plaintext);
        return sealed.cipherB64() + SEPARATOR + sealed.ivB64();
    }

    private String decrypt(String token) {
        int sep = token.indexOf(SEPARATOR);
        if (sep <= 0) {
            throw new IllegalArgumentException("状态数据不是加密格式");
        }
        return cipher.decrypt(token.substring(0, sep), token.substring(sep + 1));
    }

    private static String readToken(Path file) {
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

    private void write(Path file, String body) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            restrict(tmp, "rw-------");
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(file, "rw-------");
            restrict(file.getParent(), "rwx------");
        } catch (IOException e) {
            throw new IllegalStateException("会话状态写入失败：" + e.getMessage(), e);
        }
    }

    private static void restrict(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException ignored) {
            // 非 POSIX 文件系统：退化为默认权限，不影响功能
        }
    }

    private static void deleteIfExists(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 删除失败不影响后续写入（会被覆盖）
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(EncryptedAgentStateStore::deleteIfExists);
        } catch (IOException ignored) {
            // 目录可能已被并发删除
        }
    }
}
