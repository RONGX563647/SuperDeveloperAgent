package top.rongx.agent.domain.action;

/**
 * 配方来源（证据链的一环）。
 *
 * <p>每次执行都必须能回答："这条命令是从哪来的、谁写的、哪个提交。"
 *
 * @param kind CI_ACTIONS / TASKFILE / MAKEFILE / BUILTIN
 * @param ref  具体位置，如 {@code ci/actions.yml#backend_build}、{@code Makefile#backend-build}
 */
public record ActionSource(Kind kind, String ref) {

    public enum Kind {
        CI_ACTIONS,
        TASKFILE,
        MAKEFILE,
        BUILTIN
    }

    public static ActionSource of(Kind kind, String ref) {
        return new ActionSource(kind, ref);
    }

    public static ActionSource builtin(String action) {
        return new ActionSource(Kind.BUILTIN, "builtin#" + action);
    }

    /** 是否来自人编写的仓库文件（而非平台内置）。 */
    public boolean humanAuthored() {
        return kind != Kind.BUILTIN;
    }
}
