package top.rongx.agent.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 仓库目录布局探测：后端/前端模块根、可运行 jar、前端产物新鲜度。
 *
 * <p>本机部署与宿主部署都要回答同一组问题（后端在哪、前端在哪、哪个 jar 真能跑），
 * 这里集中实现，避免引擎与部署器各维护一份判断。
 */
final class RepoLayout {

    private RepoLayout() {
    }

    /** 后端根：仓库根有 pom.xml 用它；否则一层子目录（RuoYi 的 RuoYi-Vue/）。 */
    static Path detectBackendRoot(Path repo) {
        if (Files.isRegularFile(repo.resolve("pom.xml"))) {
            return repo;
        }
        for (Path child : children(repo)) {
            if (Files.isRegularFile(child.resolve("pom.xml"))
                    || Files.isRegularFile(child.resolve("ruoyi-admin/pom.xml"))
                    || Files.isDirectory(child.resolve("src/main/java"))) {
                return child;
            }
        }
        return null;
    }

    /** 前端根：仓库根 package.json；否则一层子目录；再否则两层（RuoYi-Vue/ruoyi-ui）。 */
    static Path detectFrontendRoot(Path repo) {
        if (Files.isRegularFile(repo.resolve("package.json"))) {
            return repo;
        }
        List<Path> first = children(repo);
        for (Path child : first) {
            if (Files.isRegularFile(child.resolve("package.json"))) {
                return child;
            }
        }
        List<String> names = List.of("ui", "web", "frontend", "client", "ruoyi-ui");
        for (Path child : first) {
            for (Path second : children(child)) {
                if (names.contains(second.getFileName().toString().toLowerCase())
                        && Files.isRegularFile(second.resolve("package.json"))) {
                    return second;
                }
            }
        }
        return null;
    }

    static List<Path> children(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 可运行 jar：优先含 BOOT-INF 的 Spring Boot 包，多个取最新。 */
    static Path findRunnableJar(Path backendRoot) {
        try (var stream = Files.walk(backendRoot, 4)) {
            List<Path> candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-javadoc.jar"))
                    .filter(p -> p.getParent() != null
                            && "target".equals(p.getParent().getFileName().toString()))
                    .filter(RepoLayout::isExecutableJar)
                    .toList();
            return candidates.stream()
                    .max(java.util.Comparator.comparingLong(RepoLayout::lastModified))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    static boolean isExecutableJar(Path jar) {
        try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile())) {
            return file.getEntry("BOOT-INF/") != null;
        } catch (IOException e) {
            return false;
        }
    }

    static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 前端 dist 是否比 src 新（避免每次发布都全量打包）。 */
    static boolean isFrontendUpToDate(Path frontendRoot, Path dist) {
        Path index = dist.resolve("index.html");
        if (!Files.isRegularFile(index)) {
            return false;
        }
        Path src = frontendRoot.resolve("src");
        if (!Files.isDirectory(src)) {
            return true;
        }
        long distTime = lastModified(index);
        try (var stream = Files.walk(src)) {
            return stream.filter(Files::isRegularFile)
                    .noneMatch(p -> lastModified(p) > distTime);
        } catch (IOException e) {
            return false;
        }
    }
}
