package top.rongx.agent.domain.env;

import java.util.ArrayList;
import java.util.List;

/**
 * 中间件寻址规则：同一份资源登记，在不同运行形态下映射成不同的连接地址。
 *
 * <ul>
 *   <li>{@link RuntimeMode#LOCAL_DEV}：应用是宿主机进程 → {@code 127.0.0.1:发布端口}；</li>
 *   <li>{@link RuntimeMode#LOCAL_TEST}：应用是容器 → {@code 容器名:容器内端口}（docker 网络 DNS）；</li>
 *   <li>{@link RuntimeMode#SERVER}：应用在服务器上 → 资源登记的服务器地址 + 发布端口。</li>
 * </ul>
 *
 * <p>登记值保持不变：{@code port} 是发布到宿主机的端口，{@code containerPort} 是容器内端口，
 * {@code internalHost} 是容器名/服务名。解析后的副本只用于注入与展示。
 */
public final class EnvAddressing {

    private EnvAddressing() {
    }

    /** 按模板形态解析全部资源。 */
    public static List<EnvResource> resolve(List<EnvResource> resources, EnvTemplate template) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        RuntimeMode mode = template == null
                ? RuntimeMode.LOCAL_DEV : template.mode();
        List<EnvResource> out = new ArrayList<>(resources.size());
        for (EnvResource r : resources) {
            if (r != null) {
                out.add(resolve(r, mode));
            }
        }
        return List.copyOf(out);
    }

    /** 单个资源解析（返回副本，不改登记值）。 */
    public static EnvResource resolve(EnvResource r, RuntimeMode mode) {
        if (r == null) {
            return null;
        }
        RuntimeMode effective = mode == null ? RuntimeMode.LOCAL_DEV : mode;
        String host = r.host();
        int port = r.port();
        switch (effective) {
            case LOCAL_DEV -> {
                host = "127.0.0.1";
                port = publishedPort(r);
            }
            case LOCAL_TEST -> {
                host = internalHost(r);
                port = r.containerPort() > 0 ? r.containerPort() : publishedPort(r);
            }
            case SERVER -> port = publishedPort(r);
        }
        if (host == null || host.isBlank()) {
            host = internalHost(r);
        }
        return new EnvResource(r.id(), r.env(), r.kind(), r.name(), host, port,
                r.containerPort(), r.internalHost(), r.databaseName(), r.username(), r.password(),
                r.network(), r.serverId(), r.source(), r.running(), r.lastCheckAt(), r.lastError());
    }

    /** 容器内 / 共享网络内的地址。 */
    public static String internalHost(EnvResource r) {
        if (r.internalHost() != null && !r.internalHost().isBlank()) {
            return r.internalHost();
        }
        return r.name() == null || r.name().isBlank() ? r.host() : r.name();
    }

    private static int publishedPort(EnvResource r) {
        if (r.port() > 0) {
            return r.port();
        }
        return r.containerPort();
    }
}
