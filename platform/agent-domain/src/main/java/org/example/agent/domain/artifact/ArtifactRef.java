package org.example.agent.domain.artifact;

import java.util.Map;

/**
 * 产出物引用。
 *
 * @param kind       image / static / report / file
 * @param ref        引用值（镜像 tag、目录、路径）
 * @param digest     sha256（镜像为 image id 或 repo digest）
 * @param sizeBytes  大小
 * @param meta       附加信息，如 {@code digestSource=repo|local}
 */
public record ArtifactRef(String kind, String ref, String digest, long sizeBytes, Map<String, String> meta) {

    public static final String KIND_IMAGE = "image";
    public static final String KIND_STATIC = "static";

    public ArtifactRef {
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    /** 镜像 digest 来源：repo（可跨机 pull）或 local（只能用 image id 导入）。 */
    public String digestSource() {
        return meta.getOrDefault("digestSource", "local");
    }
}
