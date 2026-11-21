package top.rongx.agent.executor;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code agent.sandbox.*} 的全部配置项（唯一的声明点）。
 *
 * <p>为什么从 23 个 {@code @Value} 改成这里：原实现把旋钮散在 bean 方法签名里，
 * 三个问题都真实发生过：
 * <ul>
 *   <li><b>加旋钮要改三处</b>（yml、方法签名、装配链），漏一处就是静默不生效；</li>
 *   <li><b>没有校验</b>：{@code memory=2}（少了 {@code g}）、{@code pids-limit=0}、
 *       {@code cpus=abc} 全都要等到 {@code docker run} 才失败，报的还是 CLI 的原始错误；</li>
 *   <li><b>不可枚举</b>：设置页/排障想知道"沙箱有哪些旋钮"只能去翻方法签名。</li>
 * </ul>
 *
 * <p>刻意<b>不</b>用 {@code record}：Spring Boot 的宽松绑定对 record 支持较新，
 * 而这里需要"缺省值来自 yml、空串归一为空"的构造期规整（见 {@code normalize()}）。
 * 保持可变 POJO + setter 是这一层最不容易出意外的写法。
 *
 * <p>{@link #normalize()} 在装配时统一做一次：把非法/空值收敛成安全默认，
 * 而不是让每个 setter 各自判断（那又会退化成散落各处的兜底）。
 */
@ConfigurationProperties(prefix = "agent.sandbox")
public class SandboxProperties {

    /** 执行模式：docker（默认，fail-closed）| process（仅本地开发）。 */
    private String mode = "docker";

    private String dockerBinary = "docker";
    private String image = "maven:3.9-eclipse-temurin-21";
    private String network = "bridge";
    private String memory = "2g";
    private String cpus = "2";
    private int pidsLimit = 512;
    private String user = "1000:1000";
    private String cacheVolumePrefix = "";

    private boolean precheckImage = true;
    private long pullTimeoutSeconds = 900;
    private long pullIdleTimeoutSeconds = 60;
    private int pullAttempts = 2;
    private long pullBackoffSeconds = 60;
    private long pullBackoffMaxSeconds = 900;
    private String pullMirrors = "";

    private String host = "";
    private String npmRegistry = "";
    private String runtime = "";
    private String initImage = "busybox:1.36";

    private boolean allowOffline;
    private String imageAllowlist = "";
    private boolean allowFloatingTags = true;

    /**
     * 把配置规整成可安全使用的值，并返回发现的配置问题（用于启动期告警）。
     *
     * <p>只做"不静默用错值"的收敛，不抛异常：配置错误应该变成一条明确的启动日志 +
     * 安全默认，而不是让整个平台起不来（沙箱只是平台的一个能力面）。
     * 真正必须阻断的情形（mode 非法、docker 不可用）由 {@code SandboxConfig} 负责。
     */
    public List<String> normalize() {
        List<String> problems = new ArrayList<>();

        dockerBinary = blankTo(dockerBinary, "docker");
        image = blankTo(image, "maven:3.9-eclipse-temurin-21");
        network = blankTo(network, "bridge");
        // --memory 只接受 docker 的尺寸语法。<b>单位必填</b>：docker 确实接受纯字节数
        // （--memory 2 就是 2 字节），但在这份配置里写 "2" 几乎一定是想写 "2g" 漏了单位——
        // 真的给容器 2 字节内存会让它立刻 OOM，且报错完全看不出是配置问题。
        // 宁可在这里拦下并告警，也不要让"漏一个字母"变成一次神秘的构建失败。
        if (memory == null || !memory.trim().matches("[1-9][0-9]*[bkmgBKMG]")) {
            problems.add("agent.sandbox.memory 非法：" + memory
                    + "（必须带单位，如 2g / 512m；纯数字会被当成字节数），已回落到 2g");
            memory = "2g";
        } else {
            memory = memory.trim();
        }
        // --cpus 只接受正数（小数表示核数的分数）
        if (cpus == null || !cpus.trim().matches("[0-9]+(\\.[0-9]+)?")) {
            problems.add("agent.sandbox.cpus 非法：" + cpus + "（应为 2 / 1.5），已回落到 2");
            cpus = "2";
        } else {
            cpus = cpus.trim();
        }
        // pids-limit 必须 > 0：0 在 docker 里表示"无限制"，那正好抵消了这条隔离
        if (pidsLimit <= 0) {
            problems.add("agent.sandbox.pids-limit 必须 > 0（0 表示无限制，会抵消隔离）："
                    + pidsLimit + "，已回落到 512");
            pidsLimit = 512;
        }
        user = blankTo(user, "1000:1000");
        cacheVolumePrefix = cacheVolumePrefix == null ? "" : cacheVolumePrefix.trim();

        // 超时/重试必须为正：0 或负数会让"等待"变成"立即失败"
        if (pullTimeoutSeconds <= 0) {
            pullTimeoutSeconds = 900;
        }
        if (pullIdleTimeoutSeconds <= 0) {
            pullIdleTimeoutSeconds = 60;
        }
        if (pullAttempts <= 0) {
            pullAttempts = 1;
        }
        if (pullBackoffSeconds <= 0) {
            pullBackoffSeconds = 60;
        }
        if (pullBackoffMaxSeconds < pullBackoffSeconds) {
            pullBackoffMaxSeconds = pullBackoffSeconds;
        }

        pullMirrors = pullMirrors == null ? "" : pullMirrors.trim();
        host = host == null ? "" : host.trim();
        npmRegistry = npmRegistry == null ? "" : npmRegistry.trim();
        runtime = runtime == null ? "" : runtime.trim();
        initImage = blankTo(initImage, "busybox:1.36");
        imageAllowlist = imageAllowlist == null ? "" : imageAllowlist.trim();
        mode = blankTo(mode, "docker");
        return List.copyOf(problems);
    }

    /** 供启动日志用的可审计快照（不含任何凭据，本类也没有凭据）。 */
    public String describe() {
        return "mode=" + mode + " image=" + image + " network=" + network
                + " user=" + user + " memory=" + memory + " cpus=" + cpus
                + " pids=" + pidsLimit + " runtime=" + (runtime.isBlank() ? "runc" : runtime)
                + " allowOffline=" + allowOffline;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getDockerBinary() {
        return dockerBinary;
    }

    public void setDockerBinary(String dockerBinary) {
        this.dockerBinary = dockerBinary;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public String getCpus() {
        return cpus;
    }

    public void setCpus(String cpus) {
        this.cpus = cpus;
    }

    public int getPidsLimit() {
        return pidsLimit;
    }

    public void setPidsLimit(int pidsLimit) {
        this.pidsLimit = pidsLimit;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getCacheVolumePrefix() {
        return cacheVolumePrefix;
    }

    public void setCacheVolumePrefix(String cacheVolumePrefix) {
        this.cacheVolumePrefix = cacheVolumePrefix;
    }

    public boolean isPrecheckImage() {
        return precheckImage;
    }

    public void setPrecheckImage(boolean precheckImage) {
        this.precheckImage = precheckImage;
    }

    public long getPullTimeoutSeconds() {
        return pullTimeoutSeconds;
    }

    public void setPullTimeoutSeconds(long pullTimeoutSeconds) {
        this.pullTimeoutSeconds = pullTimeoutSeconds;
    }

    public long getPullIdleTimeoutSeconds() {
        return pullIdleTimeoutSeconds;
    }

    public void setPullIdleTimeoutSeconds(long pullIdleTimeoutSeconds) {
        this.pullIdleTimeoutSeconds = pullIdleTimeoutSeconds;
    }

    public int getPullAttempts() {
        return pullAttempts;
    }

    public void setPullAttempts(int pullAttempts) {
        this.pullAttempts = pullAttempts;
    }

    public long getPullBackoffSeconds() {
        return pullBackoffSeconds;
    }

    public void setPullBackoffSeconds(long pullBackoffSeconds) {
        this.pullBackoffSeconds = pullBackoffSeconds;
    }

    public long getPullBackoffMaxSeconds() {
        return pullBackoffMaxSeconds;
    }

    public void setPullBackoffMaxSeconds(long pullBackoffMaxSeconds) {
        this.pullBackoffMaxSeconds = pullBackoffMaxSeconds;
    }

    public String getPullMirrors() {
        return pullMirrors;
    }

    public void setPullMirrors(String pullMirrors) {
        this.pullMirrors = pullMirrors;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getNpmRegistry() {
        return npmRegistry;
    }

    public void setNpmRegistry(String npmRegistry) {
        this.npmRegistry = npmRegistry;
    }

    public String getRuntime() {
        return runtime;
    }

    public void setRuntime(String runtime) {
        this.runtime = runtime;
    }

    public String getInitImage() {
        return initImage;
    }

    public void setInitImage(String initImage) {
        this.initImage = initImage;
    }

    public boolean isAllowOffline() {
        return allowOffline;
    }

    public void setAllowOffline(boolean allowOffline) {
        this.allowOffline = allowOffline;
    }

    public String getImageAllowlist() {
        return imageAllowlist;
    }

    public void setImageAllowlist(String imageAllowlist) {
        this.imageAllowlist = imageAllowlist;
    }

    public boolean isAllowFloatingTags() {
        return allowFloatingTags;
    }

    public void setAllowFloatingTags(boolean allowFloatingTags) {
        this.allowFloatingTags = allowFloatingTags;
    }
}
