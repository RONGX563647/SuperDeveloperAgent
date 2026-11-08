package top.rongx.agent.domain.deploy;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「agent 走过的动作序列」渲染成可复现的流水线定义。
 *
 * <p>这是<b>唯一</b>能把探索结果变成脚本的地方：模型只提供"用了哪些白名单动作、什么顺序"，
 * YAML 的骨架、缩进、字段由这里确定性生成。既满足"agent 主导"，又不给模型写任意 YAML 的机会。
 */
public final class RecipeRenderer {

    private RecipeRenderer() {
    }

    /** 一个 stage（动作或特殊类型）。 */
    public record Step(String id, String type, String action, String runner, String workdir) {

        public static Step action(String action, String runner) {
            return new Step(action.replace('_', '-'), "action", action, runner, null);
        }

        public static Step deploy() {
            return new Step("deploy", "deploy", null, null, null);
        }

        public static Step health() {
            return new Step("health", "health", null, null, null);
        }

        public static Step rollback() {
            return new Step("rollback", "rollback", null, null, null);
        }
    }

    /** 渲染用的部署参数（避免与 {@link DeployTarget} 枚举混淆）。 */
    public record RenderTarget(String wireName, int port, int containerPort, String healthPath) {

        public static RenderTarget docker() {
            return new RenderTarget("docker", 8080, 8080, "/healthz");
        }

        public static RenderTarget docker(int port) {
            return new RenderTarget("docker", port, port, "/healthz");
        }

        public static RenderTarget host(int port) {
            return new RenderTarget("host", port, port, "/healthz");
        }
    }

    /**
     * 渲染 pipeline.yml。
     *
     * @param name   流水线名（release / verify）
     * @param steps  有序 stage
     * @param target 部署声明（null = 纯构建）
     */
    public static String render(String name, List<Step> steps, RenderTarget target) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 由平台按「跑通的动作序列」固化的可复现脚本（agent 探索产出）\n");
        sb.append("name: ").append(name == null || name.isBlank() ? "release" : name).append('\n');
        if (target != null) {
            sb.append("\ndeploy:\n")
                    .append("  target: ").append(target.wireName()).append('\n')
                    .append("  port: ").append(target.port()).append('\n')
                    .append("  containerPort: ").append(target.containerPort()).append('\n')
                    .append("  healthPath: ").append(target.healthPath()).append('\n');
        }
        sb.append("\nstages:\n");
        for (Step step : steps) {
            sb.append("  - id: ").append(step.id()).append('\n');
            sb.append("    type: ").append(step.type()).append('\n');
            if ("action".equals(step.type())) {
                sb.append("    action: ").append(step.action()).append('\n');
                sb.append("    runner: ").append(step.runner() == null ? "container" : step.runner())
                        .append('\n');
                if (step.workdir() != null && !step.workdir().isBlank()) {
                    sb.append("    workdir: ").append(step.workdir()).append('\n');
                }
            } else if ("health".equals(step.type())) {
                sb.append("    url: ").append(healthUrl(target)).append('\n')
                        .append("    expected: 200\n");
            }
        }
        boolean hasRollback = steps.stream().anyMatch(s -> "rollback".equals(s.type()));
        if (target != null && !hasRollback) {
            sb.append("\non-failure:\n  - id: rollback\n    type: rollback\n");
        }
        return sb.toString();
    }

    /** 常用序列：构建 → 测试 →（镜像）→ 部署 → 健康检查 → 回滚。 */
    public static List<Step> standard(boolean backend, boolean frontend, boolean image) {
        List<Step> steps = new ArrayList<>();
        if (backend) {
            steps.add(Step.action("backend_build", "container"));
            steps.add(Step.action("backend_test", "container"));
        }
        if (frontend) {
            steps.add(Step.action("frontend_build", "container"));
        }
        if (image) {
            steps.add(Step.action("image_package", "host"));
        }
        steps.add(Step.deploy());
        steps.add(Step.health());
        steps.add(Step.rollback());
        return steps;
    }

    private static String healthUrl(RenderTarget target) {
        int port = target == null ? 8080 : target.port();
        String path = target == null ? "/healthz" : target.healthPath();
        return "http://127.0.0.1:" + port + (path.startsWith("/") ? path : "/" + path);
    }
}
