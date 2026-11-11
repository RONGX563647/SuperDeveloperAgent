package top.rongx.agent.governance;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.patch.CodePatch;
import top.rongx.agent.domain.port.PatchStore;

/**
 * 待确认补丁存储（内存实现；生产落库）。
 *
 * <p>只负责保存与查询，签发确认令牌仍然只能由登录用户在
 * {@code /api/v1/changes/{id}/confirm} 完成。
 */
@Service
public class PatchRegistry implements PatchStore {

    private final Map<String, CodePatch> patches = new ConcurrentHashMap<>();

    @Override
    public void save(CodePatch patch) {
        patches.put(patch.patchId(), patch);
    }

    @Override
    public Optional<CodePatch> find(String patchId) {
        return Optional.ofNullable(patches.get(patchId));
    }

    @Override
    public void remove(String patchId) {
        patches.remove(patchId);
    }
}
