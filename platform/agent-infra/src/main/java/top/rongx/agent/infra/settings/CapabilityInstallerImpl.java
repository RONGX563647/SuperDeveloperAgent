package top.rongx.agent.infra.settings;

import java.util.Map;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.settings.CapabilityInstaller;
import top.rongx.agent.domain.settings.CapabilityMarket;
import top.rongx.agent.domain.settings.McpServerRepository;

/**
 * 能力安装器实现：MCP 落 SQLite，Skill 落 {@code _platform/skills/<name>/SKILL.md}。
 *
 * <p>安装模板一律按 id 从 {@link CapabilityMarket} 重新取回，不接受前端回传的命令，
 * 避免设置页被篡改成任意进程启动参数。安装后默认启用，用户仍可随时关掉。
 */
@Component
public class CapabilityInstallerImpl implements CapabilityInstaller {

    private final McpServerRepository servers;
    private final SkillsStore skills;
    private final CapabilityMarket market;

    public CapabilityInstallerImpl(McpServerRepository servers, SkillsStore skills,
                                   CapabilityMarket market) {
        this.servers = servers;
        this.skills = skills;
        this.market = market;
    }

    @Override
    public boolean isInstalled(String kind, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        CapabilityMarket.Kind type = CapabilityMarket.Kind.parse(kind).orElse(null);
        if (type == CapabilityMarket.Kind.MCP) {
            return servers.findAll().stream().anyMatch(s -> name.equals(s.name()));
        }
        if (type == CapabilityMarket.Kind.SKILL) {
            return skills.list().stream().anyMatch(s -> name.equals(s.dirName()));
        }
        return false;
    }

    @Override
    public Result install(String capabilityId) {
        CapabilityMarket.Entry entry = market.find(capabilityId)
                .orElseThrow(() -> new IllegalArgumentException("能力不存在或已下架：" + capabilityId));
        String name = entry.name();
        String kind = entry.kind().name();

        if (entry.kind() == CapabilityMarket.Kind.MCP) {
            boolean exists = servers.findAll().stream().anyMatch(s -> name.equals(s.name()));
            if (exists) {
                return new Result(kind, name, true, "MCP 服务「" + name + "」已在列表中");
            }
            servers.save(new McpServerRepository.McpServerView(
                    null, name, entry.transport(), entry.command(), entry.args(), entry.url(), true),
                    Map.of(), "market");
            String hint = entry.requiredEnv().isEmpty() ? ""
                    : "；首次调用前需在服务环境变量中提供：" + String.join(", ", entry.requiredEnv());
            return new Result(kind, name, false,
                    "已接入 MCP 服务「" + name + "」，可在对话工具栏勾选" + hint);
        }

        boolean exists = skills.list().stream().anyMatch(s -> name.equals(s.dirName()));
        if (exists) {
            return new Result(kind, name, true, "Skill「" + name + "」已存在");
        }
        if (entry.content() == null || entry.content().isBlank()) {
            throw new IllegalStateException("技能内容为空，无法安装：" + capabilityId);
        }
        skills.create(name, entry.description(), entry.content());
        return new Result(kind, name, false, "已接入 Skill「" + name + "」，可在对话工具栏勾选");
    }
}
