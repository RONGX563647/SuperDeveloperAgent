package top.rongx.agent.executor;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.image.ImageReadiness;
import top.rongx.agent.domain.image.ImageReadinessStore;

/**
 * 工具链镜像就绪的**唯一入口**：探测 → 按源链拉取（进度感知）→ 复核 → 记录状态/退避。
 *
 * <p>设计参照 kubelet 的 ImagePullBackOff 与 containerd 的 hosts.toml 多源回退：
 * <ul>
 *   <li><b>判活看进度</b>：只有连续 {@code idleMs} 没有输出才算卡死；层下完在解压时
 *       不会被总墙钟砍断（固定 180s 曾把"下完待解压"误报成 registry 故障）；
 *   <li><b>失败要记住</b>：失败写 FAILED + 指数退避，退避窗口内直接快返上次结论，
 *       不让后续每次构建都白等一轮；
 *   <li><b>源链回退</b>：直连失败后按配置的镜像加速源/本地缓存源逐个重试，成功后
 *       tag 回原名，调用方始终只用配置里的镜像名；
 *   <li><b>只信复核</b>：就绪与否由平台自己执行的 {@code docker image inspect} 判定，
 *       不采信 pull 的退出码或输出文本。
 * </ul>
 *
 * <p>它不拼 {@code docker run} 参数，也不接受日志/模型给出的镜像名——只做「把配置里的
 * 镜像弄到本机并证明它可用」。
 */
final class ImageProvisioner {

    private static final long INSPECT_TIMEOUT_MS = 5_000L;
    /** 判断卡在哪一步只需层进度行，不需要 GB 级进度刷屏。 */
    private static final int TAIL_CHARS = 8_000;

    /** 拉取策略（全部来自配置，不写死在调用点）。 */
    record Policy(long idleMs, long totalMs, int attempts, long backoffBaseMs, long backoffMaxMs,
                  List<String> sources) {

        Policy {
            idleMs = idleMs <= 0 ? 60_000L : idleMs;
            totalMs = totalMs <= 0 ? 900_000L : totalMs;
            attempts = Math.max(1, attempts);
            backoffBaseMs = backoffBaseMs <= 0 ? 60_000L : backoffBaseMs;
            backoffMaxMs = backoffMaxMs <= 0 ? 900_000L : backoffMaxMs;
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    /** 一次就绪尝试的结论；失败时 summary/diagnosis 已是可读结论。 */
    record Result(boolean ready, String source, String digest, String errorCode,
                  String summary, String diagnosis, boolean backedOff) {

        static Result ok(String source, String digest) {
            return new Result(true, source, digest, null, null, null, false);
        }

        static Result failed(String errorCode, String summary, String diagnosis, boolean backedOff) {
            return new Result(false, null, null, errorCode, summary, diagnosis, backedOff);
        }
    }

    private final String dockerBinary;
    private final String image;
    private final String host;
    private final DockerSandbox.CommandExecutor commandExecutor;
    private final ImageReadinessStore store;
    private final Policy policy;
    private final Clock clock;

    ImageProvisioner(String dockerBinary, String image, String host,
                     DockerSandbox.CommandExecutor commandExecutor,
                     ImageReadinessStore store, Policy policy) {
        this(dockerBinary, image, host, commandExecutor, store, policy, Clock.systemUTC());
    }

    ImageProvisioner(String dockerBinary, String image, String host,
                     DockerSandbox.CommandExecutor commandExecutor,
                     ImageReadinessStore store, Policy policy, Clock clock) {
        this.dockerBinary = dockerBinary;
        this.image = image;
        this.host = host;
        this.commandExecutor = commandExecutor;
        this.store = store;
        this.policy = policy;
        this.clock = clock;
    }

    String image() {
        return image;
    }

    List<String> pullCommand() {
        return List.of(dockerBinary, "pull", image);
    }

    List<String> inspectCommand() {
        return List.of(dockerBinary, "image", "inspect", image);
    }

    /**
     * 确保镜像可用；已就绪时只花一次 inspect。
     *
     * @param cancelled 取消信号（运行被终止/超时）
     * @param live      实时输出回调，可为 null
     */
    Result provision(BooleanSupplier cancelled, Consumer<String> live) {
        BooleanSupplier abort = cancelled == null ? () -> false : cancelled;

        // 1) 本机已有：唯一可信判据是平台自己跑的 inspect。
        //    这一条在每个动作前都会走一次，所以只花这一条命令，不再额外取 digest。
        if (commandExecutor.run(inspectCommand(), INSPECT_TIMEOUT_MS, abort, null).errorCode() == null) {
            return markReady("local", null);
        }

        // 2) 退避窗口内：直接复用上次失败结论，不再等一轮超时
        long now = clock.millis();
        ImageReadiness known = store == null ? null : store.find(host, image).orElse(null);
        if (known != null && known.inBackoff(now)) {
            long waitSeconds = Math.max(1, (known.nextRetryAt() - now) / 1000);
            notify(live, "[sandbox] 镜像 " + image + " 上次拉取失败，退避中（约 " + waitSeconds
                    + "s 后再试）；上次结论：" + known.lastError() + "\n");
            return Result.failed(ErrorCode.TIMEOUT.code(),
                    "拉取处于退避窗口（" + waitSeconds + "s 后可重试）",
                    known.lastError() == null ? "" : known.lastError(), true);
        }

        StringBuilder seen = new StringBuilder();
        Consumer<String> tap = chunk -> {
            if (chunk == null) {
                return;
            }
            synchronized (seen) {
                if (seen.length() < TAIL_CHARS) {
                    seen.append(chunk);
                }
            }
            if (live != null) {
                live.accept(chunk);
            }
        };

        notify(live, "[sandbox] 镜像 " + image + " 不在本机，开始拉取（静默 " + policy.idleMs() / 1000
                + "s 判卡死；总上限 " + policy.totalMs() / 1000 + "s；最多 " + policy.attempts()
                + " 次；可取消）\n");

        // 3) 直连拉取：只对「值得再试」的失败重试（鉴权/不存在重试也没用，只会拖长失败）
        ProcessExecutor.StepOutcome last = null;
        for (int attempt = 1; attempt <= policy.attempts() && !abort.getAsBoolean(); attempt++) {
            if (attempt > 1) {
                notify(live, "[sandbox] 第 " + (attempt - 1) + " 次未完成，重试（已下载的层会复用）\n");
            }
            last = commandExecutor.runUntilIdle(pullCommand(), policy.idleMs(), policy.totalMs(),
                    abort, tap);
            if (last.errorCode() == null && verified(abort)) {
                return markReady("direct", pullDigest(abort));
            }
            if (last.errorCode() != null && !worthRetry(last, seen.toString())) {
                break;
            }
            if (last.errorCode() == null) {
                // pull 说成功但 inspect 不过：不采信子进程自述，按失败处理
                last = commandExecutor.run(inspectCommand(), INSPECT_TIMEOUT_MS, abort, null);
                break;
            }
        }

        // 4) 源链回退：按配置的镜像加速源/缓存源逐个试，成功后 tag 回原名
        String path = dockerHubPath(image);
        if (!abort.getAsBoolean() && path != null) {
            for (String source : policy.sources()) {
                if (abort.getAsBoolean()) {
                    break;
                }
                String ref = source + "/" + path;
                notify(live, "[sandbox] 改用镜像源重试：" + ref + "\n");
                ProcessExecutor.StepOutcome pulled = commandExecutor.runUntilIdle(
                        List.of(dockerBinary, "pull", ref), policy.idleMs(), policy.totalMs(),
                        abort, tap);
                if (pulled.errorCode() != null) {
                    last = pulled;
                    continue;
                }
                ProcessExecutor.StepOutcome tagged = commandExecutor.run(
                        List.of(dockerBinary, "tag", ref, image), INSPECT_TIMEOUT_MS, abort, null);
                if (tagged.errorCode() == null && verified(abort)) {
                    notify(live, "[sandbox] 镜像源已就绪并已重命名为 " + image + "\n");
                    return markReady("mirror:" + source, pullDigest(abort));
                }
                last = tagged;
            }
        } else if (!policy.sources().isEmpty() && !abort.getAsBoolean()) {
            notify(live, "[sandbox] 镜像自带 registry，跳过镜像源回退\n");
        }

        // 5) 复核：pull 说成功也不算数
        if (last == null || last.errorCode() == null) {
            last = commandExecutor.run(inspectCommand(), INSPECT_TIMEOUT_MS, abort, null);
        }
        String diagnosis = diagnose(seen.toString());
        String summary = last == null ? "拉取失败" : last.summary();
        markFailed(summary, diagnosis, known);
        return Result.failed(ErrorCode.TIMEOUT.code(), summary, diagnosis, false);
    }

    /** 鉴权/不存在/磁盘满之类重试无意义；超时、停滞、有进度说明是「慢」，值得再试。 */
    static boolean worthRetry(ProcessExecutor.StepOutcome outcome, String output) {
        if (outcome == null) {
            return true;
        }
        String text = (output == null ? "" : output).toLowerCase(Locale.ROOT);
        boolean hopeless = text.contains("unauthorized")
                || text.contains("denied")
                || text.contains("not found")
                || text.contains("manifest unknown")
                || text.contains("no space left")
                || text.contains("toomanyrequests");
        if (hopeless) {
            return false;
        }
        return ErrorCode.TIMEOUT.code().equals(outcome.errorCode())
                || text.contains("download complete")
                || text.contains("pull complete");
    }

    /** 按拉取输出判断卡在哪一步——这条诊断直接决定该改网络还是该放宽预算。 */
    static String diagnose(String output) {
        String text = output == null ? "" : output;
        boolean downloaded = text.contains("Download complete");
        boolean pulled = text.contains("Pull complete");
        boolean moving = text.contains("Downloading") || text.contains("Extracting");
        if (moving) {
            return "部分镜像层仍在下载/解压中（网络或磁盘偏慢，可配置镜像加速源或本地缓存源）";
        }
        if (downloaded && !pulled) {
            return "所有镜像层已下载完成，卡在解压/落盘（多为磁盘或 CPU 竞争，重试会直接复用已下载层）";
        }
        if (!downloaded) {
            return "没有任何镜像层开始下载（Docker 无法访问 registry，先检查网络与镜像源）";
        }
        return "部分镜像层仍未下载完（网络偏慢，可配置镜像加速源或本地缓存源）";
    }

    /**
     * Docker Hub 镜像的仓库路径（官方镜像补 {@code library/}）；自带 registry 时返回 null。
     *
     * <p>与 Testcontainers 的镜像名前缀改写同构：只在名称层做拼装，不改 pull 语义。
     */
    static String dockerHubPath(String ref) {
        int slash = ref.indexOf('/');
        if (slash < 0) {
            return "library/" + ref;
        }
        String first = ref.substring(0, slash);
        if (first.contains(".") || first.contains(":") || "localhost".equals(first)) {
            return null;
        }
        return ref;
    }

    /** 就绪后记 READY（含 digest，便于追溯「这次构建用的是哪一层」）。 */
    private Result markReady(String source, String digest) {
        if (store != null) {
            store.save(new ImageReadiness(host, image, ImageReadiness.Status.READY, source, digest,
                    0, null, 0L, clock.millis()));
        }
        return Result.ok(source, digest);
    }

    /** 失败记 FAILED 并算下一次可重试时间（按累计失败次数指数退避）。 */
    private void markFailed(String summary, String diagnosis, ImageReadiness known) {
        if (store == null) {
            return;
        }
        int attempts = (known == null ? 0 : known.attempts()) + 1;
        long backoff = Math.min(policy.backoffMaxMs(),
                policy.backoffBaseMs() * (1L << Math.min(attempts - 1, 10)));
        store.save(new ImageReadiness(host, image, ImageReadiness.Status.FAILED, null, null,
                attempts, summary == null ? diagnosis : summary + "；" + diagnosis,
                clock.millis() + backoff, clock.millis()));
    }

    /** 平台自己的复核：只有它能证明镜像真能用，pull 的退出码不算。 */
    private boolean verified(BooleanSupplier abort) {
        return commandExecutor.run(inspectCommand(), INSPECT_TIMEOUT_MS, abort, null)
                .errorCode() == null;
    }

    /** 只有需要落库时才多花一条命令取 digest。 */
    private String pullDigest(BooleanSupplier abort) {
        return store == null ? null : digestOf(abort);
    }

    /**
     * 记录 digest：本地构建出来的镜像没有 RepoDigests，返回 null 即可。
     *
     * <p>只为可追溯，不参与调度决策，所以失败也只置空，不影响就绪结论。
     */
    private String digestOf(BooleanSupplier abort) {
        StringBuilder out = new StringBuilder();
        ProcessExecutor.StepOutcome outcome = commandExecutor.run(
                List.of(dockerBinary, "image", "inspect", "--format",
                        "{{index .RepoDigests 0}}", image),
                INSPECT_TIMEOUT_MS, abort, out::append);
        if (outcome.errorCode() != null) {
            return null;
        }
        String text = out.toString().trim();
        return text.isEmpty() || text.contains("<no value>") ? null : text;
    }

    private static void notify(Consumer<String> live, String line) {
        if (live != null) {
            live.accept(line);
        }
    }
}
