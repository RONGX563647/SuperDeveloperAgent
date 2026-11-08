package top.rongx.agent.executor;

import java.nio.file.Path;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.SshAccess;

/**
 * SSH 访问实现：委托给纯 Java 的 {@link JschSsh}。
 *
 * <p>不再依赖系统 `ssh`/`sshpass`：Windows/Linux 行为一致，密码认证可用，
 * 错误信息可读（认证失败/超时/指纹变化分别提示）。
 */
public class SshExec implements SshAccess {

    private final JschSsh ssh;

    /** 默认指纹库：{@code ~/.agent/ssh_known_hosts}（测试/无工作区场景）。 */
    public SshExec() {
        this(Path.of(System.getProperty("user.home"), ".agent", "ssh_known_hosts"));
    }

    /** 指定 known_hosts（生产用工作区 {@code _platform/known_hosts}）。 */
    public SshExec(Path knownHosts) {
        this.ssh = new JschSsh(knownHosts);
    }

    @Override
    public Result probe(DeployServer server, String secret) {
        JschSsh.Result echo = ssh.run(server.host(), server.port(), server.sshUser(),
                server.authType(), server.keyPath(), secret, "echo ok");
        if (!echo.ok()) {
            return Result.failed(1, echo.stderr());
        }
        JschSsh.Result docker = ssh.run(server.host(), server.port(), server.sshUser(),
                server.authType(), server.keyPath(), secret,
                "docker version --format {{.ServerVersion}}");
        if (!docker.ok()) {
            return Result.failed(1, "SSH 已连通，但远端 docker 不可用：" + docker.stderr());
        }
        return Result.ok("docker " + docker.stdout());
    }

    @Override
    public Result run(DeployServer server, String secret, String remoteCommand, long timeoutMs) {
        JschSsh.Result result = ssh.run(server.host(), server.port(), server.sshUser(),
                server.authType(), server.keyPath(), secret, remoteCommand);
        return result.ok()
                ? Result.ok(result.stdout())
                : Result.failed(1, result.stderr());
    }

    @Override
    public Result upload(DeployServer server, String secret, Path local, String remotePath,
                         long timeoutMs) {
        JschSsh.Result result = ssh.upload(server.host(), server.port(), server.sshUser(),
                server.authType(), server.keyPath(), secret, local, remotePath);
        return result.ok()
                ? Result.ok(result.stdout())
                : Result.failed(1, result.stderr());
    }
}
