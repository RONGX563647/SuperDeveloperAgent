package top.rongx.agent.domain.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;

/**
 * 流水线引擎端口（由执行面实现）。
 *
 * <p>放在 domain 的原因：工具层需要"触发构建"，但按分层规约 tools 只能依赖 domain。
 * 于是把契约定在 domain（端口），实现放执行面（适配器）——依赖方向保持单向。
 *
 * <p>实现必须是确定性代码，不得调用任何模型（规约 §1.1 A3）。
 */
public interface PipelineEngine {

    /** 引擎标识，例如 {@code builtin}。 */
    String id();

    /** 该项目下可用的流水线（白名单，不返回任意 job）。 */
    List<String> pipelines(String projectId);

    /**
     * 当前生效的发布摘要（目标 / 端口 / 健康路径），供设置页展示"真正会用的值"。
     *
     * <p>放在端口上是为了让网关不直接依赖执行面的 YAML 解析类（规约 §2.1 单向依赖）。
     */
    default Optional<DeploySummary> deploySummary(String projectId) {
        return Optional.empty();
    }

    /** 发布摘要（只读视图）。 */
    record DeploySummary(String target, int port, String healthPath) {
    }

    /** 触发一次运行，返回 runId。参数只允许枚举/正则约束后的值。 */
    String trigger(String projectId, String pipelineId, Map<String, String> params);

    /** 运行状态。 */
    StageStatus status(String runId);

    /** 该 runId 是否真实存在（把"不存在"与"排队中"区分开；默认保守返回 true）。 */
    default boolean hasRun(String runId) {
        return true;
    }

    /** 各 stage 结果（按执行顺序）。 */
    List<StageResult> stages(String runId);

    /** 取消运行。 */
    void cancel(String runId);

    /** 运行产出物。 */
    List<ArtifactRef> artifacts(String runId);

    /** 运行日志尾部（供构建历史查看；默认无）。 */
    default String logTail(String runId) {
        return "";
    }

    /**
     * 增量日志：从 {@code offset} 起返回日志片段，供前端"追加式"渲染。
     *
     * <p>默认实现基于 {@link #logTail(String)} 的切片；实现方若持有流式缓冲可覆盖以获得更好性能。
     * offset 越界时返回空片段（end == offset），表示暂无新增。
     */
    default LogSlice logFrom(String runId, int offset) {
        String full = logTail(runId);
        if (full == null) {
            return new LogSlice(0, 0, "");
        }
        int from = Math.min(Math.max(0, offset), full.length());
        return new LogSlice(from, full.length(), full.substring(from));
    }

    /** 一段增量日志。[offset, end) 为已读区间，text 为新增文本。 */
    record LogSlice(int offset, int end, String text) {
    }

    /** 最近构建历史（含重启前落盘的运行）；默认无。 */
    default List<RunSummary> history() {
        return List.of();
    }

    /**
     * 删除一条构建历史（只对终态运行生效）。
     *
     * <p>用于工作台的滑动删除；运行中不允许删除，持久化文件不存在时返回 false。
     */
    default boolean deleteHistory(String runId) {
        return false;
    }

    /**
     * 运行元信息：谁触发的、带了哪份环境绑定、流水线定义来自哪里。
     *
     * <p>供工作台展示"这次是 Agent 固化的脚本还是仓库定义"。不支持时返回 null。
     */
    default RunMeta runMeta(String runId) {
        return null;
    }

    /** 运行元信息（字段可空）。 */
    record RunMeta(String actor, String bindingId, String confirmedBy,
                   String definitionSource, String definitionNote) {
    }

    /**
     * 预览"这次会跑哪些阶段"（不触发运行）。
     *
     * <p>供工作台在点击运行前把真实阶段列给用户看，避免"以为是完整发布、实际只跑了检查构建"。
     * 默认返回空列表（实现方不支持预览时前端不展示）。
     */
    default List<StagePreview> preview(String projectId, String pipelineId, String env) {
        return List.of();
    }

    /**
     * 阶段预览（带提交）：仓库里的 {@code ci/pipeline.yml} 会随提交变化，
     * 而执行会检出指定提交，所以预览也要按该提交解析才能"所见即所跑"。
     *
     * <p>默认忽略 {@code gitSha}（旧实现保持"当前工作区"语义）。
     */
    default List<StagePreview> preview(String projectId, String pipelineId, String env, String gitSha) {
        return preview(projectId, pipelineId, env);
    }

    /**
     * 阶段预览（只含展示需要的字段）；{@code skipReason} 非空表示这次不会执行该阶段。
     *
     * <p>{@code command} / {@code script} 是"这一阶段实际要跑的命令"（脚本由配方确定性渲染，
     * 不是任意用户输入），前端据此展示命令级预览、复制与下载；平台内置阶段
     * （部署/健康检查/回滚）没有脚本，{@code builtin} 为 true。
     */
    record StagePreview(String id, String type, String action, String runner, String workdir,
                        String skipReason, String shell, String command, String script,
                        String scriptSha, boolean builtin) {
    }

    /**
     * Dry-run：只做解析与只读预检，不创建/启动容器、不部署、不推送镜像。
     *
     * <p>{@code reportHash} 是 apply 审批的绑定对象；配方/提交/环境/密钥版本任一变化
     * 都应产生不同哈希，旧报告与令牌自动失效。
     */
    default DryRunReport dryRun(String projectId, String pipelineId, String env, String gitSha) {
        return new DryRunReport(projectId, env, pipelineId, gitSha, "", "", false,
                List.of("当前引擎不支持 dry-run"), List.of(), List.of(), 0);
    }

    record DryRunReport(String projectId, String env, String pipelineId, String gitSha,
                        String reportHash, String fingerprint, boolean ok,
                        List<String> blockers, List<String> warnings,
                        List<StageDryRun> stages, long createdAt) {
        public DryRunReport {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            stages = stages == null ? List.of() : List.copyOf(stages);
        }
    }

    record StageDryRun(String id, String type, String action, boolean runnable, String reason,
                       String command, String scriptSha, boolean builtin) {
    }

    /**
     * 从指定阶段继续执行（复用失败运行保留的构建现场）。
     *
     * <p>只允许对终态失败的运行调用；不支持或现场已清理时抛 {@link IllegalArgumentException}。
     *
     * @return runId
     */
    default String retryStage(String runId, String stageId) {
        throw new IllegalArgumentException("当前引擎不支持阶段重试");
    }

    /**
     * 某个文件产物的本地路径（供下载）。索引对应 {@link #artifacts(String)}。
     *
     * <p>实现必须校验路径位于本次运行的产物归档目录内；不支持时返回空。
     */
    default java.util.Optional<java.nio.file.Path> artifactFile(String runId, int index) {
        return java.util.Optional.empty();
    }

    /**
     * 停止平台托管的开发环境本机前后端（后端 jar + 前端 dist 静态服务器）。
     *
     * <p>用于"停掉后改用 IDE 启动"；不支持本机模式的实现返回 false。
     *
     * @return true = 至少停掉一个仍在运行的进程
     */
    default boolean stopLocalDev(String projectId, String env) {
        return false;
    }

    /** 历史视图（domain 层数据结构，避免 gateway 依赖执行面具体类）。 */
    record RunSummary(String runId, String projectId, String env, String pipelineId, String gitSha,
                      StageStatus status, long createdAt, int seq, int stageCount, long durationMs) {
    }
}
