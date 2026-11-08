package top.rongx.agent.infra.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 项目清单配置（application.yml 的 {@code agent.projects}）。
 *
 * <p>项目与环境来自<b>配置</b>而非代码：换项目 / 加环境只改 yml，不改 Java。
 *
 * <pre>
 * agent:
 *   projects:
 *     - id: RY
 *       name: RuoYi-Vue-Plus
 *       repo-url: https://gitee.com/dromara/RuoYi-Vue-Plus.git
 *       repo-type: gitee
 *       type: ruoyi-plus
 *       port-range-start: 8080
 *       port-range-end: 8099
 *       envs:
 *         - env: dev
 *           app-url: http://127.0.0.1:8080
 *           compose-dir: /opt/apps/ry
 * </pre>
 */
@ConfigurationProperties(prefix = "agent.projects")
public record ProjectsProperties(List<ProjectDef> items) {

    public ProjectsProperties {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record ProjectDef(String id, String name, String repoUrl, String repoType,
                             String type, int portRangeStart, int portRangeEnd,
                             List<EnvDef> envs) {
        public ProjectDef {
            envs = envs == null ? List.of() : List.copyOf(envs);
        }
    }

    public record EnvDef(String env, String appUrl, String composeDir) {
    }
}
