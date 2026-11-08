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

    /** 执行远程命令（合并 stderr，返回可读错误）。 */
    public Result run(String host, int port, String user, String authType,
                      String keyPath, String secret, String command) {
        Session session = null;
        ChannelExec channel = null;
        try {
            session = open(host, port, user, authType, keyPath, secret);
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            InputStream out = channel.getInputStream();
            InputStream err = channel.getErrStream();
            channel.connect(10_000);
            String stdout = readAll(out);
            String stderr = readAll(err);
            while (!channel.isClosed()) {
                Thread.sleep(50);
            }
            if (channel.getExitStatus() == 0) {
                return Result.ok(stdout);
            }
            return Result.failed(stderr.isBlank() ? stdout : stderr);
        } catch (JSchException e) {
            return Result.failed(friendly(e));
        } catch (IOException | InterruptedException e) {
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

    /** 上传文件。 */
    public Result upload(String host, int port, String user, String authType,
                         String keyPath, String secret, Path local, String remotePath) {
        Session session = null;
        ChannelSftp sftp = null;
        try {
            session = open(host, port, user, authType, keyPath, secret);
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(10_000);
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

    private String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        byte[] bytes = in.readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8).trim();
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
