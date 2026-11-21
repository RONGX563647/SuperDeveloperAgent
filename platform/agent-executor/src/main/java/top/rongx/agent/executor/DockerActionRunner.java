package top.rongx.agent.executor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.domain.error.SandboxDeniedException;
import top.rongx.agent.domain.run.StageResult;

/**
 * 容器内执行模式（生产用）。
 *
 * <p>每条命令都被包成 {@code docker run}，工作区挂载进容器；语义与宿主模式一致
 * （预算、超时、日志、产物、错误分类），差别只有隔离。
 *
 * <p>容器带固定名字并在动作结束后 {@code docker rm -f}：动作超时只杀 docker CLI 时，
 * 容器仍会继续跑（表现为"运行已失败但容器还在 RUNNING"），必须显式清理。
 *
 * <p><b>准备阶段也受预算约束</b>：依赖缓存卷初始化在 {@code super.run} 之前完成，
 * 走同一套超时 / 取消 / 实时日志；失败直接返回结构化失败（而不是抛到引擎外层
 * 变成一条没有原因的"引擎异常"），并把准备耗时从动作预算里扣掉。若准备已经吃光预算，
 * 直接以「准备阶段耗尽预算」失败——不再把剩余预算夹成 1ms 去启动一条注定 0s 超时的命令。
 */
public class DockerActionRunner extends AbstractActionRunner {

    private final DockerSandbox sandbox;

    public DockerActionRunner(DockerSandbox sandbox) {
        this.sandbox = sandbox;
    }

    /** 供引擎的自动修复层读取「管理员配置的镜像与复核命令」（不是日志里的字符串）。 */
    public DockerSandbox sandbox() {
        return sandbox;
    }

    @Override
    public ActionResult run(Path workspace, ActionSpec spec) {
        return run(workspace, spec, () -> false, null);
    }

    @Override
    public ActionResult run(Path workspace, ActionSpec spec, BooleanSupplier cancelled) {
        return run(workspace, spec, cancelled, null);
    }

    @Override
    public ActionResult run(Path workspace, ActionSpec spec, BooleanSupplier cancelled,
                            Consumer<String> live) {
        String container = sandbox.beginContainer();
        try {
            long prepareStart = System.nanoTime();
            // fail-closed 从「启动期」后移到「执行期」：平台本身（UI/配置/health）不该因
            // 没有 daemon 而起不来，但真正要跑不可信构建配方时必须有隔离能力——守护进程
            // 不可用就在这里结构化失败，绝不降级为无隔离的宿主进程。
            if (!sandbox.available()) {
                String why = "agent.sandbox.mode=docker 但 Docker 守护进程不可用，本次动作拒绝执行"
                        + "（fail-closed：不以无隔离的宿主进程运行不可信代码）；"
                        + "容器化部署请见 docker-compose.platform.yml 的 dind sidecar，"
                        + "或仅限本地开发显式设置 agent.sandbox.mode=process";
                if (live != null) {
                    live.accept("[sandbox] ⛔ " + ErrorCode.ENVIRONMENT_INVALID_STATE.code()
                            + " " + why + "\n");
                }
                return new ActionResult(StageResult.failed(spec.action().wireName(), 1,
                        ErrorCode.ENVIRONMENT_INVALID_STATE.code(), why, 0), why);
            }
            // 隔离参数先落日志：出问题时第一句话永远是「这次到底隔离了什么」
            sandbox.logIsolation(live);
            // 网络档位与阶段需求必须在起容器前对齐：否则用户等到 60 秒后只看到
            //   Could not resolve host（dig 失败），无法判断是策略还是网络问题。
            // 容器内所有动作（mvn/pnpm/docker build）都需要外联，"none 档位 + 缓存全命中"
            // 是唯一可能成功的组合，因此默认按"需要外联"判定；确需离线构建时
            // 显式打开 agent.sandbox.allow-offline。
            sandbox.requireNetworkFor(!sandbox.allowOffline(), spec.action().wireName());
            // 运行时（gVisor/Kata）必须在本机真实存在，绝不静默回落 runc
            sandbox.ensureRuntimeReady(cancelled, live);
            // 网络先就绪（BUG-35）：配置了自定义网络（build-net）但不存在时，
            // 在跑构建前就明确报"网络未就绪"，而不是等 docker run 报 network not found。
            sandbox.prepareNetwork(cancelled, live);
            // 先确认工具链镜像在本机，再动缓存卷：缺镜像是配置问题，应在数秒内明确报出
            sandbox.ensureImageReady(cancelled, live);
            // tag 会被重新 pull 覆盖，同名 tag 在不同时间可能对应不同镜像；
            // 把 digest 写进运行日志，事后才能确定这次构建到底用了哪份工具链。
            if (live != null) {
                String digest = sandbox.imageId(cancelled);
                if (!digest.isBlank()) {
                    live.accept("[sandbox] 镜像 " + sandbox.image() + " " + digest + "\n");
                }
            }
            sandbox.prepareCacheVolume(workspace, cancelled, live);
            long spentMs = (System.nanoTime() - prepareStart) / 1_000_000L;
            long budgetMs = spec.timeout().toMillis();
            // 准备耗时进入运行日志：缓存卷首次初始化（chown -R）与镜像拉取都能吃掉几分钟，
            // 不显示的话用户只能看到「动作还没开始就没有输出」。
            if (live != null && spentMs >= 1_000L) {
                live.accept("[sandbox] 准备阶段耗时 " + human(spentMs)
                        + "，剩余动作预算 " + human(Math.max(0, budgetMs - spentMs)) + "\n");
            }
            // 准备阶段（拉镜像 / 缓存卷初始化）本身不受动作预算约束：一旦它吃掉整个预算，
            // 过去的做法是把剩余预算夹成 1ms 继续跑，结果命令还没开始就报"执行超时（0s）"，
            // 用户与自动修复都只能看到一条没有原因的超时。这里改成明确的结构化失败。
            if (spentMs >= budgetMs) {
                String why = "准备阶段（镜像检查 / 依赖缓存卷初始化）已耗时 " + human(spentMs)
                        + "，超过动作预算 " + human(budgetMs) + "，命令未开始执行；"
                        + "请提高该动作的 timeout，或预热镜像与缓存卷";
                if (live != null) {
                    live.accept("[sandbox] ❌ " + ErrorCode.TIMEOUT.code() + " " + why + "\n");
                }
                return new ActionResult(StageResult.failed(spec.action().wireName(), 124,
                        ErrorCode.TIMEOUT.code(), why, spentMs), why);
            }
            ActionSpec withBudget = new ActionSpec(spec.action(), spec.steps(), spec.produce(),
                    Duration.ofMillis(budgetMs - spentMs), spec.source());
            return super.run(workspace, withBudget, cancelled, live);
        } catch (SandboxDeniedException denied) {
            // 策略拒绝是「状态冲突」而不是「命令失败」：结构化返回，交给页面与自愈分层处理
            String why = denied.structured();
            if (live != null) {
                live.accept("[sandbox] ⛔ " + ErrorCode.ENV_POLICY_DENIED.code() + " " + why + "\n");
            }
            return new ActionResult(StageResult.failed(spec.action().wireName(), 126,
                    ErrorCode.ENV_POLICY_DENIED.code(), why, 0), why);
        } catch (EnvironmentException env) {
            // 环境异常只能携带 ENVIRONMENT 组错误码，具体的「超时/取消」写在消息里：
            // 取消信号优先还原 E-303；准备/状态类通用码则按消息里的子命令码还原，
            // 这样用户与自愈看到的是"超时"而不是笼统的"准备失败"。
            String why = env.getMessage() == null ? env.toString() : env.getMessage();
            String code = env.errorCode() == null
                    ? ErrorCode.EXECUTABLE_NOT_FOUND.code() : env.errorCode().code();
            if (cancelled != null && cancelled.getAsBoolean()) {
                code = ErrorCode.CANCELLED.code();
            } else if (GENERIC_ENV_CODES.contains(code)) {
                String sub = subErrorCode(why);
                if (sub != null) {
                    code = sub;
                }
            }
            int exit = ErrorCode.TIMEOUT.code().equals(code) ? 124
                    : ErrorCode.CANCELLED.code().equals(code) ? 143 : 1;
            if (live != null) {
                live.accept("[sandbox] ❌ " + code + " " + why + "\n");
            }
            return new ActionResult(StageResult.failed(spec.action().wireName(), exit,
                    code, why, 0), why);
        } catch (RuntimeException e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            String code = why.contains(ErrorCode.TIMEOUT.code()) ? ErrorCode.TIMEOUT.code()
                    : why.contains(ErrorCode.CANCELLED.code()) ? ErrorCode.CANCELLED.code()
                    : ErrorCode.EXECUTABLE_NOT_FOUND.code();
            int exit = ErrorCode.TIMEOUT.code().equals(code) ? 124
                    : ErrorCode.CANCELLED.code().equals(code) ? 143 : 1;
            if (live != null) {
                live.accept("[sandbox] ❌ " + code + " " + why + "\n");
            }
            return new ActionResult(StageResult.failed(spec.action().wireName(), exit,
                    code, why, 0), why);
        } finally {
            sandbox.endContainer(container);
        }
    }

    @Override
    protected List<String> effectiveArgv(Path workspace, List<String> argv) {
        return sandbox.wrap(workspace, argv);
    }

    /** 耗时的人类可读形式：准备阶段可能只有几百毫秒，按整分钟显示会变成"0 分钟"。 */
    private static String human(long millis) {
        return ProcessExecutor.human(millis);
    }

    /** 通用环境码：具体原因在消息里，需要还原子命令的错误码（E-302 等）。 */
    private static final java.util.Set<String> GENERIC_ENV_CODES = java.util.Set.of(
            ErrorCode.ENV_PREPARE_FAILED.code(), ErrorCode.ENVIRONMENT_INVALID_STATE.code());

    /** 取消息里第一个 {@code E-ddd} 形式的错误码（沙箱把子命令码原样写进消息）。 */
    private static String subErrorCode(String message) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("E-\\d{3}").matcher(message == null ? "" : message);
        return matcher.find() ? matcher.group() : null;
    }
}
