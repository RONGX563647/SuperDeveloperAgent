package top.rongx.agent.gateway.web;

import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.infra.settings.SkillsStore;

/**
 * Skill 接入（设置页）。
 *
 * <p>遵循 Agent Skills 开源约定：一个技能 = 一个目录 + 必需的 {@code SKILL.md}
 * （YAML frontmatter 的 {@code name}/{@code description} 用于发现）。
 * 技能存放在平台侧 {@code <workspace>/_platform/skills}，不污染用户仓库。
 */
@RestController
@RequestMapping("/api/v1/settings/skills")
public class SkillSettingsController {

    private final SkillsStore skills;
    private final CurrentUser currentUser;

    public SkillSettingsController(SkillsStore skills, CurrentUser currentUser) {
        this.skills = skills;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Map<String, Object> list() {
        requireRead();
        return Map.of("root", skills.root().toString(), "skills", skills.list());
    }

    /** 预览 SKILL.md 原文。 */
    @GetMapping("/{name}")
    public Map<String, Object> read(@PathVariable String name) {
        requireRead();
        String content = skills.content(name)
                .orElseThrow(() -> new IllegalArgumentException("技能不存在：" + name));
        return Map.of("name", name, "content", content);
    }

    /** 新建 / 覆盖：name + description + 正文（正文自带 frontmatter 时原样保存）。 */
    @PostMapping
    public SkillsStore.Skill create(@RequestBody Map<String, Object> body) {
        requireWrite();
        return skills.create(str(body, "name"), str(body, "description"), str(body, "content"));
    }

    /** 从本地目录导入（目录必须含 SKILL.md）。 */
    @PostMapping("/import")
    public SkillsStore.Skill importSkill(@RequestBody Map<String, Object> body) {
        requireWrite();
        return skills.importFrom(str(body, "path"));
    }

    /** 启用 / 停用：对话未显式勾选时，按这里的默认状态决定是否加载。 */
    @PostMapping("/{name}/enabled")
    public Map<String, Object> setEnabled(@PathVariable String name,
                                          @RequestBody Map<String, Object> body) {
        requireWrite();
        boolean enabled = Boolean.parseBoolean(str(body, "enabled"));
        skills.setEnabled(name, enabled);
        return Map.of("ok", true, "name", name, "enabled", enabled);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        requireWrite();
        skills.delete(name);
        return Map.of("ok", true);
    }

    private void requireRead() {
        currentUser.requireUserId();
        currentUser.checkPermission("settings:read");
    }

    private void requireWrite() {
        currentUser.requireUserId();
        currentUser.checkPermission("settings:write");
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
