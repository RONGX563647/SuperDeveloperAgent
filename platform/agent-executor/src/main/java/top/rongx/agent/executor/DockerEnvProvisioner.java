package top.rongx.agent.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvProvisioner;
import top.rongx.agent.domain.env.EnvResource;
import top.rongx.agent.domain.env.EnvTemplate;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.RuntimeMode;
import top.rongx.agent.domain.env.StackAdapter;
import top.rongx.agent.domain.process.PortProbe;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;

/**
 * 本机 Docker 环境自愈：按「项目 + 环境」准备隔离的中间件容器。
 *
 * <p>决策顺序（每一步都会写进部署日志）：
 * <ol>
 *   <li>先读项目自己的 {@code .env} / {@code .env.example}，把连接信息注入项目环境覆盖
 *       （密码只写库、不回显）——这是项目作者声明的"到底连哪"；</li>
 *   <li>再探测声明的地址是否真的可达：可达就直接用，不创建任何容器；</li>
 *   <li>不可达（本机没有对应容器 / 远程库没开）才创建项目隔离的容器，
 *       并且<b>沿用项目声明的库名/账号/密码</b>，保证应用能连上；</li>
 *   <li>必填的密钥类变量（TOKEN_SECRET 等）缺失时自动生成。</li>
 * </ol>
 *
 * <p>命名与网络：
 * <pre>
 * 容器  &lt;namespace&gt;-&lt;project&gt;-&lt;kind&gt;   例如 agent-dev-ruoyiwnz-mysql
 * 数据卷 &lt;容器名&gt;-data
 * 网络  &lt;namespace&gt;-net（不存在则创建）
 * </pre>
 */
public class DockerEnvProvisioner implements EnvProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DockerEnvProvisioner.class);

    private static final Path CWD = Path.of(".");
    private static final long TIMEOUT_MS = 180_000;
    private static final long WAIT_MS = 45_000;
    private static final long POLL_MS = 750;
    /** compose 等待 healthcheck 的秒数：比自造轮询的 45s 更宽（首次初始化库文件要时间）。 */
    private static final int COMPOSE_WAIT_SECONDS = 120;
    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(1500);
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Set<String> ENDPOINT_KEYS = Set.of("DB_HOST", "DB_PORT", "REDIS_HOST",
            "REDIS_PORT", "RABBITMQ_HOST", "RABBITMQ_PORT", "MINIO_ENDPOINT", "MONGO_HOST",
            "MONGO_PORT");
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 中间件镜像默认值：只是兜底，不是平台前提。
     *
     * <p>早前这些标签直接写在 {@code runArgv} 的 switch 里，运维无法替换；本机没有时
     * {@code docker run} 会静默去拉，最长等 {@link #TIMEOUT_MS} 才报一个语焉不详的失败。
     * 现在统一从这里取值，可用 {@code agent.env.middleware-images.<kind>} 覆盖为本机已有
     * 镜像或离线 registry 镜像，并在创建容器前预检。
     */
    private static final Map<String, String> DEFAULT_MIDDLEWARE_IMAGES = Map.of(
            "mysql", "mysql:8.0",
            "mariadb", "mysql:8.0",
            "postgres", "postgres:16-alpine",
            "redis", "redis:7-alpine",
            "rabbitmq", "rabbitmq:3.12-management",
            "minio", "minio/minio:latest",
            "mongo", "mongo:7");

    /** 镜像引用只允许 registry/仓库名、tag、digest 中的安全字符，防止把任意参数塞进 argv。 */
    private static final Pattern IMAGE_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,200}");

    private final EnvironmentStore store;
    private final top.rongx.agent.domain.port.ProjectRepoLocator repos;
    private final ProcessExecutor executor = new ProcessExecutor();
    /** kind → 镜像引用；未覆盖的 kind 走 {@link #DEFAULT_MIDDLEWARE_IMAGES}。 */
    private final Map<String, String> middlewareImages;
    /** 创建容器前是否预检镜像已在本地；离线/自建 registry 可关闭。 */
    private final boolean precheckImages;
    /** Docker CLI 可执行文件（可指向 podman 等兼容实现）。 */
    private final DockerCli docker;
    /** 是否用 {@code docker compose up --wait} 一次起齐中间件（默认开；极简安装可关）。 */
    private boolean composeEnabled = true;
    /** compose 可用性缓存（null = 尚未探测）。 */
    private Boolean composeOk;

    public DockerEnvProvisioner(EnvironmentStore store) {
        this(store, null);
    }

    public DockerEnvProvisioner(EnvironmentStore store,
                                top.rongx.agent.domain.port.ProjectRepoLocator repos) {
        this(store, repos, Map.of(), true);
    }

    public DockerEnvProvisioner(EnvironmentStore store,
                                top.rongx.agent.domain.port.ProjectRepoLocator repos,
                                Map<String, String> middlewareImages,
                                boolean precheckImages) {
        this(store, repos, middlewareImages, precheckImages, DockerCli.defaults());
    }

    public DockerEnvProvisioner(EnvironmentStore store,
                                top.rongx.agent.domain.port.ProjectRepoLocator repos,
                                Map<String, String> middlewareImages,
                                boolean precheckImages,
                                DockerCli docker) {
        this.store = store;
        this.repos = repos;
        this.middlewareImages = middlewareImages == null ? Map.of() : Map.copyOf(middlewareImages);
        this.precheckImages = precheckImages;
        this.docker = docker == null ? DockerCli.defaults() : docker;
    }

    /**
     * 打开/关闭 compose 中间件路径（{@code agent.env.compose-enabled}）。
     *
     * <p>关掉时逐容器 {@code docker run}——保留这条路径是因为 compose 插件不是 docker 的
     * 必然组件（极简安装里常常只有 CLI），但默认走 compose：加固参数是声明式的、
     * 就绪判定由 healthcheck 负责、多个中间件一次起完。
     */
    public DockerEnvProvisioner setComposeEnabled(boolean enabled) {
        this.composeEnabled = enabled;
        return this;
    }

    /** 解析 {@code mysql=xxx,redis=yyy} 形式的镜像覆盖；非法条目直接报出，不静默忽略。 */
    public static Map<String, String> parseImageOverrides(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String entry : raw.split("[,;]")) {
            String pair = entry.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String kind = eq < 0 ? "" : pair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String image = eq < 0 ? "" : pair.substring(eq + 1).trim();
            if (!DEFAULT_MIDDLEWARE_IMAGES.containsKey(kind) || !IMAGE_REF.matcher(image).matches()) {
                throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "agent.env.middleware-images 含非法条目「" + pair
                        + "」；格式应为 <kind>=<image>，kind 可选 "
                        + DEFAULT_MIDDLEWARE_IMAGES.keySet() + "，镜像只含字母数字与 ._/:@-");
            }
            parsed.put(kind, image);
        }
        return Map.copyOf(parsed);
    }

    /** 当前 kind 生效的镜像引用。 */
    String imageOf(String kind) {
        return middlewareImages.getOrDefault(kind, DEFAULT_MIDDLEWARE_IMAGES.getOrDefault(kind, ""));
    }

    @Override
    public Result ensure(String projectId, String env, List<String> requiredKeys) {
        EnvTemplate template = store.template(env).orElse(null);
        RuntimeMode mode = template == null ? RuntimeMode.defaultFor(env) : template.mode();
        List<String> injected = seedFromProjectFiles(projectId, env);
        List<String> kinds = neededKinds(projectId, env, requiredKeys);
        List<String> generated = new ArrayList<>();
        if (mode == RuntimeMode.SERVER) {
            // 远程环境不自动创建容器：连接信息应由运维在对应服务器确认。
            return Result.ok(List.of(), List.of(), injected, List.of());
        }
        String namespace = template == null || template.namespace() == null
                || template.namespace().isBlank() ? "agent-" + env : template.namespace().trim();
        String network = namespace + "-net";
        // 项目额外声明的 PostgreSQL（如 NOVEL_PG_URL）没有凭据时也要落到项目本地实例，
        // 否则应用会连到共享/外部库并因认证失败把 actuator/health 拖成 DOWN。
        boolean needExtraPg = needsDeclaredPostgres(projectId, env, namespace);
        if (kinds.isEmpty() && !needExtraPg) {
            generated.addAll(generateMissingSecrets(projectId, env, requiredKeys));
            return Result.ok(List.of(), List.of(), injected, generated);
        }

        List<String> created = new ArrayList<>();
        List<String> started = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        String dockerProblem = dockerProblem();
        if (dockerProblem != null) {
            return Result.failed(List.of(dockerProblem), dockerProblem);
        }
        ensureNetwork(network);

        // compose 路径（D14）：一次起齐所有中间件，并让 compose 自己等 healthcheck。
        // 关掉时逐容器 docker run（逃生门，保留一个版本）：compose 依赖 docker compose 插件，
        // 极简安装里可能只有 docker CLI。
        if (composeEnabled && !kinds.isEmpty() && composeAvailable()) {
            try {
                if (ensureByCompose(projectId, env, namespace, network, kinds,
                        created, started, failed)) {
                    generated.addAll(generateMissingSecrets(projectId, env, requiredKeys));
                    if (!failed.isEmpty()) {
                        Result partial = Result.ok(created, started, injected, generated);
                        return new Result(false, partial.created(), partial.started(),
                                partial.injected(), partial.generated(), List.copyOf(failed),
                                "环境容器自愈未完成：" + String.join("；", failed));
                    }
                    return Result.ok(created, started, injected, generated);
                }
            } catch (RuntimeException e) {
                // compose 失败不吞掉：记日志后逐容器回退，最终仍能起来就是可用性优先
                log.warn("compose 准备中间件失败，回退逐容器创建：{}", e.toString());
                created.clear();
                started.clear();
                failed.clear();
            }
        }

        for (String kind : kinds) {
            String name = containerName(namespace, projectId, kind);
            if (!NAME.matcher(name).matches()) {
                failed.add(name + "：容器名不合法（只允许小写字母/数字/._-）");
                continue;
            }
            try {
                reconcile(projectId, env, kind, name, network, created, started, failed);
            } catch (RuntimeException e) {
                failed.add(name + "：" + e.getMessage());
            }
        }
        if (needExtraPg) {
            ensureDeclaredPostgres(projectId, env, namespace, network, created, started, failed);
        }
        generated.addAll(generateMissingSecrets(projectId, env, requiredKeys));
        if (!failed.isEmpty()) {
            Result ok = Result.ok(created, started, injected, generated);
            return new Result(false, ok.created(), ok.started(), ok.injected(), ok.generated(),
                    List.copyOf(failed), "环境容器自愈未完成：" + String.join("；", failed));
        }
        return Result.ok(created, started, injected, generated);
    }

    // ---------------------------------------------------------------- compose 路径

    /**
     * compose 可用性：{@code docker compose version} 能跑才走 compose 路径。
     *
     * <p>结论缓存一次：极简安装（只有 docker CLI、没有 compose 插件）会每次都失败，
     * 而每轮环境准备都探测一次纯属浪费。
     */
    private boolean composeAvailable() {
        Boolean cached = composeOk;
        if (cached != null) {
            return cached;
        }
        boolean ok = ops().run(MiddlewareCompose.versionArgv(docker)).ok();
        composeOk = ok;
        if (!ok) {
            log.info("未检测到 docker compose 插件，环境中间件回落逐容器 docker run");
        }
        return ok;
    }

    /**
     * 用 compose 一次起齐中间件。
     *
     * @return true = compose 路径已处理（含部分失败并已写入 failed）；
     *         false = 有服务需要逐容器处理（凭据不一致需要重建卷等），交给调用方回退
     */
    private boolean ensureByCompose(String projectId, String env, String namespace, String network,
                                    List<String> kinds, List<String> created, List<String> started,
                                    List<String> failed) {
        List<MiddlewareCompose.Service> services = new ArrayList<>();
        List<String> toRename = new ArrayList<>();
        for (String kind : kinds) {
            String name = containerName(namespace, projectId, kind);
            if (!NAME.matcher(name).matches()) {
                failed.add(name + "：容器名不合法（只允许小写字母/数字/._-）");
                continue;
            }
            Creds creds = declaredCreds(projectId, env, kind);
            EnvResource existing = findByName(env, name);
            boolean running = Boolean.TRUE.equals(liveRunning(name));
            if (existing != null && running && sameCreds(existing, creds)) {
                // 已就绪：记健康即可，不需要 compose 参与
                store.recordHealth(existing.id(), true, null);
                applyOverrides(projectId, existing, true);
                continue;
            }
            // 凭据不一致 → 必须重建（MySQL/Postgres 的初始化密码只认数据卷里的值），
            // 这类"要删卷"的动作不适合塞进 compose，交回逐容器路径。
            if (containerExists(name) && !sameCreds(existing, creds)) {
                return false;
            }
            int port = existing != null && existing.port() > 0
                    ? existing.port() : allocatePort(kind, env, projectId);
            services.add(new MiddlewareCompose.Service(kind, name, imageOf(kind), port,
                    name + "-data", volumePath(kind), middlewareEnv(kind, creds),
                    middlewareCommand(kind, creds)));
            toRename.add(name + "(" + port + ")");
        }
        if (services.isEmpty()) {
            return true;     // 全部已就绪，什么都不用做
        }

        Path file = CWD.resolve(".agent").resolve("env").resolve(env).resolve("docker-compose.yml");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MiddlewareCompose.render(namespace, network, services),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写 compose 文件失败，回退逐容器创建：{}", e.toString());
            return false;
        }

        StringBuilder sink = new StringBuilder();
        ProcessExecutor.StepOutcome up = executor.exec(CWD,
                MiddlewareCompose.upArgv(docker, file.toString(), namespace, COMPOSE_WAIT_SECONDS),
                TIMEOUT_MS, sink, () -> false);
        if (up.exitCode() != 0) {
            // compose 起来了部分容器也算失败：调用方据此报错，但已起的不再重复创建
            failed.add("compose up 失败：" + ProcessExecutor.redact(ProcessExecutor.tail(sink)));
            services.forEach(s -> failed.add(s.containerName() + "：未确认就绪"));
            return true;
        }
        log.info("环境中间件已由 compose 就绪：{}", MiddlewareCompose.describe(services));
        for (MiddlewareCompose.Service service : services) {
            Creds creds = declaredCreds(projectId, env, service.kind());
            EnvResource existing = findByName(env, service.containerName());
            EnvResource saved = store.saveResource(new EnvResource(
                    existing == null ? null : existing.id(), env, service.kind(),
                    service.containerName(), "127.0.0.1", service.hostPort(),
                    defaultPort(service.kind()), service.containerName(), creds.database(),
                    creds.username(), creds.password(), network,
                    existing == null ? null : existing.serverId(), "LOCAL_DOCKER", true,
                    System.currentTimeMillis(), null));
            applyOverrides(projectId, saved, true);
            (existing == null ? created : started).add(service.containerName()
                    + "(" + service.hostPort() + ")");
        }
        return true;
    }

    /** 中间件的环境变量（compose 与单容器路径共用，避免两处各拼一遍）。 */
    static Map<String, String> middlewareEnv(String kind, Creds creds) {
        Map<String, String> env = new LinkedHashMap<>();
        switch (kind) {
            case "mysql", "mariadb" -> {
                env.put("MYSQL_ROOT_PASSWORD", nullSafe(creds.password()));
                env.put("MYSQL_DATABASE", nullSafe(creds.database()));
            }
            case "postgres" -> {
                env.put("POSTGRES_USER", nullSafe(creds.username()));
                env.put("POSTGRES_PASSWORD", nullSafe(creds.password()));
                env.put("POSTGRES_DB", nullSafe(creds.database()));
            }
            case "rabbitmq" -> {
                env.put("RABBITMQ_DEFAULT_USER", nullSafe(creds.username()));
                env.put("RABBITMQ_DEFAULT_PASS", nullSafe(creds.password()));
            }
            case "minio" -> {
                env.put("MINIO_ROOT_USER", nullSafe(creds.username()));
                env.put("MINIO_ROOT_PASSWORD", nullSafe(creds.password()));
            }
            case "mongo" -> {
                env.put("MONGO_INITDB_ROOT_USERNAME", nullSafe(creds.username()));
                env.put("MONGO_INITDB_ROOT_PASSWORD", nullSafe(creds.password()));
            }
            default -> {
                // redis 无环境变量需求（密码走 command）
            }
        }
        return Map.copyOf(env);
    }

    /** 中间件的启动命令（只有 redis 需要）。 */
    static List<String> middlewareCommand(String kind, Creds creds) {
        if ("redis".equals(kind) && creds != null
                && creds.password() != null && !creds.password().isBlank()) {
            return List.of("redis-server", "--requirepass", creds.password());
        }
        return List.of();
    }

    // ---------------------------------------------------------------- 容器对账

    /** 让一个 kind 的容器与「项目声明」一致：可复用就复用，缺失/凭据不符就重建。 */
    private void reconcile(String projectId, String env, String kind, String name, String network,
                           List<String> created, List<String> started, List<String> failed) {
        Creds creds = declaredCreds(projectId, env, kind);
        EnvResource existing = findByName(env, name);
        boolean running = Boolean.TRUE.equals(liveRunning(name));
        if (existing != null && running && sameCreds(existing, creds)) {
            store.recordHealth(existing.id(), true, null);
            applyOverrides(projectId, existing, true);
            return;
        }
        if (containerExists(name)) {
            // 凭据不一致时必须重建：MySQL/Postgres 的初始化密码只认数据卷里的值。
            removeContainer(name);
            removeVolume(name);
        }
        int port = existing != null && existing.port() > 0
                ? existing.port() : allocatePort(kind, env, projectId);
        String output = runContainer(kind, name, port, creds.database(), creds.username(),
                creds.password(), network);
        if (output != null) {
            failed.add(name + "：创建失败 " + output);
            return;
        }
        EnvResource saved = store.saveResource(new EnvResource(
                existing == null ? null : existing.id(), env, kind, name, "127.0.0.1", port,
                defaultPort(kind), name, creds.database(), creds.username(), creds.password(),
                network, existing == null ? null : existing.serverId(), "LOCAL_DOCKER", true,
                System.currentTimeMillis(), null));
        applyOverrides(projectId, saved, true);
        (existing == null ? created : started).add(name + "(" + port + ")");
        log.info("环境容器已就绪：project={} env={} name={} port={} recreate={}",
                projectId, env, name, port, existing != null);
    }

    /** 项目声明的额外 PostgreSQL（{@code *_PG_URL}）是否缺少可用凭据，或已有平台创建的隔离实例。 */
    private boolean needsDeclaredPostgres(String projectId, String env, String namespace) {
        Map<String, String> values = store.effective(projectId, env).values();
        boolean declared = false;
        boolean missingPassword = false;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey().endsWith("_PG_URL") && isPostgresUrl(e.getValue())) {
                declared = true;
                String password = values.get(e.getKey().substring(0,
                        e.getKey().length() - "_PG_URL".length()) + "_PG_PASSWORD");
                if (password == null || password.isBlank()) {
                    missingPassword = true;
                }
            }
        }
        if (!declared) {
            return false;
        }
        // 已有平台创建的隔离实例时必须每轮重放连接覆盖：否则下一次 seed 会把
        // .env 里的外部 URL 写回来，隔离库还活着却没人用。
        return missingPassword
                || findByName(env, containerName(namespace, projectId, "postgres")) != null;
    }

    private static boolean isPostgresUrl(String url) {
        return url != null && url.trim().startsWith("jdbc:postgresql://");
    }

    /** 从 JDBC URL 取库名：{@code jdbc:postgresql://host:5432/novel?x} → {@code novel}。 */
    private static String databaseFromPgUrl(String url) {
        if (!isPostgresUrl(url)) {
            return null;
        }
        String rest = url.trim().substring("jdbc:postgresql://".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String db = rest.substring(slash + 1);
        int question = db.indexOf('?');
        if (question >= 0) {
            db = db.substring(0, question);
        }
        return db.isBlank() ? null : db;
    }

    /**
     * 为项目额外声明的 PostgreSQL 建项目专属实例，并把连接改写为容器名寻址。
     *
     * <p>只在「项目没给密码」时介入：给了密码说明是有意连外部库，平台不擅自替换。
     * 实例名与主库一致规则（{@code <namespace>-<project>-postgres}），数据卷随之保留。
     */
    private void ensureDeclaredPostgres(String projectId, String env, String namespace,
                                        String network, List<String> created,
                                        List<String> started, List<String> failed) {
        Map<String, String> values = store.effective(projectId, env).values();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String urlKey = entry.getKey();
            if (!urlKey.endsWith("_PG_URL") || !isPostgresUrl(entry.getValue())) {
                continue;
            }
            String prefix = urlKey.substring(0, urlKey.length() - "_PG_URL".length());
            String userKey = prefix + "_PG_USERNAME";
            String passwordKey = prefix + "_PG_PASSWORD";
            String name = containerName(namespace, projectId, "postgres");
            if (!NAME.matcher(name).matches()) {
                failed.add(name + "：容器名不合法");
                continue;
            }
            EnvResource existing = findByName(env, name);
            // 没有平台实例且项目已给密码 = 有意连外部库，不擅自替换
            if (existing == null && values.get(passwordKey) != null
                    && !values.get(passwordKey).isBlank()) {
                continue;
            }
            try {
                String database = databaseFromPgUrl(entry.getValue());
                if (database == null || database.isBlank()) {
                    database = prefix.toLowerCase();
                }
                String username = values.get(userKey);
                if (username == null || username.isBlank()) {
                    username = "postgres";
                }
                if (existing != null && Boolean.TRUE.equals(liveRunning(name))) {
                    // 复用：凭据必须沿用库里的值（Postgres 初始化密码只认数据卷）
                    username = existing.username() == null || existing.username().isBlank()
                            ? username : existing.username();
                    String password = existing.password() == null || existing.password().isBlank()
                            ? generatePassword() : existing.password();
                    applyPgOverrides(projectId, env, prefix, name, database, username, password);
                    continue;
                }
                if (containerExists(name)) {
                    removeContainer(name);
                    removeVolume(name);
                }
                String password = generatePassword();
                int port = existing != null && existing.port() > 0
                        ? existing.port() : allocatePort("postgres", env, projectId);
                String output = runContainer("postgres", name, port, database, username, password, network);
                if (output != null) {
                    failed.add(name + "：创建失败 " + output);
                    continue;
                }
                store.saveResource(new EnvResource(existing == null ? null : existing.id(), env,
                        "postgres", name, "127.0.0.1", port, defaultPort("postgres"), name,
                        database, username, password, network,
                        existing == null ? null : existing.serverId(), "LOCAL_DOCKER", true,
                        System.currentTimeMillis(), null));
                applyPgOverrides(projectId, env, prefix, name, database, username, password);
                (existing == null ? created : started).add(name + "(" + port + ")");
            } catch (RuntimeException e) {
                failed.add(name + "：" + e.getMessage());
            }
        }
    }

    private void applyPgOverrides(String projectId, String env, String prefix, String container,
                                  String database, String username, String password) {
        // dev 是本机模式：JDBC URL 必须用宿主机可达的 127.0.0.1 + 发布端口
        String host = container;
        int port = defaultPort("postgres");
        if (!internalAddressing(env)) {
            EnvResource r = findByName(env, container);
            if (r != null) {
                if (r.host() != null && !r.host().isBlank()) {
                    host = r.host();
                }
                if (r.port() > 0) {
                    port = r.port();
                }
            }
        }
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        store.saveOverride(projectId, env, prefix + "_PG_URL", url, false);
        store.saveOverride(projectId, env, prefix + "_PG_USERNAME", username, false);
        store.saveOverride(projectId, env, prefix + "_PG_PASSWORD", password, true);
    }

    /** 项目声明的凭据（密码等来自 .env / 项目覆盖）；缺省时才生成。 */
    private Creds declaredCreds(String projectId, String env, String kind) {
        Map<String, String> values = store.effective(projectId, env).values();
        String database = values.get(databaseKey(kind));
        String username = values.get(usernameKey(kind));
        String password = values.get(passwordKey(kind));
        if (database == null || database.isBlank()) {
            database = defaultDatabase(projectId, kind);
        }
        if (username == null || username.isBlank()) {
            username = defaultUser(kind);
        }
        if (password == null) {
            password = needsPassword(kind) ? generatePassword() : null;
        }
        return new Creds(database, username, password);
    }

    private static boolean sameCreds(EnvResource r, Creds c) {
        return eq(r.databaseName(), c.database()) && eq(r.username(), c.username())
                && eq(r.password(), c.password());
    }

    private static boolean eq(String a, String b) {
        String left = a == null ? "" : a;
        String right = b == null ? "" : b;
        return left.equals(right);
    }

    // ---------------------------------------------------------------- 需要哪些容器

    /** 需要本地容器的中间件：声明缺失，或声明的地址探测不通。 */
    private List<String> neededKinds(String projectId, String env, List<String> requiredKeys) {
        Map<String, String> values = store.effective(projectId, env).values();
        List<String> out = new ArrayList<>();
        for (String kind : kindsOf(requiredKeys)) {
            String host = values.get(hostKey(kind));
            String port = values.get(portKey(kind));
            if (host == null || host.isBlank()) {
                out.add(kind);
                continue;
            }
            if (!reachable(host, port)) {
                out.add(kind);
            }
        }
        return out;
    }

    private static boolean reachable(String host, String port) {
        String cleanHost = host.trim();
        String cleanPort = port == null ? "" : port.trim();
        if (cleanHost.startsWith("http://") || cleanHost.startsWith("https://")) {
            String stripped = cleanHost.substring(cleanHost.indexOf("://") + 3);
            int slash = stripped.indexOf('/');
            if (slash >= 0) {
                stripped = stripped.substring(0, slash);
            }
            int colon = stripped.lastIndexOf(':');
            if (colon > 0) {
                cleanPort = stripped.substring(colon + 1);
                cleanHost = stripped.substring(0, colon);
            }
        }
        int number;
        try {
            number = Integer.parseInt(cleanPort);
        } catch (NumberFormatException e) {
            return false;
        }
        return PortProbe.isOpen(cleanHost, number, (int) PROBE_TIMEOUT.toMillis());
    }

    private static String hostKey(String kind) {
        return switch (kind) {
            case "redis" -> "REDIS_HOST";
            case "rabbitmq" -> "RABBITMQ_HOST";
            case "minio" -> "MINIO_ENDPOINT";
            case "mongo" -> "MONGO_HOST";
            default -> "DB_HOST";
        };
    }

    private static String portKey(String kind) {
        return switch (kind) {
            case "redis" -> "REDIS_PORT";
            case "rabbitmq" -> "RABBITMQ_PORT";
            case "minio" -> "MINIO_ENDPOINT";
            case "mongo" -> "MONGO_PORT";
            default -> "DB_PORT";
        };
    }

    private static String databaseKey(String kind) {
        return switch (kind) {
            case "minio", "redis", "rabbitmq" -> null;
            case "mongo" -> "MONGO_DATABASE";
            default -> "DB_NAME";
        };
    }

    private static String usernameKey(String kind) {
        return switch (kind) {
            case "minio" -> "MINIO_ACCESS_KEY";
            case "rabbitmq" -> "RABBITMQ_USERNAME";
            case "mongo" -> "MONGO_USERNAME";
            case "redis" -> null;
            default -> "DB_USERNAME";
        };
    }

    private static String passwordKey(String kind) {
        return switch (kind) {
            case "minio" -> "MINIO_SECRET_KEY";
            case "rabbitmq" -> "RABBITMQ_PASSWORD";
            case "mongo" -> "MONGO_PASSWORD";
            case "redis" -> "REDIS_PASSWORD";
            default -> "DB_PASSWORD";
        };
    }

    // ---------------------------------------------------------------- 项目声明注入

    /**
     * 从项目自己的 {@code .env} / {@code .env.example} 注入变量（项目覆盖）。
     *
     * <p>只补"还没有值"的键：`.env` 通常被 gitignore（不入库），同一台机器上很容易是几周前的
     * 残留，如果它每次都覆盖，设置页里改的值会在下次发布时被悄悄改回去——这正是"配置对不上"的成因。
     * 显式配置优先，`.env` 只作为初始默认值。
     *
     * @return 本次补入的变量名（不含值）
     */
    private List<String> seedFromProjectFiles(String projectId, String env) {
        if (repos == null) {
            return List.of();
        }
        Path root = repos.repoDir(projectId).orElse(null);
        if (root == null) {
            return List.of();
        }
        Map<String, String> declared = top.rongx.agent.domain.env.ProjectEnvFiles.scan(root).values();
        Map<String, String> current = store.effective(projectId, env).values();
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, String> e : declared.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (value == null || value.isBlank() || !KEY.matcher(key).matches()) {
                continue;
            }
            String existing = current.get(key);
            if (existing != null && !existing.isBlank()) {
                continue;
            }
            store.saveOverride(projectId, env, key, value, isSecret(key));
            written.add(key);
        }
        return written;
    }

    @Override
    public List<String> seedDeclared(String projectId, String env) {
        return seedFromProjectFiles(projectId, env);
    }

    @Override
    public List<String> syncEndpoints(String projectId, String env) {
        if (projectId == null || projectId.isBlank() || env == null || env.isBlank()
                || internalAddressing(env)) {
            return List.of();
        }
        Map<String, String> values = store.effective(projectId, env).values();
        List<String> updated = new ArrayList<>();
        for (EnvResource r : store.resources(env)) {
            if (r == null || !StackAdapter.isReferenced(r, values)) {
                continue;
            }
            // dev 用 host + 发布端口；同一次调用里把历史容器名纠正为 127.0.0.1
            applyOverrides(projectId, r, true);
            updated.add(r.name());
        }
        return updated;
    }

    // ---------------------------------------------------------------- 变量注入

    /**
     * 连接信息写入项目覆盖。
     *
     * @param forceEndpoint true = 地址/端口以容器实际值为准（容器由平台创建/复用），
     *                      其它键（库名/账号/密码）只在缺失时补，避免覆盖项目声明
     */
    private void applyOverrides(String projectId, EnvResource r, boolean forceEndpoint) {
        if (r == null || projectId == null || projectId.isBlank() || r.env() == null) {
            return;
        }
        EffectiveEnvironment effective = store.effective(projectId, r.env());
        for (Map.Entry<String, String> entry : derive(r, internalAddressing(r.env())).entrySet()) {
            String key = entry.getKey();
            String current = effective.values().get(key);
            boolean endpoint = forceEndpoint && ENDPOINT_KEYS.contains(key);
            if (!endpoint && current != null && !current.isBlank()) {
                continue;
            }
            store.saveOverride(projectId, r.env(), key, entry.getValue(), isSecret(key));
        }
    }

    /**
     * 是否用容器内网名寻址。
     *
     * <p>dev 是本机模式（应用跑在宿主机进程里），只能连 {@code 127.0.0.1:<发布端口>}；
     * 只有同网络的应用容器（test/prod）才用「容器名 + 容器端口」互访。
     * 这个判断必须和 {@code BuiltInPipelineEngine.localDevBranch} 的 dev 约定一致，
     * 否则设置页展示的地址与运行时实际注入值会分叉。
     */
    private static boolean internalAddressing(String env) {
        return !"dev".equalsIgnoreCase(env == null ? "" : env.trim());
    }

    static Map<String, String> derive(EnvResource r, boolean internalAddressing) {
        Map<String, String> out = new LinkedHashMap<>();
        // 平台为本项目创建的容器：应用容器与中间件同处一个环境网络，
        // 用「容器名 + 容器端口」互访（127.0.0.1 在容器里指向自己，不能用）。
        boolean internal = internalAddressing
                && "LOCAL_DOCKER".equals(r.source())
                && r.internalHost() != null && !r.internalHost().isBlank();
        String host = internal ? r.internalHost() : r.host();
        int port = internal && r.containerPort() > 0 ? r.containerPort() : r.port();
        switch (r.kind()) {
            case "mysql", "mariadb", "postgres" -> {
                put(out, "DB_HOST", host);
                put(out, "DB_PORT", String.valueOf(port));
                put(out, "DB_NAME", r.databaseName());
                put(out, "DB_USERNAME", r.username());
                put(out, "DB_PASSWORD", r.password());
            }
            case "redis" -> {
                put(out, "REDIS_HOST", host);
                put(out, "REDIS_PORT", String.valueOf(port));
                put(out, "REDIS_PASSWORD", r.password());
            }
            case "rabbitmq" -> {
                put(out, "RABBITMQ_HOST", host);
                put(out, "RABBITMQ_PORT", String.valueOf(port));
                put(out, "RABBITMQ_USERNAME", r.username());
                put(out, "RABBITMQ_PASSWORD", r.password());
            }
            case "minio" -> {
                put(out, "MINIO_ENDPOINT", "http://" + host + ":" + port);
                put(out, "MINIO_ACCESS_KEY", r.username());
                put(out, "MINIO_SECRET_KEY", r.password());
            }
            case "mongo" -> {
                put(out, "MONGO_HOST", host);
                put(out, "MONGO_PORT", String.valueOf(port));
                put(out, "MONGO_USERNAME", r.username());
                put(out, "MONGO_PASSWORD", r.password());
            }
            default -> {
                // 未知类型不猜变量
            }
        }
        return out;
    }

    /** 必填但不是中间件连接的密钥类变量（TOKEN_SECRET 等）缺失时生成随机值。 */
    private List<String> generateMissingSecrets(String projectId, String env,
                                                List<String> requiredKeys) {
        List<String> generated = new ArrayList<>();
        EffectiveEnvironment effective = store.effective(projectId, env);
        for (String key : requiredKeys) {
            if (kindOfKey(key) != null || !isSecret(key)) {
                continue;
            }
            String current = effective.values().get(key);
            if (current != null && !current.isBlank()) {
                continue;
            }
            store.saveOverride(projectId, env, key, generatePassword(), true);
            generated.add(key);
        }
        return generated;
    }

    // ---------------------------------------------------------------- Docker 辅助

    /**
     * Docker 运维操作（唯一入口）。
     *
     * <p>原先本类自带 {@code dockerProblem} / {@code ensureNetwork} / {@code liveRunning} /
     * {@code containerExists} / {@code removeContainer} 五份私有实现，而 {@code DockerNetwork}
     * 与 {@code AppRuntimeInspector} 里还各有一份语义相同的代码——超时从 15s 到 60s 不等、
     * 运行态判据也不一致。现在统一走 {@link DockerOps}，调用方只表达"要什么事实"。
     */
    private DockerOps ops() {
        return new DockerOps(docker.binary(), CWD);
    }

    private String dockerProblem() {
        return ops().available() ? null : "本机 Docker 不可用：docker info 探测失败";
    }

    private void ensureNetwork(String network) {
        if (!NAME.matcher(network).matches()) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "网络名不合法：" + network);
        }
        String problem = ops().ensureNetwork(network);
        if (problem != null) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                    "环境网络未就绪：" + problem);
        }
    }

    /** @return true/false 运行状态；null 表示容器不存在或 docker 不可用。 */
    private Boolean liveRunning(String name) {
        return ops().containerRunning(name).orElse(null);
    }

    private boolean containerExists(String name) {
        return ops().containerRunning(name).isPresent()
                || ops().run(ops().cmd("inspect", "--format", "{{.Name}}", name)).ok();
    }

    private void removeContainer(String name) {
        ops().removeContainer(name);
    }

    private void removeVolume(String name) {
        StringBuilder sink = new StringBuilder();
        executor.exec(CWD, docker.cmd("volume", "rm", "-f", name + "-data"), 60_000, sink,
                () -> false);
    }

    private boolean waitRunning(String name) {
        return ops().awaitRunning(name, WAIT_MS, POLL_MS, null);
    }

    /** 执行 docker run；成功返回 null，失败返回可读原因（不含密码）。 */
    private String runContainer(String kind, String name, int port, String database,
                                String username, String password, String network) {
        String image = imageOf(kind);
        // 启动前预检：缺镜像时 docker run 会自行拉取，网络不通就是一次长达 180s 的静默等待。
        // 先用 image inspect 在 5s 内确认，缺了直接给出可操作的两条路径。
        if (precheckImages && !ops().imageExists(image)) {
            return "环境中间件镜像未就绪（" + image + "）：请先执行 docker pull " + image
                    + "，或通过 agent.env.middleware-images." + kind
                    + " 指定本机已有的镜像；也可设 agent.env.precheck-images=false 关闭预检";
        }
        StringBuilder sink = new StringBuilder();
        ProcessExecutor.StepOutcome outcome =
                executor.exec(CWD, runArgv(docker, kind, name, port, database, username, password,
                        network, middlewareImages), TIMEOUT_MS, sink, () -> false);
        if (outcome.exitCode() != 0) {
            return ProcessExecutor.redact(ProcessExecutor.tail(sink));
        }
        if (!waitRunning(name)) {
            return "容器启动后未进入运行状态";
        }
        return null;
    }

    static List<String> runArgv(String kind, String name, int port, String database,
                                String username, String password, String network) {
        return runArgv(DockerCli.defaults(), kind, name, port, database, username, password,
                network, DEFAULT_MIDDLEWARE_IMAGES);
    }

    /** 带镜像覆盖的构造：镜像引用由配置决定，不再写死在 switch 里。 */
    static List<String> runArgv(String kind, String name, int port, String database,
                                String username, String password, String network,
                                Map<String, String> images) {
        return runArgv(DockerCli.defaults(), kind, name, port, database, username, password,
                network, images);
    }

    static List<String> runArgv(DockerCli docker, String kind, String name, int port,
                                String database, String username, String password,
                                String network, Map<String, String> images) {
        List<String> argv = new ArrayList<>(docker.cmd("run", "-d", "--name", name,
                "--restart", "unless-stopped", "--network", network,
                // 与应用容器同一套加固（G5）：中间件容器过去是整条链路里唯一零安全参数的容器，
                // 容器内提权后可横向移动到同网络的应用容器。
                // 中间件（mysql/redis/...）都不需要额外 capability，也不需要 setuid 提权。
                "--security-opt", "no-new-privileges",
                "--cap-drop", "ALL",
                // 只绑回环：中间件绝不对外暴露，应用容器走环境网络（<namespace>-net）访问。
                "-p", "127.0.0.1:" + port + ":" + defaultPort(kind),
                // 与应用容器同一 compose 组：Docker Desktop 里一个环境一个组
                "--label", "com.docker.compose.project="
                        + DockerDeployer.composeProject(network, null),
                "--label", "com.docker.compose.service=" + kind,
                "--label", "agent.env.middleware=" + kind));
        String volume = volumePath(kind);
        if (volume != null) {
            argv.add("-v");
            argv.add(name + "-data:" + volume);
        }
        String image = images.getOrDefault(kind, DEFAULT_MIDDLEWARE_IMAGES.getOrDefault(kind, ""));
        if (image.isBlank() || !IMAGE_REF.matcher(image).matches()) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "中间件 " + kind + " 的镜像引用未配置或非法：" + image);
        }
        switch (kind) {
            case "mysql", "mariadb" -> {
                argv.addAll(List.of("-e", "MYSQL_ROOT_PASSWORD=" + nullSafe(password),
                        "-e", "MYSQL_DATABASE=" + nullSafe(database)));
                argv.add(image);
            }
            case "postgres" -> {
                argv.addAll(List.of("-e", "POSTGRES_USER=" + nullSafe(username),
                        "-e", "POSTGRES_PASSWORD=" + nullSafe(password),
                        "-e", "POSTGRES_DB=" + nullSafe(database)));
                argv.add(image);
            }
            case "redis" -> {
                argv.add(image);
                if (password != null && !password.isBlank()) {
                    argv.addAll(List.of("redis-server", "--requirepass", password));
                }
            }
            case "rabbitmq" -> {
                argv.addAll(List.of("-e", "RABBITMQ_DEFAULT_USER=" + nullSafe(username),
                        "-e", "RABBITMQ_DEFAULT_PASS=" + nullSafe(password)));
                argv.add(image);
            }
            case "minio" -> {
                argv.addAll(List.of("-e", "MINIO_ROOT_USER=" + nullSafe(username),
                        "-e", "MINIO_ROOT_PASSWORD=" + nullSafe(password)));
                argv.add(image);
                argv.addAll(List.of("server", "/data"));
            }
            case "mongo" -> {
                argv.addAll(List.of("-e", "MONGO_INITDB_ROOT_USERNAME=" + nullSafe(username),
                        "-e", "MONGO_INITDB_ROOT_PASSWORD=" + nullSafe(password)));
                argv.add(image);
            }
            default -> throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "不支持的中间件类型：" + kind);
        }
        return argv;
    }

    // ---------------------------------------------------------------- 规格

    private static List<String> kindsOf(List<String> requiredKeys) {
        Set<String> kinds = new LinkedHashSet<>();
        if (requiredKeys == null) {
            return List.of();
        }
        for (String key : requiredKeys) {
            String kind = kindOfKey(key);
            if (kind != null) {
                kinds.add(kind);
            }
        }
        return List.copyOf(kinds);
    }

    /** 必填变量名 → 需要的中间件类型（没命中返回 null）。 */
    static String kindOfKey(String key) {
        String k = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
        if (k.startsWith("RABBITMQ")) {
            return "rabbitmq";
        }
        if (k.startsWith("MINIO")) {
            return "minio";
        }
        if (k.startsWith("REDIS")) {
            return "redis";
        }
        if (k.startsWith("MONGO")) {
            return "mongo";
        }
        if (k.startsWith("POSTGRES")) {
            return "postgres";
        }
        if (k.startsWith("MYSQL") || k.startsWith("MARIADB")) {
            return "mysql";
        }
        if (k.startsWith("DB_")) {
            // DB_* 无法从名字判断方言：保留历史默认（mysql），并在部署日志里提示显式声明。
            return "mysql";
        }
        return null;
    }

    static String containerName(String namespace, String projectId, String kind) {
        return slug(namespace) + "-" + slug(projectId) + "-" + kind;
    }

    private static String slug(String raw) {
        return top.rongx.agent.domain.text.Slugs.slug(raw);
    }

    private static String defaultDatabase(String projectId, String kind) {
        if (!"mysql".equals(kind) && !"mariadb".equals(kind) && !"postgres".equals(kind)) {
            return null;
        }
        String name = slug(projectId).replace('-', '_').replace('.', '_');
        return name.isBlank() ? "app" : name;
    }

    private static String defaultUser(String kind) {
        return switch (kind) {
            case "postgres" -> "postgres";
            case "mongo" -> "root";
            case "minio" -> "minioadmin";
            case "rabbitmq" -> "admin";
            default -> "root";
        };
    }

    private static boolean needsPassword(String kind) {
        return true;
    }

    static int defaultPort(String kind) {
        return switch (kind) {
            case "mysql", "mariadb" -> 3306;
            case "redis" -> 6379;
            case "rabbitmq" -> 5672;
            case "minio" -> 9000;
            case "postgres" -> 5432;
            case "mongo" -> 27017;
            default -> throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "不支持的中间件类型：" + kind);
        };
    }

    static String volumePath(String kind) {
        return switch (kind) {
            case "mysql", "mariadb" -> "/var/lib/mysql";
            case "redis" -> "/data";
            case "rabbitmq" -> "/var/lib/rabbitmq";
            case "minio" -> "/data";
            case "postgres" -> "/var/lib/postgresql/data";
            case "mongo" -> "/data/db";
            default -> null;
        };
    }

    private static int basePort(String kind) {
        return switch (kind) {
            case "mysql", "mariadb" -> 13306;
            case "redis" -> 16379;
            case "rabbitmq" -> 15673;
            case "minio" -> 19000;
            case "postgres" -> 15432;
            case "mongo" -> 17017;
            default -> throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "不支持的中间件类型：" + kind);
        };
    }

    /** 端口段内挑一个既没被平台登记、也没被系统占用的端口。 */
    private int allocatePort(String kind, String env, String projectId) {
        Set<Integer> used = new LinkedHashSet<>();
        for (EnvResource r : store.resources(env)) {
            if (r.port() > 0) {
                used.add(r.port());
            }
        }
        for (var lease : store.leases(projectId, env)) {
            if (lease.hostPort() > 0) {
                used.add(lease.hostPort());
            }
        }
        int base = basePort(kind);
        for (int offset = 0; offset < 100; offset++) {
            int candidate = base + offset;
            if (used.contains(candidate) || !free(candidate)) {
                continue;
            }
            return candidate;
        }
        throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, kind + " 端口段 " + base + "–" + (base + 99) + " 已耗尽");
    }

    /**
     * 端口是否空闲（走 {@link PortProbe}）。
     *
     * <p>中间件必须事先知道端口：它要写进应用的环境变量（{@code DB_PORT} 等），
     * 所以不能像应用容器那样交给 docker 分配。这是"必须探测"的正当场景之一。
     */
    private static boolean free(int port) {
        return PortProbe.isFree(port);
    }

    private EnvResource findByName(String env, String name) {
        return store.resources(env).stream()
                .filter(r -> name.equalsIgnoreCase(r.name()))
                .findFirst().orElse(null);
    }

    private static String generatePassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean isSecret(String key) {
        String k = key == null ? "" : key.toUpperCase(Locale.ROOT);
        return k.contains("PASSWORD") || k.contains("SECRET") || k.contains("TOKEN")
                || k.endsWith("_KEY") || k.contains("ACCESS_KEY");
    }

    private static void put(Map<String, String> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** 一个中间件容器应有的凭据（来自项目声明或平台生成）。 */
    record Creds(String database, String username, String password) {
    }
}
