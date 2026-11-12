package top.rongx.agent.domain.error;

/**
 * 领域异常：用 {@link Kind} 明确表达「资源缺失 / 状态冲突 / 依赖不可用」，
 * 由接入层统一映射为 HTTP 状态，避免各层各自猜测该用 400 还是 500。
 */
public class DomainException extends RuntimeException {

    public enum Kind {
        /** 资源不存在。 */
        NOT_FOUND,
        /** 当前状态不允许该操作（需刷新或重新确认）。 */
        CONFLICT,
        /** 依赖服务不可用（Docker、远端主机等）。 */
        UNAVAILABLE
    }

    private final Kind kind;

    private DomainException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static DomainException notFound(String message) {
        return new DomainException(Kind.NOT_FOUND, message, null);
    }

    public static DomainException conflict(String message) {
        return new DomainException(Kind.CONFLICT, message, null);
    }

    public static DomainException unavailable(String message, Throwable cause) {
        return new DomainException(Kind.UNAVAILABLE, message, cause);
    }
}
