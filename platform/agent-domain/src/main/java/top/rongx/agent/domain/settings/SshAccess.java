package top.rongx.agent.domain.settings;

import java.nio.file.Path;

/**
 * SSH 访问端口（执行面实现）：连通性探测、远程命令、文件上传。
 *
 * <p>放在 domain 是为了让网关（设置页测试连接）与部署器共用同一份实现，
 * 同时不违反"Gateway 不得依赖 Executor"的分层约束。
 */
public interface SshAccess {

    /** 结果：ok + 输出，或失败原因。 */
    record Result(boolean ok, int exitCode, String output, String error) {
        public static Result ok(String output) {
            return new Result(true, 0, output, null);
        }

        public static Result failed(int exitCode, String message) {
            return new Result(false, exitCode, "", message);
        }
    }

    /** 连通性探测（echo + docker 版本）。 */
    Result probe(DeployServer server, String secret);

    /** 执行远程命令。 */
    Result run(DeployServer server, String secret, String remoteCommand, long timeoutMs);

    /** 上传文件（本地绝对路径 → 远端路径）。 */
    Result upload(DeployServer server, String secret, Path local, String remotePath, long timeoutMs);
}
