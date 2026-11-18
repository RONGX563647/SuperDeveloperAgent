package top.rongx.agent.executor;

import java.util.regex.Pattern;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RecipeException;

/**
 * 镜像引用的可执行校验。
 *
 * <p><b>为什么必须有</b>：镜像名最终是 {@code docker run ... <image> <argv>} 里的一个位置参数，
 * 而 docker CLI 把以 {@code -} 开头的 token 当**选项**解析。流水线定义里的
 * {@code stage.image} 来自仓库里的 {@code .agent/pipeline.yml}（可能由模型或补丁写入），
 * 若原样拼进去，{@code image: --privileged} 或 {@code image: --volume=/:/host}
 * 会被 docker 当成自己的参数，直接把沙箱的隔离拆掉——这比"镜像拉不到"严重得多。
 *
 * <p>因此镜像名只允许 registry/repo/tag/digest 的合法字符，且**不能以 {@code -} 开头**；
 * 参数注入在这一层被拒绝，而不是靠 docker 的参数解析顺序侥幸挡住。
 *
 * <p>沙箱（{@link DockerSandbox}）与部署器（{@link DockerDeployer}）共用本类：
 * 两条路径都是"把外部给的镜像名交给 docker CLI"，校验点必须一致。
 */
final class ImageRef {

    /**
     * 合法镜像引用：{@code [registry[:port]/]repo[/repo...][:tag][@sha256:digest]}。
     *
     * <p>比 docker 官方 grammar 略严（repo 段不允许大写、不允许空白），
     * 目的是让"写错了"在定义解析阶段就失败，而不是在 docker run 时报一句难懂的错。
     */
    private static final Pattern VALID = Pattern.compile(
            "^(?:[a-zA-Z0-9](?:[a-zA-Z0-9._-]*[a-zA-Z0-9])?(?::\\d{1,5})?/)?"
                    + "[a-z0-9]+(?:[._-][a-z0-9]+)*"
                    + "(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*"
                    + "(?::[A-Za-z0-9_][A-Za-z0-9._-]{0,127})?"
                    + "(?:@sha256:[a-f0-9]{64})?$");

    /** docker 侧对镜像引用长度的上限。 */
    private static final int MAX_LENGTH = 255;

    private ImageRef() {
    }

    /**
     * 校验并返回镜像引用。
     *
     * @param image  镜像名，可含 tag / digest
     * @param source 出错时写进消息的来源说明（如 {@code agent.sandbox.image}）
     * @throws RecipeException 引用非法（E-201：属于定义/配置错误，而不是运行时故障）
     */
    static String require(String image, String source) {
        if (image == null || image.isBlank()) {
            throw new RecipeException(ErrorCode.ARGV_INVALID, source + " 未配置镜像");
        }
        String trimmed = image.trim();
        // 先挡最危险的一类：以 - 开头的 token 必然被 docker 当成选项
        if (trimmed.startsWith("-")) {
            throw new RecipeException(ErrorCode.ARGV_INVALID,
                    source + " 的镜像不能以 - 开头（会被 docker 当成命令行选项）：" + trimmed);
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new RecipeException(ErrorCode.ARGV_INVALID,
                    source + " 的镜像名过长（超过 " + MAX_LENGTH + " 字符）");
        }
        if (trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new RecipeException(ErrorCode.ARGV_INVALID,
                    source + " 的镜像名不能包含空白：" + trimmed);
        }
        if (!VALID.matcher(trimmed).matches()) {
            throw new RecipeException(ErrorCode.ARGV_INVALID,
                    source + " 的镜像名非法（只允许 registry/repo[:tag][@sha256:...]）：" + trimmed);
        }
        return trimmed;
    }
}
