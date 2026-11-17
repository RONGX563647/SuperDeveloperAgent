package top.rongx.agent.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把版本号同步到项目根 {@code pom.xml} 的 {@code <version>}。
 *
 * <p>版本号是发布动作的唯一来源，pom 只是投影：发布时在构建用的临时 worktree 里改，
 * 不影响用户工作区，也不会把"改 pom"混进发布提交。快照构建写 {@code x.y.z-SNAPSHOT}，
 * 正式发布写 {@code x.y.z}。
 *
 * <p>只处理根 pom，且跳过 {@code <parent>} 块里的版本（那是父 POM 的版本，不是本项目）。
 */
final class PomVersionSync {

    private static final Pattern VERSION = Pattern.compile("<version>([^<]*)</version>");
    private static final Pattern PARENT = Pattern.compile("(?s)<parent>.*?</parent>");

    private PomVersionSync() {
    }

    /** 同步结果：oldVersion 为空表示没有改动（无 pom 或已经一致）。 */
    record Result(String oldVersion, String newVersion, Path file) {
        boolean changed() {
            return oldVersion != null && !oldVersion.equals(newVersion);
        }
    }

    /**
     * 把 {@code repoRoot/pom.xml} 的版本改成 {@code version}（快照构建带 -SNAPSHOT）。
     *
     * @return 同步结果；没有 pom 时返回空
     */
    static Optional<Result> apply(Path repoRoot, String version, boolean snapshot) {
        if (repoRoot == null || version == null || version.isBlank()) {
            return Optional.empty();
        }
        Path pom = repoRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return Optional.empty();
        }
        String target = snapshot ? version + "-SNAPSHOT" : version;
        try {
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            // 跳过 <parent> 块：第一个 <version> 很可能是父 POM 版本
            int from = 0;
            Matcher parent = PARENT.matcher(text);
            if (parent.find()) {
                from = parent.end();
            }
            Matcher matcher = VERSION.matcher(text);
            matcher.region(from, text.length());
            if (!matcher.find()) {
                return Optional.of(new Result(null, target, pom));
            }
            String old = matcher.group(1).trim();
            if (old.equals(target)) {
                return Optional.of(new Result(old, target, pom));
            }
            String updated = text.substring(0, matcher.start(1)) + target
                    + text.substring(matcher.end(1));
            Files.writeString(pom, updated, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return Optional.of(new Result(old, target, pom));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
