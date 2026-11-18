package top.rongx.agent.domain.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;

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

    /**
     * 运行中的阶段进度（已完成阶段数、当前阶段、总阶段数、耗时）。
     *
     * <p>{@link #stages} 只含已结束阶段，运行中阶段没有结果对象；工作台靠这个只读视图
     * 渲染「build 运行中」占位，避免长时间构建时页面显示「1/1 完成」却又在 RUNNING。
     * 不支持的实现返回 null。
     */
    default RunProgress progress(String runId) {
        return null;
    }

    /** 运行进度快照（字段均为只读展示值）。 */
    record RunProgress(String currentStageId, int stageTotal, int completedStages,
                       long elapsedMs, long stageElapsedMs,
                       boolean cancelRequested, boolean forceTerminated) {
    }

    /**
     * 自动修复状态快照：工作台据此显示「平台已自动修复并续跑」，
     * 用户不必从日志里猜到底有没有人介入。不支持的实现返回 null。
     */
    default AutoRepairView autoRepair(String runId) {
        return null;
    }

    record AutoRepairView(boolean enabled, int attempts, int maxAttempts, boolean resumed,
                          String lastAction, String lastResult) {
    }

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

    /**
     * 行分页日志：一次只返回 {@code limit} 行，并告知总行数与是否还有更多。
     *
     * <p>为什么字节 offset 不够：前端要做虚拟滚动就必须知道「一共多少行、我现在在第几行」。
     * 只给 {@code text} 时页面只能把整包日志塞进 DOM——几十万行的构建日志会直接卡死，
     * 右侧滚动条也因此永远算不对高度。
     *
     * @param offset 起始行号（0 基）
     * @param limit  最多返回行数（实现方需做上限保护）
     */
    default LogLinePage logLines(String runId, int offset, int limit) {
        String full = logTail(runId);
        return LogLinePage.of(full == null ? "" : full, offset, limit);
    }

    /**
     * 行分页结果。
     *
     * @param offset    起始行号（已按实际日志长度夹紧）
     * @param limit     请求的行数上限（实际生效值）
     * @param total     日志总行数
     * @param lines     本次返回的行（不含行尾换行符）
     * @param truncated 是否因为 limit 而截断（还有更多行）
     */
    record LogLinePage(int offset, int limit, int total, List<String> lines, boolean truncated) {

        /** 行分页的公共实现：按 {@code \\R} 切行，越界返回空页，limit 夹到 [1, MAX]。 */
        public static LogLinePage of(String text, int offset, int limit) {
            int size = Math.max(1, Math.min(limit, MAX_LINES));
            // split 保留尾部空串会让「最后一行换行」多算一行，先按行尾换行裁剪
            String body = text == null ? "" : text;
            List<String> all = body.isEmpty()
                    ? List.of()
                    : List.of(body.split("\\R", -1));
            // 文本以换行结尾时最后一个是空串，不算一行
            if (!all.isEmpty() && all.get(all.size() - 1).isEmpty()) {
                all = all.subList(0, all.size() - 1);
            }
            int total = all.size();
            int from = Math.min(Math.max(0, offset), total);
            int to = Math.min(from + size, total);
            return new LogLinePage(from, size, total,
                    List.copyOf(all.subList(from, to)), to < total);
        }

        /** 单次返回的行数上限：太大就失去分页的意义，也会把响应体撑爆。 */
        public static final int MAX_LINES = 5_000;
    }

    /** 最近构建历史（含重启前落盘的运行）；默认无。 */
    default List<RunSummary> history() {
        return List.of();
    }

    /**
     * 一次发布的形态事实：是否首次发布、标签是否真的在远端可见、为什么没生效。
     *
     * <p>为什么单独拿出来：这三件事散在日志里时，页面只能显示「版本号」这一个
     * 看起来成功的字段，用户到远端才发现没有标签。把它们做成结构化事实，
     * 历史行、详情页与自愈 Agent 才有一致的判定依据。
     *
     * @param runId 运行 id
     */
    default ReleaseFacts releaseFacts(String runId) {
        return new ReleaseFacts(false, "", "", "");
    }

    /**
     * 发布形态事实。
     *
     * @param firstRelease 本次是否首次发布（该环境没有上一版）
     * @param tagState     标签状态线值（PUSHED / LOCAL / FAILED / REJECTED / SNAPSHOT）
     * @param tagReason    稳定原因码（LOCAL → TAG_PUSH_CREDENTIAL_MISSING）
     * @param hint         面向用户的处理建议
     */
    record ReleaseFacts(boolean firstRelease, String tagState, String tagReason, String hint) {
    }

    /**
     * 构建历史分页（项目可选，按时间倒序）。
     *
     * <p>默认实现基于 {@link #history()} 过滤，引擎可实现为索引分页，
     * 使接口耗时与历史条数无关。
     */
    default List<RunSummary> history(String projectId, int offset, int limit) {
        java.util.stream.Stream<RunSummary> stream = history().stream();
        if (projectId != null && !projectId.isBlank()) {
            stream = stream.filter(h -> projectId.equals(h.projectId()));
        }
        return stream.skip(Math.max(0, offset)).limit(Math.max(0, limit)).toList();
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
                        String scriptSha, boolean builtin, String deployTarget) {
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
        throw new RunException(ErrorCode.RUN_INVALID_INPUT, "当前引擎不支持阶段重试");
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
                      StageStatus status, long createdAt, int seq, int stageCount, long durationMs,
                      String version, String tagStatus, String branch) {
    }

    /**
     * 镜像版本（按 (项目, 环境) 记账，独立于构建历史）。
     *
     * <p>列表按最近部署时间倒序：第一条是当前版本，第二条是上一版（回滚目标）。
     */
    record ImageVersion(String tag, String digest, long sizeBytes, String version,
                        String gitSha, String runId, String status,
                        long createdAt, long deployedAt, String deployedBy, int seq) {
    }

    /** 某环境下部署过的镜像版本（最近部署在前）；不支持时返回空。 */
    default List<ImageVersion> deployImages(String projectId, String env) {
        return List.of();
    }

    /** 按 tag 反查该版本对应的提交；未登记返回空。 */
    default java.util.Optional<String> gitShaForImage(String projectId, String env, String tag) {
        return java.util.Optional.empty();
    }

    /**
     * 按版本部署的触发前预检（只读）：镜像是否存在、部署目标是否可用、环境必填项是否齐全。
     *
     * @return 阻塞项（空 = 可以触发）；每条都是给人看的错误原因
     */
    default List<String> deployPreflight(String projectId, String env, String tag) {
        return List.of();
    }

    /**
     * 本机 Docker 是否可用（真实探测，实现方可缓存）。
     *
     * <p>部署目标下拉与按版本部署预检共用，避免"界面显示可用、触发后才报错"。
     */
    default boolean dockerAvailable() {
        return false;
    }
}
