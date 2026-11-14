package top.rongx.agent.gateway.bugfix;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 修复片段的稳健替换。
 *
 * <p>模型给的 {@code old} 片段经常和仓库里实际内容差一点：换行是 CRLF、行尾多了空格、
 * 缩进层级对不上。这里按"由严到宽"四级匹配：
 * <ol>
 *   <li>精确匹配（必须唯一）；</li>
 *   <li>统一换行符 + 去行尾空白后按行匹配；</li>
 *   <li>再忽略每行缩进后按行匹配；</li>
 *   <li>仍失败则报结构化失败（文件、原因、最接近的候选行号），供修复阶段回灌重试。</li>
 * </ol>
 *
 * <p>无论哪一级，都要求<b>唯一命中</b>：多处命中直接失败，避免"猜错位置改坏代码"。
 */
public final class PatchApplier {

    private PatchApplier() {
    }

    /** 片段无法唯一应用；message 里带文件、原因与候选行号，可直接回灌给模型。 */
    public static class ApplyException extends IllegalArgumentException {

        private final String file;
        private final String reason;
        private final List<Integer> candidateLines;

        ApplyException(String file, String reason, List<Integer> candidateLines) {
            super(buildMessage(file, reason, candidateLines));
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

    /**
     * 在 {@code current} 中用 {@code oldText} 定位并替换成 {@code newText}。
     *
     * @throws ApplyException 片段找不到或多处命中
     */
    public static String replace(String path, String current, String oldText, String newText) {
        String replacement = newText == null ? "" : newText;
        // 1. 精确匹配
        int exact = current.indexOf(oldText);
        if (exact >= 0) {
            if (current.indexOf(oldText, exact + oldText.length()) >= 0) {
                throw new ApplyException(path, "片段在文件中出现多次，需要更长的唯一上下文",
                        occurrences(current, oldText, 5));
            }
            return current.substring(0, exact) + replacement + current.substring(exact + oldText.length());
        }
        List<Line> lines = splitLines(current);
        // 片段末尾带换行时先去掉，避免多要求匹配一个空行
        String lineTarget = oldText.endsWith("\n")
                ? oldText.substring(0, oldText.length() - 1) : oldText;
        List<String> oldLines = splitLines(lineTarget).stream().map(Line::text).toList();
        // 2. 统一换行/去行尾空白；3. 再忽略缩进
        for (boolean ignoreIndent : new boolean[]{false, true}) {
            List<Integer> matches = findRuns(lines, oldLines, ignoreIndent);
            if (matches.size() == 1) {
                int startLine = matches.get(0);
                int endLine = startLine + oldLines.size() - 1;
                int start = lines.get(startLine).start();
                int end = lines.get(endLine).end();
                return current.substring(0, start) + replacement + current.substring(end);
            }
            if (matches.size() > 1) {
                throw new ApplyException(path, "片段按行匹配到多处（" + matches.size()
                        + " 处），需要更长的唯一上下文", matches.stream().map(i -> i + 1).toList());
            }
        }
        throw new ApplyException(path, "片段与基线文件内容不一致（换行/缩进/上下文均不匹配）",
                candidates(lines, oldLines));
    }

    private static List<Integer> findRuns(List<Line> lines, List<String> oldLines, boolean ignoreIndent) {
        List<Integer> matches = new ArrayList<>();
        if (oldLines.isEmpty() || oldLines.size() > lines.size()) {
            return matches;
        }
        for (int i = 0; i + oldLines.size() <= lines.size(); i++) {
            boolean same = true;
            for (int j = 0; j < oldLines.size(); j++) {
                String left = normalize(lines.get(i + j).text(), ignoreIndent);
                String right = normalize(oldLines.get(j), ignoreIndent);
                if (!left.equals(right)) {
                    same = false;
                    break;
                }
            }
            if (same) {
                matches.add(i);
            }
        }
        return matches;
    }

    private static String normalize(String line, boolean ignoreIndent) {
        String value = line.replace("\r", "").stripTrailing();
        return ignoreIndent ? value.strip() : value;
    }

    /** 候选行：包含片段首行前 20 个字符的行号，找不到时退化到首行的首个 token。 */
    private static List<Integer> candidates(List<Line> lines, List<String> oldLines) {
        String needle = oldLines.stream().map(String::trim).filter(text -> !text.isEmpty())
                .findFirst().orElse("");
        if (needle.isEmpty()) {
            return List.of();
        }
        Set<Integer> found = matchLines(lines, needle.length() > 20 ? needle.substring(0, 20) : needle);
        if (found.isEmpty()) {
            // "return 0; 这行改了" 这类片段：用首个有辨识度的 token 找候选
            for (String token : needle.split("\\s+")) {
                if (token.length() >= 4) {
                    found = matchLines(lines, token);
                    if (!found.isEmpty()) {
                        break;
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    private static Set<Integer> matchLines(List<Line> lines, String probe) {
        Set<Integer> found = new LinkedHashSet<>();
        for (int i = 0; i < lines.size() && found.size() < 5; i++) {
            if (lines.get(i).text().contains(probe)) {
                found.add(i + 1);
            }
        }
        return found;
    }

    private static List<Integer> occurrences(String text, String needle, int limit) {
        List<Integer> result = new ArrayList<>();
        int from = 0;
        while (result.size() < limit) {
            int index = text.indexOf(needle, from);
            if (index < 0) {
                break;
            }
            result.add(lineNumber(text, index));
            from = index + needle.length();
        }
        return result;
    }

    private static int lineNumber(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static List<Line> splitLines(String text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(new Line(text.substring(start, i), start, i));
                start = i + 1;
            }
        }
        lines.add(new Line(text.substring(start), start, text.length()));
        return lines;
    }

    private record Line(String text, int start, int end) {
    }
}
