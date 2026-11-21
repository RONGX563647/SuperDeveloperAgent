package top.rongx.agent.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.hash.Hashes;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.process.CommandShell;

/**
 * 阶段命令渲染：把 {@code PipelineDefinition} 的阶段翻译成可展示/可复制的命令与脚本。
 *
 * <p>渲染只发生在展示层：真正执行仍按 argv（不经 shell），因此脚本内容与安全边界一致。
 * 预览（{@code preview}/{@code dryRun}）与执行日志共用这里的渲染，保证「所见即所跑」。
 */
final class StageCommandRenderer {

    /** 命令里的 ${var} 占位符（预览展示模板，运行时替换）。 */
    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");
    /** 敏感开关形式的参数，如 --password xxx / --token=xxx。 */
    private static final java.util.regex.Pattern SECRET_FLAG = java.util.regex.Pattern.compile(
            "(?i)^--?(password|passwd|pwd|token|secret|api-?key|access-?key)(=.*)?$");
    /** KEY=VALUE 形式且 KEY 像密钥（DB_PASSWORD=xxx / TOKEN_SECRET=xxx）。 */
    private static final java.util.regex.Pattern SECRET_ASSIGN = java.util.regex.Pattern.compile(
            "(?i)^([A-Za-z0-9_]*(SECRET|TOKEN|PASSWORD|PASSWD|API_?KEY|ACCESS_?KEY)[A-Za-z0-9_]*)=(.+)$");
    /** URL 内嵌凭据：scheme://user:pass@host → 掩码 pass。 */
    private static final java.util.regex.Pattern URL_CREDENTIAL = java.util.regex.Pattern.compile(
            "(://[^/@:]+:)[^@]+(@)");

    private StageCommandRenderer() {
    }

    /** 预览里给前端展示的"这一阶段实际会跑什么"。 */
    record StageCommand(String shell, String command, String script, String sha, boolean builtin) {
    }

    /**
     * 把阶段翻译成命令级预览：action 阶段解析配方（与执行期同一个 {@code ActionResolver}），
     * 按目标 shell 渲染成可复制/可下载的脚本；部署/健康检查/回滚是平台内置步骤。
     */
    static StageCommand describe(ActionResolver recipes, java.nio.file.Path recipeRoot,
                                 PipelineDefinition.StageDef stage) {
        if (!"action".equals(stage.type())) {
            return new StageCommand("", builtinLabel(stage), "", "", true);
        }
        ActionId action = ActionId.fromWire(stage.action()).orElse(null);
        if (action == null || recipeRoot == null) {
            return new StageCommand("", stage.action(), "", "", true);
        }
        Optional<ActionSpec> resolved = recipes.resolve(recipeRoot, action);
        if (resolved.isEmpty()) {
            return new StageCommand("", "（没有配方）" + stage.action(), "", "", true);
        }
        ActionSpec spec = resolved.get();
        String shell = shellFor(stage);
        // 变量名先收集：脚本头要写清"需要哪些变量"
        java.util.Set<String> placeholders = new java.util.LinkedHashSet<>();
        for (List<String> argv : spec.steps()) {
            for (String token : argv) {
                collectPlaceholders(token, placeholders);
            }
        }
        List<String> lines = new ArrayList<>();
        if ("pwsh".equals(shell)) {
            lines.addAll(headerLines(stage, spec, placeholders));
            lines.add("$ErrorActionPreference = 'Stop'");
            if (hasWorkdir(stage)) {
                lines.add("Set-Location '" + stage.workdir().replace("'", "''") + "'");
            }
        } else {
            lines.add("#!/usr/bin/env sh");
            lines.addAll(headerLines(stage, spec, placeholders));
            lines.add("set -eu");
            if (hasWorkdir(stage)) {
                lines.add("cd '" + stage.workdir().replace("'", "'\\''") + "'");
            }
        }
        List<String> commands = new ArrayList<>();
        for (List<String> argv : spec.steps()) {
            String command = renderCommand(argv, shell);
            commands.add(command);
            lines.add(command);
        }
        String text = String.join("\n", lines) + "\n";
        return new StageCommand(shell, String.join(" && ", commands), text, sha256Short(text), false);
    }

    /** 脚本头注释：在哪跑、要哪些变量、超时多久、产物是什么。 */
    private static List<String> headerLines(PipelineDefinition.StageDef stage, ActionSpec spec,
                                            java.util.Set<String> placeholders) {
        List<String> lines = new ArrayList<>();
        lines.add("# stage: " + stage.id() + " · " + stage.action());
        lines.add("# 运行目录: " + (hasWorkdir(stage)
                ? stage.workdir() + "（脚本内含 cd，请在仓库根执行）"
                : "仓库根"));
        if (!placeholders.isEmpty()) {
            lines.add("# 变量: " + String.join(", ", placeholders) + "（预览为模板，运行时由平台替换）");
        }
        String produce = spec.produce().isEmpty() ? ""
                : "；产物: " + String.join(", ", spec.produce()) + "（未生成视为失败）";
        lines.add("# 超时: " + spec.timeout().toMinutes() + "m" + produce);
        lines.add("# 平台执行时注入 CI=true");
        return lines;
    }

    /** 收集 argv 里的 ${var} 变量名（预览展示模板，运行时才替换）。 */
    private static void collectPlaceholders(String token, java.util.Set<String> out) {
        if (token == null) {
            return;
        }
        java.util.regex.Matcher m = PLACEHOLDER.matcher(token);
        while (m.find()) {
            out.add(m.group(1));
        }
    }

    /** 目标 shell：容器内一律 sh；宿主机按操作系统（Windows → pwsh）。 */
    static String shellFor(PipelineDefinition.StageDef stage) {
        boolean inContainer = stage.runner() == null || stage.runner().isBlank()
                || !"host".equals(stage.runner());
        return inContainer ? "sh" : (CommandShell.isWindows() ? "pwsh" : "sh");
    }

    private static boolean hasWorkdir(PipelineDefinition.StageDef stage) {
        return stage.workdir() != null && !stage.workdir().isBlank() && !".".equals(stage.workdir());
    }

    /** 内置阶段的人话说明（没有可展示的脚本）。 */
    private static String builtinLabel(PipelineDefinition.StageDef stage) {
        if ("deploy".equals(stage.type())) return "（平台内置）部署 " + safe(stage.action());
        if ("health".equals(stage.type())) return "（平台内置）健康检查";
        if ("rollback".equals(stage.type())) return "（平台内置）回滚上一版";
        if ("git-checkout".equals(stage.type())) return "（平台内置）检出提交";
        return "（平台内置）" + safe(stage.type());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** argv → 一行可读命令：先掩码敏感值，再按目标 shell 的引号规则转义。 */
    static String renderCommand(List<String> argv, String shell) {
        return maskSecrets(argv).stream()
                .map(token -> shellToken(token, shell))
                .collect(Collectors.joining(" "));
    }

    /**
     * 单个参数按目标 shell 的规则渲染：安全字符原样，其余用单引号包裹。
     *
     * <p>单引号是两种 shell 里唯一"什么都不展开"的形式（避免 $、反引号、反斜杠被再次解释）；
     * 内部单引号的转义规则不同：sh 用 {@code '\''}，pwsh 用 {@code ''}。
     * {@code ${var}} 占位符刻意不加引号：它就是留给运行时替换的变量引用。
     */
    private static String shellToken(String token, String shell) {
        if (token == null) return "";
        if (token.isEmpty()) return "''";
        if (token.matches("[A-Za-z0-9_./:=@%+${}-]+")) return token;
        if ("pwsh".equals(shell)) {
            return "'" + token.replace("'", "''") + "'";
        }
        return "'" + token.replace("'", "'\\''") + "'";
    }

    /**
     * 掩码敏感参数：预览与运行日志都走这里，避免配方里硬编码的密钥被展示或留档。
     * 只改展示文本，不影响真正执行的 argv。
     */
    static List<String> maskSecrets(List<String> argv) {
        List<String> out = new ArrayList<>(argv.size());
        boolean maskNext = false;
        for (String token : argv) {
            if (maskNext) {
                out.add("******");
                maskNext = false;
                continue;
            }
            if (token != null && SECRET_FLAG.matcher(token).matches()) {
                int eq = token.indexOf('=');
                if (eq >= 0) {
                    // --token=xxx：值在同一参数内，只掩码等号后面
                    out.add(token.substring(0, eq + 1) + "******");
                } else {
                    // --token xxx：掩码下一个参数
                    out.add(token);
                    maskNext = true;
                }
                continue;
            }
            String masked = token == null ? ""
                    : SECRET_ASSIGN.matcher(token).replaceAll("$1=******");
            out.add(URL_CREDENTIAL.matcher(masked).replaceAll("$1******$2"));
        }
        return out;
    }

    private static String sha256Short(String text) {
        return Hashes.shortSha(Hashes.sha256Hex(text), 12);
    }

    /** verify 预览里被裁掉的那一步为什么跳过（执行期同样不跑）。 */
    static String verifySkipReason(PipelineDefinition.StageDef stage) {
        if ("deploy".equals(stage.type()) || "health".equals(stage.type())
                || "rollback".equals(stage.type()) || "stack_deploy".equals(stage.action())) {
            return "只检查构建：不部署、不做健康检查";
        }
        if ("image_package".equals(stage.action())) {
            return "只检查构建：不产镜像";
        }
        return "只检查构建：跳过";
    }
}
