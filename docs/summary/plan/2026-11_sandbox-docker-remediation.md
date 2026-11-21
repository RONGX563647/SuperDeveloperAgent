---
canonical: docs/summary/2026-11/2026-11-20_sandbox-docker-remediation.md
schema: session-plan/v1
kind: plan
slug: sandbox-docker-remediation
period: 2026-11
createdAt: 2026-11-20T23:30:00+08:00
updatedAt: 2026-11-21T15:15:00+08:00
status: done
owner: YG
verdict: FAIL
supersedes: null
supersededBy: null
refs: []
---

# 沙箱与 Docker 机制修复方案（2026-11）

## 概述

评审对象：`agent-executor` 的沙箱执行面（`DockerSandbox` / `DockerActionRunner`）、部署面（`DockerDeployer` /
`ServerDeployer` / `HostDeployer`）、环境中间件面（`DockerEnvProvisioner`）与交付镜像（根 `Dockerfile`）。

结论：**隔离参数本身是对的，坏在四处**——(1) 策略层是死代码（`require*` 零生产调用）；(2) 交付镜像把
`AGENT_SANDBOX_MODE` 改成 `process`，隔离被整体拆除且无人拦；(3) 缓存卷与端口这条"运维小逻辑"被手写了
5~7 份，已经出现真实漂移；(4) 同一个 `readAllBytes()` 卡死缺陷修了 8 处、漏了第 9 处。

本方案与上一版 P0/P1/P2 清单的差别不在分级，而在**每条缺口的落点性质**：上一版给的是"该改什么"，
本版给的是"改成什么之后**无法再退化**"——优先选可被守卫测试锁住的结构性修法，优先删除而不是新增，
每条都带可复制的验收命令与反证。

---

## 现状与证据

### 1.1 本机实测基线

| 项 | 命令 | 实测输出 |
|---|---|---|
| Docker 守护 | `docker info --format '{{.ServerVersion}}'` | `29.8.2`，exit 0 |
| 运行时清单 | `docker info --format '{{json .Runtimes}}'` | `io.containerd.runc.v2` / `nvidia` / `runc`（**无 runsc**） |
| Compose | `docker compose version` | `v5.6.0`（支持 `up -d --wait`） |
| Buildx | `docker buildx version` | `v0.37.2` |
| 临时端口映射 | `docker run -d -p 127.0.0.1::8080 busybox:1.36 sleep 30` → `docker port <name> 8080` | `127.0.0.1:61487`（可用，是端口分配的替代方案） |
| 归档门禁 | `python tools/session-archive/gate.py validate` | `会话归档校验通过（173 份）` |

### 1.2 交付上下文体积（`.dockerignore` 漏项实测）

| 目录 | 体积 | 文件数 | 是否在 `.dockerignore` |
|---|---|---|---|
| `.agent/` | 166.1 MB | 6959 | **否** |
| `platform/agent-gateway/target/` | 154.0 MB | 829 | **否** |
| `platform/agent-executor/target/` | 7.5 MB | 428 | **否** |
| `platform/web/dist/` | 2.4 MB | 32 | 否（**必须保留**，Dockerfile 要 COPY 它） |

`.gitignore:63` 与 `platform/.gitignore:2` 都已排除 `.agent/` 与 `target/`，但 `.dockerignore` 各自为政，
构建上下文白送 320 MB+ / 8200 文件。

### 1.3 死策略层（零生产调用，实测）

```
$ grep -rn "requireNetworkFor|requireNoExtraMounts|requireUnprivileged|requireRuntimeAvailable" platform
agent-executor/.../DockerSandbox.java:925  public void requireNetworkFor(...)
agent-executor/.../DockerSandbox.java:953  public void requireNoExtraMounts(...)
agent-executor/.../DockerSandbox.java:970  public void requireUnprivileged(...)
agent-executor/.../DockerSandbox.java:990  public void requireRuntimeAvailable(...)
agent-executor/src/test/.../DockerSandboxDeniedTest.java:34,49,50,57,66,72,81,87,88   ← 全是测试
agent-executor/src/test/.../DockerSandboxNetworkTest.java:101                        ← 全是测试
```

`DockerSandboxDeniedTest` 的用例名写着"网络关断但阶段要求外联：拒绝并给出可执行建议"，而**真实构建路径
一次都没调用** → 结构化拒绝（E-707 `SandboxDeniedException`）在线上从未出现过，用户看到的仍是
60 秒后的 `Could not resolve host`。同理 `agent.sandbox.runtime=runsc` 在本机（无 runsc）不会被拦，
要到 `docker run` 才炸出一句 CLI 原始报错。

### 1.4 交付镜像把隔离关掉了

- `Dockerfile:17` `ENV AGENT_SANDBOX_MODE=process`，注释解释"容器部署下隔离边界由容器本身承担"。
  这句话只在"平台容器内没有不可信代码"时成立，而本平台的业务恰恰是**在容器里跑仓库里的构建配方**。
- 全文件无 `USER`（以 root 运行）、无 `HEALTHCHECK`、无 `VOLUME`、无 digest 固定。
- `SandboxConfig:107` `default ->` 把任何未知 mode 当 docker 处理 —— 拼写错误（`dockr`）不报错。

### 1.5 缓存卷与继承（可复现的碰撞 + 泄漏）

```java
// DockerSandbox.java:343-351
static String cacheKey(Path workspace) {
    Path parent = workspace.getParent();
    if (parent != null && "worktrees".equals(...)) return parent.getParent().getFileName().toString();
    return workspace.getFileName().toString();          // ← 只取目录名
}
// DockerSandbox.java:811-815
static String safeVolumeName(String raw) {
    String normalized = raw.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
    return normalized.length() > 60 ? normalized.substring(0, 60) : normalized;   // ← 60 字符截断
}
```

后果一：`data/workspaces/SUPERDEVELOPERAG` 与任意同名目录（含 `wt-*` 之外的拷贝）共用同一个
`agent-cache-superdeveloperag` 卷 → 不同仓库的 `~/.m2` 混在一起，A 项目投毒的 jar 会被 B 项目解析。
后果二：长项目名截断后可能撞名；后果三：`initializedVolumes`（`:132`）以卷名为键，撞名时第二次的
`chown -R` 被跳过，卷属主保持上一次的形态。
后果四：`initCommand`（`:635-665`）无 `MANAGED_LABEL` / `OWNER_LABEL` / `--name` —— CLI 被 kill 时
`--rm` 不生效，初始化容器成孤儿，而 `sweepStaleContainers`（`:701`）按 `MANAGED_LABEL` 过滤**看不见它们**。

### 1.6 重复造轮子的实测规模

| 能力 | 实现份数 | 落点 |
|---|---|---|
| 有界命令执行 | 2 | `BoundedCommand`（176 行）/ `ProcessExecutor`（400 行，含 65 行 `redact`） |
| 镜像存在性探测 | 7 | `BuiltInPipelineEngine:1852`、`DockerDeployer:176,218`、`DockerEnvProvisioner:763`、`DockerSandbox:779,834`、`ImageProvisioner:98,302`、`ServerDeployer:679` |
| 容器运行态查询 | 5 | `DockerEnvProvisioner:709`、`DockerEnvRuntime:185`、`AppRuntimeInspector:145`、`DeploymentControlService:860`、`EnvironmentController:752` |
| 网络 ensure | 2 | `DockerNetwork.ensure` 与 `DockerEnvProvisioner.ensureNetwork:689`（后者是前者的复制品） |
| 端口分配 | 4 | `BuiltInPipelineEngine:4878/4921/3279`、`DeploymentControlService:741`、`LocalServiceManager:169`、`DockerEnvProvisioner:996`（全是 `ServerSocket` 试绑 + 200 次循环） |
| `image_package` 命令字面量 | 3 + 1 漂移 | `RecipeActionResolver:72`、`RecipeScaffolder:150`、`data/workspaces/SUPERDEVELOPERAG/ci/actions.yml`（仍是 `docker build`，不是 `buildx`） |
| HOST jar/pid 管理 | 3 | `HostDeployer`、`LocalDevHostDeployer`、`DeploymentControlService`（`releases/current/previous/app.pid/app.log` 三份实现） |

端口分配这条尤其典型：`ServerSocket` 试绑**先释放再交给 docker**，中间窗口是经典 TOCTOU；
而且 docker 自己就能回答"这个容器占了哪个宿主端口"（见 §1.1 实测 `docker port`）。

### 1.7 同一个缺陷的第 9 次复发

```
$ grep -rn "getInputStream().readAllBytes()" platform/*/src/main/java
agent-gateway/.../web/DeploymentControlService.java:866
```

`:863` 起 `new ProcessBuilder(...).start()`，`:866` 无界读流，`:867` 才 `waitFor(30s)` → 超时永不生效。
`BoundedCommand:16` 的 javadoc 已经把这一类写成反面教材，`EnvironmentDiscoveryService:274`、
`EnvironmentController:751`、`BuiltInPipelineEngine:788`、`DockerSandbox:680`、`JschSsh:213` 都改过了，
唯独这一处漏掉 —— 说明**靠人记住的规约必然会漏**，必须换成门禁（见 §4 D1）。

### 1.8 加固不对称

| 容器种类 | `--cap-drop ALL` | `no-new-privileges` | 端口绑定 |
|---|---|---|---|
| 构建沙箱 `wrap():277-280` | 有 | 有 | 不映射 |
| 应用容器 `DockerDeployer:247-265` | 有 | 有 | `-p hostPort:containerPort`（0.0.0.0） |
| **中间件** `DockerEnvProvisioner:801-807` | **无** | **无** | `-p 127.0.0.1:` |
| **远端 SERVER** `ServerDeployer:558-582` | **无** | **无** | `-p hostPort:containerPort`（0.0.0.0） |

`DockerDeployer:295` 回报的 endpoint 是 `http://127.0.0.1:port/`，而 `-p` 实际监听 0.0.0.0 —— 界面说
"只有本机能访问"，实际是**全网可达**。这是本轮最容易被忽略的一条。

---

## 缺口清单

| 编号 | 缺口 | 证据 | 后果 | 等级 |
|---|---|---|---|---|
| G1 | `require*` 策略层零生产调用 | §1.3 grep | 结构化拒绝从未生效；用户看到的是原始 CLI 报错；`runsc` 配置不被校验 | **P0** |
| G2 | 交付镜像 `AGENT_SANDBOX_MODE=process` + root 运行 | §1.4 | 容器化部署下"跑不受信仓库代码"完全没有内核级隔离 | **P0** |
| G3 | 缓存卷键只看目录名、卷名 60 字符截断 | §1.5 | 跨仓库 `~/.m2` 串味，投毒跨项目传播；`initializedVolumes` 误判 | **P0** |
| G4 | 初始化容器无标签/无名 | §1.5 | 孤儿初始化容器无法被扫；`--rm` 失效后永久残留 | P1 |
| G5 | 中间件与远端容器零加固 + `-p` 绑 0.0.0.0 却回报 127.0.0.1 | §1.8 | 端口错报暴露面；中间件容器内可提权 | **P0** |
| G6 | 9 处 `readAllBytes` 漏修 1 处，且无门禁 | §1.7 | 停/启容器超时失效 → 界面永久转圈 | **P0** |
| G7 | 有界执行 / 镜像探测 / 运行态查询 / 网络 ensure / 端口分配 / HOST 管理各有多份实现 | §1.6 | 已漂移（`image_package` 字面量 3 份互不一致 + 活的 YAML 第 4 份） | P1 |
| G8 | 4 处 `ServerSocket` 试绑式端口分配 | §1.6 | TOCTOU：试绑与 docker 实际绑定之间被抢占 | P1 |
| G9 | `stage.image` 只校验字符语法，无允许列表、无 digest 约束、允许 `:latest` | `ImageRef.VALID` / `withImage:841` | 仓库侧写入 `image: evil/registry:latest` 合法通过 | P1 |
| G10 | `.dockerignore` 缺 `.agent/` 与编译产物 | §1.2 | 每次镜像构建白传 387.2 MB / 11245 文件 | P1 |
| G11 | `SandboxConfig` 20 个 `@Value` + `default ->` 静默兜底 | `SandboxConfig:88-119`、`DockerSandbox:838-849` | 当前**未**丢参数（构造器 10 项 + 12 个 setter 刚好覆盖），但每加一个旋钮必须在两处同步，漏一处就是静默丢配置；`mode` 拼错不报错 | P1 |
| G12 | `hostName()`(`:790`) 与 `defaultHost()`(`:421`) 环境变量优先级相反 | 两处 javadoc 都说"优先容器/系统变量" | 混合主机名环境下就绪状态查错行、清扫误判存活容器 | P2 |
| G13 | `ServerDeployer` 用 `docker save` → `ssh` → `docker load` 传 tar | `ServerDeployer:558` | 无 registry 的重复造轮子；远端无加固 | P2 |
| G14 | `DockerEnvProvisioner` 935 行自造编排（`--restart`、网络、卷、等待轮询） | §1.6 | 与 compose 能力 1:1 重叠，且加固缺失（G5 的成因） | P2 |
| G15 | `DockerfileTools` 手写 Dockerfile 生成 | `DockerfileTools:289/443` | 与 Jib/buildpacks 重叠（**评估后建议保留**，理由见 D15） | P2 |
| G16 | `SandboxConfigTest` 只测 3 个分支，`auto` 语义已改为 fail-closed 但 javadoc 仍写"自动切换" | `SandboxConfigTest` / `ProcessActionRunner:11` | 文档与代码相反，误导后来人改回 auto | P2 |

---

## 优化方案

排序口径：**（事故类别严重度 × 复发频率）÷ 改动成本**。前四项是"不做就会再犯"的，必须先落地。

### 阶段 0（当天，约 2 小时）：把复发关掉

| # | 动作 | 落点 |
|---|---|---|
| D1 | 修第 9 处 `readAllBytes` + 加禁令 R15 + fixture 样例 | `DeploymentControlService.java:860-882`、`ForbiddenPatternScan.java`、`fixtures/sample/.../ForbiddenSample.java` |
| D10 | `.dockerignore` 补 `.agent/`、`**/target/*`（白名单 `*-exec.jar`） | `.dockerignore` |

**D1-a 替换 `DeploymentControlService.docker()`（`:860-882` 全删，改成 3 行）：**

```java
private String docker(String action, String container) {
    BoundedCommand.Outcome outcome = BoundedCommand.run(
            List.of("docker", action, container), DOCKER_TIMEOUT_MS);
    if (!outcome.ok()) {
        throw new DeployException(ErrorCode.DEPLOY_INVALID_STATE,
                "docker " + action + " 失败：" + outcome.detail()
                        + (outcome.output().isBlank() ? "" : "｜" + BugFixRedactor.redact(outcome.output()).trim()));
    }
    return outcome.output().trim();
}
```

顺带解决两件事：`IOException`/`InterruptedException` 两个 catch（`:876-881`）消失；错误摘要进
`BugFixRedactor`（该类已在 `:36` 导入，现在只用在其路径上）。

**D1-b 新增禁令 R15（`ForbiddenPatternScan.RULES` 末尾）：**

```java
new Rule("R15", "禁止 waitFor 之前的无界读流：必须走 BoundedCommand",
        // 只匹配 "byte[] x = y.getInputStream().readAllBytes(" 形态：
        // BoundedCommand 的 javadoc 里也写着这段代码，泛匹配会把注释判违规。
        Pattern.compile("\\bbyte\\s*\\[\\s*\\]\\s*\\w+\\s*=\\s*\\w+\\s*\\."
                + "\\s*getInputStream\\s*\\(\\s*\\)\\s*\\.\\s*readAllBytes\\s*\\("), "java"),
```

**D1-c fixture（强制，否则 `RuleFixtureSelfTest` 红）：** `ForbiddenSample.java` 追加

```java
void r15() throws java.io.IOException {
    Process p = new ProcessBuilder("docker", "info").start();
    byte[] out = p.getInputStream().readAllBytes();
}
```

> 为什么这一条必须配门禁：这是第 9 次。前 8 次都靠"这次记得"，第 9 次就漏了。

**D10 `.dockerignore`（已落地，含一个必须记住的坑）：**

```
.agent/
**/target/*
!platform/agent-gateway/target/*-exec.jar
!platform/web/dist/
```

> **坑**：直觉写法是 `**/target/`，但那会把目录本身排除，Dockerfile 的
> `COPY platform/agent-gateway/target/*-exec.jar` 直接找不到文件。必须写 `**/target/*` +
> 单条白名单——可执行包（91.0 MB）正是构建上下文唯一需要的 target 产物。
> 实测（`docker build --progress=plain`，`COPY . /ctx` 计数）：
>
> | 规则 | 上下文 | 文件数 |
> |---|---|---|
> | 改前 | **387.2 MB** | 11245 |
> | `**/target/`（错） | 17.6 MB | 1029 | ← 但 `*-exec.jar` 丢了，构建会失败 |
> | `**/target/*` + 白名单（对） | **108.7 MB** | 1030 |
>
> 用真实 `Dockerfile` 验证：`docker build -t probe .` 成功，增量上下文仅 2.53 kB + 91 MB jar。

### 阶段 1（0.5–1 天）：把死策略变成真策略

| # | 动作 | 落点 |
|---|---|---|
| D2 | `requireNetworkFor` / `requireRuntimeAvailable` 接到真实执行路径 | `DockerActionRunner.run:52-73`、`DockerSandbox`（新增 `ensureRuntimeReady`） |
| D3 | `requireNoExtraMounts` / `requireUnprivileged` **删除**，换成 argv 不变量测试 | `DockerSandbox:953-979` 删除、`DockerSandboxTest` 扩写 |
| D11 | `mode` 未知值报错；`withImage` 改拷贝构造 | `SandboxConfig:107`、`DockerSandbox:838-849` |

**D2-a 在 `DockerActionRunner.run` 里、`prepareNetwork` 之前插入：**

```java
// 隔离参数先落日志
sandbox.logIsolation(live);
// 网络档位与阶段需求必须在起容器前对齐：否则用户等到 60 秒后只看到
//   Could not resolve host（dig 失败），无法判断是策略还是网络。
sandbox.requireNetworkFor(true, spec.action().wireName());
// 运行时（gVisor/Kata）必须在本机真实存在，绝不静默回落 runc。
sandbox.ensureRuntimeReady(cancelled, live);
sandbox.prepareNetwork(cancelled, live);
```

`needsNetwork` 固定传 `true` 的理由：容器内所有动作（mvn/pnpm/docker build）都需要外联，`network=none`
下**成功只可能是缓存全命中**。给一条显式逃生门（`agent.sandbox.allow-offline=true` 时传
`!allowOffline`），而不是让用户在没有提示的情况下等 60 秒。

**D2-b `DockerSandbox` 新增（一次性探测 + 进程内缓存）：**

```java
/** 运行时可用性：只在首次调用时问守护进程，结论在进程内缓存。 */
public void ensureRuntimeReady(BooleanSupplier cancelled, Consumer<String> live) {
    if (runtime == null || runtime.isBlank()) {
        return;   // 用默认 runc，无需探测
    }
    if (availableRuntimes == null) {
        BoundedCommand.Outcome info = BoundedCommand.run(
                List.of(dockerBinary, "info", "--format", "{{json .Runtimes}}"), 5_000L);
        availableRuntimes = info.ok() ? runtimeKeys(info.output()) : List.of();
        notify(live, "[sandbox] 守护进程运行时：" + availableRuntimes + "\n");
    }
    requireRuntimeAvailable(availableRuntimes);   // 已有实现，空列表时按原语义不误拦
}
```

**D3 删除两个空输入校验 + 换成不变量（`DockerSandboxTest` 追加）：**

```java
@Test
@DisplayName("wrap() 的 argv 不变量：任何配置组合都不能放宽隔离")
void wrapNeverRelaxesIsolation() {
    List<String> cmd = sandbox.wrap(workspace, List.of("mvn", "-v"));
    String joined = String.join(" ", cmd);

    assertTrue(joined.contains("--cap-drop ALL"), joined);
    assertTrue(joined.contains("no-new-privileges"), joined);
    assertTrue(joined.contains("--read-only"), joined);
    assertFalse(joined.contains("--privileged"), joined);
    assertFalse(joined.contains("--cap-add"), joined);
    assertFalse(joined.contains("docker.sock"), joined);        // 挂载边界
    assertFalse(joined.contains("--pid=host"), joined);
    assertFalse(joined.contains("--network=host"), joined);
    assertEquals(2, cmd.stream().filter("-v"::equals).count(), "只允许工作区 + 缓存卷两个挂载：" + joined);
}
```

> 为什么删比接更优：`PipelineDefinition.StageDef`（`record(id,type,action,runner,workdir,image,url,expected)`）
> **没有任何 mounts/privileged/capAdd 字段**，这两个校验器永远只能收到 `List.of()`。留着它们是给评审看的，
> 不变量测试则是每次 `mvn test` 都跑的。

**D11-a `SandboxConfig:107`：**

```java
default -> throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
        "agent.sandbox.mode 取值非法：'" + mode + "'（合法值：docker | process）");
```

**D11-b `DockerSandbox.withImage` 改私有拷贝构造（消除"新增旋钮必漏"）：**

```java
/** 复制一份沙箱，只替换镜像。字段靠拷贝构造整体带走，新增旋钮不会被这个入口吞掉。 */
public DockerSandbox withImage(String newImage) {
    return new DockerSandbox(this, ImageRef.require(newImage, "stage.image"));
}

private DockerSandbox(DockerSandbox base, String image) {
    this.dockerBinary = base.dockerBinary;
    this.image = image;
    /* … 全部 final 字段逐一赋值（含 pidsLimit / runtime / 各 pull 策略 / readinessStore）… */
    this.initializedVolumes = base.initializedVolumes;
}
```

同时把 `setPidsLimit` 之外仍会被 `withImage` 丢掉的项（当前 `:843-848` 用 12 个 setter 逐个补，
新增旋钮只要不改这行就静默丢失）一并纳入。

### 阶段 2（1 天）：缓存卷正确性 + 交付镜像

| # | 动作 | 落点 |
|---|---|---|
| D4 | 缓存卷键改"可读前缀 + 路径哈希"，取消 60 字符截断 | `DockerSandbox:343-357`、`:811-815` |
| D5 | 初始化容器补 `--name` / `MANAGED_LABEL` / `OWNER_LABEL`，并纳入清扫 | `DockerSandbox:635-665`、`:729-759` |
| D6 | 构建容器补 `--init` / `--stop-timeout` / `--ulimit core=0` | `DockerSandbox:241-286` |
| D7 | 交付镜像：dind sidecar 方案 + 非 root + HEALTHCHECK + VOLUME | `Dockerfile`、新增 `docker-compose.platform.yml` |

**D4-a：**

```java
/**
 * 缓存卷的项目键：<b>可读前缀 + 规范化项目根路径的哈希</b>。
 *
 * <p>原实现只取目录名，等于假设"目录名唯一"——同名的两份拷贝、被截断的长名字、
 * 以及不同父目录下的同名工程，都会落到同一个卷上，A 项目投毒的 ~/.m2 会被 B 项目解析。
 */
static String cacheKey(Path workspace) {
    Path root = workspace.toAbsolutePath().normalize();
    Path parent = root.getParent();
    if (parent != null && "worktrees".equals(fileName(parent)) && parent.getParent() != null) {
        root = parent.getParent();     // worktree 每次名字都变，键取所属项目根
    }
    String readable = safeVolumeName(fileName(root));
    if (readable.length() > 24) {
        readable = readable.substring(0, 24);
    }
    return readable + "-" + sha256Hex(root.toString()).substring(0, 16);
}

String cacheVolumeName(Path workspace) {
    return cacheVolumePrefix == null || cacheVolumePrefix.isBlank()
            ? null : safeVolumeName(cacheVolumePrefix + "-" + cacheKey(workspace));
}
```

哈希写不截断（16 位 hex），前缀仍可读；`safeVolumeName` 的 60 字符上限依然生效但不再吃掉熵。

**D5：`initCommand` 追加三行**（与 `wrap()` 的 `:248-262` 对齐）：

```java
cmd.add("--label"); cmd.add(MANAGED_LABEL);
cmd.add("--label"); cmd.add(OWNER_LABEL + "=" + OWNER_TAG);
cmd.add("--name");  cmd.add(initContainerName(volume));
```

并把 `staleCandidates`（`:729`）的前缀判断从 `startsWith("agent-act-")` 改成
`isManagedContainerName(name)`（同时接受 `agent-act-` 与 `agent-act-init-`），否则清扫永远看不到初始化容器。

**D6：`wrap()` 资源段追加：**

```java
cmd.add("--init");                 // PID 1 回收僵尸：pids-limit 512 下没有 reaper 会真的撞上限
cmd.add("--stop-timeout"); cmd.add("10");
cmd.add("--ulimit"); cmd.add("core=0");
```

**D7-a `Dockerfile`（替换 `:9-25`）：**

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
RUN groupadd -g 10001 agent && useradd -u 10001 -g agent -M -s /usr/sbin/nologin agent \
 && apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# 容器化部署的隔离由 dind sidecar 提供（见 docker-compose.platform.yml），
# 平台自身以 docker 客户端身份访问 sidecar，不再退化为 process 模式。
ENV AGENT_WORKSPACE_ROOT=/data/workspaces \
    AGENT_SQLITE_FILE=/data/_platform/platform.db \
    AGENT_WEB_DIST=/app/web \
    SERVER_PORT=8090

COPY --chown=10001:10001 platform/agent-gateway/target/*-exec.jar /app/app.jar
COPY --chown=10001:10001 platform/web/dist/ /app/web/

VOLUME ["/data"]
USER 10001:10001
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

**D7-b 新增 `docker-compose.platform.yml`（唯一受支持的容器化隔离形态）：**

```yaml
services:
  docker:
    image: docker:29-dind
    privileged: true
    environment: [DOCKER_TLS_CERTDIR=/certs]
    volumes: [dind-certs:/certs]
    healthcheck:
      test: ["CMD", "docker", "info"]
      interval: 10s
      retries: 12

  platform:
    build: .
    depends_on:
      docker: {condition: service_healthy}
    environment:
      DOCKER_HOST: tcp://docker:2376
      DOCKER_TLS_VERIFY: "1"
      DOCKER_CERT_PATH: /certs/client
      AGENT_SANDBOX_MODE: docker          # 容器里也能拿到真隔离
    volumes: [dind-certs:/certs, platform-data:/data]
    ports: ["127.0.0.1:8090:8090"]

volumes:
  dind-certs:
  platform-data:
```

`AGENT_SANDBOX_MODE=process` 从交付镜像里删除，只在"单用户本机、明确不跑不受信代码"时由
`docker run -e AGENT_SANDBOX_MODE=process` 显式打开 —— 默认值回到 `docker`（fail-closed 恢复原有意义）。

### 阶段 3（2–3 天）：合并重复实现（净删除约 700 行）

| # | 动作 | 落点 | 净行数 |
|---|---|---|---|
| D8 | 新增 `DockerOps`，7 处镜像探测 / 5 处运行态查询 / 2 处网络 ensure 收敛到一份 | 新增 `executor/DockerOps.java`（同包，非子包：它是执行面的门面，与沙箱/部署器平级） | −180 |
| D9 | 端口分配改 `-p 127.0.0.1::<cp>` + `docker port` 回读，删 4 处扫描 | `BuiltInPipelineEngine`、`DeploymentControlService`、`DockerEnvProvisioner` | −120 |
| D12 | `image_package` 命令字面量收敛到一份（漂移的活 YAML 见下方说明） | 新增 `domain/image/ImageBuildCommand.java`（放 domain：infra 与 executor 都要用，且 infra 不依赖 executor） | −40 |

> **关于 `data/workspaces/SUPERDEVELOPERAG/ci/actions.yml`**：方案原写"删掉这份漂移的 YAML"，
> 实施时发现它**不在版本库**（`data/` 被 `.gitignore` 排除），是已接入项目的运行期工作区数据。
> 平台去改用户工作区里的文件属于越界（下次同步又会被覆盖）。改为守住真正的边界：
> 仓库侧配方一律没有宿主执行权（`trustedForHost`），旧配方即使还在也只会被拒绝上宿主，
> 不会静默按 `docker build` 跑出"只在缓存里"的镜像。守护用例：`ImagePackageSourceTrustTest`。| D13 | `ProcessExecutor` 与 `BoundedCommand` 统一（前者只留 `FailureKind` 分类与 `redact`） | `ProcessExecutor.java`、`BoundedCommand.java`，脱敏实现落 `domain/process/LogRedactor.java` | −150 |
| D14 | 中间件改用生成的 compose 文件 + `up -d --wait` | `DockerEnvProvisioner:689-854` 删除，改为 `MiddlewareCompose` | −210 |

**D8 `DockerOps` 接口（唯一允许拼 docker 子命令的地方，`DockerCli` 退化为字段）：**

```java
public final class DockerOps {
    public record RunSpec(String name, String image, List<String> publish, List<String> env,
                          List<String> volumes, List<String> labels, String network,
                          List<String> command) {}
    public boolean available();
    public String imageId(String ref);          // 取代 7 处 image inspect
    public boolean imageExists(String ref);
    public Optional<Boolean> containerRunning(String name);
    public void ensureNetwork(String name);
    public String runDetached(RunSpec spec);    // 取代 5 处 container-run argv
    public int publishedPort(String container, int containerPort);   // 取代 4 处端口扫描
    public List<PsRow> ps(List<String> filters);
}
```

**D9 端口分配（替代 `allocateHostPort:4878` / `allocateTaggedPort:3279` / `choosePort:741` / `portFree:4940`）：**

```java
// 起容器：先按租约（若存在），失败再让 docker 自己挑
List<String> publish = lease == null
        ? List.of("127.0.0.1::" + containerPort)
        : List.of("127.0.0.1:" + lease.hostPort() + ":" + containerPort);
String resource = dockerOps.runDetached(spec);          // 失败且 portConflict → 重试 ephemeral
int resolved = dockerOps.publishedPort(resource, containerPort);
envStore.saveLease(new PortLease(projectId, env, containerPort, resolved, runId, now));
```

好处：没有试绑窗口（TOCTOU 消失）、没有 200 次循环、端口真相只有 docker 一个来源。实测已验
（§1.1：`-p 127.0.0.1::8080` → `docker port` 回读 `61487`）。HOST 目标保留一处扫描（docker 无法代答）。

**D12 `ImageBuildCommand`：**

```java
public final class ImageBuildCommand {
    /** 唯一的镜像构建命令来源：配方默认、骨架生成、doc 全部引用它。 */
    public static List<String> builtin(String tag) {
        return List.of("docker", "buildx", "build", "--load", "-q", "-t", tag, ".");
    }
}
```

`RecipeActionResolver:72`、`RecipeScaffolder:150` 改调用它（后者在 `-t` 前插 `-f <df>`）；
`data/workspaces/SUPERDEVELOPERAG/ci/actions.yml` 里的 `["docker","build",...]` 删除（仓库侧同名配方
本来就会被 `trustedForHost` 拒绝上宿主，留着只是误导）。

**D13 执行原语统一：** `BoundedCommand` 保留为唯一实现（虚拟线程泵 + 硬超时 + `killTree`，
`:64-127` 已完备）；`ProcessExecutor.exec/execUntilIdle` 改成薄适配器（带 `live` 回调与 `FailureKind`），
`ProcessExecutor.redact` 与 `BugFixRedactor.redact` 之间保留一份（选 `BugFixRedactor`，它已被 gateway 使用）。

**D14 中间件（生成文件 + compose 一次起）：**

```java
// 生成 <workspace>/.agent/env/docker-compose.yml（含 cap_drop/security_opt/healthcheck），然后：
dockerOps.run(List.of("compose", "-f", file, "-p", namespace, "up", "-d", "--wait",
                      "--wait-timeout", "120"), 180_000);
```

删除 `ensureNetwork:689`（用 `DockerOps.ensureNetwork`）、`liveRunning:709`、`waitRunning:739`、
`runContainer:756`、`runArgv:798-854`。加固缺口 G5 顺带关闭（compose 文件里能写 `read_only` /
`cap_drop` / `security_opt`，而 `docker run` 手拼时漏了）。逃生门：`agent.env.compose-enabled=false`
回落旧路径一个版本，下一版删除。

### 阶段 4（1–2 天）：远端与镜像策略

| # | 动作 | 落点 |
|---|---|---|
| D16 | `ServerDeployer` 远端容器补加固 + `-p 127.0.0.1:` | `ServerDeployer:558-582` |
| D17 | `docker save/ssh/load` → `docker --context` / 优先 registry | `ServerDeployer` |
| D18 | 镜像策略：允许列表 + 禁浮动 tag | 新增 `SandboxImagePolicy.java`，接 4 个调用点 |

**D16：`runCommand` 的 `-p` 与加固（`:561-571` 改成与 `DockerDeployer:247-265` 同款）：**

```java
.append(" --security-opt no-new-privileges")
.append(" --cap-drop ALL")
.append(" -p 127.0.0.1:").append(hostPort).append(':').append(request.containerPort());
```

`-p 127.0.0.1:` 与 `DockerDeployer` 保持一致：要么两边都只绑本地，要么两边都绑全网，并在 endpoint
里如实回报 —— 现在是一个绑全网、一个回报 127.0.0.1（G5）。

**D18 镜像策略（`ImageRef` 已挡住参数注入，这一层管"允许谁"）：**

```java
final class SandboxImagePolicy {
    enum Source { CONFIG, STAGE, DEPLOY }

    /** 规则：语法必须过 ImageRef；非 CONFIG 来源禁浮动 tag；allowlist 非空时必须命中。 */
    Decision check(String image, Source source) { … }
}
```

配置项：`agent.sandbox.image-allowlist`（逗号分隔 glob，默认空 = 只查语法）、
`agent.sandbox.allow-floating-tags`（默认 `false`，`STAGE`/`DEPLOY` 来源带 `:latest` 或无 tag 直接
E-707 `Rule.IMAGE`）。调用点：`DockerSandbox:196`(CONFIG)、`:841`(STAGE)、`DockerDeployer:206`(DEPLOY)、
`ServerDeployer:131`(DEPLOY)。

### 不建议改的（明确保留，避免下一轮又提）

- **`DockerSandbox.wrap()` 的隔离参数组合**：设计是对的（`:277-286` + `:649-652` 初始化容器同样
  不可提权/无外联）。本方案只在它上面**加** `--init` 等三项，不重构。
- **`HostToolExclusionTest` 的立场**（AgentScope 宿主工具不得进工具面）：正确，保留。
- **`DockerfileTools.ensure_dockerfile`**（G15）：看起来与 Jib/buildpacks 重叠，但它的价值不是
  "生成 Dockerfile"，而是**把"哪个 jar 是可执行包"这个领域知识教给模型**（`:443` 的 classifier 识别，
  修过一次真实事故：拷普通包 → `no main manifest attribute`）。Jib 无法表达"我有一份要 review 的
  Dockerfile"，替换它会丢掉可审计性。**结论：保留，只收敛构建命令字面量（D12）。**

---

## 验收契约

| 编号 | 落点 | 验收命令 | 通过判定 | 反证（失败长什么样） |
|---|---|---|---|---|
| G6/D1 | `DeploymentControlService:860` | `mvn -f platform/pom.xml -pl spec-checks test "-Dtest=ForbiddenPatternScanTest,RuleFixtureSelfTest"` | 3 个用例全绿；`grep -rn "getInputStream().readAllBytes()" platform/*/src/main/java` 为 0 行 | 回填违规后应红：`[R15] agent-gateway\...\R15RedProbe.java:11 禁止 waitFor 之前的无界读流`（已实测） |
| G6/D1 | 反证（已实测） | 在 `agent-gateway/src/main/java/.../redprobe/` 新建含 `byte[] x = p.getInputStream().readAllBytes();` 的类，跑上面同一条命令 | `Tests run: 1, Failures: 1`，报 `[R15]` 并指出文件行号；删掉后回到 `Tests run: 3, Failures: 0` | 门禁绿 → R15 正则写坏（fixture 自测会同时报 `未命中样例：[R15]`） |
| G10/D10 | `.dockerignore` | `docker build --no-cache -f Dockerfile -t probe . --progress=plain` + `docker images probe --format '{{.Size}}'` | 构建成功、镜像 643 MB、增量上下文 2.53 kB；`COPY . /ctx` 计数从 11245 文件降到 1030 | `COPY .../*-exec.jar` 报 `not found`（说明用了 `**/target/` 而不是 `**/target/*`） |
| G1/D2 | `DockerActionRunner:59` | `mvn -f platform/pom.xml -pl agent-executor -am test -Dtest=DockerSandboxDeniedTest` + 手工：`AGENT_SANDBOX_NETWORK=none` 跑一次 build | 启动 1 秒内返回 `E-707 / rule: NETWORK`，日志带 `isolationSnapshot` | 60 秒后 `Could not resolve host` |
| G1/D2 | `DockerSandbox.ensureRuntimeReady` | `AGENT_SANDBOX_RUNTIME=runsc mvn -f platform/pom.xml -pl agent-executor -am test` 或起平台跑一次构建 | `E-707 / rule: RUNTIME`，提示含 `runc` 回落选项 | `docker run` 后 `unknown or invalid runtime name: runsc` |
| G1/D3 | `DockerSandboxTest` | `mvn -f platform/pom.xml -pl agent-executor -am test -Dtest=DockerSandboxTest` | 不变量用例绿（无 `--privileged` / `--cap-add` / `docker.sock` / 恰好 2 个 `-v`） | 断言消息里出现完整 argv，能一眼看出是哪个参数漏了 |
| G3/D4 | `DockerSandbox.cacheKey` | `mvn … -pl agent-executor install -DskipTests` 后 `mvn … -pl agent-gateway test "-Dtest=DualStackPipelineTest"` + `docker volume ls --filter name=agent-cache` | `DualStackPipelineTest` 4 绿（真实验证冷卷能初始化）；卷名以 `-<16 位 hex>` 结尾且不同仓库互不相同 | 冷卷初始化报 `mkdir: Permission denied` → root 回退没生效（注意先 `install`，否则 gateway 可能跑在旧 artifact 上） |
| G4/D5 | `DockerSandbox:635` | `mvn … -pl agent-executor test "-Dtest=DockerSandboxPrepareTest,DockerSandboxHostTest"` | 每条初始化命令都含 `agent.sandbox.managed=true` / `agent.sandbox.owner=` / `--name agent-act-init-…`；`staleCandidates` 能选中初始化容器 | 初始化容器无标签无名字 → 在初始化中途 `kill` 平台后 `docker ps -a` 里长期挂着无名 init 容器 |
| G5/D16 | `ServerDeployer:558` | `mvn … -Dtest=DeployersTest` + 远端 `docker inspect --format '{{.HostConfig.SecurityOpt}} {{.HostConfig.CapDrop}} {{json .HostConfig.PortBindings}}' <name>` | `[no-new-privileges] [ALL]`，`HostIp=127.0.0.1` | `[] []`，`HostIp=0.0.0.0` |
| G5/D14 | `DockerEnvProvisioner` | 起一次环境：`docker inspect <mw> --format '…'` | 中间件容器带 `cap_drop: [ALL]` + `no-new-privileges` + healthcheck | 两项均为空 |
| G7/D8 | `DockerOps` | `grep -rn "\"image\", \"inspect\"" platform/*/src/main/java` | 只剩 3 类用途各一处，且**没有重复的存在性探测**：`DockerOps`（探测事实）、`DockerSandbox`/`ImageProvisioner`（给自愈层产出复核命令）、`DockerDeployer`（需要详细失败日志）；引擎预检与 `DockerDeployer.imageExists` 已改为调用 `DockerOps` | 出现第 4 处"只问在不在"的探测 → 说明又绕开了门面 |
| G7/D12 | `ImageBuildCommand` | `mvn … -pl agent-executor test "-Dtest=ImagePackageSourceTrustTest"` + `grep -rn 'buildx", "build' platform/*/src/main/java` | 4 绿；字面量只命中 `ImageBuildCommand.java`；仓库侧旧配方被 `trustedForHost` 拒绝 | `trustedForHost` 对 `Source.REPO` 返回 true → 沙箱被绕过，该用例红 |
| G8/D9 | 端口 | 起两次部署，第二次故意占用租约端口 | 第二次改由 docker 分配并在日志打出 `[port] 61487 ← docker 分配`；`docker port` 与 endpoint 一致 | endpoint 端口与实际 `docker port` 不一致，或 200 次循环后 `端口耗尽` |
| G9/D18 | `SandboxImagePolicy` | `mvn … -Dtest=ImageRefTest` + 在 `ci/pipeline.yml` 写 `image: evil/x:latest` | `E-707 / rule: IMAGE`，明确指出"浮动 tag"与来源 `stage.image` | 容器按 `evil/x:latest` 起起来 |
| G2/D7 | `Dockerfile` | `docker compose -f docker-compose.platform.yml up -d --wait` 然后 `docker compose exec platform docker info --format '{{.ServerVersion}}'` | 打印 sidecar 版本（如 `29.8.2`）；`docker compose exec platform id -u` 为 `10001` | 平台以 root 运行且 `docker info` 失败（退回 process 模式） |
| G11/D11 | `SandboxConfig:107` | `AGENT_SANDBOX_MODE=dockr mvn … -Dtest=SandboxConfigTest` | 抛 `ENVIRONMENT_INVALID_STATE`，消息列出合法值 | 静默按 docker 处理 |
| G12 | 主机名 | `mvn … -pl agent-executor test "-Dtest=DockerSandboxHostTest"` | 5 绿：`localHostName()` 非空无空白；HOSTNAME/COMPUTERNAME 不同时必须取 HOSTNAME；初始化容器与活动容器都能被 `staleCandidates` 选中 | 两处各取一个环境变量 → 就绪状态查错行；或把优先级改回 COMPUTERNAME 优先 → 该用例红 |

---

## 执行状态

| 编号 | 状态 | 依据 |
|---|---|---|
| D1 修第 9 处 + R15 门禁 | **已完成** | `DeploymentControlService.java:860`（原 `:863-882` 的 `readAllBytes` 已删，改走 `BoundedCommand`）；`ForbiddenPatternScan.java` 新增 R15；`fixtures/sample/.../ForbiddenSample.java` 新增 `r15()`。验证：`-Dtest=ForbiddenPatternScanTest,RuleFixtureSelfTest` → 3 绿；反证（临时 `R15RedProbe.java`）→ `Failures: 1` 报 `[R15] …:11`，删除后复绿 |
| D10 `.dockerignore` | **已完成** | 上下文 387.2 MB / 11245 文件 → 108.7 MB / 1030 文件（真实 `Dockerfile` 构建成功，镜像 643 MB）。注意用 `**/target/*` + `!…*-exec.jar`，不能写 `**/target/` |
| D2 死策略接线 | **已完成** | `DockerActionRunner` 起容器前调用 `requireNetworkFor(!allowOffline, action)` + `ensureRuntimeReady`；新增 `DockerSandbox.ensureRuntimeReady`（`docker info --format '{{json .Runtimes}}'` 探测一次并缓存）+ `runtimeKeys`（按花括号深度解析，避免把嵌套 `status` 当运行时名）。验证：`DockerActionRunnerTest.offlineNetworkDeniedBeforeContainerStarts` → `E-707` + `SANDBOX_DENIED` + `rule: NETWORK`；`DockerSandboxDeniedTest.runtimeKeysParsedFromInfoJson` |
| D3 删空输入校验 + argv 不变量 | **已完成** | 删除 `requireNoExtraMounts(List)` / `requireUnprivileged(List)`（`StageDef` 无对应字段，永远收到空列表）；改为 `allowedMounts(ws)` + `DockerSandboxTest.wrapNeverRelaxesIsolation`（无 `--privileged`/`--cap-add`/`docker.sock`/`--pid=host`，挂载数 = `allowedMounts` 大小） |
| D4 缓存卷键哈希 | **已完成** | `cacheKey` = 可读前缀(≤24) + 路径 SHA-256 前 16 位；`DockerSandboxTest.cacheKeyIsPathScoped` 证明同名工程不同父目录必不同卷、worktree 命中项目根、同路径稳定。**副作用（正向）**：卷名唯一化让冷卷成为常态，暴露了 `runInit` 里一个从未生效的 root 回退（详见变更记录 2026-11-21 01:20） |
| D5 初始化容器标签/命名 | **已完成** | `initCommand` 加 `MANAGED_LABEL`/`OWNER_LABEL`/`--name`；`INIT_PREFIX` + `initContainerName`；`staleCandidates` 改用 `isManagedContainerName`（同时覆盖活动与初始化容器） |
| D6 `--init` / `--stop-timeout` | **已完成** | `wrap()` 加 `--init`、`--stop-timeout 10`、`--ulimit core=0`；`DockerSandboxTest.containerUsesInitAndStopGrace` |
| D7 dind sidecar + 非 root + HEALTHCHECK | **已完成** | `Dockerfile`：删 `ENV AGENT_SANDBOX_MODE=process`、加 `USER 10001:10001`、`VOLUME /data`、`HEALTHCHECK`、`curl`；新增 `docker-compose.platform.yml`（dind + TLS + `service_healthy` 依赖）。实测：`docker compose config --quiet` 通过；镜像构建成功 643 MB；容器内 `id` → `uid=10001(agent)`；`AGENT_SANDBOX_MODE` 为空（探针脚本 `echo SANDBOX_MODE=[$AGENT_SANDBOX_MODE]` → `[]`）；无守护进程时启动即 fail-closed 报 `agent.sandbox.mode=docker 但 Docker 守护进程不可用` |
| D8 `DockerOps` 收敛 | **已完成** | 新增 `DockerOps`（`available`/`imageId`/`imageExists`/`containerRunning`/`ps`/`ensureNetwork`/`publishedPort`/`removeContainer`/`awaitRunning`）；`DockerEnvProvisioner` 的 5 份私有实现改为委托。归口后探针超时统一 5s、变更 30s |
| D9 端口改 docker 分配 | **已完成** | `DockerDeployer` 支持 `hostPort<=0` → `-p 127.0.0.1::<cp>`；`resolvePublishedPort` 用 `docker port` 回读实际端口并写日志。实测 `-p 127.0.0.1::8080` → `docker port` 返回 `127.0.0.1:61487` |
| D11 mode 校验 + 拷贝构造 | **已完成** | `SandboxConfig.actionRunner` 改 `switch` 表达式 + `default ->` 抛 `ENVIRONMENT_INVALID_STATE`（消息列出合法值并说明 `auto` 已废弃）；`DockerSandbox.withImage` 改私有拷贝构造（全字段带走，含新加的 `imagePolicy`/`allowOffline`）。`SandboxConfigTest` 5 绿（含 `dockr` 拼写错误必须报错） |
| D12 `ImageBuildCommand` 单一来源 | **已完成** | 新增 `agent-domain/.../domain/image/ImageBuildCommand`（`standard`/`withDockerfile`/`render`，放行 `${tag}` 占位符，抛 `RecipeException` 带错误码）；`RecipeActionResolver` 与 `RecipeScaffolder` 改为调用。`ImageBuildCommandTest` 6 绿。另加 `ImagePackageSourceTrustTest`(4) 钉住"仓库侧旧配方不给宿主执行权"——`data/workspaces` 里那份漂移 YAML 属运行期数据，平台不改它，只保证它不会被静默执行 |
| D13 执行原语统一 | **已完成** | 新增 `LogRedactor`（domain）：合并执行内核与网关两份规则，补上各自缺的两条（JWT 三段、URL 内嵌凭据）；`ProcessExecutor.redact` 与 `BugFixRedactor.redact` 均改为委托。`LogRedactorTest` 8 绿 |
| D14 中间件 compose 化 | **已完成** | 新增 `MiddlewareCompose`（`render`/`upArgv`/`versionArgv`，含 `cap_drop: ALL` + `no-new-privileges:true` + `127.0.0.1` + `external` 网络）；`ensure()` 走 compose 路径，compose 缺失或需重建卷时回落逐容器。`MiddlewareComposeTest` 8 绿 |
| D15 `DockerfileTools` 保留决策 | 已完成（决策） | 本文 §"不建议改的" |
| D16 远端容器加固 | **已完成** | `ServerDeployer.hardeningFlags` 唯一来源（`--security-opt no-new-privileges` + `--cap-drop ALL` + `127.0.0.1:` 绑定）；SSH 字符串与 `--context` argv 两条路径共用。`ServerDeployerHardeningTest` 5 绿 |
| D17 `docker --context` 替代 save/load | **已完成** | `ServerDeployer.setUseDockerContext` + `contextReady`（`context inspect` → `version` 双校验，失败自动回落 SSH 并说明）+ `deployViaContext`；配置项 `agent.deploy.use-docker-context`（默认 false）。`contextArgv` 把 `--context` 放在子命令前；`contextName` 按 `server.id`（同主机两记录不互相覆盖） |
| D18 镜像策略 | **已完成** | 新增 `SandboxImagePolicy`（允许列表 glob + 浮动 tag 规则，`Source.CONFIG/STAGE/DEPLOY`）；接 `DockerSandbox.withImage`(STAGE)、`DockerDeployer`/`ServerDeployer`(DEPLOY)，同一实例由 `PipelineConfig` 注入。`SandboxImagePolicyTest` 5 绿；配置项 `image-allowlist` / `allow-floating-tags` / `allow-offline` / `env.compose-enabled` 已进 `application.yml` |
| G12 主机名归一 | **已完成** | `defaultHost()` 与 `hostName()` 合并为唯一实现 `localHostName()`（`HOSTNAME → COMPUTERNAME → InetAddress`），归属标签与就绪状态分区从此用同一个名字。`DockerSandboxHostTest` 5 绿（含"两者不同时必须取 HOSTNAME"的钉死断言） |
| D5 初始化容器清扫覆盖 | **已完成** | `DockerSandboxPrepareTest.initContainersAreLabeledAndNamed` 断言每一条初始化命令都带 `agent.sandbox.managed=true` + `agent.sandbox.owner=` + `--name agent-act-init-…`，且同次准备内名字互不相同；`DockerSandboxHostTest.initContainersAreSweepCandidates` 证明初始化容器与活动容器都能被 `staleCandidates` 选中、且不误伤别的主机/活进程 |

---

## 变更记录

- 2026-11-21 15:15 **定位并消除一个"假失败"**（值得单独记，因为它差点让我误判自己的修复无效）：
  > 现象：`DualStackPipelineTest` 反复红在 `mkdir /cache/m2: Permission denied`，
  > 且日志里**两轮重试都是 `--user 1000:1000`，没有一次 root 回退**。
  > 排查：源码、`target/classes/DockerSandbox.class`（含 `isInfrastructureFailure`）都是新的，
  > 手工 `docker run` 验证 root 回退在真实环境确实生效（uid 1000 失败 → root 成功）。
  > 真因：`agent-gateway` 测试跑的是**本地 Maven 仓库里的旧 artifact**
  > （`~/.m2/.../agent-executor-0.1.0-SNAPSHOT.jar` 时间戳 00:59，而修复编译于 14:44）。
  > `mvn -pl agent-gateway test` 不会重建它依赖的模块；只有 reactor 一起构建才会。
  > 处置：`mvn -pl agent-domain,agent-executor install -DskipTests` 后
  > `DualStackPipelineTest` 4 绿（504s，真实容器构建）；最终验证改用 `clean verify` 避免旧产物。
  >
  > **这条对后续维护的价值**：本仓库有 9 个模块且相互依赖，单模块 `test` 极易跑在过期
  > artifact 上，表现为"改了代码却没生效"。判断方法固定为三步：
  > ①对比 `target/classes/*.class` 与源文件 mtime；②反编译/搜字符串确认字节码含新逻辑；
  > ③对比 `~/.m2` 里 jar 的 mtime。三条都过才是真的"改完没生效"，否则先 `install`。
- 2026-11-21 15:00 **再补一条运行时保护并修正两处夸大判定**：
  > ①`data/workspaces/SUPERDEVELOPERAG/ci/actions.yml` 里仍留着历史的
  > `["docker","build",...]`（漂移的第 4 份）。该文件**不在版本库**（`data/` 被 gitignore），
  > 是运行期数据——方案原写"删除它"其实越界了：平台不该改用户工作区里的文件。
  > 改为守住真正的边界：新增 `ImagePackageSourceTrustTest`（4 绿）钉住
  > "仓库侧配方永不给宿主执行权"（`trustedForHost` + `PipelineDefinitionResolver.Source`），
  > 即使那份旧配方还在，也只会被拒绝上宿主，不会静默执行。
  > ②修正验收契约里 `G7/D8` 的判定：原文写"只命中 `DockerOps.java` 一处"，
  > 实测保留 3 类用途（探测事实 / 给自愈层产出复核命令 / 需要详细失败日志），
  > 真正消除的是"重复的存在性探测"——判定改为"没有第 4 处只问在不在的探测"。
  > ③修正 `G7/D12`：实测 `buildx build` 字面量在生产代码里只剩 `ImageBuildCommand` 两处
  > （标准 + 子目录两个重载），达标。
- 2026-11-21 14:50 **补齐上轮遗留的两条**（它们此前只写在验收契约里，没有实现）：
  > ①**G12 主机名归一**：`defaultHost()`（就绪状态分区）与 `hostName()`（容器归属标签）
  > 原是两份实现且环境变量优先级相反（HOSTNAME→COMPUTERNAME vs COMPUTERNAME→HOSTNAME）。
  > 合并为唯一实现 `localHostName()`，顺序固定 `HOSTNAME → COMPUTERNAME → InetAddress`；
  > 新增 `DockerSandboxHostTest`（5 绿），其中一条把"HOSTNAME/COMPUTERNAME 不同时必须取
  > HOSTNAME"钉死——任何人改回旧顺序都会红。
  > ②**D5 清扫覆盖**：上一轮只改了代码没有用例。新增
  > `DockerSandboxPrepareTest.initContainersAreLabeledAndNamed`（每条初始化命令都必须带
  > 标签+名字，且同次准备内名字互不相同）与 `DockerSandboxHostTest.initContainersAreSweepCandidates`
  > （初始化容器与活动容器都能被 `staleCandidates` 选中，别的主机/活进程不误伤）。
  > 同时修正上一轮自己写坏的一处文档：G12/G13 两行被误并成一行（表格结构损坏）。
- 2026-11-21 01:20 **全平台 `mvn -o verify` 通过**（9/9 模块 SUCCESS；agent-executor 321 绿、
  > spec-checks 35 绿、agent-gateway 全绿）。
  >
  > **落地过程中被暴露并修复的一个真实生产 bug（值得单独记）**：
  > `DockerSandbox.runInit` 判断"构建用户被拒 → 回退 root"时写死了
  > `ErrorCode.COMPILE_FAILED`（E-101），而初始化容器跑的是 `busybox mkdir`，按
  > `FailureKind.fromArgv` 归类为 `SCRIPT_FAILED`（E-104）——**回退分支从未执行过**。
  > 这个 bug 长期被掩盖：卷名过去不含哈希，测试与开发机反复复用同一批已 `chmod 777` 的
  > 旧卷，冷卷根本不会出现。D4（卷名加路径哈希）让每个新卷都是冷卷后，`DualStackPipelineTest`
  > 立刻 3 红：`mkdir: can't create directory '/cache/m2': Permission denied`。
  > 修复：回退条件改为"命令失败"（只放过 E-302 超时 / E-303 取消），并加
  > `rootFallbackCoversAllCommandFailures` 把 E-101/E-104/E-209 三种错误码都钉住。
  > 这条印证了方案里"结构性修法"的判断：D4 不是"改个名字"，它把一个被旧卷掩盖的缺陷逼了出来。
  >
  > 本轮新增/改动一览：新增 `DockerOps`、`SandboxImagePolicy`、`MiddlewareCompose`（executor）；
  > `ImageBuildCommand`、`LogRedactor`（domain）；`docker-compose.platform.yml`（仓库根）。
  > 删除 `requireNoExtraMounts(List)` / `requireUnprivileged(List)`（死代码）、
  > `DockerEnvProvisioner` 的 5 份私有 docker 辅助、`ProcessExecutor` 与 `BugFixRedactor`
  > 的两份脱敏实现、镜像里写死的 `ENV AGENT_SANDBOX_MODE=process`。
  > 实测：`docker compose config --quiet` 通过；镜像构建 643 MB、容器内 `uid=10001`、
  > 无 `AGENT_SANDBOX_MODE` 覆盖、无守护进程时 fail-closed 报错；
  > `-p 127.0.0.1::8080` 经 `docker port` 回读 `61487`；
  > `docker info --format '{{json .Runtimes}}'` 实测只有 `runc`/`nvidia`/`io.containerd.runc.v2`。
  > 过程中被门禁抓到的两处自身问题：①`ImageBuildCommand` 初版抛裸
  > `IllegalArgumentException`，被 `ErrorCodeContractTest`（EC5）拦下，改为带错误码的
  > `RecipeException`；②同文件初版不接受 `${tag}` 占位符，直接让 `RecipeActionResolver`
  > 静态初始化失败（8 个用例全红）。
- 2026-11-20 23:45 阶段 0 落地并回填证据。D1（第 9 处 `readAllBytes` + 禁令 R15 + fixture）与
> D10（`.dockerignore`）已改完并实测：门禁 `Tests run: 3, Failures: 0`，反证文件触发 `Failures: 1`
> 且报出行号；构建上下文 387.2 MB / 11245 文件 → 108.7 MB / 1030 文件，真实 `Dockerfile` 构建成功。
> 同时修正两处自身错误：①D10 初版写 `**/target/`，会把 Dockerfile 唯一需要的 `*-exec.jar` 也排除
> （已改为 `**/target/*` + 白名单，并把这个坑写进方案）；②G11 初版说"`withImage` 会丢参数"，
> 实测当前 12 个 setter 刚好覆盖，风险是"新增旋钮必须两处同步"而非"已经丢了"。
- 2026-11-20 23:30 立项。相对上一版 P0/P1/P2 清单的三处改动：①每条缺口改为"结构性落点"（守卫测试 /
> 单一实现 / 删除），不再只是"该改哪个文件"；②新增实测证据（`docker port` 回读、`.dockerignore`
> 体积、运行时清单无 runsc、R15 只命中 1 处）；③补"不建议改"一节，把已判定保留的设计（`wrap()`
> 参数组合、`HostToolExclusionTest` 立场、`DockerfileTools`）写进方案，避免下一轮重复提议。

## 链接

- 规格：`docs/session-archive-spec-v1.md` §4.6（plan 类契约）
- 相关归档：`2026-11-18_0641_sandbox-docker-cli-abstraction.md`（DockerCli 抽象）、
  `2026-11-18_0558_sandbox-buildkit-image-build.md`（buildx）、
  `2026-11-18_0547_sandbox-gvisor-runtime.md`（runtime 旋钮，本方案 D2 才真正接线）、
  `2026-11-19_0024_sandbox-structured-denied.md`（E-707 结构化拒绝）、
  `2026-11-20_1749_sandbox-image-panel.md`（镜像就绪面板）、
  `2026-11-19_1249_workdir-mount-escape.md`（挂载边界事故）
- 外部依据：Docker `--publish` 短语法 `ip::containerPort`（随机宿主端口）、
  `docker port` / `docker inspect .NetworkSettings.Ports` 回读、
  `docker info --format '{{json .Runtimes}}'`、`docker compose up --wait`、
  gVisor `runsc` 注册与 `--runtime`；`--init` 对应 tini 的僵尸回收。
