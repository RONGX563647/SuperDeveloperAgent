package top.rongx.agent.gateway.auth;

import top.rongx.agent.domain.workspace.WorkspacePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.UserRolePort;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.domain.error.PlatformException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 用户仓库（JPA 持久化）。
 *
 * <p>首次启动（users 表为空）时创建 3 个种子账号，三者<b>共用同一个固定初始密码</b>
 * {@link #DEFAULT_INITIAL_PASSWORD}：本地/单机部署开箱即用，登录后到「设置 → 账号安全」
 * 修改即可，不需要去文件里找随机密码。需要区分密码或用于共享环境时，用
 * {@code agent.auth.seed-passwords}（环境变量 {@code AGENT_SEED_PASSWORDS}，格式
 * {@code admin=xxx,devops=yyy,viewer=zzz}）覆盖，未指定的账号仍用统一默认值。
 *
 * <p>旧版本会为每个账号生成随机密码并写入 {@code <workspace>/_platform/seed-passwords.txt}。
 * 为让已经生成过账号数据的环境平滑升级：启动时若该文件里的明文仍能匹配库中哈希
 * （说明账号从未改过密码），自动重置为统一初始密码；已自行改密的账号保持原样。
 * 文件随后删除，避免明文凭据残留。
 *
 * <p>密码用 BCrypt 存哈希（spring-security-crypto），明文不落任何地方。
 */
@Component
public class UserStore implements UserRolePort {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);

    /** 项目成员列表里的通配符：可访问全部项目。 */
    public static final String ALL_PROJECTS = "*";

    /** 三个种子账号共用的初始密码：公开常量，便于首次登录；登录后应立即修改。 */
    public static final String DEFAULT_INITIAL_PASSWORD = "admin-2026";

    /** 自定义密码的最小长度，避免改成一个比初始密码更弱的口令。 */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** 一个平台账号。 */
    public record User(String userId, String displayName, String bcryptHash,
                       Role role, List<String> projects) {

        /**
         * 项目级访问判定：{@code projects} 含通配符 {@link #ALL_PROJECTS("*")} 或显式包含
         * {@code projectId} 才放行。登录回显（/me）与服务端鉴权共用本方法，口径一致。
         */
        public boolean canAccess(String projectId) {
            return projects.contains(ALL_PROJECTS)
                    || (projectId != null && projects.contains(projectId));
        }
    }

    /** 种子账号定义：userId / 显示名 / 角色 / 项目范围。 */
    private static final List<SeedUser> SEEDS = List.of(
            new SeedUser("admin", "管理员", Role.ADMIN, List.of(ALL_PROJECTS)),
            new SeedUser("devops", "运维开发", Role.DEVOPS, List.of("RY")),
            new SeedUser("viewer", "访客", Role.VIEWER, List.of("RY")));

    private record SeedUser(String userId, String displayName, Role role, List<String> projects) {
    }

    private final PlatformDb db;
    /** 旧版一次性密码文件；新版本不再生成，只在升级时读取并清理。 */
    private final Path seedFile;
    /** userId → 该账号的初始密码（配置覆盖值或统一默认值），用于初始化与「是否仍未改密」判断。 */
    private final Map<String, String> initialPasswords;

    @Autowired
    public UserStore(PlatformDb db,
                     @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
                     @Value("${agent.auth.seed-passwords:}") String seedPasswords) {
        this.db = db;
        this.seedFile = WorkspacePaths.seedPasswords(Path.of(workspaceRoot));
        this.initialPasswords = initialPasswords(seedPasswords);
        if (count() == 0) {
            seed();
        } else {
            migrateLegacySeedFile();
        }
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public UserStore(String workspaceRoot, String seedPasswords) {
        this(PlatformDb.sqlite(platformDbFile(workspaceRoot)), workspaceRoot, seedPasswords);
    }

    /** 独立运行时要保证库文件所在目录存在；schema 仍由 Hibernate 生成。 */
    private static Path platformDbFile(String workspaceRoot) {
        Path dbFile = WorkspacePaths.db(Path.of(workspaceRoot));
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "用户存储目录创建失败：" + e.getMessage(), e);
        }
        return dbFile;
    }

    /** 首次初始化：所有账号用固定初始密码（配置可覆盖），日志不输出任何凭据。 */
    private void seed() {
        for (SeedUser seed : SEEDS) {
            insert(new User(seed.userId(), seed.displayName(),
                    BCrypt.hashpw(initialPasswords.get(seed.userId()), BCrypt.gensalt()),
                    seed.role(), seed.projects()));
        }
        log.info("已初始化 {} 个种子账号（统一初始密码，请登录后到设置页修改）", SEEDS.size());
    }

    /**
     * 旧版随机密码迁移：只有「库里仍是文件里那把初始密码」的账号才重置，
     * 已经自行改密的账号不受影响。无论是否重置，明文文件都会被删除。
     */
    private void migrateLegacySeedFile() {
        if (!Files.isRegularFile(seedFile)) {
            return;
        }
        try {
            int migrated = 0;
            for (String line : Files.readAllLines(seedFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String userId = trimmed.substring(0, eq).trim();
                String legacy = trimmed.substring(eq + 1).trim();
                User user = find(userId);
                if (user != null && !legacy.isEmpty() && matches(user.bcryptHash(), legacy)
                        && updateHash(userId, initialPasswords.getOrDefault(userId, DEFAULT_INITIAL_PASSWORD))) {
                    migrated++;
                }
            }
            Files.deleteIfExists(seedFile);
            log.info("已把 {} 个旧版账号重置为统一初始密码，并清理历史明文文件", migrated);
        } catch (java.io.IOException e) {
            log.warn("历史凭据文件清理失败，可手动删除：{}", seedFile.toAbsolutePath());
        }
    }

    /** 解析 {@code admin=xxx,devops=yyy}（空值跳过），并补齐未指定账号的默认值。 */
    private static Map<String, String> initialPasswords(String spec) {
        Map<String, String> configured = parseSeedPasswords(spec);
        Map<String, String> out = new LinkedHashMap<>();
        for (SeedUser seed : SEEDS) {
            String raw = configured.get(seed.userId());
            out.put(seed.userId(), raw == null || raw.isBlank() ? DEFAULT_INITIAL_PASSWORD : raw);
        }
        return out;
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

    private int count() {
        try {
            long total = db.read(em -> PlatformDb.count(em, "SELECT COUNT(*) FROM users"));
            return (int) total;
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "用户查询失败：" + e.getMessage(), e);
        }
    }

    private void insert(User user) {
        try {
            db.run(em -> PlatformDb.update(em, """
                    INSERT INTO users (user_id, display_name, bcrypt_hash, role, projects)
                    VALUES (?,?,?,?,?)
                    """,
                    user.userId(),
                    user.displayName(),
                    user.bcryptHash(),
                    user.role().code(),
                    String.join(",", user.projects())));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "用户写入失败：" + e.getMessage(), e);
        }
    }

    /** 写入新的 BCrypt 哈希；返回是否命中了账号。 */
    private boolean updateHash(String userId, String raw) {
        try {
            return db.inTx(em -> PlatformDb.update(em,
                    "UPDATE users SET bcrypt_hash = ? WHERE user_id = ?",
                    BCrypt.hashpw(raw, BCrypt.gensalt()), userId) > 0);
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "用户更新失败：" + e.getMessage(), e);
        }
    }

    /** 校验密码（常量时间比较由 BCrypt.checkpw 保证）。 */
    public boolean checkPassword(String userId, String raw) {
        User user = find(userId);
        return user != null && matches(user.bcryptHash(), raw == null ? "" : raw);
    }

    /** BCrypt 哈希损坏时不让登录/改密链路抛 500，按「不匹配」处理。 */
    private static boolean matches(String hash, String raw) {
        try {
            return BCrypt.checkpw(raw, hash);
        } catch (PlatformException | IllegalArgumentException e) {
            return false;
        }
    }

    /** 当前密码是否仍是初始密码（用于前端提示「去设置页修改」）。 */
    public boolean isUsingInitialPassword(String userId) {
        User user = find(userId);
        return user != null && matches(user.bcryptHash(),
                initialPasswords.getOrDefault(userId, DEFAULT_INITIAL_PASSWORD));
    }

    /**
     * 修改自己的密码：校验原密码，禁止把新密码设回初始密码或当前密码。
     *
     * @throws IllegalArgumentException 原密码错误 / 新密码不合规
     */
    public synchronized void changePassword(String userId, String current, String next) {
        User user = find(userId);
        if (user == null || !matches(user.bcryptHash(), current == null ? "" : current)) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "原密码不正确");
        }
        if (next == null || next.length() < MIN_PASSWORD_LENGTH) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "新密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (next.equals(initialPasswords.getOrDefault(userId, DEFAULT_INITIAL_PASSWORD))
                || matches(user.bcryptHash(), next)) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "新密码不能与初始密码或当前密码相同");
        }
        updateHash(userId, next);
    }

    /** 按登录名取用户；不存在返回 null。 */
    public User find(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return db.read(em -> PlatformDb.row(em, """
                            SELECT user_id, display_name, bcrypt_hash, role, projects
                            FROM users WHERE user_id = ?
                            """, userId)
                    .map(UserStore::map)
                    .orElse(null));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "用户查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean isReadOnly(String userId) {
        User user = find(userId);
        return user == null || user.role() == Role.VIEWER;
    }

    /** 全部用户（管理面板预留）。 */
    public List<User> all() {
        try {
            return db.read(em -> PlatformDb.rows(em, """
                            SELECT user_id, display_name, bcrypt_hash, role, projects
                            FROM users ORDER BY user_id
                            """).stream()
                    .map(UserStore::map)
                    .toList());
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "用户查询失败：" + e.getMessage(), e);
        }
    }

    private static User map(Object[] row) {
        String projects = (String) row[4];
        List<String> list = projects == null || projects.isBlank()
                ? List.of()
                : Arrays.stream(projects.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new User((String) row[0], (String) row[1], (String) row[2],
                Role.of((String) row[3]), list);
    }
}
