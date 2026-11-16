package top.rongx.agent.gateway.apidocs;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * OpenAPI 快照导出。
 *
 * <p>供外部 MCP / 离线工具读取与运行时一致的规范；路径在 /api/** 下，
 * 登录态由 Sa-Token 拦截器保证，权限码与 /doc.html 一致（settings:read）。
 */
@RestController
@RequestMapping("/api/v1/api-docs")
@Tag(name = "API 文档", description = "运行时 OpenAPI 快照（只读）")
public class ApiDocsController {

    private final ApiDocsSnapshotService snapshots;
    private final CurrentUser currentUser;

    public ApiDocsController(ApiDocsSnapshotService snapshots, CurrentUser currentUser) {
        this.snapshots = snapshots;
        this.currentUser = currentUser;
    }

    /**
     * 导出 OpenAPI 规范。
     *
     * @param refresh true 时重新扫描控制器（默认复用进程内缓存）
     */
    @GetMapping(value = "/snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "导出 OpenAPI 快照", description = "默认返回缓存；refresh=true 重新生成并落盘")
    public String snapshot(@RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        currentUser.checkPermission("settings:read");
        return refresh ? snapshots.refresh() : snapshots.json();
    }
}
