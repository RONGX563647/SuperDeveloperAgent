package top.rongx.agent.domain.action;

/**
 * 配方来源（证据链的一环）。
 *
 * <p>每次执行都必须能回答："这条命令从哪来、谁写的、哪个提交。" commit 用于审计@commit（验收#2）。
 *
 * @param kind   来源类型：CI_ACTIONS / TASKFILE / MAKEFILE / BUILTIN
 * @param ref    具体位置，如 ci/actions.yml#backend_build、Makefile#backend-build
 * @param commit 配方文件所在 commit（仅仓库来源有；内置默认为 null）
 */
public record ActionSource(Kind kind, String ref, String commit) {

    public enum Kind {
        CI_ACTIONS,
        TASKFILE,
        MAKEFILE,
        BUILTIN
    }

    public static ActionSource of(Kind kind, String ref) {
        return of(kind, ref, null);
    }

    public static ActionSource of(Kind kind, String ref, String commit) {
        return new ActionSource(kind, ref, commit);
    }

    public static ActionSource builtin(String action) {
        return new ActionSource(Kind.BUILTIN, "builtin#" + action, null);
    }

    /** 是否来自人编写的仓库文件（而非平台内置）。 */
    public boolean humanAuthored() {
        return kind != Kind.BUILTIN;
    }
}
