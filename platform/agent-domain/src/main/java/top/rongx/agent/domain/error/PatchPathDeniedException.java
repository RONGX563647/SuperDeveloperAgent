package top.rongx.agent.domain.error;

import java.util.List;

/**
 * 补丁路径被守卫拒绝。
 *
 * <p>为什么要单独一个类型：模型只能看到工具返回的字符串，笼统的「不允许修改该路径」
 * 会让它反复重试同一个路径。这里把「被拒路径、命中的拒绝规则、允许前缀清单」
 * 一起带出去，调用方可以渲染成结构化的 {@code PATH_DENIED} 结果。
 */
public class PatchPathDeniedException extends RunException {

    /** 结构化原因码：模型与前端据此区分「路径被拒」和「补丁本身非法」。 */
    public static final String REASON_CODE = "PATH_DENIED";

    private final String path;
    private final String rule;
    private final List<String> allowedPatterns;
    private final List<String> deniedPatterns;

    public PatchPathDeniedException(String path, String rule, List<String> allowedPatterns,
                                    List<String> deniedPatterns) {
        super(ErrorCode.RUN_INVALID_INPUT, buildMessage(path, rule));
        this.path = path;
        this.rule = rule;
        this.allowedPatterns = List.copyOf(allowedPatterns);
        this.deniedPatterns = List.copyOf(deniedPatterns);
    }

    /** 被拒绝的仓库相对路径。 */
    public String path() {
        return path;
    }

    /** 命中的拒绝原因（废路径 / 逃逸 / 命中禁止规则 / 不在允许清单）。 */
    public String rule() {
        return rule;
    }

    public List<String> allowedPatterns() {
        return allowedPatterns;
    }

    public List<String> deniedPatterns() {
        return deniedPatterns;
    }

    /** 结构化正文：调用方原样回给模型即可，不需要再拼说明。 */
    public String structured() {
        // strip() 很关键：文本块以换行开头，不去掉就会变成 "err: \nPATH_DENIED"，
        // 模型与测试都按首行读原因码。
        return """
                %s
                path: %s
                reason: %s
                allowed: %s
                denied: %s
                next: 换一个在 allowed 里的路径，或先用 read 类工具确认文件位置；不要重复提交同一路径
                """.formatted(REASON_CODE, path, rule,
                String.join(", ", allowedPatterns), String.join(", ", deniedPatterns)).strip();
    }

    private static String buildMessage(String path, String rule) {
        return "不允许修改该路径：" + path + "（" + rule + "）";
    }
}
