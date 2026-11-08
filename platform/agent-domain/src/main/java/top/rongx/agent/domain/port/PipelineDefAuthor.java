package top.rongx.agent.domain.port;

import java.nio.file.Path;
import java.util.Optional;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;

/**
 * 流水线定义的「agent 兜底」生成器。
 *
 * <p>平台对流水线定义是<b>规则优先</b>：按仓库结构（pom.xml / package.json / Dockerfile）
 * 用确定性规则生成。规则识别不出技术栈时（比如自定义构建系统），才请模型按同一套契约
 * 给出候选定义——模型只能引用白名单动作，不能写命令（命令仍来自 {@code ci/actions.yml} 或内置默认）。
 *
 * <p>实现必须<b>fail-soft</b>：模型不可用（未配置 Key / 超时 / 输出不合契约）时返回 empty，
 * 由调用方退回规则骨架并提示人工确认，绝不能因此阻塞接入或触发。
 */
public interface PipelineDefAuthor {

    /**
     * 生成候选流水线定义。
     *
     * @param projectId 项目 id
     * @param repoRoot  仓库目录（用于给模型描述结构；可为 null）
     * @param rulePlan  规则生成的骨架（stack=UNKNOWN 时文件内容只是骨架）
     * @param reason    为什么要兜底（写进日志，便于审计）
     * @return YAML 文本；不可用/不合契约时 empty
     */
    Optional<String> author(String projectId, Path repoRoot, ScaffoldPlan rulePlan, String reason);
}
