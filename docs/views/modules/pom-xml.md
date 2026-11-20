<!-- 由 tools/docs/build.py 生成，勿手改 -->
# 模块：pom.xml

3 个会话；检索用 `python tools/docs/find.py --module pom.xml`。

## 2026-11-17 · 接入 springdoc、前端契约审计与错误码收敛（feat `e075cd6` `e2202b3` `979d6dd` `7941122`）
- 问题：缺少可机器校验的 API 契约，前后端类型漂移；
- 方案：接入 springdoc 生成 OpenAPI 与类型快照，增加前端契约审计与 Mock 联调；
- 验证：Tests run: 484, Failures: 0, Errors: 0, Skipped: 2（排除 ApiDocsSnapshotTes…

## 2026-11-17 · 会话级模型选择、确认卡续跑与多版本部署（feat `89cb898` `8da8302` `886e7a3` `44e4394` `200d1d2` `f2e6ac5` `8d3fb5a` `05621c2` `0b21f44` `249a2a0` `844dff0` `26741f8` `bddd38a` `c6d150d`）
- 问题：会话不能选模型且无用量统计；
- 方案：会话级模型/网关选择、token 统计与会话时间线入库；
- 验证：Tests run: 478, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-17 · 平台加固：有界命令、部署配置与镜像自动拉取（feat `30be8dc` `0d1f3de` `f4f9e49` `3d4a763` `ac4534a` `0345fc1` `435c17f`）
- 问题：外部命令无界等待、JDK/镜像硬依赖、Windows 私有仓库认证失败；
- 方案：引入有界命令原语与 OS 自适应解释器、按当前 JVM 解析路径；
- 验证：Tests run: 449, Failures: 0, Errors: 0, Skipped: 2

