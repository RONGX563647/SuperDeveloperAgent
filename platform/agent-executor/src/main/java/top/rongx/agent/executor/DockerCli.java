package top.rongx.agent.executor;

import java.util.ArrayList;
import java.util.List;

/** Docker CLI 可执行文件的唯一来源，允许指向 podman 等兼容实现。 */
public final class DockerCli {

    private final String binary;

    public DockerCli(String binary) {
        this.binary = binary == null || binary.isBlank() ? "docker" : binary.trim();
    }

    public static DockerCli defaults() {
        return new DockerCli("docker");
    }

    public String binary() {
        return binary;
    }

    public List<String> cmd(String... args) {
        List<String> argv = new ArrayList<>(args.length + 1);
        argv.add(binary);
        for (String arg : args) {
            argv.add(arg);
        }
        return List.copyOf(argv);
    }

    public List<String> cmd(List<String> args) {
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(binary);
        argv.addAll(args);
        return List.copyOf(argv);
    }
}
