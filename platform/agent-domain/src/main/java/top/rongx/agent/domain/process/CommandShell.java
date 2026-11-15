package top.rongx.agent.domain.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 用户配置的自由格式构建/测试命令的解释器前缀（平台自适应 + 可配置）。
 *
 * <p>此前把 {@code /bin/sh -c} 写死在 gateway 的确定性检查里：Windows 上直接
 * CreateProcess 失败，POSIX 绝对路径也被写进了平台代码，与「工具链镜像/路径
 * 不该绑死在某台机器上」是同一类问题。这里改为按操作系统推断默认值，并允许
 * 通过配置覆盖（离线环境可指定 Git Bash、PowerShell、自定义 bash 等）。
 *
 * <p>与规约 §10.4 的边界：平台自身构造的命令（git / docker / java）一律走
 * {@link BoundedCommand} 的 argv 形式，不经 shell、不拼字符串。这里的命令是
 * 设置页里由操作员显式保存的策略输入（buildCommand / testCommand），Windows
 * 下 mvn.cmd / npm.cmd 这类批处理入口必须经解释器才能启动，因此以「单个整体
 * 参数」交给解释器，不参与任何字符串拼接，也不做变量展开。
 *
 * <p>配置解析错误不在启动期抛出：与「API Key 不该是启动前置条件」同理，
 * 配置写错时只应让对应的那次检查明确失败，不能让整个应用起不来。
 */
public final class CommandShell {

    private static final String POSIX_PROGRAM = "/bin/sh";
    private static final String POSIX_FLAG = "-c";
    private static final String WINDOWS_PROGRAM = "cmd.exe";
    private static final String WINDOWS_FLAG = "/c";

    /** 前缀最多允许「可执行文件 + 3 个参数」，防止把整条业务命令塞进配置。 */
    private static final int MAX_PREFIX_TOKENS = 4;

    private CommandShell() {
    }

    /**
     * 解析解释器前缀。
     *
     * @param configured 配置值，如 {@code /bin/bash -c}、{@code pwsh -Command}；
     *                   {@code null} / 空白表示按操作系统自适应
     * @return 至少含可执行文件名与一个命令开关的 argv 前缀
     * @throws IllegalArgumentException 配置非法时给出可直接照做的提示
     */
    public static List<String> prefix(String configured) {
        String raw = configured == null ? "" : configured.trim();
        if (raw.isEmpty()) {
            return defaultPrefix();
        }
        List<String> tokens = split(raw);
        if (tokens.isEmpty() || tokens.size() > MAX_PREFIX_TOKENS) {
            throw new IllegalArgumentException(
                    "命令解释器配置非法：应为「可执行文件 + 参数」（如 /bin/bash -c），当前为 " + raw);
        }
        return List.copyOf(tokens);
    }

    /** 单点 OS 判定：命令解释器、生成的辅助脚本共用，避免各模块各写一套。 */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Windows 用 cmd.exe /c，其余用 /bin/sh -c；不写死任何安装目录。 */
    public static List<String> defaultPrefix() {
        return isWindows() ? List.of(WINDOWS_PROGRAM, WINDOWS_FLAG)
                : List.of(POSIX_PROGRAM, POSIX_FLAG);
    }

    /** 把命令作为最后一个整体参数追加，避免逐 token 被二次解释。 */
    public static List<String> argv(List<String> shellPrefix, String command) {
        List<String> argv = new ArrayList<>(shellPrefix);
        argv.add(command);
        return List.copyOf(argv);
    }

    /**
     * 简单分词：支持单/双引号包裹（Windows 路径常含空格）；
     * 不做变量展开、不解释转义，配置值按字面量使用。
     */
    static List<String> split(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean touched = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                touched = true;
            } else if (c == '"' || c == '\'') {
                quote = c;
                touched = true;
            } else if (Character.isWhitespace(c)) {
                if (touched) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    touched = false;
                }
            } else {
                current.append(c);
                touched = true;
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("命令解释器配置存在未闭合的引号：" + text);
        }
        if (touched) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
