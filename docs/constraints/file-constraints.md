# file 模块已知约束与风险

> **主题文件**：本文件是 [constraints-index.md](constraints-index.md) 的主题文件之一，按模块拆分；后续新增条目在文末追加，编号接着全局最大号（当前 R8）。
> **性质**：已知、但暂时不必处理，只有触发条件成立时才需要动手；本条目**不改代码**，触发时按「处置方式」核对。

## R8 · file 模块 Sentinel 降级规则是否真正加载未验证（关键词：Sentinel / SeaweedFS / degrade-rules）

- **现象**：[SeaweedFsClient.java:130](../../litisle-file/src/main/java/com/litisle/litislefile/client/SeaweedFsClient.java#L130) 与 [SeaweedFsClient.java:202](../../litisle-file/src/main/java/com/litisle/litislefile/client/SeaweedFsClient.java#L202) 各有一个 `@SentinelResource`（`seaweedfs-put` / `seaweedfs-get-stream`）；[application-local.yaml:16-24](../../litisle-file/src/main/resources/application-local.yaml#L16-L24) **已配置** `spring.cloud.sentinel.datasource.degrade-rules`（Nacos，`data-id=litisle-file-degrade-rules.json`，`rule-type=degrade`）。仓库内没有该规则 JSON 本体；Nacos 中 dataId 是否存在、规则是否非空、运行期是否加载成功，均**未验证**。
  - 口径纠正（2026-11-08）：此前「仓库内没有任何 `spring.cloud.sentinel.datasource` 配置」的判断**不成立**——配置已存在于 `application-local.yaml`；真正未验证的是 Nacos 侧规则内容与运行期加载结果。
- **触发条件**：SeaweedFS 上传/下载超时或故障；Nacos 缺 `litisle-file-degrade-rules.json` 或规则为空；本地/联调环境未连 Nacos 或未加 `local` profile。
- **影响**：规则未加载时 `@SentinelResource` 不会触发降级，异常直接抛给调用方（上传/下载失败）；注解看似生效、实际空转，启动与日志均不报错，只在故障时暴露。
- **处置方式**：① 登录 Nacos 查 `DEFAULT_GROUP` 下 `litisle-file-degrade-rules.json` 是否存在且规则非空；② 启动 file 后查 Sentinel Dashboard「簇点链路」与「流控/熔断规则」是否出现 `seaweedfs-put` / `seaweedfs-get-stream`；③ 停 SeaweedFS 或注入超时，验证 fallback 是否执行；④ 若规则缺失，补规则或调整 datasource 配置。未验证前不要认为 file 已有 Sentinel 兜底。
- **当前状态（2026-11-08 登记）**：未验证，不做代码与配置改动。
