package top.rongx.agent.domain.text;

/**
 * 文本规整（唯一实现）。
 *
 * <p>「空白串转 {@code null}」曾在会话节点、进程身份、会话存储与环境存储各写一遍；
 * 语义虽短，但任何一处写成 {@code isEmpty()} 都会让只含空格的输入进入数据库。
 */
public final class Texts {

    private Texts() {
    }

    /** 空白（含仅空格）转 {@code null}，其余 {@code trim} 后返回。 */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
