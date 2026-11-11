package top.rongx.agent.domain.port;

import java.util.Optional;
import top.rongx.agent.domain.patch.CodePatch;

/**
 * 待确认补丁存储端口。
 *
 * <p>与 {@link ChangeStore} 分工：变更单负责「谁确认、令牌绑定哪个哈希」，
 * 这里负责保存补丁内容（diff 与文件全文），apply 时按 patchId 取回。
 */
public interface PatchStore {

    void save(CodePatch patch);

    Optional<CodePatch> find(String patchId);

    /** 应用成功后移除，避免同一补丁被二次确认后重复建分支。 */
    void remove(String patchId);
}
