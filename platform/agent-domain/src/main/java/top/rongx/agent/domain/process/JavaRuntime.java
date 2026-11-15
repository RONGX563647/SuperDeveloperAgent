package top.rongx.agent.domain.process;

import java.nio.file.Path;

/**
 * 当前运行平台的 Java 可执行文件解析（不绑定任何安装目录）。
 *
 * <p>部署器派生 {@code java -jar} 子进程时，写 {@code "java"} 会去 PATH 上找——
 * 那可能是另一个版本（例如平台跑在 JDK 25、PATH 里是 JDK 17），于是用错 JDK
 * 启动 jar，报出一串与平台无关的 class version 错误。也不能把某个绝对路径
 * 写进代码/配置：换台机器就失效。
 *
 * <p>正确做法是向<b>当前 JVM</b> 问它自己是谁：JDK 9+ 的
 * {@link ProcessHandle#info()} 给出实际启动本进程的可执行文件；拿不到时再用
 * {@code java.home} 拼接兜底，最后才回退到 PATH 名称。
 */
public final class JavaRuntime {

    private JavaRuntime() {
    }

    /** 与当前 JVM 一致的 {@code java} 可执行文件（绝对路径优先）。 */
    public static String javaBinary() {
        String current = ProcessHandle.current().info().command().orElse("");
        if (!current.isBlank()) {
            return current;
        }
        String home = System.getProperty("java.home", "");
        if (!home.isBlank()) {
            String exe = CommandShell.isWindows() ? "java.exe" : "java";
            Path candidate = Path.of(home, "bin", exe);
            if (java.nio.file.Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        // 最后兜底：让 PATH 决定（jlink 精简运行时等场景）
        return CommandShell.isWindows() ? "java.exe" : "java";
    }
}
