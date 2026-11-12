package top.rongx.agent.executor;

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
import top.rongx.agent.domain.env.MiddlewareKind;
import top.rongx.agent.domain.env.RuntimeMode;
import top.rongx.agent.domain.env.SecretNames;
import top.rongx.agent.domain.env.StackAdapter;
import top.rongx.agent.domain.net.PortProbe;

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
    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(1500);
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Set<String> ENDPOINT_KEYS = Set.of("DB_HOST", "DB_PORT", "REDIS_HOST",
            "REDIS_PORT", "RABBITMQ_HOST", "RABBITMQ_PORT", "MINIO_ENDPOINT", "MONGO_HOST",
            "MONGO_PORT");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EnvironmentStore store;
    private final top.rongx.agent.domain.port.ProjectRepoLocator repos;
    private final ProcessExecutor executor = new ProcessExecutor();

    public DockerEnvProvisioner(EnvironmentStore store) {
        this(store, null);
    }

    public DockerEnvProvisioner(EnvironmentStore store,
                                top.rongx.agent.domain.port.ProjectRepoLocator repos) {
        this.store = store;
        this.repos = repos;
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
            password = generatePassword();
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
        return PortProbe.open(cleanHost, number, (int) PROBE_TIMEOUT.toMillis());
    }

    private static String hostKey(String kind) {
        MiddlewareKind k = MiddlewareKind.of(kind).orElse(null);
        return k == null ? "DB_HOST" : k.hostKey;
    }

    private static String portKey(String kind) {
        MiddlewareKind k = MiddlewareKind.of(kind).orElse(null);
        return k == null ? "DB_PORT" : k.portKey;
    }

    private static String databaseKey(String kind) {
        MiddlewareKind k = MiddlewareKind.of(kind).orElse(null);
        return k == null ? "DB_NAME" : k.databaseKey;
    }

    private static String usernameKey(String kind) {
        MiddlewareKind k = MiddlewareKind.of(kind).orElse(null);
        return k == null ? "DB_USERNAME" : k.usernameKey;
    }

    private static String passwordKey(String kind) {
        MiddlewareKind k = MiddlewareKind.of(kind).orElse(null);
        return k == null ? "DB_PASSWORD" : k.passwordKey;
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
        if (r == null) {
            return new LinkedHashMap<>();
        }
        // 平台为本项目创建的容器：应用容器与中间件同处一个环境网络，
        // 用「容器名 + 容器端口」互访（127.0.0.1 在容器里指向自己，不能用）。
        boolean internal = internalAddressing
                && "LOCAL_DOCKER".equals(r.source())
                && r.internalHost() != null && !r.internalHost().isBlank();
        String host = internal ? r.internalHost() : r.host();
        int port = internal && r.containerPort() > 0 ? r.containerPort() : r.port();
        return MiddlewareKind.of(r.kind())
                .map(kind -> kind.derive(r, host, port))
                .orElseGet(LinkedHashMap::new);
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

    private String dockerProblem() {
        StringBuilder sink = new StringBuilder();
        ProcessExecutor.StepOutcome outcome =
                executor.exec(CWD, List.of("docker", "version", "--format", "{{.Server.Version}}"),
                        20_000, sink, ProcessExecutor.NEVER);
        if (outcome.exitCode() == 0) {
            return null;
        }
        return "本机 Docker 不可用：" + ProcessExecutor.redact(ProcessExecutor.tail(sink));
    }

    private void ensureNetwork(String network) {
        if (!NAME.matcher(network).matches()) {
            throw new IllegalArgumentException("网络名不合法：" + network);
        }
        StringBuilder sink = new StringBuilder();
        ProcessExecutor.StepOutcome inspect =
                executor.exec(CWD, List.of("docker", "network", "inspect", network), 15_000,
                        sink, ProcessExecutor.NEVER);
        if (inspect.exitCode() == 0) {
            return;
        }
        ProcessExecutor.StepOutcome created = executor.exec(CWD,
                List.of("docker", "network", "create", network), 30_000, sink, ProcessExecutor.NEVER);
        if (created.exitCode() != 0) {
            throw new IllegalStateException("环境网络创建失败 " + network + "："
                    + ProcessExecutor.redact(ProcessExecutor.tail(sink)));
        }
    }

    /** @return true/false 运行状态；null 表示容器不存在或 docker 不可用。 */
    private Boolean liveRunning(String name) {
        StringBuilder sink = new StringBuilder();
        ProcessExecutor.StepOutcome outcome = executor.exec(CWD,
                List.of("docker", "inspect", "--format", "{{.State.Running}}", name), 15_000,
                sink, ProcessExecutor.NEVER);
        if (outcome.exitCode() != 0) {
            return null;
        }
        return "true".equalsIgnoreCase(ProcessExecutor.tail(sink).trim());
    }

    private boolean containerExists(String name) {
        StringBuilder sink = new StringBuilder();
        ProcessExecutor.StepOutcome outcome = executor.exec(CWD,
                List.of("docker", "inspect", "--format", "{{.Name}}", name), 15_000, sink,
                ProcessExecutor.NEVER);
        return outcome.exitCode() == 0;
    }

    private void removeContainer(String name) {
        StringBuilder sink = new StringBuilder();
        executor.exec(CWD, List.of("docker", "rm", "-f", name), 60_000, sink, ProcessExecutor.NEVER);
    }

    private void removeVolume(String name) {
        StringBuilder sink = new StringBuilder();
        executor.exec(CWD, List.of("docker", "volume", "rm", "-f", name + "-data"), 60_000, sink,
                ProcessExecutor.NEVER);
    }

    private boolean waitRunning(String name) {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(liveRunning(name))) {
                return true;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return Boolean.TRUE.equals(liveRunning(name));
    }

    /** 执行 docker run；成功返回 null，失败返回可读原因（不含密码）。 */
    private String runContainer(String kind, String name, int port, String database,
                                String username, String password, String network) {
        StringBuilder sink = new StringBuilder();
        ProcessExecutor.StepOutcome outcome =
                executor.exec(CWD, runArgv(kind, name, port, database, username, password, network),
                        TIMEOUT_MS, sink, ProcessExecutor.NEVER);
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
        List<String> argv = new ArrayList<>(List.of("docker", "run", "-d", "--name", name,
                "--restart", "unless-stopped", "--network", network,
                // 与应用容器同一 compose 组：Docker Desktop 里一个环境一个组
                "--label", "com.docker.compose.project="
                        + DockerDeployer.composeProject(network, null),
                "--label", "com.docker.compose.service=" + kind,
                "-p", "127.0.0.1:" + port + ":" + defaultPort(kind)));
        String volume = volumePath(kind);
        if (volume != null) {
            argv.add("-v");
            argv.add(name + "-data:" + volume);
        }
        MiddlewareKind spec = MiddlewareKind.of(kind)
                .orElseThrow(() -> new IllegalArgumentException("不支持的中间件类型：" + kind));
        argv.addAll(spec.dockerEnvArgs(database, username, password));
        argv.add(spec.image);
        argv.addAll(spec.containerCommand(password));
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
        return MiddlewareKind.fromKey(key).map(k -> k.wireName).orElse(null);
    }

    static String containerName(String namespace, String projectId, String kind) {
        return slug(namespace) + "-" + slug(projectId) + "-" + kind;
    }

    private static String slug(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "-");
        while (value.startsWith("-") || value.startsWith(".")) {
            value = value.substring(1);
        }
        return value;
    }

    private static String defaultDatabase(String projectId, String kind) {
        MiddlewareKind k = MiddlewareKind.of(kind).orElse(null);
        if (k == null || !k.relational) {
            return null;
        }
        String name = slug(projectId).replace('-', '_').replace('.', '_');
        return name.isBlank() ? "app" : name;
    }

    private static String defaultUser(String kind) {
        return MiddlewareKind.of(kind).map(k -> k.defaultUser).orElse("root");
    }

    private static int defaultPort(String kind) {
        return MiddlewareKind.of(kind)
                .orElseThrow(() -> new IllegalArgumentException("不支持的中间件类型：" + kind))
                .containerPort;
    }

    private static String volumePath(String kind) {
        return MiddlewareKind.of(kind).map(k -> k.volumePath).orElse(null);
    }

    private static int basePort(String kind) {
        return MiddlewareKind.of(kind)
                .orElseThrow(() -> new IllegalArgumentException("不支持的中间件类型：" + kind))
                .hostPortBase;
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
            if (used.contains(candidate) || !PortProbe.free("127.0.0.1", candidate)) {
                continue;
            }
            return candidate;
        }
        throw new IllegalStateException(kind + " 端口段 " + base + "–" + (base + 99) + " 已耗尽");
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
        return SecretNames.isSecret(key);
    }

    /** 一个中间件容器应有的凭据（来自项目声明或平台生成）。 */
    private record Creds(String database, String username, String password) {
    }
}
