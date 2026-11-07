# 自研流水线引擎 · Java + Vue 双栈设计

> 日期：2026-11-07 ｜ 结论：**自研，但只锁定两栈**（Maven/Java + pnpm/npm/Vue），靠 **stage type 插件化**扩展
> 前置：[PRD v1.3](PRD-DevOps-Agent平台-v1.0.md) §4.7 ｜ [窄控制器](窄控制器设计-单机滚动部署器.md)

---

## 0. 一句话结论

> **"兼容 Java 和 Vue" ≠ 在流水线里多写一条 `npm run build`。**
> 真正要解决的是三件事：**① 两套工具链的镜像与缓存**、**② 两种交付物的部署语义（容器滚动 vs 静态原子切换）**、**③ 前后端版本的成对发布与成对回滚**。

锁定机制：**4 种 stage type**（`mvn` / `npm` / `docker-build` / `static-publish`）。加栈 = 加一种 stage type + 一个镜像，**引擎本身不改**。

---

## 1. 能力模型：4 种 stage 类型

| type | 基础镜像（按 digest 钉死） | 输入 | 产出 |
|---|---|---|---|
| `mvn` | `maven:3.9-eclipse-temurin-21@sha256:…` | `pom.xml` + `src/` | `target/*.jar` |
| `npm` | `node:22-bookworm-slim@sha256:…` | `package.json` + 锁文件 | `dist/` |
| `docker-build` | 由执行器调用（不经沙箱） | `Dockerfile` + 构建上下文 | `image` + `digest` |
| `static-publish` | `alpine + rsync + curl` | `dist/` | nginx 站点目录 |

每个 stage 的公共字段：

```yaml
- id: build
  type: mvn
  image: "…@sha256:…"        # 必须 digest，禁 tag
  run: ["mvn","-B","-q","-DskipTests","package"]   # argv 数组，禁 shell
  timeout: 20m
  cache: ["m2"]
  env: { "MAVEN_OPTS": "-Xmx1g" }                   # 仅白名单变量
  continueOnError: false
```

> **`run` 里出现 `sh`/`bash`/`cmd`/`powershell` 或 shell 元字符 → 直接拒绝。** 这是唯一的"代码入口"，必须堵死。

---

## 2. PipelineSpec v2（双栈示例）

两个项目（后端仓 / 前端仓）各自一份；若将来合并成 monorepo，只是把两个 stage 放进同一个 spec。

**后端 `ruoyi-server`**

```yaml
version: 2
id: ruoyi-server
stages:
  - { id: build, type: mvn, image: "…maven@sha256:…", run: ["mvn","-B","-q","-DskipTests","package"], cache: ["m2"], timeout: 20m }
  - { id: test,  type: mvn, image: "…maven@sha256:…", run: ["mvn","-B","-q","test"], cache: ["m2"], timeout: 25m }
  - { id: package, type: docker-build, dockerfile: "Dockerfile", tagPattern: "${version}-${gitSha7}" }
deliverable: image            # → 交给窄控制器滚动发布
```

**前端 `plus-ui`**

```yaml
version: 2
id: plus-ui
toolchain:
  packageManager: auto        # 按锁文件识别 pnpm/npm/yarn
  nodeVersion: auto           # .nvmrc → package.json engines → 项目配置
stages:
  - { id: install, type: npm, image: "…node@sha256:…", run: ["pnpm","install","--frozen-lockfile","--prefer-offline"], cache: ["pnpm-store"], timeout: 15m }
  - { id: build,   type: npm, image: "…node@sha256:…", run: ["pnpm","build"], cache: ["pnpm-store"], timeout: 20m,
      env: { "NODE_OPTIONS": "--max-old-space-size=2048" } }
  - { id: publish, type: static-publish, target: "/docker/nginx/html", keepVersions: 5 }
deliverable: static           # → 走静态原子发布，不是容器
```

---

## 3. 工具链探测（防"锁文件漂移"）

| 项 | 探测顺序 | 检测到不一致时 |
|---|---|---|
| 包管理器 | `pnpm-lock.yaml` → `pnpm`；`package-lock.json` → `npm`；`yarn.lock` → `yarn` | 多个锁文件同时存在 → **失败并要求人处理**（不要猜） |
| Node 版本 | `.nvmrc` → `package.json engines` → 项目配置 | 三者冲突 → 失败 |
| Java 版本 | `pom.xml` 的 `java.version` → 项目配置 | 与构建镜像不一致 → 失败 |
| 锁文件与 `package.json` 一致性 | `--frozen-lockfile`（pnpm）/ `npm ci` | 不一致 → 构建失败（这是对的，别静默修复） |

> **不允许"构建时自动升级依赖"**。锁文件是产物的一部分，改动必须走 PR。

---

## 4. 执行模型

```
① Run 调度      项目级串行（concurrency=1）+ 全局并发上限 + 队列可见
② Stage 执行    每 stage 一个一次性容器：
                docker run --rm \
                  -v <worktree>:/ws -v m2-<proj>:/root/.m2 -v pnpm-<proj>:/root/.local/share/pnpm/store \
                  --network=${SANDBOX_NETWORK:-bridge} \
                  --memory=2g --cpus=2 --pids-limit=512 \
                  --user 1000:1000 --read-only=false <pinned image> <argv…>
③ 产物          docker-build 由执行器（非 agent）执行；静态发布由 publisher 执行
④ 日志          容器 stdout/stderr 逐行 → SSE + 落库（保留头尾，中间折叠）
```

**网络**：`build-net` 只允许访问 Nexus / npm 镜像 / 私有 registry，**禁止公网**（防供应链拉取 + 防外传）。

---

## 5. 缓存（决定体验成败）

| 缓存 | 做法 | 注意 |
|---|---|---|
| Maven | 命名卷 `m2-<project>` 挂 `/root/.m2` | 或挂 Nexus 的 `settings.xml` |
| pnpm | 命名卷挂 **store 目录**（`pnpm store path`），**不缓存 `node_modules`** | `--prefer-offline`；`node_modules` 缓存在锁文件变动时反而更慢 |
| npm | `~/.npm` 卷 | 同上 |
| Docker 层 | `--cache-from` 上次同名 tag | 需要 buildx 才有多层缓存 |
| 工作区 | 每 run 独立 worktree | 避免脏文件污染 |

**目标**：二次构建 ≤ 首次 **40%**。达不到就不是"体验问题"，是"这个功能没人用"。

---

## 6. 前端发布：原子切换与秒级回滚（本节是重点）

### 6.1 你们 nginx 的现状（A 级核实）

```nginx
gzip_static on;                                    # ← 注意：没有 gzip on
location / {
    root   /usr/share/nginx/html;                  # 目录挂载 /docker/nginx/html
    try_files $uri $uri/ /index.html;              # SPA history 模式已支持
    index  index.html index.htm;
}
location /prod-api/ { proxy_pass http://server/; } # API 走这个前缀
```

三条直接结论：
1. **SPA fallback 已就绪**，发布不用改 nginx；
2. **`gzip_static on` 但没有 `gzip on`** → 若无预压缩 `.gz` 文件，浏览器拿到的是**未压缩**资源。前端构建应产出 `.gz`（如 `vite-plugin-compression`），否则首屏明显变慢；
3. `html` 是**目录挂载**，所以可以在其内部自由增删文件；但**绝不要替换挂载点目录本身**（容器持有旧 inode）。

### 6.2 发布算法：靠"内容哈希"实现原子性

Vite 产物天生带内容哈希：`index.html` 引用 `assets/index-a1b2c3.js`。这让发布可以做到零停机：

```
1. 备份       cp html/index.html → releases/<ts>/index.html
2. 传资源     rsync -a --exclude index.html dist/ html/     ← 不 --delete，旧 hash 文件保留
3. 预检       curl 新 index.html 中引用的每个 assets → 必须全 200
4. 换入口     写 html/index.html.tmp → fsync → rename 到 html/index.html   ← 同目录内 rename，原子
5. 后检       curl / → 200 且首页引用的资源 200
6. 失败回滚   cp releases/<ts>/index.html → html/index.html（**秒级**）
7. 清理       保留最近 keepVersions 个版本；资源文件 30 天内被引用则保留
```

**为什么不需要 symlink、不需要改 nginx**：
- 旧 `index.html` 只引用旧 hash 文件，而旧文件仍在（步骤 2 不删除）→ **任何时刻页面都自洽**；
- 换入口是**单文件原子 rename**（同目录）；
- 回滚只是把 `index.html` 换回去 → **无需重新构建、无需重启 nginx**。

> 对比：如果用 `rsync --delete` 或整目录替换，就会出现"新 index 引用被删掉的旧资源"或"跳到一半 404"的经典事故。

### 6.3 前端部署与后端部署的区别

| | 后端（容器） | 前端（静态） |
|---|---|---|
| 发布动作 | 摘流 → 换容器 → 健康检查 → 回填 | 传资源 → 换 index.html |
| 时长 | 30–60s（JVM 冷启） | **1–5s** |
| 回滚 | 按 digest 重建容器（30–60s） | **换回 index.html（秒级）** |
| 健康判定 | `/actuator/health` 连续 3 次 UP | 首页 200 + 关键资源 200 |
| 需要重启吗 | 是 | **否** |

---

## 7. 前后端版本对齐（最容易出事的地方）

**问题**：前端和后端是两次独立发布。如果只回滚其中一个，可能出现「新前端 + 旧后端」（调不存在的 API）或「旧前端 + 新后端」（字段不兼容）。

**方案：Release Train（成对发布单）**

```
Release #123
├── backend:  image ruoyi/ruoyi-server@sha256:aaa   （发布顺序：1）
└── frontend: dist hash 9f3c…                        （发布顺序：2）
```

| 阶段 | 顺序 | 理由 |
|---|---|---|
| 发布 | **后端 → 前端** | 新前端可能调用新 API；而**旧前端必须能跑在新后端上**（后端 API 向后兼容原则） |
| 回滚 | **前端 → 后端** | 先回前端立刻恢复可用界面；再回后端 |
| 只回滚前端 | 安全 | 老前端对新后端兼容（前提：后端遵守向后兼容） |
| 只回滚后端 | ⚠️ **危险** | 新前端 + 老后端 → 白屏/接口 404 |

**兼容性预检（建议纳入 M4）**：
- 前端构建产物里出现的 API 路径（可从 `dist/assets/*.js` 提取 `/prod-api/...`）与后端 OpenAPI 的路径集合做差集，**缺失项告警**；
- 这不是万能的（动态拼接路径抓不到），但能拦住 80% 的低级错误。

> **红线**：后端 API 的变更必须遵守"向后兼容"（加字段不改语义、不删字段、不改类型），与数据库 expand-contract 同一套思路。

---

## 8. 安全（双栈各自的坑）

| 风险 | 措施 |
|---|---|
| npm 供应链（`postinstall` 执行任意脚本） | **`--ignore-scripts`**（如 `pnpm install --ignore-scripts`）；确有需要则按项目白名单放行单个包 |
| 私有 registry 凭证 | 注入 `.npmrc`（来自平台凭证库），**不写进仓库、不回显日志** |
| Maven 依赖投毒 | 只走内网 Nexus；禁用 `http://` 仓库；`settings.xml` 由平台注入 |
| 镜像漂移 | 所有 stage 镜像按 **digest** 钉死；tag 一律拒绝 |
| 命令注入 | argv 数组；禁 shell；路径不含 `..` |
| 构建产物外传 | 构建网络禁公网 |
| 日志泄露密钥 | redactor（正则 + 已知密钥值匹配） |

---

## 9. 工作量

| 模块 | 人日 |
|---|---|
| 引擎骨架（Run/Stage 状态机、落库、SSE、取消/重试） | 3 |
| `mvn` stage（含 Maven 缓存与 Nexus 注入） | 2 |
| `npm` stage（包管理器/Node 版本探测、store 缓存、ignore-scripts） | 3 |
| `docker-build` stage（tag 规则、digest 记账） | 2 |
| **`static-publish` + 回滚 + 版本清理** | **4** |
| 工具链探测与校验器（PipelineSpec schema） | 2 |
| 工具面（7 个 tool）+ 参数白名单 | 2 |
| 测试（含 6 个混沌用例：node 版本漂移、锁文件不一致、发布中断、回滚、OOM、镜像不存在） | 4 |
| **合计** | **约 22（18–26）** |

> 对比：只做 Java 是 12–18；加 Vue **净增 6–8 人日**，主要花在 `npm` stage 与静态发布上。

---

## 10. 风险清单

| # | 风险 | 影响 | 对策 |
|---|---|---|---|
| P-01 | Node 版本漂移导致"本地能构建、流水线不能" | 高 | `.nvmrc`/engines 强制；镜像按项目版本选择；冲突直接失败 |
| P-02 | `pnpm store` 缓存失效或污染 | 中 | 缓存带 `--prefer-offline`；锁文件变更时自动重建 |
| P-03 | Vite 大项目构建 OOM | 中 | `NODE_OPTIONS=--max-old-space-size=2048`；容器内存 2g 起 |
| P-04 | **前后端版本错配** | **致命** | Release Train + 发布顺序（后端→前端）+ 回滚顺序（前端→后端） |
| P-05 | 静态发布非原子（并发请求 404） | 高 | assets 先传、index 后换、不 `--delete` |
| P-06 | `gzip_static` 无 `.gz` 文件 → 传输未压缩 | 中 | 构建产出 `.gz`；发布校验 `.gz` 与源文件同存在 |
| P-07 | npm `postinstall` 供应链 | 高 | `--ignore-scripts` + 白名单 |
| P-08 | 前端产物含 sourcemap 泄露源码 | 中 | 生产不产 sourcemap，或只上传到平台私有存储 |

---

## 11. 验收标准（双栈）

| # | 验收 | 判据 |
|---|---|---|
| 1 | 后端构建 | 产出 image + digest；二次构建 ≤ 首次 40% |
| 2 | 前端构建 | 产出 `dist/`（含 `.gz`）；二次构建 ≤ 首次 40% |
| 3 | 前端零停机发布 | 100 QPS 持续请求，发布期间 **0 个 5xx / 0 个 404** |
| 4 | 前端回滚 | ≤ **10 秒**，无需重新构建 |
| 5 | 成对发布 | 一次 Release 同时产出两产物，回滚按"前端→后端"执行 |
| 6 | 安全 | 15 条红队用例全过（argv 注入、锁文件篡改、镜像 tag 冒充、脚本注入、日志泄露） |
| 7 | 可观测 | 每个 stage 有耗时、日志、产物；失败能定位到行 |

---

## 12. 与 PRD 的对应

| PRD 条目 | 本设计的落点 |
|---|---|
| US-C3-5（面板内构建） | §1–§4：stage 类型 + 执行模型 |
| US-C3-1（滚动部署） | §6.3：后端走窄控制器 |
| US-C3-2（发布前风险） | §7：Release Train + 兼容性预检 |
| N4（不做通用 CI） | §1：只锁 4 种 stage type |
| R-11 / R-14 | §10：风险对应 |

---

## 13. 构建位置与镜像仓库（T6 = 没有仓库）

> **已决（P10）**：构建**固定在平台服务器**执行，不在业务宿主机。
> 因此下表的第 1 个问题已有答案，代价是**镜像必须跨机传递**——见 §13.4。

### 13.1 这个"没有"引出四个连锁问题

平台服务器 ≠ RuoYi 业务服务器。没有 registry，镜像就无法跨机传递。于是：

| # | 问题 | 不解决的后果 |
|---|---|---|
| 1 | **`docker build` 在哪台机器执行？** | 在平台机构建 → 镜像到不了业务机；在业务机构建 → 平台要远程调度 |
| 2 | 回滚依赖本地镜像存在 | 镜像被 `prune` 掉 → **回滚失败**，只能重新构建（10 分钟） |
| 3 | 磁盘无上限增长 | 每版约 400–500MB，10 版就是 5GB，撑爆根分区 |
| 4 | 多机/扩容无路可走 | 以后加一台机器，整套发布链要重做 |

### 13.2 两条路（选一条，别混）

**路 A（推荐）：装一个极简 registry**

```bash
docker run -d -p 5000:5000 --restart=always --name registry \
  -v /docker/registry:/var/lib/registry registry:2
```

| 收益 | 说明 |
|---|---|
| 成本极低 | 1 个容器、约 200MB 磁盘、无外部依赖、10 分钟 |
| 构建位置自由 | 平台机构建 → push；业务机 pull |
| 回滚可靠 | digest 在仓库里，不怕本地 prune |
| 磁盘可控 | 定期 `registry garbage-collect`，按 tag 保留 |
| 未来可扩 | 加机器只需 pull |

> 装 `registry:2` 不算"引入外部依赖"——它没有外部网络、没有账号体系，就是一个本地 blob 存储。**建议直接用这条路。**

**路 B：真·无仓库（只有一条约束成立才行）**

- **构建必须在业务宿主机执行**（平台通过 **Docker API over TLS** 或 SSH 远程调度）；
- 镜像只在本地，靠 `image id / RepoDigest` 记账；
- 回滚前 **preflight 必须校验 `previousDigest` 仍在本地**，不在则拒绝发布（而不是"发布后才发现回不了滚"）；
- 镜像保留：脚本化保留最近 N 版（N ≥ 3）**且**被 `Release` 引用的版本永久保留；
- 禁止 `docker image prune -a` 进入任何自动化流程（列入红线）；
- 接受"单机天花板"：不能扩到第二台。

### 13.3 磁盘估算（决策依据，估算值）

| 项 | 单项目占用 | 备注 |
|---|---|---|
| 基础构建镜像（maven + node） | ~800MB–1.2GB | 全项目共享一份 |
| `ruoyi-server` 每版镜像 | 400–500MB | JVM 基础 + jar |
| 保留 5 版 | ~2.5GB | 回滚窗口 |
| Maven `m2` 卷 | 500MB–1.5GB | 首次构建后趋于稳定 |
| pnpm store 卷 | 300MB–1GB | 同上 |
| **单项目合计** | **约 2–4GB** | 8 个项目 → 16–32GB |

→ 部署机根分区建议 ≥ 100GB；磁盘水位 70% 告警；`/docker/server*/logs` 也要计入。

---

### 13.4 镜像跨机传递（P10 的必然结果）

既然构建在平台机、部署在业务机：

| 方案 | 做法 | 单次耗时 | 评价 |
|---|---|---|---|
| **A（推荐）** | 平台机跑 `registry:2`（绑内网 IP）→ 业务机 pull | 5–10s | 平台机顺带就是私有仓库；digest 天然存在；可 GC；可扩容 |
| B | `docker save \| ssh \| docker load` 流式导入 | 20–40s（约 450MB） | 无需新组件；但慢、断线重传、**无 RepoDigest** |

选 B 时的三条硬约束：① 传前校验业务机磁盘 ≥ 3×镜像；② 传后比对两端 **image id 一致**；③ 失败重试 ≤2，失败即中止（此时尚未摘流，零影响）。

**磁盘归属变化**：构建缓存（m2/pnpm ≈2.5GB/项目）与镜像存到**平台机** → 平台机 ≥100GB；业务机省下约 1GB（不再需要 maven/node 构建镜像）。

---

## 14. 阿里云镜像源配置（T7 = 可以）

| 生态 | 配置 | 落点 |
|---|---|---|
| **Maven** | `settings.xml` 加 `mirror`（`mirrorOf=*`）指向阿里云公共仓库 | 平台注入到 stage 的 `-v settings.xml`，**不写进项目仓库** |
| **npm** | `.npmrc` → `registry=https://registry.npmmirror.com` | 平台注入；私有包另配 scope |
| **Docker 基础镜像** | 阿里云容器镜像加速器（账号级地址），或**预拉取** | ⚠️ 见下 |

**一个必须注意的点：digest 锚点的来源**

- 若基础镜像从 registry pull → 有 `RepoDigests`（`repo@sha256:...`），可以用它钉死；
- 若基础镜像**预加载**（`docker save/load` 或本地构建）→ **没有 RepoDigest**，只有 `image id`。

**结论**：`PipelineSpec` 的 `image` 字段要支持两种写法——`repo@sha256:...`（优先）与 `sha256:...`（本地 id），并在启动时校验镜像存在。**不要假设一定有 registry。**

另：内网环境可能访问不了镜像加速器，**建议把 `maven:3.9-eclipse-temurin-21` 与 `node:22-bookworm-slim` 预加载到每台构建机**，构建流程只依赖本地镜像。这样即使外网抖动也不影响构建。

---

## 15. 单副本容量判定（T5 = 不清楚）

滚动发布的隐含前提是：**另一副本能独扛 100% 流量**（约 60–90 秒）。不清楚就必须测，否则第一次发布就是性能事故。

**测法（半天可完成）**：

1. 摘掉一个副本（nginx upstream 只留一个）；
2. 用 `wrk` / `jmeter` 对**真实业务接口**（登录 + 列表 + 一个写操作）阶梯加压：50 → 100 → 200 → 400 QPS；
3. 记录每档的 **P99、错误率、JVM GC、连接池活跃数**；
4. 找出"错误率 < 0.1% 且 P99 < 500ms"的最大 QPS → 记为**单副本拐点**；
5. 对比**业务低峰期实际 QPS**：
   - 低峰 QPS × 1.5 < 拐点 → ✅ 可以双副本滚动；
   - 否则 → 需第 3 个端口位（金丝雀位），或只能低峰 + 接受短暂容量减半。

**在测出来之前，按"扛不住"设计**（保守假设 A6），即 M2 预留第 3 端口位。这个位子成本很低（一个 compose service + 一个 nginx upstream 条目），但能避免"发布即事故"。
