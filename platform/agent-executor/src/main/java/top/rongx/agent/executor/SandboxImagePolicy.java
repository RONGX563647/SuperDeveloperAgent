package top.rongx.agent.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import top.rongx.agent.domain.error.SandboxDeniedException;

/**
 * 镜像准入策略：在 {@link ImageRef} 的<b>语法校验之上</b>回答"允许谁"。
 *
 * <p>两层职责必须分开，否则会出现"改了允许列表却漏掉参数注入"这类退化：
 * <ul>
 *   <li>{@link ImageRef}：拒绝非法字符与以 {@code -} 开头的 token（防 docker 参数注入）；</li>
 *   <li>本类：拒绝"合法但不应被接受"的镜像——浮动 tag（{@code :latest} / 无 tag）、
 *       不在允许列表里的 registry/repo。</li>
 * </ul>
 *
 * <p>为什么需要第二层：{@code stage.image} 与部署镜像名都来自仓库里的
 * {@code ci/pipeline.yml}（可能由模型或补丁写入）。语法校验只能保证"这不是一条 docker 选项"，
 * 拦不住 {@code image: evil.example.com/backdoor:latest} —— 那是一句语法完全合法的引用。
 *
 * <p>默认策略不改变现有行为（允许列表为空 = 只查语法，浮动 tag 允许），
 * 生产环境通过 {@code agent.sandbox.image-allowlist} 与
 * {@code agent.sandbox.allow-floating-tags=false} 收紧。
 */
final class SandboxImagePolicy {

    /** 镜像来源：决定"浮动 tag"是否可接受（平台配置的镜像可以跟 latest，仓库给的不能）。 */
    enum Source {
        /** 平台配置（{@code agent.sandbox.image} / 中间件镜像）：运维自己写的，允许浮动。 */
        CONFIG("平台配置"),
        /** 流水线 {@code stage.image}：仓库里的外部输入。 */
        STAGE("stage.image"),
        /** 部署镜像名（{@code image_package} 产物或 {@code deploy.services[].image}）。 */
        DEPLOY("部署镜像");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** 判定结论：{@code reason} 为空表示通过。 */
    record Decision(boolean allowed, String reason) {

        static Decision ok() {
            return new Decision(true, null);
        }

        static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    /** 允许列表（glob：{@code *} 任意字符；空 = 不限制仓库/registry）。 */
    private final List<String> allowlist;
    /** 是否允许 {@code :latest} 或无 tag 的镜像引用（默认允许，保持向后兼容）。 */
    private final boolean allowFloatingTags;

    SandboxImagePolicy(List<String> allowlist, boolean allowFloatingTags) {
        this.allowlist = allowlist == null ? List.of() : allowlist.stream()
                .map(item -> item == null ? "" : item.trim().toLowerCase(Locale.ROOT))
                .filter(item -> !item.isEmpty())
                .toList();
        this.allowFloatingTags = allowFloatingTags;
    }

    /** 默认策略：不限制（与引入本类之前的行为完全一致）。 */
    static SandboxImagePolicy permissive() {
        return new SandboxImagePolicy(List.of(), true);
    }

    /**
     * 校验一个镜像引用。
     *
     * @param image  已通过 {@link ImageRef#require} 的引用（本方法不重复做语法校验）
     * @param source 来源，用于报错与"浮动 tag 是否可接受"的判定
     * @throws SandboxDeniedException 策略不允许（E-707 / {@code Rule.IMAGE}）
     */
    void require(String image, Source source) {
        Decision decision = check(image, source);
        if (!decision.allowed()) {
            throw new SandboxDeniedException(SandboxDeniedException.Rule.IMAGE,
                    decision.reason(),
                    List.of("image-allowlist=" + (allowlist.isEmpty() ? "(不限)" : allowlist),
                            "allow-floating-tags=" + allowFloatingTags),
                    source == Source.CONFIG
                            ? "改 agent.sandbox.image / agent.env.middleware-images 指向允许的镜像"
                            : "在 ci/pipeline.yml 里换成带明确版本（或 digest）的允许镜像；"
                                    + "确需放开请由运维调整 agent.sandbox.image-allowlist");
        }
    }

    /**
     * 纯判定（不抛异常，便于单测与预览）。
     *
     * @param image  镜像引用
     * @param source 来源
     */
    Decision check(String image, Source source) {
        if (image == null || image.isBlank()) {
            return Decision.deny(source.label() + " 未指定镜像");
        }
        String value = image.trim();
        String lower = value.toLowerCase(Locale.ROOT);

        // 1. 浮动 tag：平台配置的镜像不拦（运维自己负责），仓库输入必须钉住版本
        if (!allowFloatingTags && source != Source.CONFIG && isFloating(value)) {
            return Decision.deny(source.label() + " 的镜像必须是明确版本或 digest（浮动 tag 不允许）：" + value);
        }

        // 2. 允许列表：空列表 = 只查语法
        if (allowlist.isEmpty()) {
            return Decision.ok();
        }
        for (String pattern : allowlist) {
            if (matches(lower, pattern)) {
                return Decision.ok();
            }
        }
        return Decision.deny(source.label() + " 的镜像不在允许列表内：" + value
                + "（允许：" + String.join(", ", allowlist) + "）");
    }

    /**
     * 是否浮动引用：无 tag 且无 digest，或 tag 是 {@code latest}。
     *
     * <p>判据要跳过 registry 的端口冒号（{@code localhost:5000/app} 不是 tag）。
     */
    static boolean isFloating(String image) {
        String value = image.trim();
        if (value.contains("@sha256:")) {
            return false;       // digest 固定，最严格的形式
        }
        int slash = value.lastIndexOf('/');
        int colon = value.lastIndexOf(':');
        if (colon <= slash) {
            return true;        // 冒号在最后一个 / 之前（或不存在）→ 没有 tag
        }
        return "latest".equalsIgnoreCase(value.substring(colon + 1));
    }

    /** glob 匹配：{@code *} 匹配任意长度，其余按字面（正则元字符已转义）。 */
    private static boolean matches(String value, String pattern) {
        return java.util.regex.Pattern
                .compile(toRegex(pattern), java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(value)
                .matches();
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.append('$').toString();
    }

    /** 供设置页展示当前策略（不含敏感信息）。 */
    List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("allow-floating-tags=" + allowFloatingTags);
        out.add("image-allowlist=" + (allowlist.isEmpty() ? "(不限)" : String.join(",", allowlist)));
        return List.copyOf(out);
    }
}
