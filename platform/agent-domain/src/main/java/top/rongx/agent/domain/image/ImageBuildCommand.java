package top.rongx.agent.domain.image;

import java.util.ArrayList;
import java.util.List;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RecipeException;

/**
 * 镜像构建命令的<b>唯一来源</b>。
 *
 * <p>为什么放在 domain 层：{@code image_package} 的命令字面量同时被配方解析
 * （{@code agent-infra/RecipeActionResolver}，内置默认配方）与骨架生成
 * （{@code agent-infra/RecipeScaffolder}，写进仓库的 {@code ci/actions.yml}）引用，
 * 而 infra 不依赖 executor。放进 domain 是唯一能让两边共用而不引入反向依赖的位置。
 *
 * <p>曾经的三份字面量已经漂移：内置配方与骨架是 {@code docker buildx build --load}，
 * 而仓库里已固化的动作文件仍是 {@code docker build}。BuildKit 不加 {@code --load} 时
 * 镜像只留在构建缓存里 —— 本地按老配方跑能出镜像、流水线跑完 {@code docker run} 却报
 * {@code No such image}，正是这种漂移造成的。
 *
 * <p>刻意不引入"构建后端枚举"：当前就是 BuildKit，多一层抽象只会让三处各自再解释一遍。
 */
public final class ImageBuildCommand {

    /** 项目根下的 Dockerfile 约定名（与 {@code DockerfileTools} 一致）。 */
    public static final String DEFAULT_DOCKERFILE = "Dockerfile";

    private ImageBuildCommand() {
    }

    /**
     * 项目根有 {@code Dockerfile} 时的标准构建命令。
     *
     * <p>{@code --load} 必须有：BuildKit 默认只把结果留在构建缓存，不导回 docker 镜像库，
     * 后续 {@code docker run} / {@code docker save} 会报 {@code No such image}。
     * {@code -q} 让日志可读（否则每层刷屏，动作日志截断后看不到真实报错）。
     */
    public static List<String> standard(String tag) {
        return List.of("docker", "buildx", "build", "--load", "-q", "-t", requireTag(tag), ".");
    }

    /**
     * 指定 Dockerfile 与构建上下文的构建命令（monorepo 子模块 / 非默认文件名）。
     *
     * @param dockerfile 相对仓库根的 Dockerfile 路径，如 {@code backend/Dockerfile}
     * @param tag        镜像 tag
     * @param context    相对仓库根的构建上下文，如 {@code backend}；空则用 {@code .}
     */
    public static List<String> withDockerfile(String dockerfile, String tag, String context) {
        List<String> argv = new ArrayList<>(List.of("docker", "buildx", "build", "--load", "-q"));
        argv.add("-f");
        argv.add(requireToken(dockerfile, "dockerfile"));
        argv.add("-t");
        argv.add(requireTag(tag));
        argv.add(requireToken(context == null || context.isBlank() ? "." : context, "构建上下文"));
        return List.copyOf(argv);
    }

    /** 渲染成单行命令（写进 {@code ci/actions.yml} 的 yml 里需要字符串形态）。 */
    public static String render(List<String> argv) {
        return String.join(" ", argv);
    }

    /**
     * tag 校验：最终是 {@code docker build -t} 之后的位置参数，以 {@code -} 开头会被当成选项。
     *
     * <p>必须放行 {@code ${tag}} 这类<b>占位符</b>：内置配方与骨架生成的都是模板，
     * 真正的 tag 由配方引擎在注入步骤前替换（{@code RecipeActionResolver} 的 params）。
     * 占位符形态本身也要校验（只允许 {@code [A-Za-z0-9_.-]}），否则
     * {@code ${tag}} 被替换前就可能夹带空白或选项前缀。
     *
     * <p>不复用 executor 的 {@code ImageRef}：那里要求完整的 {@code repo[:tag]}，
     * 而流水线传进来的是裸 tag（{@code ci-abc1234}）。
     */
    private static String requireTag(String tag) {
        String value = tag == null ? "" : tag.trim();
        boolean placeholder = value.startsWith("${") && value.endsWith("}")
                && value.substring(2, value.length() - 1).matches("[A-Za-z0-9_.-]{1,64}");
        if (!placeholder && !value.matches("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}")) {
            // 用平台异常而不是裸 IllegalArgumentException：错误码契约要求所有对外失败
            // 都带分组错误码（E-xxx），否则页面/自愈无法分类（spec-checks EC5）。
            throw new RecipeException(ErrorCode.RECIPE_INVALID_INPUT, "镜像 tag 非法：" + tag);
        }
        return value;
    }

    /** 路径/上下文校验：不允许以 {@code -} 开头（会被 docker 当选项），不允许空白。 */
    private static String requireToken(String value, String what) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("-")
                || trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new RecipeException(ErrorCode.RECIPE_INVALID_INPUT, what + " 非法：" + value);
        }
        return trimmed;
    }
}
