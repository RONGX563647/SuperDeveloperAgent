package top.rongx.agent.gateway.bugfix;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import top.rongx.agent.domain.bugfix.BugFixEdit;
import top.rongx.agent.gateway.bugfix.structured.FixOutput;

/**
 * 结构化输出的语义校验：schema 只能约束类型/格式，不能保证“路径安全”“证据非空”“不能同时
 * 提交 edits 与 files”。这里集中处理这些细节，避免每个阶段各写一遍、各漏一处。
 */
public final class BugFixOutputValidation {

    static final int MAX_FILES = 15;
    static final int MAX_EDITS = 30;
    static final int MAX_PATHS = 50;

    private BugFixOutputValidation() {
    }

    /** 去空白；null → 空串。 */
    public static String text(String value) {
        return value == null ? "" : value.strip();
    }

    /** 置信度归一化到 [0,1]；NaN/缺失按 0 处理（由阶段阈值决定是否继续）。 */
    public static double confidence(Double value) {
        if (value == null || value.isNaN() || value < 0) {
            return 0;
        }
        return Math.min(value, 1d);
    }

    /** 清洗路径列表：去空、去重、截断；非法路径直接报错而不是静默丢弃。 */
    public static List<String> paths(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String path = safePath(value);
            if (!path.isBlank()) {
                unique.add(path);
            }
            if (unique.size() >= MAX_PATHS) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    /**
     * 把结构化修复结果转成平台补丁。
     *
     * <p>硬性规则：edits 与 files 必须二选一；路径不能逃逸仓库；edits 的 old 不能为空；
     * 数量分别为 30 / 15 以内——超限说明模型没有按分析范围收敛，宁可直接失败。
     */
    public static List<BugFixEdit> edits(FixOutput output) {
        if (output == null) {
            throw new IllegalArgumentException("修复结果为空");
        }
        List<FixOutput.Edit> edits = output.edits() == null ? List.of() : output.edits();
        List<FixOutput.FileOutput> files = output.files() == null ? List.of() : output.files();
        if (edits.isEmpty() == files.isEmpty()) {
            throw new IllegalArgumentException(edits.isEmpty()
                    ? "修复结果没有 edits 或 files" : "edits 与 files 只能二选一");
        }
        List<BugFixEdit> result = new ArrayList<>();
        if (!edits.isEmpty()) {
            if (edits.size() > MAX_EDITS) {
                throw new IllegalArgumentException("改动过多（" + edits.size() + "），上限 "
                        + MAX_EDITS + " 处");
            }
            int index = 0;
            for (FixOutput.Edit edit : edits) {
                index++;
                if (edit == null) {
                    throw new IllegalArgumentException("第 " + index + " 条改动为空");
                }
                String path = safePath(edit.path());
                String oldText = edit.oldText();
                if (oldText == null || oldText.isEmpty()) {
                    throw new IllegalArgumentException("第 " + index + " 条改动缺少 old：" + path);
                }
                result.add(BugFixEdit.replace(path, oldText,
                        edit.newText() == null ? "" : edit.newText()));
            }
            return result;
        }
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("改动文件过多（" + files.size() + "），上限 "
                    + MAX_FILES + " 个");
        }
        Set<String> seen = new LinkedHashSet<>();
        int index = 0;
        for (FixOutput.FileOutput file : files) {
            index++;
            if (file == null) {
                throw new IllegalArgumentException("第 " + index + " 个文件为空");
            }
            String path = safePath(file.path());
            if (!seen.add(path)) {
                throw new IllegalArgumentException("文件重复提交：" + path);
            }
            result.add(BugFixEdit.full(path, file.content() == null ? "" : file.content()));
        }
        return result;
    }

    /**
     * 仓库相对路径校验；拒绝绝对路径、{@code ..} 逃逸、.git 内部与控制字符。
     */
    public static String safePath(String raw) {
        String path = raw == null ? "" : raw.strip().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("不允许绝对路径：" + path);
        }
        if (path.equals("..") || path.startsWith("../") || path.contains("/../")) {
            throw new IllegalArgumentException("路径不允许逃逸仓库：" + path);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.equals(".git") || lower.startsWith(".git/") || lower.contains("/.git/")) {
            throw new IllegalArgumentException(".git 目录不允许修改：" + path);
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) {
                throw new IllegalArgumentException("路径包含非法控制字符：" + raw);
            }
        }
        return path;
    }
}
