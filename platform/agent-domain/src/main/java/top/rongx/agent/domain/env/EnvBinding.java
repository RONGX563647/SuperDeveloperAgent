package top.rongx.agent.domain.env;

/**
 * 环境绑定：Agent 生成映射草案 → 人工确认 → 才能被 Agent 用来触发运行。
 *
 * @param id          绑定 id
 * @param projectId   项目
 * @param env         环境
 * @param mappingJson 映射草案（只含 key/资源 id/来源，不含明文）
 * @param mappingHash 映射内容 hash（确认后变更即失效）
 * @param createdBy   草案生成者（agent / user）
 * @param confirmedBy 确认人（未确认为空）
 * @param confirmedAt 确认时间（0 = 未确认）
 * @param expiresAt   过期时间
 * @param status      DRAFT / CONFIRMED / EXPIRED
 */
public record EnvBinding(
        String id,
        String projectId,
        String env,
        String mappingJson,
        String mappingHash,
        String createdBy,
        String confirmedBy,
        long confirmedAt,
        long expiresAt,
        String status) {

    public boolean confirmed() {
        return "CONFIRMED".equals(status) && confirmedBy != null && !confirmedBy.isBlank();
    }

    public boolean expired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }
}
