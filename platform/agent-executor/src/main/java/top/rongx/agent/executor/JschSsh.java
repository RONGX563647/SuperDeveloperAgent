package top.rongx.agent.executor;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Vector;

/**
 * 纯 Java SSH 实现（JSch）——设置页的「测试连接」与远端部署共用。
 *
 * <p>为什么不用系统 ssh：
 * <ul>
 *   <li>Windows 没有 sshpass，密码认证无法非交互；</li>
 *   <li>JSch 行为在 Windows/Linux 一致，错误信息可控（超时/认证失败/指纹变化）。</li>
 * </ul>
 *
 * <p>主机指纹（TOFU）：首次连接把指纹写入 {@code known_hosts}；之后指纹变化直接拒绝，
 * 不静默接受（防中间人）。
 */
public class JschSsh {

    private final Path knownHosts;

    public JschSsh(Path knownHosts) {
        this.knownHosts = knownHosts.toAbsolutePath().normalize();
    }

    /** 一次远程执行结果。 */
    public record Result(boolean ok, String stdout, String stderr) {
        public static Result ok(String out) {
            return new Result(true, out, "");
        }

        public static Result failed(String message) {
            return new Result(false, "", message);
        }
    }

    /** 打开会话（含认证与主机指纹校验）。 */
    public Session open(String host, int port, String user, String authType,
                        String keyPath, String secret) throws JSchException, IOException {
        JSch jsch = new JSch();
        if ("KEY".equalsIgnoreCase(authType)) {
            if (keyPath == null || keyPath.isBlank()) {
                throw new JSchException("未配置 SSH 私钥路径");
            }
            String key = expandHome(keyPath);
            if (secret != null && !secret.isBlank()) {
                jsch.addIdentity(key, secret);
            } else {
                jsch.addIdentity(key);
            }
        }
        Files.createDirectories(knownHosts.getParent());
        if (Files.notExists(knownHosts)) {
            Files.writeString(knownHosts, "");
        }
        jsch.setKnownHosts(knownHosts.toString());

        Session session = jsch.getSession(user, host, port);
        if ("PASSWORD".equalsIgnoreCase(authType)) {
            if (secret == null || secret.isBlank()) {
                throw new JSchException("未填写服务器密码");
            }
            session.setPassword(secret);
        }
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "publickey,password,keyboard-interactive");
        session.setConfig(config);
        session.setServerAliveInterval(15_000);
        session.connect(10_000);

        verifyHostKey(jsch, session);
        return session;
    }

    /** TOFU 指纹校验：未知 → 记录；已记录且不一致 → 断开并拒绝。 */
    private void verifyHostKey(JSch jsch, Session session) throws JSchException, IOException {
        HostKey hostKey = session.getHostKey();
        if (hostKey == null) {
            return;
        }
        HostKeyRepository repository = jsch.getHostKeyRepository();
        byte[] keyBytes = java.util.Base64.getDecoder().decode(hostKey.getKey());
        int status = repository.check(hostKey.getHost(), keyBytes);
        if (status == HostKeyRepository.CHANGED) {
            session.disconnect();
            throw new JSchException(
                    "主机指纹与已保存的不一致（可能的中间人攻击）：" + hostKey.getHost()
                            + "。如确认服务器重装，请删除 " + knownHosts + " 中对应条目后重试");
        }
        if (status == HostKeyRepository.NOT_INCLUDED) {
            repository.add(hostKey, null);
            // 某些实现不会自动落盘，这里兜底追加一行
            String encoded = hostKey.getKey();
            String line = hostKey.getHost() + " " + hostKey.getType() + " " + encoded;
            String existing = Files.readString(knownHosts);
            if (!existing.contains(encoded)) {
                Files.writeString(knownHosts, existing
                        + (existing.endsWith("\n") || existing.isEmpty() ? "" : "\n") + line + "\n");
            }
        }
    }

    /** 未显式指定预算时的远程命令上限：必须有限，不能让远端 hang 无限拖住调用方。 */
    private static final long DEFAULT_REMOTE_TIMEOUT_MS = 30_000L;
    /** 通道建立阶段的超时（与整体预算分开，避免慢握手吃掉整个预算）。 */
    private static final long CHANNEL_CONNECT_TIMEOUT_MS = 10_000L;
    /** 远程输出上限，避免异常命令刷爆内存。 */
    private static final int MAX_REMOTE_OUTPUT_CHARS = 200_000;

    /** 兼容既有调用（连通性探测、测试）：使用默认预算。 */
    public Result run(String host, int port, String user, String authType,
                      String keyPath, String secret, String command) {
        return run(host, port, user, authType, keyPath, secret, command, DEFAULT_REMOTE_TIMEOUT_MS);
    }

    /**
     * 执行远程命令：并行排空 stdout/stderr，并按 {@code timeoutMs} 硬超时。
     *
     * <p>原实现先顺序 {@code readAllBytes()} 两个流（stderr 写满就会互锁），
     * 再 {@code while (!isClosed()) sleep(50)} 无上限等待；上层
     * {@code SshAccess.run(..., timeoutMs)} 传来的预算被整段丢弃。
     * 远端命令卡住时设置页「测试连接」/部署会永久转圈且没有任何错误。
     */
    public Result run(String host, int port, String user, String authType,
                      String keyPath, String secret, String command, long timeoutMs) {
        long budget = Math.max(1_000L, timeoutMs);
        long deadline = System.nanoTime() + budget * 1_000_000L;
        Session session = null;
        ChannelExec channel = null;
        try {
            session = open(host, port, user, authType, keyPath, secret);
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            InputStream out = channel.getInputStream();
            InputStream err = channel.getErrStream();
            channel.connect((int) Math.min(CHANNEL_CONNECT_TIMEOUT_MS, budget));
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outPump = Thread.ofVirtual().start(() -> pump(out, stdout));
            Thread errPump = Thread.ofVirtual().start(() -> pump(err, stderr));

            boolean closed = awaitClosed(channel, deadline, outPump, errPump);
            joinQuietly(outPump);
            joinQuietly(errPump);
            if (!closed) {
                return Result.failed("SSH 命令超时（" + (budget / 1000) + "s），已断开："
                        + command);
            }
            if (channel.getExitStatus() == 0) {
                return Result.ok(stdout.toString().trim());
            }
            String reason = stderr.toString().trim();
            return Result.failed(reason.isBlank() ? stdout.toString().trim() : reason);
        } catch (JSchException e) {
            return Result.failed(friendly(e));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Result.failed("SSH 执行失败：" + e.getMessage());
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    /** 等待通道关闭且两条输出流都已排空；超时返回 false 让 finally 断开连接。 */
    private boolean awaitClosed(ChannelExec channel, long deadline,
                                Thread outPump, Thread errPump) throws InterruptedException {
        while (System.nanoTime() < deadline) {
            if (channel.isClosed() && !outPump.isAlive() && !errPump.isAlive()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /** 并行排空单条远程流（按字符解码 + 限长），进程断开时自然结束。 */
    private static void pump(InputStream in, StringBuilder sink) {
        try (var reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                synchronized (sink) {
                    sink.append(buffer, 0, read);
                    int overflow = sink.length() - MAX_REMOTE_OUTPUT_CHARS;
                    if (overflow > 0) {
                        sink.delete(0, overflow);
                    }
                }
            }
        } catch (IOException ignored) {
            // 通道断开时流会关闭，属正常收尾
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 兼容既有调用：上传预算取默认值。 */
    public Result upload(String host, int port, String user, String authType,
                         String keyPath, String secret, Path local, String remotePath) {
        return upload(host, port, user, authType, keyPath, secret, local, remotePath,
                DEFAULT_REMOTE_TIMEOUT_MS);
    }

    /** 上传文件：超时预算通过 JSch 的 session timeout 生效，不再被丢弃。 */
    public Result upload(String host, int port, String user, String authType,
                         String keyPath, String secret, Path local, String remotePath,
                         long timeoutMs) {
        Session session = null;
        ChannelSftp sftp = null;
        try {
            session = open(host, port, user, authType, keyPath, secret);
            session.setTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1_000L, timeoutMs)));
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect((int) Math.min(CHANNEL_CONNECT_TIMEOUT_MS, Math.max(1_000L, timeoutMs)));
            try {
                sftp.mkdir(remotePath.substring(0, remotePath.lastIndexOf('/')));
            } catch (Exception ignored) {
                // 目录已存在
            }
            try (InputStream in = Files.newInputStream(local)) {
                sftp.put(in, remotePath);
            }
            return Result.ok("uploaded " + local.getFileName());
        } catch (JSchException e) {
            return Result.failed(friendly(e));
        } catch (Exception e) {
            return Result.failed("SFTP 上传失败：" + e.getMessage());
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    private String friendly(JSchException e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("Auth fail") || message.contains("Auth cancel")) {
            return "SSH 认证失败：用户名/密码或私钥不正确";
        }
        if (message.contains("timeout") || message.contains("Timeout")) {
            return "SSH 连接超时：检查主机、端口与网络/防火墙";
        }
        if (message.contains("UnknownHost")) {
            return "无法解析主机名：" + message;
        }
        if (message.contains("Connection refused")) {
            return "连接被拒绝：SSH 端口未监听或被防火墙拦截";
        }
        return "SSH 连接失败：" + message;
    }

    private String expandHome(String path) {
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home"), path.substring(2)).toString();
        }
        return path;
    }

    /** 仅用于测试：把 known_hosts 文件路径暴露出来。 */
    Path knownHostsFile() {
        return knownHosts;
    }

    /** 让 JSch 的 sftp 目录向量不产生告警（保留方法以便未来列目录）。 */
    @SuppressWarnings("unused")
    private Vector<ChannelSftp.LsEntry> list(ChannelSftp sftp, String path) throws Exception {
        return sftp.ls(path);
    }
}
