package top.rongx.agent.executor;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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

    /**
     * 打开会话（含认证与主机指纹校验）。
     *
     * <p>瞬断（超时 / connection reset / broken pipe）重试一次：生产网络跨机房、
     * 跳板机、云防火墙空闲回收都会造成首次握手失败，重试一次通常即可成功；
     * 认证失败等确定性错误不重试，避免白等。
     */
    public Session open(String host, int port, String user, String authType,
                        String keyPath, String secret) throws JSchException, IOException {
        JSchException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return openOnce(host, port, user, authType, keyPath, secret);
            } catch (JSchException e) {
                last = e;
                if (attempt == 0 && retryable(e)) {
                    try {
                        Thread.sleep(300L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
        throw last;
    }

    private Session openOnce(String host, int port, String user, String authType,
                             String keyPath, String secret) throws JSchException, IOException {
        JSch jsch = new JSch();
        boolean keyAuth = "KEY".equalsIgnoreCase(authType);
        if (keyAuth) {
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
        Path parent = knownHosts.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.notExists(knownHosts)) {
            Files.writeString(knownHosts, "");
        }
        jsch.setKnownHosts(knownHosts.toString());

        Session session = jsch.getSession(user, host, port);
        if (!keyAuth && (secret == null || secret.isBlank())) {
            throw new JSchException("未填写服务器密码");
        }
        if (secret != null && !secret.isBlank()) {
            if (!keyAuth) {
                session.setPassword(secret);
            }
            // 很多 sshd 把密码认证交给 PAM 的 keyboard-interactive：只 setPassword
            // 时 JSch 不会回答挑战，表现为"密码正确却 Auth fail"。
            session.setUserInfo(new UserInfo() {
                @Override
                public String getPassphrase() {
                    return secret;
                }

                @Override
                public String getPassword() {
                    return secret;
                }

                @Override
                public boolean promptPassword(String message) {
                    return true;
                }

                @Override
                public boolean promptPassphrase(String message) {
                    return true;
                }

                @Override
                public boolean promptYesNo(String message) {
                    return false;
                }

                @Override
                public void showMessage(String message) {
                    // 非交互场景忽略服务端提示
                }
            });
        }
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", keyAuth
                ? "publickey,keyboard-interactive" : "password,keyboard-interactive");
        session.setConfig(config);
        session.setServerAliveInterval(15_000);
        session.connect(10_000);

        verifyHostKey(jsch, session);
        return session;
    }

    /** 只对网络瞬断重试；认证/密钥/算法问题重试也不会变好。 */
    private static boolean retryable(JSchException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return message.contains("timeout") || message.contains("timed out")
                || message.contains("connection reset") || message.contains("broken pipe")
                || message.contains("session is down") || message.contains("connection is closed");
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

    /**
     * 上传文件：优先 SFTP；服务器未启用 sftp 子系统（常见的精简 sshd / 跳板机）时
     * 自动退回 {@code exec + cat} 二进制上传，避免"测试机 SFTP 正常、生产机直接失败"。
     *
     * <p>远端父目录逐级创建：配置的 {@code /opt/agent/deploy} 首次部署时往往连
     * {@code /opt/agent} 都不存在，单层 mkdir 会失败并让 put 一起失败。
     */
    public Result upload(String host, int port, String user, String authType,
                         String keyPath, String secret, Path local, String remotePath,
                         long timeoutMs) {
        Session session = null;
        try {
            session = open(host, port, user, authType, keyPath, secret);
            session.setTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1_000L, timeoutMs)));
            try {
                return uploadSftp(session, local, remotePath, timeoutMs);
            } catch (Exception sftpFailure) {
                if (!sftpUnavailable(sftpFailure)) {
                    return Result.failed("SFTP 上传失败：" + sftpFailure.getMessage());
                }
                Result fallback = uploadViaExec(session, local, remotePath, timeoutMs);
                if (fallback.ok()) {
                    return fallback;
                }
                return Result.failed("SFTP 上传失败（" + sftpFailure.getMessage()
                        + "），改用 exec 上传也失败：" + fallback.stderr());
            }
        } catch (JSchException e) {
            return Result.failed(friendly(e));
        } catch (Exception e) {
            return Result.failed("SFTP 上传失败：" + e.getMessage());
        } finally {
            if (session != null) {
                session.disconnect();
            }
        }
    }

    private Result uploadSftp(Session session, Path local, String remotePath, long timeoutMs)
            throws JSchException, SftpException, IOException {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        try {
            sftp.connect((int) Math.min(CHANNEL_CONNECT_TIMEOUT_MS, Math.max(1_000L, timeoutMs)));
            String parent = parentOf(remotePath);
            if (!parent.isEmpty()) {
                mkdirs(sftp, parent);
            }
            try (InputStream in = Files.newInputStream(local)) {
                sftp.put(in, remotePath);
            }
            return Result.ok("uploaded " + local.getFileName());
        } finally {
            sftp.disconnect();
        }
    }

    /** 逐级建目录；已存在（SftpException）继续下一层。 */
    private static void mkdirs(ChannelSftp sftp, String dir) {
        StringBuilder current = new StringBuilder();
        for (String part : dir.split("/")) {
            if (part.isEmpty()) {
                if (current.isEmpty() && dir.startsWith("/")) {
                    current.append('/');
                }
                continue;
            }
            if (!current.isEmpty() && current.charAt(current.length() - 1) != '/') {
                current.append('/');
            }
            current.append(part);
            try {
                sftp.mkdir(current.toString());
            } catch (SftpException ignored) {
                // 目录已存在或无权创建：交给后续 put 给出真实原因
            }
        }
    }

    /** exec 兜底上传：{@code mkdir -p parent && cat > file}，按二进制写 stdin。 */
    private Result uploadViaExec(Session session, Path local, String remotePath, long timeoutMs) {
        long budget = Math.max(1_000L, timeoutMs);
        long deadline = System.nanoTime() + budget * 1_000_000L;
        ChannelExec channel = null;
        try {
            String parent = parentOf(remotePath);
            String command = (parent.isEmpty() ? "" : "mkdir -p " + shellQuote(parent) + " && ")
                    + "cat > " + shellQuote(remotePath);
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            InputStream err = channel.getErrStream();
            channel.connect((int) Math.min(CHANNEL_CONNECT_TIMEOUT_MS, budget));
            StringBuilder stderr = new StringBuilder();
            Thread errPump = Thread.ofVirtual().start(() -> pump(err, stderr));
            try (OutputStream out = channel.getOutputStream();
                 InputStream in = Files.newInputStream(local)) {
                in.transferTo(out);
                out.flush();
            }
            while (!channel.isClosed() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            joinQuietly(errPump);
            if (!channel.isClosed()) {
                return Result.failed("SSH 上传超时（" + (budget / 1000) + "s）：" + remotePath);
            }
            if (channel.getExitStatus() == 0) {
                return Result.ok("uploaded " + local.getFileName());
            }
            String reason = stderr.toString().trim();
            return Result.failed(reason.isEmpty() ? "远端写入失败：" + remotePath : reason);
        } catch (JSchException e) {
            return Result.failed(friendly(e));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Result.failed("exec 上传失败：" + e.getMessage());
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    /** 只有"通道/子系统不可用"才值得改用 exec；权限、磁盘满等问题换通道也一样。 */
    private static boolean sftpUnavailable(Exception e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return message.contains("channel") || message.contains("subsystem")
                || message.contains("sftp") || message.contains("session is down")
                || message.contains("connection reset");
    }

    private static String parentOf(String remotePath) {
        int slash = remotePath == null ? -1 : remotePath.lastIndexOf('/');
        return slash <= 0 ? "" : remotePath.substring(0, slash);
    }

    /** POSIX shell 单引号转义：路径含空格、引号、$ 时不会被远端 shell 展开。 */
    static String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
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
        if (message.contains("Algorithm negotiation fail")) {
            return "SSH 算法协商失败：服务器与客户端没有共同支持的加密/主机密钥算法"
                    + "（老旧服务器常见），请升级 sshd 或检查其 KexAlgorithms/HostKeyAlgorithms";
        }
        if (message.contains("invalid privatekey") || message.contains("invalid key")
                || message.contains("PEM")) {
            return "SSH 私钥无法解析：请确认为 PEM/OpenSSH 格式，且已填写私钥口令（如有）";
        }
        if (message.toLowerCase(java.util.Locale.ROOT).contains("passphrase")) {
            return "SSH 私钥已加密：请在设置页填写私钥口令后重试";
        }
        if (message.contains("Too many authentication failures")) {
            return "SSH 认证失败次数过多：请确认私钥路径正确，或在服务器端调整 MaxAuthTries";
        }
        if (message.contains("No such file") || message.contains("not found")) {
            return "SSH 配置引用的文件不存在：" + message;
        }
        if (message.contains("No more authentication methods")) {
            return "SSH 认证方式均不可用：请确认服务器允许 publickey/password 认证";
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
}
