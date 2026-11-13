package top.rongx.agent.infra.settings;

import java.util.Map;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.settings.CapabilityCatalog;
import top.rongx.agent.domain.settings.CapabilityInstaller;
import top.rongx.agent.domain.settings.McpServerRepository;

/**
 * 能力安装器实现：MCP 落 SQLite，Skill 落 {@code _platform/skills/<name>/SKILL.md}。
 *
 * <p>安装只负责"接入"，默认启用；用户仍可在设置页或对话工具栏关掉。
 */
@Component
public class CapabilityInstallerImpl implements CapabilityInstaller {

    private final McpServerRepository servers;
    private final SkillsStore skills;

    public CapabilityInstallerImpl(McpServerRepository servers, SkillsStore skills) {
        this.servers = servers;
        this.skills = skills;
    }

    @Override
    public boolean isInstalled(String kind, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        CapabilityCatalog.Kind type = CapabilityCatalog.Kind.parse(kind).orElse(null);
        if (type == CapabilityCatalog.Kind.MCP) {
            return servers.findAll().stream().anyMatch(s -> name.equals(s.name()));
        }
        if (type == CapabilityCatalog.Kind.SKILL) {
            return skills.list().stream().anyMatch(s -> name.equals(s.dirName()));
        }
        return false;
    }

    @Override
    public Result install(String capabilityId) {
        CapabilityCatalog.Capability capability = CapabilityCatalog.find(capabilityId)
                .orElseThrow(() -> new IllegalArgumentException("能力不存在：" + capabilityId));
        String name = capability.name();
        String kind = capability.kind().name();

        if (capability.kind() == CapabilityCatalog.Kind.MCP) {
            boolean exists = servers.findAll().stream().anyMatch(s -> name.equals(s.name()));
            if (exists) {
                return new Result(kind, name, true, "MCP 服务「" + name + "」已在列表中");
            }
            var spec = capability.mcp();
            servers.save(new McpServerRepository.McpServerView(
                    null, name, spec.transport(), spec.command(), spec.args(), spec.url(), true),
                    Map.of(), "market");
            return new Result(kind, name, false, "已接入 MCP 服务「" + name + "」，可在对话工具栏勾选");
        }

        boolean exists = skills.list().stream().anyMatch(s -> name.equals(s.dirName()));
        if (exists) {
            return new Result(kind, name, true, "Skill「" + name + "」已存在");
        }
        var spec = capability.skill();
        skills.create(name, spec.description(), spec.content());
        return new Result(kind, name, false, "已接入 Skill「" + name + "」，可在对话工具栏勾选");
    }
}
