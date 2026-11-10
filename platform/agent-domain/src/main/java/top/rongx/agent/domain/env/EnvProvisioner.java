package top.rongx.agent.domain.env;

import java.util.List;

/**
 * 环境容器自愈：为「项目 + 环境」准备隔离的中间件容器。
 *
 * <p>解决的问题：部署前只知道"资源登记表里有没有容器"，但项目第一次部署时
 * 环境里往往一个容器都没有，直接失败在"缺 DB_HOST / REDIS_HOST"这种前置检查上。
 *
 * <p>规则：
 * <ul>
 *   <li>容器按项目隔离：{@code <namespace>-<project>-<kind>}，不与机器上其它项目的
 *       中间件混用（旧实现按"主机相同"匹配，会把无关容器当成项目依赖）；</li>
 *   <li>容器缺失时按需创建，连接信息（地址/端口/账号/密码）写入项目环境覆盖，明文不落日志；</li>
 *   <li>必填但对中间件无关键（如 TOKEN_SECRET）缺失时自动生成随机值；</li>
 *   <li>只处理本机 Docker；远程服务器环境保持只读，交给人确认。</li>
 * </ul>
 */
public interface EnvProvisioner {

    /**
     * 一次自愈的结果。
     *
     * @param ok        是否全部就绪
     * @param created   新建的容器名
     * @param started   已存在但本次启动的容器名
     * @param injected  从项目自身 .env / .env.example 注入的变量名（只有键）
     * @param generated 自动生成的变量名（只有键，没有值）
     * @param failed    失败信息（"名称：原因"）
     * @param message   汇总文案（可直接写进部署日志）
     */
    record Result(boolean ok, List<String> created, List<String> started, List<String> injected,
                  List<String> generated, List<String> failed, String message) {

        public Result {
            created = created == null ? List.of() : List.copyOf(created);
            started = started == null ? List.of() : List.copyOf(started);
            injected = injected == null ? List.of() : List.copyOf(injected);
            generated = generated == null ? List.of() : List.copyOf(generated);
            failed = failed == null ? List.of() : List.copyOf(failed);
        }

        public static Result ok(List<String> created, List<String> started, List<String> generated) {
            return ok(created, started, List.of(), generated);
        }

        public static Result ok(List<String> created, List<String> started, List<String> injected,
                                List<String> generated) {
            boolean changed = !created.isEmpty() || !started.isEmpty() || !generated.isEmpty()
                    || !injected.isEmpty();
            StringBuilder sb = new StringBuilder();
            sb.append(injected.isEmpty() ? "" : "已注入项目变量 " + String.join("、", injected));
            sb.append(created.isEmpty() ? "" : (sb.length() > 0 ? "；" : "")
                    + "已创建容器 " + String.join("、", created));
            sb.append(started.isEmpty() ? "" : (sb.length() > 0 ? "；" : "")
                    + "已启动容器 " + String.join("、", started));
            sb.append(generated.isEmpty() ? "" : (sb.length() > 0 ? "；" : "")
                    + "已生成变量 " + String.join("、", generated));
            return new Result(true, created, started, injected, generated, List.of(),
                    changed ? sb.toString() : "环境容器已就绪");
        }

        public static Result failed(List<String> failed, String message) {
            return new Result(false, List.of(), List.of(), List.of(), List.of(), failed, message);
        }
    }

    /**
     * 确保项目在该环境需要的中间件容器存在并运行，缺失的按需创建。
     *
     * @param requiredKeys 部署定义里的必填变量名（如 DB_HOST / REDIS_HOST / TOKEN_SECRET），
     *                     用于推断需要哪些中间件、以及哪些密钥需要自动生成
     */
    Result ensure(String projectId, String env, List<String> requiredKeys);

    /**
     * 只把项目源码（{@code .env} 等）声明的非空变量写入项目覆盖，不创建/启动容器。
     *
     * <p>dev 本机部署不会走 {@link #ensure} 的容器自愈，但仍需要仓库里已有的连接信息，
     * 否则只能注入环境模板/手工覆盖，出现"源码有值却没注入"。
     *
     * @return 实际写入的变量名（只有键，值不回显）
     */
    default List<String> seedDeclared(String projectId, String env) {
        return List.of();
    }

    /**
     * 按当前运行形态重算「被项目引用的中间件」的连接地址，写回项目覆盖。
     *
     * <p>dev 是本机模式：宿主机只能用 {@code 127.0.0.1:<发布端口>}。历史数据里如果存的是
     * 容器内网名，这里负责把它纠正过来；test/prod 不做任何改动。
     *
     * @return 实际校正的资源名（空 = 无需校正）
     */
    default List<String> syncEndpoints(String projectId, String env) {
        return List.of();
    }

    /** 空实现（单测 / 未装配）：不自愈，也不阻塞部署。 */
    static EnvProvisioner noop() {
        return (projectId, env, requiredKeys) -> Result.ok(List.of(), List.of(), List.of());
    }
}
