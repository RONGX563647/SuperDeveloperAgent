package top.rongx.agent.domain.bugfix;

/**
 * 修复阶段的一个文件改动。
 *
 * <p>优先使用「精确片段替换」：{@code oldText} 必须在基线文件里<b>唯一</b>出现，
 * 这样模型只需给出真正要改的几行，既省 token，也不会像「完整文件内容」那样因为
 * 输出被截断而把文件写坏。只有在需要新建文件或整体重写小文件时才使用完整内容。
 *
 * <p>{@code oldText} 为空串表示「新建文件」，平台会拒绝覆盖已存在的文件。
 *
 * @param path    相对仓库根目录的路径
 * @param oldText 待替换的唯一片段；null = 使用完整内容模式
 * @param newText 替换后的片段
 * @param content 完整文件内容（仅完整内容模式使用）
 */
public record BugFixEdit(String path, String oldText, String newText, String content) {

    public static BugFixEdit replace(String path, String oldText, String newText) {
        return new BugFixEdit(path, oldText == null ? "" : oldText,
                newText == null ? "" : newText, null);
    }

    public static BugFixEdit full(String path, String content) {
        return new BugFixEdit(path, null, null, content == null ? "" : content);
    }

    /** 是否为「精确片段替换」模式（含新建文件）。 */
    public boolean replaceMode() {
        return oldText != null;
    }
}
