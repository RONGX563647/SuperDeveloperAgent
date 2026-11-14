package top.rongx.agent.gateway.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.UserRolePort;

/**
 * 用户仓库（SQLite 持久化）。
 *
 * <p>首次启动（users 表为空）时创建 3 个种子账号。种子密码<b>不写死在代码里</b>：
 * <ol>
 *   <li>优先读配置 {@code agent.auth.seed-passwords}（格式 {@code admin=xxx,devops=yyy,viewer=zzz}，
 *       通常由环境变量 {@code AGENT_SEED_PASSWORDS} 注入）；</li>
 *   <li>未提供的账号生成一次性随机密码，写入 {@code <workspace>/_platform/seed-passwords.txt}
 *       （权限 600）并在日志里<b>只提示文件位置</b>，不打印密码本身。</li>
 * </ol>
 *
 * <p>密码用 BCrypt 存哈希（spring-security-crypto），明文不落任何地方。
 */
@Component
public class UserStore implements UserRolePort {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);

    /** 项目成员列表里的通配符：可访问全部项目。 */
    public static final String ALL_PROJECTS = "*";

    /** 一个平台账号。 */
    public record User(String userId, String displayName, String bcryptHash,
                       Role role, List<String> projects) {
    }

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS users (
                user_id      TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                bcrypt_hash  TEXT NOT NULL,
                role         TEXT NOT NULL,
                projects     TEXT NOT NULL
            )
            """;

    /** 种子账号定义：userId / 显示名 / 角色 / 项目范围。 */
    private static final List<SeedUser> SEEDS = List.of(
            new SeedUser("admin", "管理员", Role.ADMIN, List.of(ALL_PROJECTS)),
            new SeedUser("devops", "运维开发", Role.DEVOPS, List.of("RY")),
            new SeedUser("viewer", "访客", Role.VIEWER, List.of("RY")));

    private record SeedUser(String userId, String displayName, Role role, List<String> projects) {
    }

    private final Path dbFile;
    private final Path seedFile;

    public UserStore(@Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
                     @Value("${agent.auth.seed-passwords:}") String seedPasswords) {
        Path platformDir = Path.of(workspaceRoot).toAbsolutePath().normalize().resolve("_platform");
        this.dbFile = platformDir.resolve("users.db");
        this.seedFile = platformDir.resolve("seed-passwords.txt");
        initSchema();
        if (count() == 0) {
            seed(seedPasswords);
        }
    }

    private void initSchema() {
        try {
            Files.createDirectories(dbFile.getParent());
            try (Connection conn = open(); PreparedStatement st = conn.prepareStatement(SCHEMA)) {
                st.executeUpdate();
            }
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("users 表初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    /**
     * 首次初始化：按配置的密码建账号；缺省则生成随机密码并落到 600 权限的文件里。
     * 日志只提示文件路径，不输出任何凭据。
     */
    private void seed(String seedPasswords) {
        Map<String, String> configured = parseSeedPasswords(seedPasswords);
        Map<String, String> generated = new LinkedHashMap<>();
        for (SeedUser seed : SEEDS) {
            String raw = configured.get(seed.userId());
            if (raw == null || raw.isBlank()) {
                raw = randomPassword();
                generated.put(seed.userId(), raw);
            }
            insert(new User(seed.userId(), seed.displayName(),
                    BCrypt.hashpw(raw, BCrypt.gensalt()), seed.role(), seed.projects()));
        }
        if (generated.isEmpty()) {
            log.info("已按配置初始化种子账号：{}", configured.keySet());
            return;
        }
        writeSeedFile(generated);
        log.warn("已生成一次性初始密码并写入 {}（权限 600），请登录后尽快修改；"
                + "也可通过 agent.auth.seed-passwords 配置项重新初始化", seedFile.toAbsolutePath());
    }

    private void writeSeedFile(Map<String, String> generated) {
        StringBuilder text = new StringBuilder("# 首次启动生成的账号密码（请登录后修改并删除本文件）\n");
        generated.forEach((user, raw) -> text.append(user).append('=').append(raw).append('\n'));
        try {
            Files.createDirectories(seedFile.getParent());
            Files.writeString(seedFile, text.toString());
            try {
                Files.setPosixFilePermissions(seedFile,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                log.warn("无法收紧种子密码文件权限（非 POSIX 文件系统）：{}", seedFile);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("种子密码写入失败：" + e.getMessage(), e);
        }
    }

    /** 解析 {@code admin=xxx,devops=yyy}（空值跳过）。 */
    private static Map<String, String> parseSeedPasswords(String spec) {
        Map<String, String> out = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String part : spec.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String user = part.substring(0, eq).trim();
            String raw = part.substring(eq + 1).trim();
            if (!user.isEmpty() && !raw.isEmpty()) {
                out.put(user, raw);
            }
        }
        return out;
    }

    private static String randomPassword() {
        byte[] buf = new byte[18];
        new SecureRandom().nextBytes(buf);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private int count() {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("SELECT COUNT(*) FROM users");
             ResultSet rs = st.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("用户查询失败：" + e.getMessage(), e);
        }
    }

    private void insert(User user) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO users (user_id, display_name, bcrypt_hash, role, projects)
                     VALUES (?,?,?,?,?)
                     """)) {
            st.setString(1, user.userId());
            st.setString(2, user.displayName());
            st.setString(3, user.bcryptHash());
            st.setString(4, user.role().code());
            st.setString(5, String.join(",", user.projects()));
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("用户写入失败：" + e.getMessage(), e);
        }
    }

    /** 校验密码（常量时间比较由 BCrypt.checkpw 保证）。 */
    public boolean checkPassword(String userId, String raw) {
        User user = find(userId);
        return user != null && BCrypt.checkpw(raw, user.bcryptHash());
    }

    /** 按登录名取用户；不存在返回 null。 */
    public User find(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT user_id, display_name, bcrypt_hash, role, projects
                     FROM users WHERE user_id = ?
                     """)) {
            st.setString(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String projects = rs.getString("projects");
                List<String> list = projects == null || projects.isBlank()
                        ? List.of()
                        : Arrays.stream(projects.split(","))
                                .map(String::trim).filter(s -> !s.isEmpty()).toList();
                return new User(rs.getString("user_id"), rs.getString("display_name"),
                        rs.getString("bcrypt_hash"), Role.of(rs.getString("role")), list);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("用户查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean isReadOnly(String userId) {
        User user = find(userId);
        return user == null || user.role() == Role.VIEWER;
    }

    /** 全部用户（管理面板预留）。 */
    public List<User> all() {
        List<User> out = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT user_id, display_name, bcrypt_hash, role, projects
                     FROM users ORDER BY user_id
                     """);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                String projects = rs.getString("projects");
                out.add(new User(rs.getString("user_id"), rs.getString("display_name"),
                        rs.getString("bcrypt_hash"), Role.of(rs.getString("role")),
                        projects == null || projects.isBlank()
                                ? List.of()
                                : Arrays.stream(projects.split(","))
                                        .map(String::trim).filter(s -> !s.isEmpty()).toList()));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("用户查询失败：" + e.getMessage(), e);
        }
    }
}
