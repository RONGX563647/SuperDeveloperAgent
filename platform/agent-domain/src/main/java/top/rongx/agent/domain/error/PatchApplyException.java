package top.rongx.agent.domain.error;

import java.util.List;

/**
 * 修复片段无法唯一应用；message 里带文件、原因与候选行号，可直接回灌给模型。
 */
public class PatchApplyException extends RunException {

    private final String file;
    private final String reason;
    private final List<Integer> candidateLines;

    public PatchApplyException(String file, String reason, List<Integer> candidateLines) {
        super(ErrorCode.RUN_INVALID_INPUT, buildMessage(file, reason, candidateLines));
        this.file = file;
        this.reason = reason;
        this.candidateLines = List.copyOf(candidateLines);
    }

    public String file() {
        return file;
    }

    public String reason() {
        return reason;
    }

    public List<Integer> candidateLines() {
        return candidateLines;
    }

    private static String buildMessage(String file, String reason, List<Integer> candidates) {
        StringBuilder text = new StringBuilder("片段替换失败：").append(file)
                .append("（").append(reason).append("）");
        if (!candidates.isEmpty()) {
            text.append("；最接近的位置在第 ")
                    .append(String.join(", ", candidates.stream().map(String::valueOf).toList()))
                    .append(" 行，请据此提供包含完整上下文的片段");
        } else {
            text.append("；文件中找不到相近内容，请确认文件与片段是否来自同一基线提交");
        }
        return text.toString();
    }
}
