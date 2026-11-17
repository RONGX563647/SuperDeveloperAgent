package top.rongx.agent.domain.error;

/**
 * 错误码的语义类型，用于把业务异常映射回既有 HTTP 语义。
 *
 * <p>输入类错误返回 400，状态冲突返回 409，内部失败返回 500；
 * 这样把通用异常迁移为业务异常时不会改变对外状态码。
 */
public enum ErrorKind {

    /** 请求参数或输入非法。 */
    INPUT,
    /** 当前状态不允许该操作。 */
    STATE,
    /** 平台内部执行失败。 */
    INTERNAL
}
