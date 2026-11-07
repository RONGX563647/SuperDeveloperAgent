# AgentScope Java 2.x 能力盘点 —— 面向 RuoYi 4.8.3 嵌入式接入的技术评估

> 审计对象：`E:\CODE\Agent\agentscope-ruoyi\agentscope-java`（Apache-2.0，`2.1.0-SNAPSHOT`）
> 目标宿主：`E:\CODE\Agent\agentscope-ruoyi\RuoYi`（4.8.3 / Spring Boot 4.1.0 / Shiro 3.0.0 / MyBatis / Thymeleaf / JDK17）
> 所有结论均标注 `文件路径:行号`。路径默认相对于 `agentscope-java/`（RuoYi 侧标注为 `RuoYi/...`）。
> 明确写到「未找到证据」的地方，是我检索过但仓库中没有对应实现的内容。

---

## 0. 先说结论（执行摘要）

| 维度 | 判定 | 关键依据 |
|---|---|---|
| 能否嵌进 RuoYi 单体（Spring MVC + Shiro） | **可以**，用 `agentscope-core` + 一个模型扩展 + 自建 Controller | 核心库零 Spring Security 依赖；官方提供 Spring MVC 版 SSE 控制器 `AguiRestController`（`SseEmitter`） |
| 最小可用依赖 | `agentscope-core` + `agentscope-extensions-model-*`（2 个 jar） | `docs/v2/zh/docs/quickstart.md:31-41` |
| 工具调用 | **成熟**，`@Tool` / `@ToolParam` + `Toolkit`，支持分组、按调用裁剪、超时/重试/并发 | §3 |
| 权限 / HITL | **成熟且可落地**，`PermissionMode` + `PermissionRule` + `RequireUserConfirmEvent` + `ConfirmResult` | §4 |
| 事件流 | **32 种细粒度事件**，官方 AG-UI 适配已把工具调用/思考/审批都映射成 SSE | §5 |
| 会话持久化 | MySQL/Redis 有 `AgentStateStore`，但 **SessionLogStore 只有内存和文件系统两种** —— 这是最大落地缺口 | §6 |
| 沙箱 | Docker/K8s/AgentRun/Daytona/E2B；**没有 local 沙箱**；Windows 可跑 Docker 版 | §7 |
| 包体积 | 会额外引入 **sqlite-jdbc（非 optional）+ okhttp 5 + tree-sitter 原生库 + MCP SDK** | §9 |
| 主要风险 | Jackson 2 vs Spring Boot 4 的 Jackson 3、springdoc 版本冲突、Redis 扩展强拉 3 个客户端 | §9 |

**一句话**：Agent 内核、工具、权限、事件四块质量很高，可以放心用；但「会话日志持久化到 MySQL/Redis」和「前端」这两块必须自己做，不能直接复用。

---

## 1. 工程坐标与依赖

### 1.1 Maven 坐标

```
groupId    : io.agentscope
artifactId : agentscope-parent        （聚合父 POM）
version    : ${revision} = 2.1.0-SNAPSHOT
java       : 17
```

证据：
- `pom.xml:23-30`（groupId / artifactId / version / revision=2.1.0-SNAPSHOT）
- `pom.xml:33-35`（`java.version` = 17，compiler source/target 跟随）
- `pom.xml:61-69`（模块清单：`agentscope-core`、`agentscope-harness`、`agentscope-service`、`agentscope-extensions`、`agentscope-examples`、`agentscope-dependencies-bom`、`agentscope-distribution`）
- 许可证 Apache 2.0：`pom.xml:85-91`

各子模块 artifactId 与父 POM 一致（如 `agentscope-core/pom.xml:30`、`agentscope-harness/pom.xml:30`），版本统一走 `${revision}`。

### 1.2 BOM 有两个，别搞混

| BOM | 作用 | 证据 |
|---|---|---|
| `io.agentscope:agentscope-dependencies-bom` | **第三方**依赖版本（Jackson / Reactor / OkHttp / MySQL / Jedis…） | `agentscope-dependencies-bom/pom.xml:23-25`、`:62-119` |
| `io.agentscope:agentscope-bom` | **AgentScope 自家** 78 个 artifact 的版本（含全部 starter） | `agentscope-distribution/agentscope-bom/pom.xml`（`<artifactId>` 共 78 处）；导入点在 `pom.xml:147-153` |

父 POM 同时 import 两者：

```xml
<!-- pom.xml:147-153 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-bom</artifactId>
    <version>${revision}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

关键第三方版本（`agentscope-dependencies-bom/pom.xml`）：

| 依赖 | 版本 | 行号 |
|---|---|---|
| Spring Framework | 7.0.7 | `:106` |
| Spring Boot | **4.0.4** | `:107` |
| Jackson | **2.21.1** | `:73`（import `jackson-bom`，`:174-178`） |
| Reactor BOM | 2025.0.2 | `:72` |
| OkHttp | 5.3.2 | `:71` |
| MySQL Connector/J | 9.6.0 | `:96`、`:367-372` |
| Jedis / Redisson / Lettuce | 7.4.1 / 4.2.0 / 6.4.2 | `:99`、`:101`、`:102` |
| a2a-java-sdk | 1.4.0.Final | `:100` |
| MCP SDK | 2.1.0 | `:83`（用的是 `mcp-json-jackson2` 而非默认 Jackson 3 版，`:252-262`） |

⚠️ **版本不一致点**：starter 父 POM 里 Spring Boot 是 **4.0.3**（`agentscope-extensions/agentscope-spring-boot-starters/pom.xml:35`），依赖 BOM 里是 **4.0.4**（`agentscope-dependencies-bom/pom.xml:107`）。两个值同时存在于构建中，实际以先声明者为准。

### 1.3 `agentscope-extensions/*` 下有哪些模块

`agentscope-extensions/pom.xml` 声明的 22 个子模块（实测目录）：

```
agentscope-extensions-channel        agentscope-extensions-mongodb
agentscope-extensions-controlplane   agentscope-extensions-mysql
agentscope-extensions-cos            agentscope-extensions-nacos
agentscope-extensions-higress        agentscope-extensions-oss
agentscope-extensions-jdbc           agentscope-extensions-postgresql
agentscope-extensions-judge          agentscope-extensions-protocol
agentscope-extensions-mem            agentscope-extensions-rag
agentscope-extensions-model          agentscope-extensions-redis
agentscope-extensions-sandbox        agentscope-extensions-scheduler
agentscope-extensions-skills         agentscope-extensions-studio
agentscope-extensions-training       agentscope-spring-boot-starters
```

其中两个是"目录套目录"的聚合模块，里层才有代码：

- `agentscope-extensions-model/` → `model-anthropic`、`model-dashscope`、`model-gemini`、`model-ollama`、`model-openai`、`model-openai-official`、`model-e2e-tests`
- `agentscope-extensions-protocol/` → `a2a`（下分 `-client` / `-server`）、`agent-protocol`、`agui`、`chat-completions-web`
- `agentscope-extensions-sandbox/` → `-kubernetes`、`-agentrun`、`-daytona`、`-e2b`
- `agentscope-extensions-channel/` → `-common`、`-dingtalk`、`-feishu`、`-github`、`-gitlab`、`-wecom`、`-weixin`

### 1.4 14 个 Spring Boot Starter 及其 AutoConfiguration / ConfigurationProperties

全部 starter 位于 `agentscope-extensions/agentscope-spring-boot-starters/`（`pom.xml:39-54` 注册 14 个）。

| Starter artifactId | `@AutoConfiguration` 类（FQCN） | `@ConfigurationProperties` 类（prefix） |
|---|---|---|
| `agentscope-spring-boot-starter` | `io.agentscope.spring.boot.AgentscopeAutoConfiguration` | `AgentscopeProperties` (`agentscope`，含 `agent` / `model` 两个子块) |
| `agentscope-dashscope-spring-boot-starter` | `...dashscope.DashScopeAutoConfiguration` | `DashScopeProperties` (`agentscope.dashscope`) |
| `agentscope-openai-spring-boot-starter` | `...openai.OpenAIAutoConfiguration` | `OpenAIProperties` (`agentscope.openai`) |
| `agentscope-openai-official-spring-boot-starter` | `...openaiofficial.OpenAIOfficialAutoConfiguration` | `OpenAIOfficialProperties` (`agentscope.openai-official`) |
| `agentscope-anthropic-spring-boot-starter` | `...anthropic.AnthropicAutoConfiguration` | `AnthropicProperties` (`agentscope.anthropic`) |
| `agentscope-gemini-spring-boot-starter` | `...gemini.GeminiAutoConfiguration` | `GeminiProperties` (`agentscope.gemini`) |
| `agentscope-ollama-spring-boot-starter` | `...ollama.OllamaAutoConfiguration` | `OllamaProperties` (`agentscope.ollama`) |
| `agentscope-jev-spring-boot-starter` | `...jev.JevAutoConfiguration` | `JevProperties` (`agentscope.jev`) |
| `agentscope-openai-decisions-spring-boot-starter` | `...openaidecisions.OpenAIDecisionsAutoConfiguration` | `OpenAIDecisionsProperties` (`agentscope.openai-decisions`) |
| `agentscope-a2a-spring-boot-starter` | `...a2a.AgentscopeA2aAutoConfiguration` | `A2aCommonProperties` / `A2aAgentCardProperties` / `JSONRPCProperties` (`agentscope.a2a.*`) |
| `agentscope-agui-spring-boot-starter` | `...agui.common.AguiAgentRegistryAutoConfiguration`<br>`...agui.mvc.AgentscopeAguiMvcAutoConfiguration`<br>`...agui.webflux.AgentscopeAguiWebFluxAutoConfiguration` | `AguiProperties` (`agentscope.agui`) |
| `agentscope-chat-completions-web-starter` | `...chat.config.ChatCompletionsWebAutoConfiguration` | `ChatCompletionsProperties` (`agentscope.chat-completions`) |
| `agentscope-nacos-spring-boot-starter` | `...nacos.AgentscopeA2aNacosAutoConfiguration`<br>`...nacos.AgentscopeNacosPromptAutoConfiguration`<br>`...nacos.AgentscopeNacosReActAgentAutoConfiguration` | `AgentScopeNacosProperties` / `AgentScopeNacosPromptProperties` / `AgentScopeA2aNacosProperties` (`agentscope.nacos.*`) |
| `agentscope-admin-spring-boot-starter` | `...admin.AgentscopeAdminAutoConfiguration` | `AdminProperties` (`agentscope.admin`) |

证据：
- AutoConfiguration 注册文件 `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（每个 starter 各一份，已逐个读取）
- 类定义行号：`AgentscopeAutoConfiguration.java:55-58`、`DashScopeAutoConfiguration.java:32-35`、`OpenAIAutoConfiguration.java:32-33`、`AnthropicAutoConfiguration.java:32-33`、`GeminiAutoConfiguration.java:32-33`、`OllamaAutoConfiguration.java:32-33`、`JevAutoConfiguration.java:31-32`、`OpenAIDecisionsAutoConfiguration.java:31-32`、`OpenAIOfficialAutoConfiguration.java:32-33`、`AgentscopeA2aAutoConfiguration.java:72-85`、`AguiAgentRegistryAutoConfiguration.java:37-44`、`AgentscopeAguiMvcAutoConfiguration.java:51-56`、`AgentscopeAguiWebFluxAutoConfiguration.java:54-58`、`ChatCompletionsWebAutoConfiguration.java:53-54`、`AgentscopeAdminAutoConfiguration.java:89-92`
- Properties 类：`AgentscopeProperties.java:30`、`OpenAIProperties.java:38`、`DashScopeProperties.java:38`、`AnthropicProperties.java:37`、`GeminiProperties.java:52`、`OllamaProperties.java:35`、`OpenAIOfficialProperties.java:37`、`JevProperties.java:24`、`OpenAIDecisionsProperties.java:23`、`AguiProperties.java:46`、`ChatCompletionsProperties.java:34`、`AdminProperties.java:27`

**核心 starter 只做三件事**（`AgentscopeAutoConfiguration.java:70-117`）：

1. 一个 **prototype** 作用域的 `InMemoryMemory`（`:70-76`）
2. 一个 **prototype** 作用域的 `Toolkit`（`:88-94`）
3. 一个 singleton 的 `ReActAgent`，把 `Model`/`Memory`/`Toolkit`/`AgentscopeProperties.agent` 拼起来（`:103-117`）

```java
// AgentscopeAutoConfiguration.java:107-117
public ReActAgent agentscopeReActAgent(
        Model model, Memory memory, Toolkit toolkit, AgentscopeProperties properties) {
    AgentProperties config = properties.getAgent();
    return ReActAgent.builder()
            .name(config.getName())
            .sysPrompt(config.getSysPrompt())
            .model(model)
            .toolkit(toolkit)
            .maxIters(config.getMaxIters())
            .build();
}
```

⚠️ 注意：这个默认 bean **没有设置 `stateStore`**，所以走 starter 的默认 `ReActAgent` 是**完全不持久化**的（见 §6.1）。

### 1.5 「最小可用 agent + 工具调用」需要哪几个依赖

**答案：2 个 jar。**

```xml
<!-- 方式 A：只要 ReAct 内核（推荐给 RuoYi，最轻） -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-dashscope</artifactId> <!-- 或 model-openai -->
</dependency>
```

证据：`docs/v2/zh/docs/quickstart.md:13-41`——「如果只需要 `ReActAgent` 的推理、工具和上下文 API … `agentscope-core` 足够提供 agent 本身。具体模型提供商是独立的…还需要引入对应模型扩展」。

**方式 B**：想要开箱的 workspace / 记忆 / 会话日志 / 子 agent，换成 `agentscope-harness`（会自动带 `agentscope-core`，`agentscope-harness/pom.xml:34-37`）。代价是 Harness 默认会做工作区扫描、技能装配等（见 §9 启动开销）。

**不需要**任何 starter。`agentscope-spring-boot-starter` 只是给你一个 `agent.name` / `agent.sys-prompt` / `agent.max-iters` 的 YAML 配置糖，`AgentscopeAutoConfiguration.java:46-53`。RuoYi 场景我建议**直接 new**，用 `@Bean` 自己管，可控性更高。

---

## 2. Agent 抽象：接口、调用方式、Builder、模型注册

### 2.1 接口层次

```
Agent  (io.agentscope.core.agent.Agent)
  = CallableAgent      → Mono<Msg> call(...)
  + StreamableAgent    → Flux<Event>  stream(...)   [@Deprecated forRemoval]
  + ObservableAgent    → Mono<Void> observe(...)
```

证据：`agentscope-core/src/main/java/io/agentscope/core/agent/Agent.java:49`；
`StreamableAgent` 整个接口被标记 `@Deprecated(since="2.0.0", forRemoval=true)`，`StreamableAgent.java:36-44`，**新代码不要用 `stream()`，用 `streamEvents()`**。

两个实现：

| 类 | 定位 | 证据 |
|---|---|---|
| `ReActAgent`（6395 行） | 内核：推理循环 + 工具 + 权限 + 上下文 | `agentscope-core/.../core/ReActAgent.java` |
| `HarnessAgent`（3345 行） | 在内核上加 workspace / 记忆 / 压缩 / 子 agent / 技能 / 沙箱 | `agentscope-harness/.../harness/agent/HarnessAgent.java` |
| `A2aAgent` | 把远端 A2A agent 当本地 agent 用 | `agentscope-extensions-protocol/agentscope-extensions-a2a-client/.../a2a/agent/A2aAgent.java` |

### 2.2 `call()` / `streamEvents()` 签名

**ReActAgent**（`ReActAgent.java`）：

```java
// 同步（返回 Mono，阻塞用 .block()）
public Mono<Msg> call(List<Msg> msgs, RuntimeContext context)                  // :1147
public Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass, RuntimeContext ctx) // :1151
public Mono<Msg> call(List<Msg> msgs, JsonNode outputSchema, RuntimeContext ctx)          // :1155
public Mono<Msg> call(String text, RuntimeContext context)                                // :1166
public Mono<Msg> call(String text, Class<?> structuredOutputClass, RuntimeContext ctx)    // :1179

// 流式（推荐）
public Flux<AgentEvent> streamEvents(List<Msg> msgs)                                      // :1489
public Flux<AgentEvent> streamEvents(Msg msg)                                             // :1499
public Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext context)              // :1514
public Flux<AgentEvent> streamEvents(Msg msg, RuntimeContext context)                     // :1526
public Flux<AgentEvent> streamEvents(String text)                                         // :1536
public Flux<AgentEvent> streamEvents(String text, RuntimeContext context)                 // :1548

// 句柄式（把「准备」和「执行」分开）
public AgentRun<AgentEvent> prepareRun(List<Msg> msgs, RuntimeContext context)            // :1459
public AgentRun<Msg>        prepareCall(List<Msg> msgs, RuntimeContext context)           // :1471

// 中断（协作式中断，按 (userId, sessionId) 定位）
public void interrupt(String userId, String sessionId)                                    // :1253
public void interrupt(String userId, String sessionId, Msg msg)                           // :1261
public void interrupt(RuntimeContext ctx)                                                 // :1227
```

**HarnessAgent** 完全对齐（`HarnessAgent.java:776-1000`）：`call(...)` 在 `:776/785/795/799/810/814/820/826`，`streamEvents(...)` 在 `:941/949/961/969/981/1000`，`prepareRun/prepareCall` 在 `:918/930`。

**同步 / 异步 / 流式怎么用**：

```java
// 1) 同步：Mono → block（只在非阻塞线程外使用）
Msg reply = agent.call(new UserMessage("你好"), ctx).block();

// 2) 异步：不阻塞，链式组合
Mono<Msg> m = agent.call(new UserMessage("你好"), ctx);

// 3) 流式：Flux<AgentEvent>，适合 SSE
agent.streamEvents(new UserMessage("你好"), ctx)
     .subscribe(evt -> { /* 按 evt.getType() 分发 */ });
```

官方推荐（`docs/v2/zh/docs/quickstart.md:123-136`）：Web 环境用 `Mono.using` 管实例生命周期，**不要在 `try` 里返回未订阅的 `Mono`**：

```java
// docs/v2/zh/docs/quickstart.md:128-133
RuntimeContext ctx = RuntimeContext.builder()
        .userId(userId).sessionId(sessionId).build();
return Mono.using(
        AGENT_BUILDER::build,
        agent -> agent.call(new UserMessage(userInput), ctx),
        HarnessAgent::close);
```

### 2.3 Builder 模式

**有，而且是最主要的构造方式**（两个 agent 都是 `builder()` + 内部 `Builder` 类）。

`ReActAgent.builder()` → `ReActAgent.java:5886`，`Builder` 类在 `:5901`。关键方法（行号）：

| 方法 | 行号 | 说明 |
|---|---|---|
| `name(String)` / `agentId(String)` / `description(String)` | `:6089` / `:6109` / `:6114` | 身份 |
| `sysPrompt(String)` | `:6131` | 系统提示 |
| `model(Model)` / `model(String)` | `:6142` / `:6158` | 直接给实例或给 `provider:model` 字符串 |
| `toolkit(Toolkit)` | `:6172` | 工具集 |
| `maxIters(int)` | `:6183` | 最大推理轮数 |
| `hook(Hook)` / `hooks(List)` / `middleware(...)` / `middlewares(...)` | `:6200-6238` | 扩展点 |
| `stateStore(AgentStateStore)` | `:6401` | **持久化，不设就不落盘** |
| `conflictPolicy(ConflictPolicy)` | `:6411` | 并发冲突策略 |
| `permissionContext(PermissionContextState)` | `:6490` | 权限/HITL |
| `toolExecutionConfig(ExecutionConfig)` / `modelExecutionConfig(...)` | `:6330` / `:6314` | 超时/重试 |
| `maxRetries(int)` / `fallbackModel(...)` / `failoverListener(...)` | `:6431` / `:6443` / `:6466` | 模型故障转移 |
| `longTermMemory(...)` / `knowledge(...)` / `ragMode(...)` | `:6506` / `:6533` / `:6555` | 长期记忆 / RAG |
| `skillBox(...)` / `skillRepository(...)` / `skillFilter(...)` | `:6581` / `:6596` / `:6625` | 技能 |

`HarnessAgent.builder()` → `HarnessAgent.java:1214`，`Builder` 在 `:1290`。除了上面全部，还有一大批 `disableXxx()` 开关，**对 RuoYi 极有价值**（关掉不需要的能力来减负重）：

```java
// HarnessAgent.java:2355-2570 的 disable 系列（节选）
.disableFilesystemTools()   // :2355
.disableShellTool()         // :2361
.disableWebTools()          // :2367
.disableSubagents()         // :2565
.disableDynamicSkills()     // :2398
.disableMemoryTools()       // :2525
.disableMemoryHooks()       // :2536
.disableWorkspaceContext()  // :2555
.disableAtPathExpansion()   // :2560
.disableToolsConfig()       // :2570
```

另有 `.workspace(Path)` (`:2028`)、`.sessionLogStore(SessionLogStore)` (`:1312`)、`.compaction(...)` (`:2107`)、`.memory(MemoryConfig)` (`:2127`)、`.filesystem(LocalFilesystemSpec)` (`:2079`)。

### 2.4 模型注册：支持哪些 provider

**两套机制**：

**(a) 字符串解析（`ModelRegistry` + SPI）** —— `agent.model("dashscope:qwen-plus")`

- `ModelRegistry` 支持：命名实例 `register(name, model)`、正则工厂 `registerFactory(regex, factory)`、SPI 提供者 `ModelProvider`（`ModelRegistry.java:76-137`、`:18`）
- 解析顺序：命名实例 → 用户工厂 → SPI（`ModelRegistry.java:41-47`）

**SPI 提供者（`implements ModelProvider` + `providerName()`）实测清单**：

| provider id | 实现类 | 证据 |
|---|---|---|
| `dashscope` | `DashScopeModelProvider` | `.../model-dashscope/.../DashScopeModelProvider.java:38,54` |
| `openai` | `OpenAIModelProvider` | `.../model-openai/.../OpenAIModelProvider.java:30,38` |
| `anthropic` | `AnthropicModelProvider` | `.../model-anthropic/.../AnthropicModelProvider.java:39,48` |
| `gemini` | `GeminiModelProvider` | `.../model-gemini/.../GeminiModelProvider.java:39,50` |
| `ollama` | `OllamaModelProvider` | `.../model-ollama/.../OllamaModelProvider.java:35,43` |
| `openai-official`（Responses API） | `OpenAIOfficialModelProvider` | `.../model-openai-official/.../OpenAIOfficialModelProvider.java:32` |
| OpenAI 兼容：DeepSeek / GLM / Kimi / MiniMax | `DeepSeekModelProvider` / `GLMModelProvider` / `KimiModelProvider` / `MiniMaxModelProvider` | `.../model-openai/.../compat/{deepseek,glm,kimi,minimax}/*.java:32/58/60/38` |

**自建 OpenAI 兼容端点 → 支持**。两种方式：

```java
// 方式 1：builder 直接给 baseUrl（OpenAIChatModel.java:322）
OpenAIChatModel.builder()
    .apiKey("sk-xxx")
    .modelName("qwen2.5-72b-instruct")
    .baseUrl("http://10.0.0.5:8000/v1")
    .endpointPath("/chat/completions")   // OpenAIChatModel.java:337，非标准路径时用
    .build();

// 方式 2：YAML（OpenAIProperties.java:30-36）
// agentscope.openai.base-url: http://10.0.0.5:8000/v1
// agentscope.openai.endpoint-path: /v1/chat/completions
```

### 2.5 各 provider 的配置 key 一览

| provider | prefix | 可配字段 | 证据 |
|---|---|---|---|
| DashScope | `agentscope.dashscope` | `enabled`、`api-key`、`model-name`、`base-url`、`stream`、`enable-thinking`、`multimodal-model-patterns` | `DashScopeProperties.java:38-69`；`DashScopeAutoConfiguration.java:37-85` |
| OpenAI | `agentscope.openai` | `enabled`、`api-key`、`model-name`(默认 `gpt-4.1-mini`)、`base-url`、`endpoint-path`、`stream` | `OpenAIProperties.java:38-74` |
| OpenAI Official | `agentscope.openai-official` | `enabled`、`api-key`、`base-url`、`model-name`、`stream` | `OpenAIOfficialProperties.java:37-60` |
| Anthropic | `agentscope.anthropic` | `enabled`、`api-key`、`auth-token`、`base-url`、`model-name`、`stream` | `AnthropicProperties.java:37-59` |
| Gemini | `agentscope.gemini` | `enabled`、`api-key`、`base-url`、`project`、`location`、`vertex-ai`、`model-name` | `GeminiProperties.java:52-93` |
| Ollama | `agentscope.ollama` | `enabled`、`base-url`、`model-name` | `OllamaProperties.java:35-51` |
| Agent（starter 级） | `agentscope.agent` | `enabled`、`name`、`sys-prompt`、`max-iters` | `AgentscopeAutoConfiguration.java:46-53`；`AgentscopeProperties.java:30-43` |

> 注：`DashScopeAutoConfiguration.java:48-53` 明确要求 `api-key` 与 `model-name` 必须配置，否则启动期抛 `IllegalStateException`——这与 RuoYi 希望"没配 key 也能启动"的习惯不同，接 starter 前要注意。

---

## 3. 工具（Tool / Function Calling）机制

### 3.1 注解：类名与包路径

| 注解 | FQCN | 证据 |
|---|---|---|
| `@Tool` | `io.agentscope.core.tool.Tool` | `agentscope-core/src/main/java/io/agentscope/core/tool/Tool.java:60` |
| `@ToolParam` | `io.agentscope.core.tool.ToolParam` | `.../tool/ToolParam.java:62` |

`@Tool` 的可选属性（`Tool.java`）：

| 属性 | 行号 | 用途 |
|---|---|---|
| `name` | `:70` | 工具名，空则用方法名（建议 snake_case） |
| `description` | `:87` | 给 LLM 看的说明 |
| `strict` | `:104` | 严格 JSON Schema（递归注入 `additionalProperties:false`，满足 OpenAI strict mode） |
| `deferLoading` | `:116` | 延迟加载 schema（OpenAI Responses `tool_search`） |
| `readOnly` | `:126` | 只读 → 在 `EXPLORE`/`ACCEPT_EDITS` 下自动放行 |
| `concurrencySafe` | `:136` | 默认 `true`；false 则在并行批次中串行化 |
| `timeoutManaged` | `:144` | 工具自己管超时，不吃框架的 tool timeout |
| `externalTool` | `:154` | 不在框架内执行，由调用方回填结果（`TOOL_SUSPENDED`） |
| `stateInjected` | `:165` | 注入 `AgentState`（方法必须正好一个该类型参数） |
| `returnDirect` | `:198` | 结果直接作为最终答复，跳过下一轮模型推理 |
| `dangerousFiles` / `dangerousDirectories` | `:207` / `:216` | 追加敏感路径，命中强制 ASK |
| `converter` | `:251` | 自定义 `ToolResultConverter` |

`@ToolParam` 属性：`name`（必填，`:76`）、`required`（默认 true，`:87`）、`description`（`:103`）。

### 3.2 怎么把一个 Java 方法暴露成工具

**三步**，不需要 XML、不需要实现接口：

```java
// 1) 普通 POJO + 注解
public class OrderTools {
    @Tool(name = "query_order", description = "按订单号查询订单状态", readOnly = true)
    public String queryOrder(
            @ToolParam(name = "order_no", description = "订单号，例如 SO202401001") String orderNo) {
        return orderService.findByNo(orderNo).toString();   // 返回值会被序列化
    }
}

// 2) 注册到 Toolkit（反射扫描所有 @Tool 方法）
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new OrderTools());

// 3) 挂到 agent
ReActAgent agent = ReActAgent.builder()
        .name("order-assistant").sysPrompt("...").model(model).toolkit(toolkit).build();
```

证据：`Toolkit.registerTool(Object)` 在 `Toolkit.java:160`；`@Tool` 的 javadoc 明确写着「Methods annotated with `@Tool` are automatically registered with the toolkit…The toolkit uses reflection to discover tool methods and generate appropriate JSON schemas」（`Tool.java:26-29`）。完整可运行示例：`agentscope-examples/documentation/.../tool/ToolCallingExample.java:60-85`（注册）与 `:127-145`（注解方法）。

```java
// ToolCallingExample.java:60-85
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new SimpleTools());

ReActAgent agent =
        ReActAgent.builder()
                .name("ToolAgent")
                .sysPrompt("You are a helpful assistant with access to tools. ...")
                .model(DashScopeChatModel.builder()
                        .apiKey(apiKey).modelName("qwen-max").stream(true)
                        .enableThinking(false)
                        .formatter(new DashScopeChatFormatter())
                        .build())
                .toolkit(toolkit)
                .middleware(new AgentTraceMiddleware())
                .build();
```

**依赖注入**：工具方法可以声明一个任意 POJO 参数，由 `RuntimeContext` 按类型注入（无需 `@ToolParam`）：

```java
// RuntimeContext.java:254 / :467
RuntimeContext ctx = RuntimeContext.builder()
        .userId("1001")
        .put(LoginUser.class, shiroLoginUser)     // 泛型 put
        .build();
agent.call(msg, ctx);

@Tool(name = "who_am_i", description = "返回当前登录用户")
public String whoAmI(LoginUser user) { return user.getUsername(); }   // 自动注入
```

> `ToolExecutionContext` 已被 `@Deprecated`，官方建议改用 `RuntimeContext.builder().put(Type.class, value)`（`ToolExecutionContext.java:47-49`；`RuntimeContext.java:467`）。`RuntimeContext.get(Class<T>)` 在 `:239`。

### 3.3 工具分组 Toolkit

`Toolkit` 支持两层概念：**ToolGroup（组）** + **activeGroups（激活集）**。

| 方法 | 行号 | 作用 |
|---|---|---|
| `createToolGroup(name, description, active)` | `:782` | 建组 |
| `registerToolGroup(ToolGroup)` | `:850` | 注册自定义组 |
| `addToolToGroup(group, tool)` | `:864` | 加成员 |
| `updateToolGroups(List<String>, boolean active)` | `:885` | 批量启停 |
| `getActiveGroups()` / `setActiveGroups(List)` | `:954` / `:968` | 读写激活集 |
| `removeTool(name)` / `removeToolGroups(List)` | `:900` / `:931` | 删工具/删组 |
| `copy()` / `copyExcludingTools(Collection)` | `:1034` / `:1050` | 复制工具集（按需裁剪） |
| `getToolSchemas(activeGroups)` / `getToolSchemas(..., activeGroups, requestConfig)` | `:433` / `:454` | 按激活组算 schema |

`ToolGroupScope` 决定组的生命周期由谁管（`ToolGroupScope.java:29-41`）：
- `META`：由 agent 的 meta tool（`reset_equipped_tools`）运行时开关；
- `EXTERNAL`：完全由开发者代码控制，meta tool 看不见也改不了 —— **RuoYi 应选这个**。

### 3.4 是否支持「动态注册」与「按用户裁剪」

**动态注册：支持。**
- 运行时加/删：`registerTool`(`:160`)、`registerAgentTool`(`:209`)、`replaceAgentTool`(`:227`)、`removeTool`(`:900`)、`removeToolIfSame`(`:915`)
- 可用 `ToolkitConfig.allowToolDeletion(false)` 锁死删除权限（`ToolkitConfig.java:223`）
- `MiddlewareBase` 可在调用链里往 `agent.getToolkit()` 动态注册（`Agent.java:103-115` 的注释明确要求"必须用运行时 accessor"）

**按用户/按调用裁剪：支持，而且是官方设计的核心路径** —— `ToolRequestConfig`：

```java
// ToolRequestConfig.java:41-50  —— 每调用一份不可变配置，不复制、不改共享 Toolkit
public record ToolRequestConfig(
        Map<String, SchemaOnlyTool> externalTools,   // name -> schema-only 工具，覆盖 backend
        ToolMergeMode mergeMode                      // AGENT_ONLY / MERGE_EXTERNAL_PRIORITY / EXTERNAL_ONLY
) {
    public static final ToolRequestConfig NONE = new ToolRequestConfig(Map.of(), ToolMergeMode.AGENT_ONLY);
}
```

挂载点：`RuntimeContext.Builder#toolRequestConfig(ToolRequestConfig)`（`RuntimeContext.java:519`），
执行时由 `Toolkit.callTools(...)` 读取（`Toolkit.java:665-673`、`:721-732`）。

三种实现路线，RuoYi 按需要选：

| 路线 | 做法 | 适用 |
|---|---|---|
| A. 每用户一份 Toolkit | `toolkit.copyExcludingTools(deniedTools)` 或 `setActiveGroups(...)` | 用户工具集差异大 |
| B. 每调用一份 `ToolRequestConfig` | 从 `RuntimeContext` 传，不碰共享 Toolkit，**并发安全** | 推荐，最轻 |
| C. `ToolCallInterceptor` 运行时拒 | `ToolkitConfig.interceptor(...)`，按 `RuntimeContext` 里的身份拒绝 | 参数级鉴权（数据权限） |

`ToolCallInterceptor` 的定位（`ToolCallInterceptor.java:22-48`）：**参数级业务校验**（按身份沙箱化路径、审计、改写参数、策略拒绝），与权限引擎分工明确 —— 权限引擎管"这个工具能不能调"，拦截器管"这次调用的参数对不对"。

### 3.5 工具返回值如何序列化

默认 `DefaultToolResultConverter`（`@Tool.converter` 的默认值，`Tool.java:251`）：

| 输入 | 行为 | 行号 |
|---|---|---|
| `void` / `Void` | 文本 `"(completed with no output)"` | `DefaultToolResultConverter.java:45-46, 76-79` |
| `null` | 文本 `"null"` | `:49-51, 66-68` |
| 已经是 `ToolResultBlock` | 原样透传 | `:54-56` |
| 其他任意对象 | `JsonUtils.getJsonCodec().toJson(result)` → 单 `TextBlock`；失败降级 `String.valueOf` | `:88-98` |

```java
// DefaultToolResultConverter.java:88-98
protected ToolResultBlock serialize(Object result, Type returnType) {
    try {
        String json = JsonUtils.getJsonCodec().toJson(result);
        return ToolResultBlock.of(List.of(TextBlock.builder().text(json).build()));
    } catch (Exception e) {
        logger.warn("Failed to serialize result to JSON, falling back to toString()", e);
        return ToolResultBlock.of(List.of(TextBlock.builder().text(String.valueOf(result)).build()));
    }
}
```

> RuoYi 的实体（含 `Date`、`BigDecimal`）会被 Jackson 2 序列化成 JSON 字符串。想控制输出（比如剔除敏感字段、只返回摘要），实现自定义 `ToolResultConverter` 并在 `@Tool(converter = XxxConverter.class)` 上指定。

**流式进度**：方法可额外声明 `ToolEmitter` 参数（框架注入，无需注解），随时 `emitter.emit(ToolResultBlock.text("..."))` → 前端会收到 `TOOL_PROGRESS` 事件。示例：`agentscope-examples/agents/agentscope-chat/.../DemoTools.java:34-48`。

### 3.6 超时 / 重试 / 并发控制

| 维度 | 默认 | 配置点 | 证据 |
|---|---|---|---|
| 工具超时 | **5 分钟**，`maxAttempts=1`（不重试） | `ExecutionConfig.TOOL_DEFAULTS` | `ExecutionConfig.java:166-167` |
| 模型超时 | **5 分钟**，`maxAttempts=3`，退避 2s→30s ×2.0 | `ExecutionConfig.MODEL_DEFAULTS` | `:148-156` |
| 重试判定 | 仅 429 / 5xx / 超时 / IO 错误 | `RETRYABLE_ERRORS` / `isRetryableError` | `:91-131` |
| 并行 | **默认并行**执行同轮多工具 | `ToolkitConfig.isParallel()`，`ToolkitConfig.builder().parallel(false)` | `ToolkitConfig.java:33-36, 62, 186` |
| 单工具串行化 | `@Tool(concurrencySafe = false)` | `Tool.java:136` | |
| 自管超时 | `@Tool(timeoutManaged = true)` | `Tool.java:144` | |
| 自定义线程池 | 不设则 `Schedulers.boundedElastic()` | `ToolkitConfig.builder().executorService(...)` | `ToolkitConfig.java:191-201` |
| 内建 Shell 工具超时 | 300s | `ShellCommandTool.DEFAULT_TIMEOUT` | `.../tool/coding/ShellCommandTool.java:76` |

配置示例：

```java
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
        .parallel(true)
        .executionConfig(ExecutionConfig.builder()
                .timeout(Duration.ofSeconds(30))
                .maxAttempts(2)
                .build())
        .interceptor(myRuoYiDataScopeInterceptor)
        .build());
```

> `ExecutionConfig` 支持 `mergeConfigs` 逐参数合并（`ExecutionConfig.java:43-52`），优先级：per-request > agent-level > component-defaults > system-defaults。

---

## 4. 权限与 HITL（human-in-the-loop）

### 4.1 核心 API：allow / require approval / deny 怎么表达

**两个正交维度：`PermissionMode`（全局策略）× `PermissionRule`（逐工具规则）。**

**`PermissionMode`（`PermissionMode.java:33-38`）**

| 枚举 | 语义 | 证据 |
|---|---|---|
| `DEFAULT` | 所有操作都要显式 allow 规则或用户确认（**最安全，推荐**） | `:34` |
| `ACCEPT_EDITS` | 工作目录内的文件编辑自动放行 | `:35` |
| `EXPLORE` | 只读：放行读，拒绝写与命令 | `:36` |
| `BYPASS` | 全放行（deny/ask 规则仍生效） | `:37` |
| `DONT_ASK` | ASK 降级为 DENY（无人值守） | `:38` |

**`PermissionBehavior`（`PermissionBehavior.java:32-36`）**：`ALLOW` / `DENY` / `ASK` / `PASSTHROUGH`

- 「allow」= `ALLOW` 规则
- 「require approval」= `ASK` 规则（或 `DEFAULT` 模式下未命中任何规则 → 兜底 ASK）
- 「deny」= `DENY` 规则
- `PASSTHROUGH` = 工具自己不表态，交回引擎按规则/模式评估

**`PermissionRule`（record，`PermissionRule.java:33-37`）**

```java
public record PermissionRule(
        @JsonProperty("tool_name")    String toolName,     // 必填
        @JsonProperty("rule_content") String ruleContent,  // 可选，null = 匹配该工具所有调用
        @JsonProperty("behavior")     PermissionBehavior behavior,
        @JsonProperty("source")       String source)       // "userSettings"/"projectSettings"/"session"/"suggested"
```

`ruleContent` 由各工具的 `matchRule()` 解释（例如 shell 命令工具可以按命令前缀匹配）。

**`PermissionContextState`（`PermissionContextState.java:37-43`）** 持有 `mode` + `workingDirectories` + `allowRules` / `denyRules` / `askRules` 三张表，序列化 key 为 `mode` / `working_directories` / `allow_rules` / `deny_rules` / `ask_rules`（`:36`）。

**完整配置示例**：

```java
// docs/v2/zh/docs/building-blocks/permission-system.md:324-344
PermissionContextState permCtx =
        PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addAllowRule("safe_read",
                        new PermissionRule("safe_read", null, PermissionBehavior.ALLOW, "policy"))
                .addAskRule("dangerous_delete",
                        new PermissionRule("dangerous_delete", null, PermissionBehavior.ASK, "policy"))
                .addDenyRule("drop_table",
                        new PermissionRule("drop_table", null, PermissionBehavior.DENY, "userSettings"))
                .build();

ReActAgent agent = ReActAgent.builder()
        .name("GuardedAgent").sysPrompt("...").model(model)
        .toolkit(toolkit)
        .permissionContext(permCtx)        // ReActAgent.java:6490
        .build();
```

**求值顺序**（`PermissionEngine.java:30-41`，逐条实现于 `:139-202`）：

```
1. deny rules         (最高优先级)          :143-147
2. ask rules                                :149-153
3. tool 自检 checkPermissions（不可绕过）     :155-177
4. allow rules                              :182-186
5. BYPASS mode 兜底 ALLOW                    :188-196
6. 默认 ASK（DONT_ASK 下转 DENY）            :198-201
```

**工具侧自检**：继承 `ToolBase` 覆写 `checkPermissions(Map<String,Object>, ToolExecutionContext)` → 返回 `PermissionDecision.allow/deny/ask/passthrough`（`docs/v2/zh/docs/building-blocks/permission-system.md:244-282`）。这层**永远生效**，不受 mode/rules 覆盖 —— RuoYi 的数据权限校验放这里最稳。

**运行时切换模式**（RuoYi 可以按角色切）：

```java
agent.setPermissionMode(userId, sessionId, PermissionMode.EXPLORE);   // ReActAgent.java:5649
agent.setPermissionMode(RuntimeContext ctx, PermissionMode mode);      // :5709
agent.getPermissionMode(String userId, String sessionId);              // :5732
agent.replacePermissionContext(...);                                   // :5676
```

**危险路径保护**：`ToolBase` 内置敏感文件/目录清单（`.ssh/`、`.aws/`、`.env`、`.git/` 等），命中后即使 `BYPASS` 也强制 ASK（`permission-system.md:284-294`）。对 RuoYi 宿主服务器是有价值的兜底。

### 4.2 审批请求如何以事件形式抛出

**阻塞式 `call()`**：agent 提前返回，`Msg.getGenerateReason() == GenerateReason.PERMISSION_ASKING`，`Msg` 内容里是 `ToolCallState.ASKING` 的 `ToolUseBlock`。
证据：`permission-system.md:298`、`:350-357`；可运行示例 `PermissionHITLExample.java:159-174`。

**流式 `streamEvents()`（推荐给 Web）**：直接拿 `RequireUserConfirmEvent`。

```java
// RequireUserConfirmEvent.java:26-58
public class RequireUserConfirmEvent extends AgentEvent {
    private final String replyId;
    private final List<ToolUseBlock> toolCalls;     // 待确认的工具调用
    public AgentEventType getType() { return AgentEventType.REQUIRE_USER_CONFIRM; }
}
```

配套事件：
- `UserConfirmResultEvent`（`USER_CONFIRM_RESULT`）—— 恢复时有，携带本次确认结果，用 `replyId` 关联回上一条 `RequireUserConfirmEvent`（`permission-system.md:437-439`）
- `AllToolsDeniedEvent`（`ALL_TOOLS_DENIED`）—— 本轮全部被拒
- `RequestStopEvent`（`REQUEST_STOP`）—— 可主动终止

### 4.3 如何在 Web 端响应（关键：有没有现成的 REST/SSE 端点）

**有，而且有两套。**

#### 路线 A：AG-UI（推荐，官方就是按 HITL 设计的）

**服务端端点**（Spring MVC，用 `SseEmitter`，**不依赖 WebFlux**）：

```java
// AguiRestController.java:41, 84-97
@RestController
public class AguiRestController {
    @PostMapping(
            value = "${agentscope.agui.path-prefix:/agui}/run",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@RequestBody String body,
                          @RequestHeader(value = "${agentscope.agui.agent-id-header:X-Agent-Id}",
                                        required = false) String agentIdHeader,
                          HttpServletRequest request) {
        RunAgentInput input = requestBodyParser.parse(body);
        return aguiMvcController.handle(input, agentIdHeader, request);
    }
}
```

还有 `/agui/run/{agentId}` 路径变量版（`:109-120`）。自动装配条件：`@ConditionalOnWebApplication(type = SERVLET)` + 需要 `AguiAgentRegistry` bean（`AgentscopeAguiMvcAutoConfiguration.java:51-56`）。

**审批怎么变成 SSE 事件**：`PermissionConfirmEventConverter` 把 `RequireUserConfirmEvent` 转成 AG-UI 的 `Interrupt`，挂在 `RUN_FINISHED.outcome.interrupts[]`：

```java
// PermissionConfirmEventConverter.java:116-138
private static AguiEvent.Interrupt buildInterrupt(String replyId, ToolUseBlock toolUse) {
    String toolCallId = toolUse.getId();
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (!isBlank(toolUse.getName())) metadata.put(METADATA_TOOL_NAME, toolUse.getName());
    if (toolUse.getInput() != null && !toolUse.getInput().isEmpty())
        metadata.put(METADATA_TOOL_INPUT, toolUse.getInput());
    metadata.put(METADATA_TOOL_CONTENT, JsonUtils.resolveToolCallArgsJson(toolUse));
    metadata.put(METADATA_AGENTSCOPE_INTERRUPT_KIND, INTERRUPT_KIND_PERMISSION_CONFIRM);
    if (!isBlank(replyId)) metadata.put(METADATA_REPLY_ID, replyId);
    return new AguiEvent.Interrupt(
            interruptId(replyId, toolCallId),       // "replyId:toolCallId"
            TOOL_CALL_INTERRUPT_REASON,
            confirmMessage(toolUse),
            toolCallId,
            CONFIRM_RESPONSE_SCHEMA,                // {approved:boolean, editedArgs?:object, reason?:string}
            null,
            Map.copyOf(metadata));
}
```

**前端怎么回**：**发第二次 `POST /agui/run`**，在 `RunAgentInput.resume` 里带上 `AguiResume(interruptId, status, payload)`。

```java
// AguiResume.java:33-57
public class AguiResume {
    public static final String STATUS_RESOLVED  = "resolved";
    public static final String STATUS_CANCELLED = "cancelled";
    private final String interruptId;   // 来自 RunFinished.outcome.interrupts[]
    private final String status;
    private final Object payload;       // {approved:true/false, editedArgs, reason}
}
```

服务端做**契约校验**（`AguiResumeCoordinator.java:56-101`）：必须覆盖全部未决 interrupt，不能有未知/重复 id，且同一 threadId 不能并发两个 run（`:113-127`）。
`PermissionConfirmEventConverter` 的 javadoc 明确写了 `payload` 里 `approved=false` 也算 `resolved`（不是 cancelled），见 `AguiResume.java:28-32`。

⚠️ **重要限制**：`AguiResumeCoordinator` 的 pending 表是**进程内 `ConcurrentHashMap`**（`AguiResumeCoordinator.java:46-48`）——**多副本部署会丢审批状态**，RuoYi 单机无影响，集群部署需要注意。

#### 路线 B：OpenAI Chat Completions 兼容端点

`ChatCompletionsController` 暴露 `POST /v1/chat/completions`（前缀见 `agentscope.chat-completions`），支持 `stream=true` → SSE（`ChatCompletionsController.java:42-76`）。
特点：**100% 无状态**，会话历史由客户端每次全量上传（`:45-56`），**不支持 HITL 审批**（tool_calls 直接返回给客户端自己执行，`:58-67`）。适合"接一个现成 OpenAI 客户端"，不适合审批场景。

#### 运维侧审批查看（只读）

Actuator 端点 `GET /actuator/agentscope-permissions[/{agentId}]` 返回每个 agent 的 mode + allow/deny/ask 规则表。

```java
// AgentscopePermissionsEndpoint.java:39-58, 92-94
@Endpoint(id = "agentscope-permissions")
public class AgentscopePermissionsEndpoint {
    @ReadOperation public List<Map<String,Object>> listAll() { ... }
    ...
    out.put("allow", flatten(engine.getAllowRules()));
    out.put("deny",  flatten(engine.getDenyRules()));
    out.put("ask",   flatten(engine.getAskRules()));
}
```

⚠️ **明确是只读的**，类注释原文：

> "Read-only — mutating rules requires changes in agentscope-core to support live rule edits, which is tracked in `ADMIN_OPS_API_PLAN.md`."（`AgentscopePermissionsEndpoint.java:36-37`）

也就是说**没有 REST API 可以动态改规则**。RuoYi 若要从后台页面改规则，只能走 `agent.setPermissionMode(...)`（模式可改）或重建 agent 的 `PermissionContextState`（规则表不可热改）。**这是需要自己补的一块。**

### 4.4 与 Spring Security / 自定义鉴权如何衔接

**好消息：核心库对 Spring Security 零依赖。**

全仓检索 `org.springframework.security`，只出现在 examples 里（`agentscope-examples/agents/agentscope-dataagent/...` 共 10 个文件），**`agentscope-core`、`agentscope-harness`、全部 starter 都不引用**（`grep -l "springframework.security"` 结果仅命中 dataagent 示例）。所以 Shiro 与它**不存在任何自动配置层面的冲突**。

衔接设计有三条缝：

**(1) 身份注入 —— `RuntimeContext`**

```java
// RuoYi 侧
LoginUser loginUser = ShiroUtils.getLoginUser();
RuntimeContext ctx = RuntimeContext.builder()
        .userId(String.valueOf(loginUser.getUserId()))   // RuoYi 的 userId
        .sessionId(conversationId)                        // 你自己生成/校验
        .put(LoginUser.class, loginUser)                  // 供 @Tool 方法注入
        .build();
```

定义在 `RuntimeContext.java:39-40`（`sessionId` / `userId`），builder 在 `:410-471`。`userId` 可为 null（匿名/单租户），但 RuoYi 场景应总是传，否则多人会话会串（见 §6.3）。

**(2) AG-UI 请求 → RuntimeContext：`AguiRuntimeContextResolver`**

这是把 Shiro 身份接进 SSE 通道的官方钩子：

```java
// AguiRuntimeContextResolver.java:33-42
@FunctionalInterface
public interface AguiRuntimeContextResolver {
    RuntimeContext resolve(AguiRuntimeContextRequest<?> request);
}
```

从 `request.getNativeRequest()` 拿到底层 `HttpServletRequest`，再用 `ShiroUtils.getLoginUser()` 取身份（`AguiRuntimeContextResolver.java:28-31` 明确说 native request 可 `instanceof` 还原）。注册为 bean 即生效（`AgentscopeAguiMvcAutoConfiguration.java:102, 125`）。

**(3) 工具级数据权限：`ToolCallInterceptor`**

Shiro 的 `@RequiresPermissions` 是 AOP 方法级；工具调用是反射调用，走不到 Shiro 的 AOP 代理。所以：

```java
// 在拦截器里显式判权（ToolCallInterceptor.java:55-66）
public class RuoYiToolGuard implements ToolCallInterceptor {
    @Override
    public Mono<ToolResultBlock> intercept(ToolInvocation inv, Function<ToolInvocation, Mono<ToolResultBlock>> next) {
        LoginUser u = inv.runtimeContext().get(LoginUser.class);
        String perm = PERM_MAP.get(inv.toolName());     // "system:user:list" 之类
        if (perm != null && !ShiroUtils.getSubject().isPermitted(perm)) {
            return Mono.just(ToolResultBlock.text("无权限：" + perm).withState(ToolResultState.DENIED));
        }
        return next.apply(inv);
    }
}
```

策略拒绝用 `ToolResultBlock.text(msg).withState(ToolResultState.DENIED)`（`ToolCallInterceptor.java:34-36`），会被记录为 `denied` 决策。

**(4) HITL 与 Shiro 会话的适配**：审批是「异步二次请求」，Shiro 的 `HttpSession` 天然能带过来（同源 Cookie），不需要额外 token 传递。只要保证两次 `/agui/run` 打到同一节点（或 §4.3 的进程内 pending 表做共享）。

---

## 5. 事件流与前端渲染

### 5.1 32 种事件类型全清单

`io.agentscope.core.event.AgentEventType` 共 **32** 个枚举值（`AgentEventType.java:40-91`，实测计数 32）：

| # | 枚举 | 行号 | 对应事件类 | 前端用途 |
|---|---|---|---|---|
| 1 | `AGENT_START` | `:42` | `AgentStartEvent` | 开一个气泡 |
| 2 | `AGENT_END` | `:44` | `AgentEndEvent` | 结束气泡 |
| 3 | `AGENT_RESULT` | `:45` | `AgentResultEvent` | **拿最终 `Msg`（`:57 getResult()`）** |
| 4 | `MODEL_CALL_START` | `:48` | `ModelCallStartEvent` | 显示"思考中" |
| 5 | `MODEL_CALL_END` | `:50` | `ModelCallEndEvent` | 收起 |
| 6 | `TEXT_BLOCK_START` | `:52` | `TextBlockStartEvent` | 正文开始 |
| 7 | **`TEXT_BLOCK_DELTA`** | `:53` | `TextBlockDeltaEvent` | **正文逐字（`:59 getDelta()`）** |
| 8 | `TEXT_BLOCK_END` | `:54` | `TextBlockEndEvent` | 正文结束 |
| 9 | `THINKING_BLOCK_START` | `:56` | `ThinkingBlockStartEvent` | 思考折叠块开始 |
| 10 | **`THINKING_BLOCK_DELTA`** | `:57` | `ThinkingBlockDeltaEvent` | **思考逐字（`:59 getDelta()`）** |
| 11 | `THINKING_BLOCK_END` | `:58` | `ThinkingBlockEndEvent` | 思考结束 |
| 12 | `DATA_BLOCK_START` | `:61` | `DataBlockStartEvent` | 二进制/图片块 |
| 13 | `DATA_BLOCK_DELTA` | `:63` | `DataBlockDeltaEvent` | |
| 14 | `DATA_BLOCK_END` | `:65` | `DataBlockEndEvent` | |
| 15 | **`TOOL_CALL_START`** | `:67` | `ToolCallStartEvent` | **工具卡片出现（`:55 getToolCallId()`, `:59 getToolCallName()`）** |
| 16 | **`TOOL_CALL_DELTA`** | `:68` | `ToolCallDeltaEvent` | **工具参数流式拼装（`:71 getDelta()`）** |
| 17 | `TOOL_CALL_END` | `:69` | `ToolCallEndEvent` | 参数收齐 |
| 18 | `TOOL_RESULT_START` | `:71` | `ToolResultStartEvent` | 结果区开始 |
| 19 | **`TOOL_PROGRESS`** | `:72` | `ToolProgressEvent` | **工具执行进度（`:95 getContent()`, `:107 getText()`）** |
| 20 | `TOOL_RESULT_TEXT_DELTA` | `:73` | `ToolResultTextDeltaEvent` | 结果流式文本 |
| 21 | `TOOL_RESULT_DATA_DELTA` | `:75` | `ToolResultDataDeltaEvent` | 结果二进制 |
| 22 | `TOOL_RESULT_END` | `:76` | `ToolResultEndEvent` | **结果收尾（`:85 getState()` → `ToolResultState`）** |
| 23 | `EXCEED_MAX_ITERS` | `:78` | `ExceedMaxItersEvent` | 轮数超限提示 |
| 24 | **`REQUIRE_USER_CONFIRM`** | `:80` | `RequireUserConfirmEvent` | **审批弹窗（`:57 getToolCalls()`）** |
| 25 | **`REQUIRE_EXTERNAL_EXECUTION`** | `:81` | `RequireExternalExecutionEvent` | 外部工具挂起（`:52 getReplyId()`） |
| 26 | **`USER_CONFIRM_RESULT`** | `:82` | `UserConfirmResultEvent` | **审批回执（关联 replyId）** |
| 27 | `EXTERNAL_EXECUTION_RESULT` | `:83` | `ExternalExecutionResultEvent` | 外部结果回填 |
| 28 | `REQUEST_STOP` | `:84` | `RequestStopEvent` | 主动停止（含 PERMISSION_ASKING 场景） |
| 29 | `SUBAGENT_EXPOSED` | `:87` | `SubagentExposedEvent` | 子 agent 暴露 |
| 30 | `HINT_BLOCK` | `:89` | `HintBlockEvent` | 提示块 |
| 31 | `ALL_TOOLS_DENIED` | `:90` | `AllToolsDeniedEvent` | 全被拒 |
| 32 | `CUSTOM` | `:91` | `CustomEvent` | 自定义 |

事件类文件都在 `agentscope-core/src/main/java/io/agentscope/core/event/`（共 36 个 `.java`，其中 32 个是事件 + `AgentEvent` 基类、`AgentEventEmitter`、`ConfirmResult`、`AgentEventType`）。

**旧名兼容**：`RUN_STARTED`/`REPLY_START` → `AGENT_START`，`MODEL_CALL_STARTED` → `MODEL_CALL_START`，`BINARY_BLOCK_*` → `DATA_BLOCK_*` 等（`AgentEventType.java:28-36, 124-136`）。

### 5.2 前端展示「工具调用 / 思考 / 审批」的最小实现

```java
// 直接挂在你自己的 Controller 上，SSE 推 JSON
@GetMapping(value = "/ai/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestParam String q) {
    RuntimeContext ctx = RuntimeContext.builder()
            .userId(String.valueOf(ShiroUtils.getUserId())).sessionId(convId).build();
    return agent.streamEvents(new UserMessage(q), ctx)
            .map(evt -> switch (evt.getType()) {
                case TEXT_BLOCK_DELTA        -> sse("delta",     ((TextBlockDeltaEvent) evt).getDelta());
                case THINKING_BLOCK_DELTA    -> sse("thinking",  ((ThinkingBlockDeltaEvent) evt).getDelta());
                case TOOL_CALL_START         -> sse("tool",      ((ToolCallStartEvent) evt).getToolCallName());
                case TOOL_PROGRESS           -> sse("progress",  ((ToolProgressEvent) evt).getText());
                case REQUIRE_USER_CONFIRM    -> sse("approval",  ((RequireUserConfirmEvent) evt).getToolCalls());
                default                      -> null;
            })
            .filter(Objects::nonNull);
}
```

### 5.3 有没有可复用的 SSE / WebSocket 服务端实现

| 实现 | 传输 | 端点 | 证据 |
|---|---|---|---|
| `AguiRestController` | **Spring MVC + `SseEmitter`** | `POST /agui/run`、`POST /agui/run/{agentId}` | `AguiRestController.java:84-97, 109-120` |
| `AguiMvcController` | `SseEmitter`（10 分钟默认超时，可配） | 同上，供自定义 `@RequestMapping` 复用 | `AguiMvcController.java:69-104, 165` |
| `AguiWebFluxHandler` | WebFlux `ServerResponse` + SSE | `POST /agui/run` | `AgentscopeAguiWebFluxAutoConfiguration.java`、`agui/webflux/AguiWebFluxHandler.java` |
| `ChatCompletionsController` | MVC，`Accept: text/event-stream` 或 `stream=true` | `POST /v1/chat/completions` | `ChatCompletionsController.java:42-76` |
| `agentscope-chat` 示例 | WebFlux SSE + 断线续传（`Last-Event-ID`） | `GET /api/sessions/{id}/stream` | `agentscope-examples/agents/agentscope-chat/.../ChatController.java:102-165` |
| `dataagent` 示例 | WebFlux SSE + 工具事件总线 | `POST /api/agents/{agentId}/chat/stream` | `.../agentscope-dataagent/.../web/api/ChatController.java:156-240` |

**WebSocket**：仓库内的 WebSocket 只出现在 `com.agentscope.core.model.transport.websocket`（**模型侧传输**，不是前端通道）和 `socket.io-client`（`agentscope-dependencies-bom/pom.xml:95`，用途未在 Java 侧找到前端 WS 服务端实现）。→ **要在 RuoYi 里做 WebSocket 聊天，得自己写，没有现成的 `WebSocketHandler`。**

### 5.4 官方前端 `agentscope-service/frontend` 是什么技术栈？能否复用？

**技术栈**（`agentscope-service/frontend/package.json`）：

```
React 18.3 + TypeScript 5.7 + Vite 6
UI : Tailwind CSS 4 + Radix UI (@radix-ui/react-*)
状态: @tanstack/react-query 5
其他: react-router-dom 6、react-markdown 10、lucide-react、prism-react-renderer
测试: vitest 4 + Playwright
规模: src 下 225 个文件，api/ 下 40+ 个 TS 模块（agents.ts / chats.ts / permissions.ts / managedToolApproval.ts …）
```

**结论：不能复用，只能参考。** 原因有三：

1. **构建产物直接打进 Go 二进制**：
   ```ts
   // agentscope-service/frontend/vite.config.ts
   build: { outDir: '../service-controlplane/ui', emptyOutDir: true }
   ```
   且仓库规则明确 "Edit UI source in `agentscope-service/frontend/`; do not hand-edit generated `agentscope-service/service-controlplane/ui/`"（`AGENTS.md`「Code and tests」）。
2. **API 完全对齐 Control Plane**：`api/http.ts` 打的是 `/api/...` 的 Control Plane 资源模型（Application / Endpoint / Invocation / Vault / Workspace…），与 RuoYi 的 `/system/*` 是两套世界。
3. **没有 Thymeleaf 片段形态**：它是 SPA（`react-router-dom`），不是可嵌入的 Web Component。

**真正能嵌 Thymeleaf 的东西在示例里**：

```
agentscope-examples/documentation/src/main/resources/agui/static/index.html      （26,946 B）
agentscope-examples/documentation/src/main/resources/agui/static/js/agui-client.js（10,796 B）
```

`agui-client.js` 是**零依赖原生 JS 的 AG-UI 客户端**，手写 SSE 解析：

```js
// agui-client.js:81-103
async run(input, callbacks = {}) {
    this.abortController = new AbortController();
    const signal = this.abortController.signal;
    const response = await fetch(this.endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
        body: JSON.stringify(input),
        signal: signal
    });
    if (!response.ok) { this.abortController = null; throw new Error(`HTTP error: ${response.status}`); }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    ...
}
```

它的回调里已经带了 `onTextContent` / `onReasoningContent` / `onRunFinished`（`agui-client.js:22-33`）。

→ **RuoYi 落地建议**：把这两个文件丢进 `ruoyi-admin/src/main/resources/static/`，在 Thymeleaf 页面里 `<script src="/js/agui-client.js">`，30 行 JS 就能做一个聊天面板。**这是全仓最省力的前端路径**，比接 React SPA 现实得多。

---

## 6. 会话与状态

### 6.1 三个不同的持久化概念，别混淆

| 概念 | 接口 | 存什么 | 官方实现 |
|---|---|---|---|
| **AgentState**（状态快照） | `AgentStateStore` | 对话上下文、权限规则、Plan Mode、工具状态 | InMemory / JsonFile / MySQL / JDBC / PostgreSQL / Redis / OSS / COS |
| **SessionLog**（执行日志） | `SessionLogStore` | 完整事实流、checkpoint、可重放事件 | **只有 InMemory 和 Workspace（文件系统）** |
| **Memory**（对话记忆） | `Memory` / `LongTermMemory` | 消息列表 / 长期事实 | `InMemoryMemory`(已废弃) / `StateBackedMemory` / `StaticLongTermMemoryHook` |

证据：
- `AgentStateStore` 接口 + slot 语义：`agentscope-core/.../state/AgentStateStore.java:22-61`
- `SessionLogStore` 只有两个实现：`InMemorySessionLogStore`（`.../session/InMemorySessionLogStore.java:25`）与 `WorkspaceSessionLogStore`（`agentscope-harness/.../session/WorkspaceSessionLogStore.java:33`）——全局 `implements SessionLogStore` 检索仅命中这 2 处
- `InMemoryMemory` 已被 `@Deprecated(forRemoval=true)`：`.../memory/InMemoryMemory.java:32-37`；新模型是 `AgentState.getContext()`

这两者的关系由 `SessionHistoryMode` 控制（"Exactly one authority"）：

```java
// SessionHistoryMode.java:18-22
public enum SessionHistoryMode { LEGACY, EVENT_LOG }
```

- `LEGACY` → 用 `AgentStateStore`
- `EVENT_LOG`（**Harness 默认**）→ 用 `SessionLogStore`

证据：`HarnessAgent.java:2889-2895`（不 legacy 时设 `sessionLogStore`，未提供则 `new WorkspaceSessionLogStore(wsManager)`）。

### 6.2 各后端的具体类名

**`AgentStateStore` 实现清单（实测）**

| 后端 | 类 | 证据 |
|---|---|---|
| 内存 | `InMemoryAgentStateStore` | `agentscope-core/.../state/InMemoryAgentStateStore.java` |
| 本地 JSON 文件（**ReActAgent 唯一默认**） | `JsonFileAgentStateStore` | `.../state/JsonFileAgentStateStore.java:44-65` |
| MySQL | `MysqlAgentStateStore` | `agentscope-extensions-mysql/.../mysql/state/MysqlAgentStateStore.java:74` |
| JDBC（通用，MySQL/PG/H2/SQLite 方言） | `JdbcAgentStateStore` | `agentscope-extensions-jdbc/.../jdbc/state/JdbcAgentStateStore.java`；方言 `MysqlDialect` / `PostgresDialect` / `H2Dialect` / `SqliteDialect`（`.../jdbc/dialect/vendor/`） |
| PostgreSQL | `PostgresAgentStateStore` | `agentscope-extensions-postgresql/.../state/PostgresAgentStateStore.java` |
| Redis（Jedis） | `JedisAgentStateStore` | `agentscope-extensions-redis/.../state/jedis/JedisAgentStateStore.java` |
| Redis（Redisson） | `RedissonAgentStateStore` | `.../state/redisson/RedissonAgentStateStore.java` |
| Redis（Lettuce） | `RedisAgentStateStore` + `LettuceClientAdapter` | `.../state/RedisAgentStateStore.java`、`.../state/lettuce/LettuceClientAdapter.java` |
| 阿里云 OSS | `OssAgentStateStore` | `agentscope-extensions-oss/.../oss/OssAgentStateStore.java` |
| 腾讯云 COS | `CosAgentStateStore` | `agentscope-extensions-cos/.../cos/CosAgentStateStore.java` |
| MongoDB | （`agentscope-extensions-mongodb` 下 20 个类，未逐一核实主类名） | 目录 `agentscope-extensions-mongodb/src/main/java/io/agentscope/extensions/mongodb/` |

### 6.3 能否复用 RuoYi 已有的 MySQL / Redis

**MySQL：能，而且表结构自带自动建表。**

```
CREATE TABLE IF NOT EXISTS agentscope_sessions (
    session_id  VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    state_key   VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    item_index  INT NOT NULL DEFAULT 0,
    state_data  LONGTEXT NOT NULL,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

证据：`MysqlAgentStateStore.java:46-58`（javadoc 里就是 DDL），`:76-77`（默认库 `agentscope` / 表 `agentscope_sessions`）。

只依赖 `javax.sql.DataSource`（`MysqlAgentStateStore.java:33, 96`）—— **可以直接把 RuoYi 现成的 Druid `DataSource` 注入进去**，不用新配连接池：

```java
@Bean(destroyMethod = "close")
public AgentStateStore agentStateStore(DataSource dataSource) {   // RuoYi 的 Druid DataSource
    return new MysqlAgentStateStore(dataSource, "ruoyi_ai", "agentscope_sessions");
}
```

⚠️ **关键细节：表里没有 `user_id` 列，用户维度被拼进 `session_id`。**

```java
// MysqlAgentStateStore.java:894-902
/**
 * Combine {@code (userId, sessionId)} into the single {@code session_id} column value.
 */
private static String slotId(String userId, String sessionId) {
    ...
    return normalizeUser(userId) + ":" + sessionId;
}
```

`normalizeUser(null)` → `ANON_USER`（`:889-890`）。所以：
- 好处：**天然按用户隔离**，`listSessionIds(userId)` 靠前缀匹配（`:861-862`）
- 代价：`session_id` 列实际是 `"1001:conv-abc"`；如果你要在 RuoYi 后台页面按业务主键查，得知道这个拼接规则。**建议 RuoYi 侧统一用 `userId + ":" + bizSessionId` 生成 `sessionId` 的语义，或干脆自己维护一张映射表。**

**Redis：能，但代价不小。** 键布局 v1 用 hash tag 保证 Cluster 安全：

```java
// RedisAgentStateKeyLayout.java:24-25, 56-61
 * V1 is the Redis Cluster-safe layout, where versioned state keys use the
 * same segment wrapped in a Redis hash tag: {@code {userId/sessionId}}.
...
static RedisAgentStateKeyLayout v1(String keyPrefix, String userId, String sessionId) {
    validateV1KeyPrefix(keyPrefix);
    validateV1UserId(normalizeUser(userId));
    ...
    keyPrefix, "{" + sessionSegment(userId, sessionId) + "}");
}
```

⚠️ **`agentscope-extensions-redis` 的 pom 里三个客户端全是非 optional 的硬依赖**：

```xml
<!-- agentscope-extensions-redis/pom.xml:46-60 -->
<dependency><groupId>redis.clients</groupId><artifactId>jedis</artifactId></dependency>
<dependency><groupId>org.redisson</groupId><artifactId>redisson</artifactId></dependency>
<dependency><groupId>io.lettuce</groupId><artifactId>lettuce-core</artifactId></dependency>
```

版本被 BOM 钉在 Jedis 7.4.1 / Redisson 4.2.0 / Lettuce 6.4.2（`agentscope-dependencies-bom/pom.xml:99,101,102`）。RuoYi 若已有 Redis 客户端（比如 Lettuce 5.x），**会一次性塞进另外两个大依赖**。要么排除，要么直接自己写一个 `AgentStateStore`（接口只有 ~10 个方法，`AgentStateStore.java:61-231`，不难）。

### 6.4 会话 ID 如何与业务用户绑定

**唯一正确姿势：`RuntimeContext` 的 `(userId, sessionId)` 二元组。**

```java
// SessionKey.java:23
public record SessionKey(String userId, String agentId, String sessionId) {
    public String storagePath() {          // :31-38
        return "agents/" + encode(agentId) + "/sessions/"
             + encode(userId == null ? "" : userId) + "/" + encode(sessionId);
    }
}
```

设计约束写在 `AgentStateStore.java:30-41`：

> - `sessionId` — non-null, non-blank; identifies a conversation / session.
> - `userId` — nullable. `null` represents an anonymous / single-tenant caller… Implementations group all anonymous sessions under a single namespace.
> - **Callers MUST NOT concatenate them manually before calling.**

三个入口：

| 入口 | 怎么绑 | 证据 |
|---|---|---|
| 直接 `call/streamEvents` | `RuntimeContext.builder().userId(...).sessionId(...)` | 官方示例 `agentscope-chat/.../ChatSessions.java:100-102` |
| AG-UI | `AguiRuntimeContextResolver` 从 `HttpServletRequest` 取 | `AguiRuntimeContextResolver.java:33-42` |
| Gateway/Channel | `MsgContext(userId, ...)` + `SendOptions.userId(...)` | `MsgContext.java:39-46`、`channel.md:45-53` |

**RuoYi 推荐做法**：

```java
String userId    = String.valueOf(ShiroUtils.getUserId());   // RuoYi 主键
String sessionId = "ai-" + userId + "-" + bizNo;             // 或前端传来的 conversationId
RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
```

⚠️ 官方原文警告（`docs/v2/zh/docs/quickstart.md:136`）：「`userId` 应来自已认证身份，`sessionId` 应经过访问权限检查。」—— **`sessionId` 必须由服务端生成或校验**，不能直接信任前端传值，否则就是水平越权。示例代码里做了白名单校验：

```java
// agentscope-chat/.../ChatSessions.java:94-98
static String id(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,80}"))
        throw new IllegalArgumentException("Invalid ID");
    return value;
}
```

### 6.5 ⚠️ 最大的落地缺口：SessionLogStore 没有 DB 实现

Harness 默认 `EVENT_LOG` 模式，`SessionLogStore` 只有 `InMemorySessionLogStore` 和 `WorkspaceSessionLogStore`（§6.1）。也就是说：

- 想要「断线重连、按事件游标续传、执行可重放」（`agentscope-chat` 示例演示的能力），**日志会落在本地文件系统**（`agentscope-chat/.../ChatSessions.java:44` 就是 `new WorkspaceSessionLogStore(new LocalFilesystem(workspace))`）。
- 多副本 / 容器化后文件不共享 → 会话历史"丢"。

三条出路：

1. **写自定义 `SessionLogStore`**：接口极简，只有 `open(SessionKey, RuntimeContext)` 一个必需方法 + 可选 `list(RuntimeContext)`（`SessionLogStore.java:21-33`）。落 MySQL 即可。
2. **切 `LEGACY` 模式**：`.sessionHistoryMode(SessionHistoryMode.LEGACY)`（`HarnessAgent.java:1304`）或 `.legacySessionHistory(true)`（`:1318`），改用 `MysqlAgentStateStore`。代价是**丢掉事件级重放能力**，只保留状态快照。
3. **只用 `ReActAgent` + `MysqlAgentStateStore`**：不要 Harness，最简单、最可控，符合 RuoYi 单体场景。

---

## 7. 沙箱与代码执行

### 7.1 支持哪些后端

**没有 "local" 沙箱。** 全局检索 `class LocalSandbox` 只命中 `LocalSandboxSnapshot`（一个快照实现，不是沙箱）。

真正的沙箱实现：

| 后端 | 类 | 模块 | 证据 |
|---|---|---|---|
| **Docker**（本地/远程 Docker daemon） | `DockerSandbox` / `DockerSandboxClient` / `DockerFilesystemSpec` | **`agentscope-harness`**（不是 extensions） | `agentscope-harness/.../sandbox/impl/docker/DockerSandbox.java:74`、`DockerSandboxClient.java` |
| Kubernetes | 6 个类（`client/`、`client/crd/`、`client/model/`…） | `agentscope-extensions-sandbox-kubernetes` | 目录统计 6+6+5+4+8+2+3 个类 |
| 阿里云 AgentRun | `AgentRunSandbox` / `AgentRunSandboxClient` / `AgentRunSandboxClientOptions` / `AgentRunDataPlaneHttp` / `AgentRunMcpChannel` / `AgentRunOssMountConfig` / `AgentRunNasMountConfig` / `AgentRunRetry` | `agentscope-extensions-sandbox-agentrun` | 11 个类 |
| Daytona | 8 个类 | `agentscope-extensions-sandbox-daytona` | 目录 |
| E2B | `E2bSandbox` / `E2bSandboxClient` / `E2bEnvdProcessClient` / `E2bPlatformHttp` / `E2bVolumeMount` / `E2bFilesystemSpec` … | `agentscope-extensions-sandbox-e2b` | 13 个类 |

`agentscope-extensions-sandbox/pom.xml` 只注册 4 个 module：`-kubernetes`、`-agentrun`、`-daytona`、`-e2b`（**Docker 不在其中**，在 harness 里）。

### 7.2 Windows 开发环境可用吗

**Docker 后端：可用，而且代码里显式照顾了 Windows。**

`DockerSandbox` 用 CLI 而不是 docker-java：

```java
// DockerSandbox.java:46-50
/**
 * Docker {@link io.agentscope.harness.agent.sandbox.Sandbox} that runs commands in a container.
 *
 * <p>Uses the {@code docker} CLI via {@link ProcessBuilder}; no docker-java library
 * dependency is required. The Docker daemon must be accessible on the host.
 */
```

→ 只要 `docker` 在 PATH 上（Docker Desktop for Windows 即可），就能跑。

POSIX 权限设置**显式 try/catch 了 Windows**：

```java
// DockerSandbox.java:468-480
/**
 * ... Skipped on filesystems without POSIX permissions (e.g. Windows), which carry no mode to preserve.
 */
private static void applyUploadFileMode(Path temp) throws IOException {
    try {
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-r--r--"));
    } catch (UnsupportedOperationException e) {
        // No POSIX permissions on this filesystem — nothing to align.
    }
}
```

另外核心的 `ShellCommandTool` 有专门的 Windows 分支：`WindowsCommandValidator`（`.../tool/coding/WindowsCommandValidator.java`），且支持 GBK 输出编码（`ShellCommandTool.java:60-63`：「particularly useful when working with systems that use non-UTF-8 encodings (e.g., GBK, GB2312 for Chinese Windows systems)」）。

**K8s / AgentRun / Daytona / E2B**：都要远端服务或集群，Windows 本地开发只能用 Docker 或不用沙箱。

### 7.3 代码执行工具会写入宿主文件系统吗

**会 —— 这正是"没有 local 沙箱"的后果，必须警惕。**

**`LocalFilesystemWithShell`**（harness，`agentscope-harness/.../filesystem/local/LocalFilesystemWithShell.java:39-49`）的原文警告：

```java
/**
 * Filesystem with unrestricted local shell command execution.
 *
 * <p>This implementation extends {@link LocalFilesystem} to add shell command execution
 * capabilities. Commands are executed directly on the host system without any
 * sandboxing, process isolation, or security restrictions.
 *
 * <p><b>WARNING:</b> This implementation grants agents BOTH direct filesystem access AND unrestricted
 * shell execution on your local machine. Use with extreme caution and only in
 * appropriate environments (local dev, CI/CD with proper secret management).
 */
```

超时默认 120s（`:55`），输出截断 100,000 字节（构造函数 `:86`）。

**`ShellCommandTool`**（core）安全默认值：

- 默认构造 `new ShellCommandTool()` **允许任意命令**（`ShellCommandTool.java:65-67`原文警告）
- 推荐用白名单：`new ShellCommandTool(allowedCommands)` 或带审批回调 `new ShellCommandTool(allowedCommands, approvalCallback)`（`:66-67`）
- 默认超时 300s（`:76`）
- 有命令白名单 / 多命令检测 / 平台校验（`:56-58`）

**RuoYi 决策建议**：

| 场景 | 配置 |
|---|---|
| 只要"对话式操作入口"（查数据、改配置） | **`.disableShellTool()` + `.disableFilesystemTools()`**（`HarnessAgent.java:2361` / `:2355`），或用 `ReActAgent`（本身不带这些工具） |
| 确实要跑代码 | `.filesystem(new DockerFilesystemSpec().image("ubuntu:24.04"))`（`docs/v2/zh/docs/harness/sandbox.md:26-28`） |
| 完全不隔离但要限权 | `PermissionMode.EXPLORE` 或 `DONT_ASK` + 只 allow 白名单工具 |

**沙箱快照存哪**（决定多副本可恢复性）：`NoopSnapshotSpec`（默认，不持久化）/ `LocalSnapshotSpec` / `OssSnapshotSpec` / `RedisSnapshotSpec` / `JdbcSnapshotSpec`（`docs/v2/zh/docs/harness/sandbox.md:69-83`）。RuoYi 有 MySQL，`JdbcSnapshotSpec` 最自然。

---

## 8. 协议与集成：A2A / AG-UI / Channel

### 8.1 A2A —— **可直接用，且是最完整的对外协议**

| 组件 | 状态 | 证据 |
|---|---|---|
| 客户端（把远端 A2A agent 当本地 agent） | 完整 | `agentscope-extensions-a2a-client/.../a2a/agent/A2aAgent.java` + 30 个消息解析器（`message/` 下 22 个）+ 事件处理（`event/` 下 6 个） |
| 服务端（把本地 agent 暴露成 A2A） | 完整 | `agentscope-extensions-a2a-server/.../server/AgentScopeA2aServer.java`、`AgentScopeAgentExecutor.java`、`AgentCardController` |
| 传输 | JSON-RPC（web） | `.../transport/jsonrpc/JsonRpcTransportWrapper*.java`、`JSONRPCProperties` |
| Spring Boot starter | 有 | `agentscope-a2a-spring-boot-starter`，`@ConditionalOnWebApplication`（`AgentscopeA2aAutoConfiguration.java:72-85`） |
| SDK | `a2a-java-sdk 1.4.0.Final` | `agentscope-dependencies-bom/pom.xml:100` |

⚠️ 限制（原文）："Only support export web type transport for now."（`AgentscopeA2aAutoConfiguration.java:69`）。

**对 RuoYi 的意义**：如果未来要把 Agent 拆成独立服务、供其他系统调用，A2A 是现成答案。单体嵌入阶段用不上。

### 8.2 AG-UI —— **可直接用，且是 HITL 场景的最佳选择**

已详述于 §4.3 / §5.3。补充状态：

| 能力 | 状态 | 证据 |
|---|---|---|
| 协议事件（RUN_STARTED / TEXT_MESSAGE_* / TOOL_CALL_* / STATE_* / RUN_ERROR / MESSAGES_SNAPSHOT …） | 完整 | `AguiEventType.java:21-95` |
| `AgentEvent` → `AguiEvent` 转换器 | 完整，14 个（Lifecycle / Text / Thinking / ToolCall / ToolResult / PermissionConfirm / ExternalExecution / Subagent / Custom / Raw / ModelCallUsage） | `adapter/strategy/` 目录 |
| MVC 传输（servlet，`SseEmitter`） | 可用 | `AguiRestController.java` |
| WebFlux 传输 | 可用 | `AguiWebFluxHandler.java` |
| 中断/恢复契约 | 完整（含校验） | `AguiResumeCoordinator.java`、`AguiResume.java` |
| 服务端内存（按 threadId 保 agent 实例） | 有，默认关 | `AguiProperties.java:93-109`（`serverSideMemory` 默认 false） |

⚠️ **`AguiMvcController` 用一个无界 `Executors.newCachedThreadPool()`**：

```java
// AguiMvcController.java:103
this.executorService = Executors.newCachedThreadPool();
```

高并发下线程数不受控。RuoYi 里建议**不用 `aguiRestController` bean，自己写 Controller 直接调 `agent.streamEvents()`**，用 Tomcat 的 MVC 线程 + `SseEmitter`，或显式传自己的线程池（`AguiMvcController.Builder` 没有暴露 executor 注入点，只能改源码/自己接管）。

### 8.3 Channel（钉钉 / 飞书 / 企微）—— **实现完整，可直接用**

| Channel | 类数 | 关键类 | 状态 |
|---|---|---|---|
| **钉钉** | 9 | `DingTalkChannel`、`DingTalkCallbackController`、`DingTalkStreamClient`（Stream 模式）、`DingTalkCallbackCrypto`、`DingTalkOutboundClient`、`DingTalkAccessTokenProvider` | 完整（回调 + 出站 + 加解密 + token 缓存） |
| **飞书** | 8 | `FeishuChannel`、`FeishuCallbackController`、`FeishuCrypto`、`FeishuOutboundClient`、`FeishuAccessTokenProvider` | 完整 |
| **企微** | 8 | `WeComChannel`、`WeComCallbackController`、`WeComCrypto`、`WeComOutboundClient` | 完整 |
| 微信公众号 | 20 | `WeixinChannel`、`WeixinLoginClient`、`WeixinInboxClaim`、`WeixinLease`、`WeixinRuntimeListener` | 完整（含登录/租约/幂等） |
| GitHub | 8 | `GitHubChannel`、`GitHubWebhookController`、`GitHubSignatureVerifier` | 完整 |
| GitLab | 7 | `GitLabChannel`、`GitLabWebhookController` | 完整 |
| ChatUI（内嵌网页聊天） | 4 | `ChatUiChannel`、`ChatUiRequest`、`OutboundEnvelope`、`SendOptions` | 完整 |
| 公共基础设施 | 7 | `AccessTokenStore`、`InMemoryAccessTokenStore`、`IdempotencyStore`、`InboundEventDeduplicator`、`BotLoopGuard`、`ChannelMediaMetadata` | 完整 |

用起来很简单（`docs/v2/zh/docs/harness/channel.md:28-39`）：

```java
HarnessAgent agent = HarnessAgent.builder()
        .name("assistant").sysPrompt("你是一个有用的助手。").model("dashscope:qwen-plus").build();

ChatUiChannel chat = agent.channel(ChatUiChannel.create());
Msg reply     = chat.send(SendOptions.userId("user-1"), "你好！").block();
Msg followUp  = chat.send(SendOptions.userId("user-1"), "再多说一些。").block();  // 同会话
Msg otherUser = chat.send(SendOptions.userId("user-2"), "你好").block();          // 新会话
```

**对 RuoYi 的意义**：`ChatUiChannel` 是"业务系统内嵌对话入口"的官方抽象 —— 每个 `userId` 自动一个 session、per-session 并发排队（`Gateway` javadoc 要求 serialize turns per logical session，`Gateway.java:32-33`）。但注意它是**进程内 API，不是 HTTP**；要暴露给浏览器仍需自己写 Controller。

### 8.4 `agentscope-extensions-agent-protocol`（ASDP）

用于 AgentScope **Service 平台**的 external-application 接入（`agentscope-service/README.md:39-43` 的 `external-application` 执行模型），与 RuoYi 单体集成无关。**未深入审计**。

---

## 9. 风险点

### 9.1 Spring Boot 4.1 / JDK 17 兼容性

| 项 | AgentScope | RuoYi 4.8.3 | 判断 |
|---|---|---|---|
| JDK | 17（`pom.xml:33`） | 17（`RuoYi/pom.xml:18`） | ✅ 完全一致 |
| Spring Boot | 4.0.3（starter 父）/ 4.0.4（BOM） | **4.1.0**（`RuoYi/pom.xml:20`） | ⚠️ 同代小版本差 |
| Spring Framework | 7.0.7（BOM `:106`） | 4.1.0 对应 7.1.x | ⚠️ 需要让 RuoYi 的版本赢 |
| Servlet | `jakarta.servlet`（`AguiMvcController.java:30`） | Boot 4 = Jakarta EE 11 | ✅ |
| 打包 | `spring-boot-autoconfigure` / `spring-boot-configuration-processor` | war（`ruoyi-admin/pom.xml:76-80` maven-war-plugin） | ✅ 都能用 |

**Boot 版本落地的关键操作**：Maven `dependencyManagement` **先声明者赢**。RuoYi 的 `spring-boot-dependencies` 在 `RuoYi/pom.xml:41-47`。所以**导入 `agentscope-bom` 必须放在它之后**，否则 AgentScope 的 `spring-boot-dependencies:4.0.4` 会把整个 Spring 栈降级。

```xml
<!-- RuoYi/pom.xml dependencyManagement 中，顺序很关键 -->
<dependency>                                  <!-- ① 先 Boot 4.1.0 -->
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-dependencies</artifactId>
  <version>${spring-boot.version}</version>
  <type>pom</type><scope>import</scope>
</dependency>
<dependency>                                  <!-- ② 再 AgentScope -->
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-bom</artifactId>
  <version>2.1.0-SNAPSHOT</version>
  <type>pom</type><scope>import</scope>
</dependency>
```

### 9.2 Jackson 版本冲突：**这是真问题**

- AgentScope 全线用 **Jackson 2**（`com.fasterxml.jackson.*`），BOM 钉 **2.21.1**（`agentscope-dependencies-bom/pom.xml:73`）
- `agentscope-core` 硬依赖 `jackson-databind` / `jackson-annotations` / `jackson-datatype-jsr310` / `jackson-dataformat-yaml`（`agentscope-core/pom.xml:59-85, 159-162`）
- **Spring Boot 4 转用 Jackson 3（`tools.jackson`）**

仓库作者是**知情**的，代码里三处注释直接说明：

```java
// AgentscopeAguiMvcAutoConfiguration.java:74-76
 * <p>The parser decodes request bodies with AgentScope's Jackson 2 codec so that Spring Boot
 * 4's Jackson 3 converters do not touch AG-UI's Jackson 2-annotated models. Applications can
 * override this bean to customize request body parsing.
```

```java
// AguiRequestBodyParser.java:26
 * avoids selecting Spring Boot 4's Jackson 3 codec for AG-UI's Jackson 2-annotated models, while
```

```xml
<!-- agentscope-dependencies-bom/pom.xml:252-253 -->
<!-- Model Context Protocol (MCP) SDK: use core + Jackson 2 module
     instead of the mcp aggregator (which defaults to Jackson 3) -->
```

**结论**：AgentScope 的 JSON 处理是**自包含**的（`JsonUtils.getJsonCodec()`，见 `DefaultToolResultConverter.java:90`），不依赖 Spring 的 `ObjectMapper`。所以**两者能共存**，但要记住：

- ❌ 不要指望在 `@RestController` 方法参数里直接吃 AgentScope 的模型（`Msg`、`ToolUseBlock`、`AgentEvent`）—— Spring Boot 4 会用 Jackson 3 的转换器，那些类的 Jackson 2 注解不生效。
- ✅ 正确做法：像官方一样，`@RequestBody String body` + 自己用 `JsonUtils` 解析；出参用 `String`/`Map` 或自己序列化。`AguiRestController.java:88-96` 就是这么做的。

### 9.3 与 Shiro 共存

**没有冲突。** 依据：

1. **零 Spring Security 依赖** —— 全仓 `org.springframework.security` 只出现在 examples（§4.4）
2. **不过滤请求链** —— AgentScope 只注册 `@RestController` / `@Endpoint`，没有自己的 `Filter`（`META-INF/spring/...AutoConfiguration.imports` 全部 14 份已逐一读过，无 Filter/Security 注册）
3. **Shiro 的 `/agui/**` 放行**：RuoYi 的 `ShiroConfig` 需要把 `/agui/**`、`/ai/**` 纳入 `filterChainDefinitionMap`，这属于常规配置

⚠️ **唯一需要注意**：AG-UI 的 `AguiProperties.corsEnabled` 默认 `true` + `corsAllowedOrigins = ["*"]`（`AguiProperties.java:52-56`）。在 RuoYi 里如果开 AG-UI，**必须覆盖成确切域名**，否则等于给所有源开了跨域。

⚠️ **孤儿会话风险**：AgentScope 的身份完全来自你传的 `RuntimeContext`。Shiro 退出登录后，若前端还拿着旧 `sessionId` 打 SSE，仍然能读到历史——**RuoYi 必须在 Controller 层每次校验 `ShiroUtils.getUserId()` 与 `sessionId` 的归属**（SessionKey 里虽含 userId，但查询时是你自己传参，框架不校验）。

### 9.4 包体积

**没有找到已构建的 jar，无法给出精确 MB 数。** 但可以从 POM 精确推断增量：

| 依赖 | 是否可选 | 说明 | 证据 |
|---|---|---|---|
| `reactor-core` | 否 | RuoYi 现在没有 | `agentscope-core/pom.xml:53-56` |
| `jackson-databind` / `-annotations` / `-datatype-jsr310` | 否 | RuoYi 已有 Jackson 2（Boot 4 默认给 Jackson 3，需确认是否两份共存） | `:59-85` |
| `jsonschema-generator` + `jsonschema-module-jackson` (victools) | 否 | 工具 schema 生成 | `:70-79` |
| `mcp-core` + `mcp-json-jackson2` | 否 | **MCP SDK，即使不用也拉进来** | `:94-101` |
| `okhttp` + `okhttp-jvm` **5.3.2** | 否 | OkHttp 5 是 Kotlin 实现 | `:104-111`；版本 `agentscope-dependencies-bom/pom.xml:71` |
| `json-schema-validator` (networknt) | 否 | 工具入参校验 | `:114-117` |
| `snakeyaml` | 否 | | `:119-122` |
| **`tree-sitter` + `tree-sitter-bash`** | 否 | **含各平台原生二进制** | `:127-134` |
| `opentelemetry-api` + `opentelemetry-reactor-3.1` | 否 | | `:136-144` |
| **`sqlite-jdbc 3.47.1.0`** | **否（非 optional！）** | **~13MB 级，含所有平台 native** | `:171-176`；harness 也重复声明 `agentscope-harness/pom.xml:68-73` |
| `commons-compress 1.27.1` | 否 | **硬编码版本，会覆盖 Boot 托管版本** | `:165-169` |
| `jedis` / `aliyun-sdk-oss` / `k8s-client` / `protobuf-java` | 是（optional） | 未用不拉 | `:179-203` |

**最容易踩的坑：`sqlite-jdbc` 不是 optional。** 它是给"本地 workspace 索引"用的（`:171`注释），但 RuoYi 用 MySQL，这个 13MB 级依赖纯属浪费。**建议加 `<exclusions>` 排除**（需实测是否触发 `NoClassDefFoundError`——只在 workspace index 路径用到，`ReActAgent` 单用应不受影响；**未验证，需编译后确认**）。

### 9.5 启动开销

| 因素 | 评估 | 证据 |
|---|---|---|
| `ReActAgent` 构建 | 轻（builder + 反射扫工具方法） | `ReActAgent.java:5886` |
| `HarnessAgent` 构建 | **重**：工作区扫描、技能装配、记忆 hook、压缩器、子 agent 装配 | `HarnessAgent.java:2863-2910`（middleware 装配段） |
| 官方建议 | 「每次请求用 `builder.build()` 创建新实例，执行完成后关闭」 | `docs/v2/zh/docs/quickstart.md:47`、`:11` |

⚠️ 这与 RuoYi 传统「单例 Bean 常驻」的心智模型不同。RuoYi 侧建议：
- **共享 `Builder`**（static 或 singleton bean），请求内 `build()` → `try-with-resources` / `Mono.using` 关闭
- 或者**用 `ReActAgent` 单例**（注释说 2.0 的 ReActAgent 是 thread-safe，`AgentscopeAutoConfiguration.java:100-101`：「ReActAgent in 2.0 is thread-safe, so we just use a singleton instance.」）

### 9.6 线程模型：**不用虚拟线程，用 Reactor**

- 全仓检索 `Thread.ofVirtual` / `Executors.newVirtualThreadPerTask` → **0 命中**（`agentscope-core`、`agentscope-harness`、全部 extensions 均无）
- 核心模型是 **Reactor**（`Flux` / `Mono`），阻塞 I/O 卸载到 `Schedulers.boundedElastic()`：
  ```java
  // ToolkitConfig.java:191-193
   * Set a custom executor service for tool execution.
   * If not set, Reactor's Schedulers.boundedElastic() will be used.
  ```
- 工具侧自建线程池（非虚拟线程）：
  ```java
  // ShellCommandTool.java:83-98
  private static final ExecutorService STREAM_READER_POOL =
          Executors.newCachedThreadPool(new ThreadFactory() { ... t.setDaemon(true); ... });
  ```
  ```java
  // AguiMvcController.java:103
  this.executorService = Executors.newCachedThreadPool();
  ```
- **不是 WebFlux-only**：AG-UI 有 `AgentscopeAguiMvcAutoConfiguration`，条件 `@ConditionalOnWebApplication(type = SERVLET)`（`:54`），用 `SseEmitter`。所以 **RuoYi 的 MVC + Tomcat 栈可以原样承载**。

⚠️ **`Schedulers.boundedElastic()` 的默认上限是 `10 × CPU 核数`**（Reactor 默认值，非本项目定义）。RuoYi 若并发高，建议 `ToolkitConfig.executorService(...)` 显式给一个受控线程池，避免与 Tomcat 线程池争夺。

⚠️ **不要在 MVC 的 `@RequestMapping` 里 `.block()`** —— 会占死 Tomcat 线程。用 `Flux` 返回值（Spring MVC 支持 `Flux`/`Flux<ServerSentEvent>` 的异步流式返回）或 `SseEmitter`。

### 9.7 其他已发现的具体风险

| # | 风险 | 证据 | 缓解 |
|---|---|---|---|
| 1 | `agentscope-admin-spring-boot-starter` 硬依赖 **springdoc 2.6.0**，RuoYi 用 **3.1.0** | `agentscope-admin-spring-boot-starter/pom.xml`（`springdoc-openapi-starter-webmvc-ui` + `<version>2.6.0`）；`RuoYi/pom.xml:33`（`<springdoc.version>3.1.0`） | 别引 admin starter；或用 `<exclusions>` |
| 2 | BOM 把 **mysql-connector-j 钉到 9.6.0**，RuoYi 是 Boot 4.1.0 托管版本（未显式写版本，`ruoyi-admin/pom.xml:32-36`） | `agentscope-dependencies-bom/pom.xml:96, 367-372` | 在 RuoYi 里显式写 `<version>`，别被 BOM 带走 |
| 3 | `commons-compress` 硬版本 **1.27.1**（不走 Boot 托管） | `agentscope-core/pom.xml:165-169`、`agentscope-harness/pom.xml:61-66` | 检查与 Boot 4.1 托管的 commons-compress 是否冲突 |
| 4 | `agentscope-agui-spring-boot-starter` 拉 **spring-boot-starter-webflux** | `agentscope-agui-spring-boot-starter/pom.xml` | RuoYi 是 MVC 应用；同时存在时 Boot 仍选 SERVLET（因 `WebApplicationType` 判定），但会多带 reactor-netty，**建议 `<exclusions>` 掉 webflux** |
| 5 | `agentscope-extensions-redis` 强拉 jedis + redisson + lettuce 三个客户端 | `agentscope-extensions-redis/pom.xml:46-60` | 排除不需要的两个，或自写 store |
| 6 | `AguiResumeCoordinator` 的 pending interrupt 是**进程内 Map** | `AguiResumeCoordinator.java:46-48` | 单机无碍；集群需自建共享态 |
| 7 | `AguiMvcController` 用**无界 cachedThreadPool** | `AguiMvcController.java:103` | 自己写 Controller |
| 8 | 权限规则**无法通过 REST 热改** | `AgentscopePermissionsEndpoint.java:36-37` | 只支持 `setPermissionMode` |
| 9 | 无 local 沙箱，`LocalFilesystemWithShell` 无隔离 | `LocalFilesystemWithShell.java:39-49` | 生产禁用 shell/filesystem 工具 |
| 10 | `DashScopeAutoConfiguration` 缺 key 直接启动失败 | `DashScopeAutoConfiguration.java:48-53` | 别用 starter，自己 `@Bean` + `@ConditionalOnProperty` |
| 11 | `agentscope-extensions-model-e2e-tests` 等测试模块在 modules 里 | `agentscope-extensions/pom.xml` | 只依赖具体 artifact，别依赖聚合 POM |

---

## 10. 可直接复用的示例

### 10.1 全景（按"离目标有多近"排序）

| 排名 | 示例 | 路径 | 为什么相关 |
|---|---|---|---|
| 🥇 | **`agentscope-dataagent`**（web 层） | `agentscope-examples/agents/agentscope-dataagent/` | **完整的"给业务系统加对话入口"**：JWT 鉴权、per-user SSE、工具事件总线、会话管理、ACL、JPA 持久化、前端 React SPA。规模最大、最像产品 |
| 🥈 | **`agentscope-chat`** | `agentscope-examples/agents/agentscope-chat/` | **最小可运行**：5 个类，HarnessAgent + SSE + 持久事件 + 断线续传。最适合当 RuoYi 的骨架抄 |
| 🥉 | **AG-UI 静态页示例** | `agentscope-examples/documentation/src/main/resources/agui/static/{index.html, js/agui-client.js}` | **可直接塞进 Thymeleaf `static/`** 的零依赖前端 |
| 4 | `agentscope-paw`（claw2） | `agentscope-examples/agents/agentscope-paw/` | 另一套业务系统 web（marketplace + session），可参考 workspace/binding 设计 |
| 5 | `agentscope-codingagent` | `agentscope-examples/agents/agentscope-codingagent/` | 侧重 coding/review，与业务系统关系较远 |
| 6 | `documentation2/quickstart` / `tool` / `hitl` / `agui` | `agentscope-examples/documentation/src/main/java/io/agentscope/examples/documentation2/` | **17 个专题的最小片段**，抄代码最快 |

`documentation2` 下的专题目录（实测）：`agui`、`context`、`copilotkit`、`harness`、`hitl`、`judge`、`mcp`、`middleware`、`model`、`multimodal`、`quickstart`、`rag`、`skill`、`state`、`streaming`、`structuredoutput`、`tool`。

### 10.2 示例 A：`agentscope-chat` —— 最小可运行骨架（**强烈推荐抄这个**）

**依赖只有 4 个**（`agentscope-examples/agents/agentscope-chat/pom.xml:16-21`）：

```xml
<dependency><groupId>io.agentscope</groupId><artifactId>agentscope-harness</artifactId></dependency>
<dependency><groupId>io.agentscope</groupId><artifactId>agentscope-extensions-model-dashscope</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webflux</artifactId></dependency>
```

**核心：把所有不需要的 Harness 能力显式关掉**（`ChatSessions.java:62-88`）—— 这段是 RuoYi 减负的核心参考：

```java
// agentscope-examples/agents/agentscope-chat/src/main/java/io/agentscope/examples/chat/ChatSessions.java:62-88
agent =
        HarnessAgent.builder()
                .name("Session Chat")
                .agentId(AGENT)
                .model(model)
                .sysPrompt(
                        "You are a concise chat assistant. Use ask_user when a needed"
                                + " preference is missing.")
                .workspace(workspace.toAbsolutePath())
                .sessionLogStore(store)
                .toolkit(toolkit)
                .disableShellTool()
                .disableFilesystemTools()
                .disableWebTools()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableToolsConfig()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .maxIters(8)
                .build();
```

**工具定义 + 进度上报**（`DemoTools.java:34-48`）：

```java
// agentscope-examples/agents/agentscope-chat/src/main/java/io/agentscope/examples/chat/DemoTools.java:34-48
@Tool(
        name = "demo_lookup",
        description = "Look up a local demo topic with progress updates",
        readOnly = true)
public Mono<ToolResultBlock> lookup(
        @ToolParam(name = "topic", description = "Topic to look up") String topic,
        ToolEmitter emitter) {
    return Flux.range(1, 4)
            .delayElements(stepDelay)
            .doOnNext(
                    step ->
                            emitter.emit(
                                    ToolResultBlock.text(topic + "：已读取 " + step + "/4 段资料\n")))
            .then(Mono.fromSupplier(() -> ToolResultBlock.text(topic + "：资料已就绪，支持按持久事件恢复。")));
}
```

**会话绑定 + 任务提交**（`ChatSessions.java:100-137`）：

```java
// :100-102
private RuntimeContext context(String session) {
    return RuntimeContext.builder().userId(USER).sessionId(id(session)).build();
}

// :108-110
AgentSession session(String id) {
    return agent.session(context(id));
}

// :125-137
public AgentSession.Task submit(String session, String requestId, String text) {
    validateText(text);
    return session(session).submit(id(requestId), text);
}
public AgentSession.Task resume(String session, String turn) {
    return session(session).resume(id(turn));
}
public AgentSession.Task answer(String session, String request, String output) {
    validateText(output);
    return session(session).respond(id(request), output);
}
```

**SSE 断线续传（`Last-Event-ID` + 心跳）**（`ChatController.java:102-165`，节选自 `:107-121` 与 `:140-158`）：

```java
// agentscope-examples/agents/agentscope-chat/src/main/java/io/agentscope/examples/chat/ChatController.java:102-165
@GetMapping(value = "/sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream(
        @PathVariable String id,
        @RequestParam(required = false) String after,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    return Mono.fromCallable(() -> {
                    long position = Math.max(ChatHistory.position(id, after),
                                             ChatHistory.position(id, lastEventId));
                    sessions.events(id, position, 1);   // 先校验再发响应头
                    return new AtomicLong(position);
                })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(cursor -> {
                Flux<ServerSentEvent<String>> committed = Flux
                        .interval(Duration.ZERO, Duration.ofMillis(300))
                        .onBackpressureDrop()
                        .concatMap(tick -> Mono.fromCallable(() -> sessions.events(id, cursor.get(), 128))
                                              .subscribeOn(Schedulers.boundedElastic()), 1)
                        .flatMapIterable(batch -> batch)
                        .map(event -> {
                            cursor.set(event.seq());
                            return ServerSentEvent.<String>builder(
                                    JsonUtils.getJsonCodec().toJson(ChatHistory.frame(event)))
                                    .event("committed")
                                    .id(ChatHistory.cursor(id, event.seq()))
                                    .build();
                        });
                Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                        .map(t -> ServerSentEvent.<String>builder().comment("keepalive").build());
                return Flux.merge(committed, heartbeat)
                        .startWith(ServerSentEvent.<String>builder().comment("connected").build());
            });
}
```

### 10.3 示例 B：`agentscope-dataagent` —— 业务系统集成全景（**最像目标场景**）

关键文件：

```
agentscope-examples/agents/agentscope-dataagent/src/main/java/io/agentscope/dataagent/
  web/api/ChatController.java          ← per-user SSE 聊天入口 + 工具事件总线
  web/config/SecurityConfig.java       ← JWT 鉴权（RuoYi 换成 Shiro）
  web/auth/{JwtService,UserStore,AuthController}.java
  web/api/AgentBindingController.java  ← 用户与 agent 的绑定（RuoYi 的"哪个用户用哪个 agent"）
  web/persistence/jpa/*.java           ← JPA 持久化（RuoYi 换 MyBatis）
  web/toolbus/{ToolEventBus,ToolNotificationMiddleware}.java  ← **工具调用事件总线**
  web/share/{AgentAclService,AgentAccessGuard}.java           ← agent 级 ACL
  web/session/{SessionLifecycleScheduler,SessionTurnParser,SessionReadStateStore}.java
  runtime/channel/webhook/*.java
  tools/data/*.java                    ← 数据类工具（图表、数据源注册）
```

**per-user SSE 聊天端点**（`ChatController.java:156-240`，节选 `:156-183`）：

```java
// agentscope-examples/agents/agentscope-dataagent/.../web/api/ChatController.java:156-183
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream(
        @PathVariable String agentId, @RequestBody ChatRequest req, Authentication auth) {
    String userId = (String) auth.getPrincipal();          // ← RuoYi 换成 ShiroUtils.getLoginUser()
    AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
    // If the caller did not pin a conversation, mint one server-side so the gateKey stays
    // stable across turns and the FE can persist the URL session.
    String conversationId = normalizedConversationId(req.sessionKey());
    if (conversationId == null) {
        conversationId = UUID.randomUUID().toString();
    }
    final String resolvedConversationId = conversationId;
    recordRunSession(def, agentId, userId, resolvedConversationId);
    ...
}
```

注意这段的价值：**`conversationId` 由服务端兜底生成**（前端不传就 UUID），并且返回给前端的是 `conversationId` 而不是内部 `sessionKey`：

```java
// ChatController.java:212-215
// Always echo the conversationId, NOT the storage key —
// otherwise the FE would persist the storage key and use it
// as the next turn's conversationId, splintering the session.
doneFrame.put("sessionKey", resolvedConversationId);
```

**这是一个很实用的安全/正确性教训**：内部存储键（含 `userId:` 前缀）绝不能泄漏给前端。

### 10.4 示例 C：HITL 审批（`PermissionHITLExample`）

`agentscope-examples/documentation/src/main/java/io/agentscope/examples/documentation2/hitl/PermissionHITLExample.java:96-124`：

```java
// :96-124
PermissionContextState permCtx =
        PermissionContextState.builder()
                .mode(mode)                                            // DEFAULT / DONT_ASK
                .addAllowRule("safe_read",
                        new PermissionRule("safe_read", null, PermissionBehavior.ALLOW, "policy"))
                .addAskRule("dangerous_delete",
                        new PermissionRule("dangerous_delete", null, PermissionBehavior.ASK, "policy"))
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("GuardedAgent")
                .sysPrompt("You are a file assistant. You have safe_read and dangerous_delete tools.")
                .model(DashScopeChatModel.builder()
                        .apiKey(apiKey).modelName("qwen-max").stream(true)
                        .formatter(new DashScopeChatFormatter())
                        .build())
                .toolkit(toolkit)
                .permissionContext(permCtx)
                .build();
```

恢复逻辑（`:166-193`）：

```java
// :166-193
List<ToolUseBlock> askingTools =
        result.getContent().stream()
                .filter(b -> b instanceof ToolUseBlock)
                .map(ToolUseBlock.class::cast)
                .filter(t -> t.getState() == ToolCallState.ASKING)
                .toList();
askingTools.forEach(t -> System.out.println("  - " + t.getName() + " " + t.getInput()));

boolean approved = ...;
List<ConfirmResult> confirmResults =
        askingTools.stream().map(t -> new ConfirmResult(approved, t)).toList();
Map<String, Object> meta = new HashMap<>();
meta.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
Msg resumeMsg =
        Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(approved ? "approved" : "denied")
                .metadata(meta)
                .build();
Msg finalResult = agent.call(List.of(resumeMsg)).block();
```

### 10.5 示例 D：quickstart 的 Web 用法（`docs/v2/zh/docs/quickstart.md`）

```java
// docs/v2/zh/docs/quickstart.md:62-85 —— 共享 Builder + 每请求 build
private static final HarnessAgent.Builder AGENT_BUILDER = HarnessAgent.builder()
        .name("note-taker")
        .agentId("note-taker")
        .sysPrompt("你是一个帮助用户做笔记的助手。")
        .model("dashscope:qwen-plus")
        .workspace(Path.of(".agentscope/workspace"));

try (HarnessAgent agent = AGENT_BUILDER.build()) {
    RuntimeContext ctx = RuntimeContext.builder()
            .userId("alice").sessionId("demo-session").build();
    agent.call(new UserMessage("我叫天宇，今天准备一个技术分享。"), ctx).block();
}
```

```java
// docs/v2/zh/docs/quickstart.md:102-114 —— 流式看推理与工具调用
try (HarnessAgent agent = AGENT_BUILDER.build()) {
    RuntimeContext ctx = RuntimeContext.builder()
            .userId("alice").sessionId("demo-session").build();
    agent.streamEvents(new UserMessage("帮我把分享的准备工作列成三条。"), ctx)
            .doOnNext(event -> {
                if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                    System.out.print(((TextBlockDeltaEvent) event).getDelta());
                } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                    System.out.println("\n[tool] " + ((ToolCallStartEvent) event).getToolCallName());
                }
            })
            .blockLast();
}
```

---

## 11. 接入 RuoYi 所需依赖与配置清单

### 11.1 依赖清单（推荐：ReAct 路线，最轻）

```xml
<!-- ========== RuoYi/pom.xml（父）========== -->
<properties>
    <agentscope.version>2.1.0-SNAPSHOT</agentscope.version>
</properties>

<dependencyManagement>
  <dependencies>
    <!-- ① 必须放前面：让 Boot 4.1.0 赢 -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>${spring-boot.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
    <!-- ② AgentScope 自家 artifact 版本 -->
    <dependency>
      <groupId>io.agentscope</groupId>
      <artifactId>agentscope-bom</artifactId>
      <version>${agentscope.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

```xml
<!-- ========== 新建 ruoyi-ai 模块，或加在 ruoyi-framework/pom.xml ========== -->

<!-- 必需：Agent 内核 -->
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-core</artifactId>
  <exclusions>
    <!-- 强烈建议排除：RuoYi 用 MySQL，不需要 SQLite（约 13MB） -->
    <exclusion>
      <groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId>
    </exclusion>
  </exclusions>
</dependency>

<!-- 必需：模型适配（三选一或全要） -->
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-model-openai</artifactId>   <!-- 自建/兼容端点首选 -->
</dependency>
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-model-dashscope</artifactId> <!-- 通义千问 -->
</dependency>

<!-- 可选：会话状态落 MySQL -->
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-mysql</artifactId>
</dependency>

<!-- 可选：会话状态落 Redis -->
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-redis</artifactId>
  <exclusions>
    <exclusion><groupId>org.redisson</groupId><artifactId>redisson</artifactId></exclusion>
    <exclusion><groupId>io.lettuce</groupId><artifactId>lettuce-core</artifactId></exclusion>
    <!-- 保留 jedis；或反过来按你已有的客户端保留 -->
  </exclusions>
</dependency>

<!-- 可选：Harness（workspace/记忆/子agent）。不需要就别引，省启动开销 -->
<!--
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-harness</artifactId>
</dependency>
-->

<!-- 可选：AG-UI 协议（想用官方的 HITL/Interrupt 契约时） -->
<!--
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-agui-spring-boot-starter</artifactId>
  <exclusions>
    <exclusion><groupId>org.springframework.boot</groupId>
               <artifactId>spring-boot-starter-webflux</artifactId></exclusion>
  </exclusions>
</dependency>
-->

<!-- ❌ 不要引的 -->
<!-- agentscope-admin-spring-boot-starter  → springdoc 2.6.0 与 RuoYi 3.1.0 冲突 -->
<!-- agentscope-spring-boot-starter        → 默认 ReActAgent 无 stateStore，且 DashScope 缺 key 会启动失败 -->
<!-- agentscope-chat-completions-web-starter → 无状态，不支持 HITL -->
<!-- agentscope-extensions-model-e2e-tests → 测试模块 -->
```

### 11.2 最小 Java 装配代码

```java
@Configuration
@ConditionalOnProperty(prefix = "ruoyi.ai", name = "enabled", havingValue = "true")
public class RuoYiAiConfig {

    /** 共享 Builder，请求内 build() / close()（官方推荐，docs/quickstart.md:47） */
    @Bean
    public ReActAgent.Builder ruoyiAgentBuilder(RuoYiAiProperties props,
                                                AgentStateStore stateStore) {
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
                .parallel(true)
                .executionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(props.getToolTimeout()))
                        .maxAttempts(1)
                        .build())
                .interceptor(new RuoYiToolGuard())     // Shiro 数据权限
                .build());
        toolkit.registerTool(new SystemQueryTools());  // 你的业务工具
        toolkit.registerTool(new DictTools());

        return ReActAgent.builder()
                .name("ruoyi-assistant")
                .agentId("ruoyi-assistant")
                .sysPrompt(props.getSysPrompt())
                .model(OpenAIChatModel.builder()
                        .apiKey(props.getApiKey())
                        .baseUrl(props.getBaseUrl())
                        .modelName(props.getModelName())
                        .stream(true)
                        .build())
                .toolkit(toolkit)
                .maxIters(10)
                .stateStore(stateStore)                            // MySQL
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.DEFAULT)
                        // 注意签名：addAllowRule(String toolName, PermissionRule rule)
                        //           PermissionContextState.java:223 / :228 / :233
                        .addAllowRule("query_user",
                                new PermissionRule("query_user", null, PermissionBehavior.ALLOW, "ruoyi"))
                        .addAskRule("update_user",
                                new PermissionRule("update_user", null, PermissionBehavior.ASK, "ruoyi"))
                        .addDenyRule("drop_table",
                                new PermissionRule("drop_table", null, PermissionBehavior.DENY, "ruoyi"))
                        .build())
                .build();
    }

    @Bean(destroyMethod = "close")
    public AgentStateStore agentStateStore(DataSource dataSource) {   // RuoYi 的 Druid DataSource
        return new MysqlAgentStateStore(dataSource, "ruoyi", "agentscope_sessions");
    }
}
```

### 11.3 配置清单（`application.yml` 追加）

```yaml
ruoyi:
  ai:
    enabled: true
    sys-prompt: "你是若依后台的智能助手。可以查询用户、部门、字典、菜单等系统数据；涉及修改必须请求确认。"
    api-key: ${AI_API_KEY:}
    base-url: http://10.0.0.5:8000/v1      # 自建 OpenAI 兼容端点
    model-name: qwen2.5-72b-instruct
    tool-timeout: 30
    max-iters: 10

# —— 若改用官方 starter（不推荐），配置项在这里 ——
# agentscope:
#   agent:     { enabled: true, name: Assistant, sys-prompt: "...", max-iters: 10 }
#   openai:    { enabled: true, api-key: ${AI_API_KEY}, base-url: ..., model-name: ..., stream: true }
#   dashscope: { enabled: true, api-key: ${DASHSCOPE_API_KEY}, model-name: qwen-plus, enable-thinking: false }
#   agui:
#     path-prefix: /ai/agui
#     cors-enabled: false                  # ⚠️ 默认 true + "*"，务必关掉或改白名单
#     sse-timeout: 600000
#     run-timeout: 10m
#     enable-reasoning: true               # 想展示思考过程
#     server-side-memory: false
#   admin:     { enabled: false }          # ⚠️ springdoc 冲突，建议关掉
```

### 11.4 落地检查表

| # | 事项 | 必须/建议 | 依据 |
|---|---|---|---|
| 1 | `agentscope-bom` 放在 `spring-boot-dependencies` **之后** import | 必须 | `RuoYi/pom.xml:41-47` + Maven 先声明者赢 |
| 2 | 排除 `sqlite-jdbc` | 建议 | `agentscope-core/pom.xml:171-176` |
| 3 | 显式钉 `mysql-connector-j` 版本 | 建议 | BOM 钉 9.6.0（`agentscope-dependencies-bom/pom.xml:96`） |
| 4 | 不引 `agentscope-admin-spring-boot-starter`（springdoc 冲突） | 建议 | 其 pom 硬钉 2.6.0；`RuoYi/pom.xml:33` 是 3.1.0 |
| 5 | `ShiroConfig` 放行 `/ai/**` 并校验 `sessionId` 归属 | 必须 | `quickstart.md:136` |
| 6 | `RuntimeContext.userId` 取 `ShiroUtils.getUserId()` | 必须 | `AgentStateStore.java:30-41` |
| 7 | `sessionId` 服务端生成/白名单校验 | 必须 | `ChatSessions.java:94-98` |
| 8 | 生产禁用 `ShellCommandTool` / `LocalFilesystemWithShell` | 必须 | `LocalFilesystemWithShell.java:39-49` |
| 9 | 若开 AG-UI，`cors-enabled: false` | 必须 | `AguiProperties.java:52-56` |
| 10 | 自己实现 `SessionLogStore`（若要事件级重放）或切 `LEGACY` | 视需求 | `SessionLogStore` 仅 2 个实现 |
| 11 | 不要把 `Msg`/`AgentEvent` 直接作为 `@RequestBody`/返回值 | 建议 | Jackson 2 vs 3（`AguiRequestBodyParser.java:26`） |
| 12 | 前端直接抄 `agui-client.js` 塞 `static/` | 建议 | `agui-client.js:81-103` |
| 13 | 显式给 `ToolkitConfig.executorService(...)` | 建议 | 默认 `boundedElastic`（`ToolkitConfig.java:191-193`） |
| 14 | 不用 `.block()` 写在 MVC Controller 里 | 必须 | 会占死 Tomcat 线程 |
| 15 | 权限规则热改需自建（框架只读） | 知悉 | `AgentscopePermissionsEndpoint.java:36-37` |

---

## 12. 未找到证据 / 存疑项

诚实标注，避免误导：

1. **没有精确的包体积数字**。仓库内无 `target/`、无已构建 jar，本地 `~/.m2/repository/io/agentscope` 不存在（实测 `Test-Path` 为 False）。§9.4 的结论基于 POM 依赖树推断，未实测。
2. **`sqlite-jdbc` 排除后是否 `NoClassDefFoundError`** —— 未编译验证。它标注用途是 "best-effort local workspace index"（`agentscope-core/pom.xml:171`），`ReActAgent` 路径理论上用不到，但**必须实测**。
3. **`agentscope-extensions-mongodb` 的 `AgentStateStore` 主类名** —— 目录有 20 个类，未逐一核实类名，未列入 §6.2 的确切清单。
4. **`agentscope-extensions-agent-protocol`（ASDP）** —— 只确认存在及其在 Service 平台中的定位，未审计其 API 与可用性。
5. **`agentscope-extensions-controlplane` / `-judge` / `-studio` / `-training` / `-higress` / `-scheduler`** —— 仅统计了文件数，未深入。
6. **`agentscope-service` 各 plane 的具体实现** —— 只读了 `README.md`（架构分层）与 `frontend/package.json`、`vite.config.ts`。`service-controlplane` 是 Go（`AGENTS.md`「Modules」），未审计。
7. **`ChatUiChannel` 是否有配套的 HTTP 端点** —— `ChatUiChannel`（19,538 B）未逐行读完；根据 `dataagent` 的 `ChatController` 自己写 SSE 的事实推断"**没有开箱 HTTP 端点**"，属于推断而非直接证据。
8. **WebSocket 前端通道** —— 未找到任何 `WebSocketHandler` / `@ServerEndpoint` 实现，`socket.io-client` 的用途未定位。若需要 WS 聊天，需自建。
9. **`agentscope-service/frontend` 能否被单独 `npm run build` 出可嵌入产物** —— 配置是 `outDir: '../service-controlplane/ui'`，可以改，但 40+ 个 API 模块全部指向 Control Plane，改造工作量未评估。
10. **Reactor `boundedElastic` 的具体上限** —— 我引用的是 Reactor 的通用默认值（10×CPU），非本仓库定义；仓库内未找到自定义 scheduler 配置。

---

*报告生成基于对 `agentscope-java` 仓库的实际文件读取与检索（read / grep / glob / PowerShell 文件枚举），所有行号均可在当前 checkout 中复核。*
