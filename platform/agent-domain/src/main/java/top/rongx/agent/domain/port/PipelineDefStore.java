package top.rongx.agent.domain.port;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 平台侧流水线定义存储。
 *
 * <p>为什么定义要存在<b>平台</b>而不是用户仓库：
 * <ul>
 *   <li>仓库里那份必须提交才算数（构建检出的是提交），而"生成 → 提交"这一步
 *       在真实项目里经常被跳过，结果就是"点了运行却报缺文件"；</li>
 *   <li>定义只是编排数据（stage → 白名单动作），不改变被构建代码的语义，
 *       放在平台侧便于按项目自动生成、随时重生成；</li>
 *   <li>团队仍可在仓库里放 {@code ci/pipeline.yml} 覆盖平台定义（随代码 review），
 *       那是可选项，不是前置条件。</li>
 * </ul>
 *
 * <p>目录形状与仓库根一致（{@code <root>/ci/pipeline.yml}），
 * 这样 {@code PipelineDefinition.load(root)} 与配方解析都能直接复用。
 */
public interface PipelineDefStore {

    /** 平台侧定义根目录；{@code ci/pipeline.yml} 就在它下面。 */
    Path rootOf(String projectId);

    /** 平台侧是否已有定义。 */
    boolean exists(String projectId);

    /** 读平台侧定义原文（没有则 empty）。 */
    Optional<String> read(String projectId);

    /** 写入定义与（可选的）配方；返回是否真的落盘。 */
    boolean write(String projectId, String pipelineYaml, String actionsYaml);
}
