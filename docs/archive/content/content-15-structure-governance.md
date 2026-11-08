> **拆分归档**：新建板块文件（2026-11-08 归档）——迁自 [next_update.md](../../next/next_update.md) 批次十五「content 结构与口径治理」的已完成条目 15.1、15.2-B；条目原文、拍板结论与验证记录**原样搬运，未压缩**。
> **归档范围**：15.1（`support` 包收敛文档同步）、15.2-B（`unknown` 提取为 common 侧常量）共 2 项。
> **仍在主清单**：15.2-A（统一 content 内两份 IP 解析口径，`[~]` 代码已改、`unknown` 分支无法构造未标完成）见 [next_update.md](../../next/next_update.md)。
> **互链**：活跃待办见 [next_update.md](../../next/next_update.md)；本模块归档索引见 [content-implemention-records.md](content-implemention-records.md)。

## 板块十五：content 结构与口径治理（2026-11-08 立项；已完成部分归档）

> 编号说明（迁自 next_update.md）：批次十三（common-redis）、批次十四（基础设施）之后，本轮内容侧两项独立登记为批次十五，使用 `15.1` / `15.2`，不与已归档的 11.1~11.17 混排。两项均为结构调整/口径统一，不改业务语义。

### 15.1 content `support` 包收敛（已完成 ✅）

> **现状核对（2026-11-08）**：当前工作区**不存在**顶层 `com.litisle.litislecontent.support` 目录；`FeedItemAssembler`、`FeedRateLimitSupport`、`LikeRateLimitSupport` 三个类的 `package` 均已是 `com.litisle.litislecontent.service.support`（路径 `litisle-content/src/main/java/com/litisle/litislecontent/service/support/`）。因此代码侧「把 3 个类并入 `service/support`、删空目录」已完成，本条只剩文档同步；若执行时发现分支上仍有顶层 `support/`，按原方案先挪类再改文档。

- [x] 【文档同步｜15.1】同步 3 处包结构文档，消除仍在描述的顶层 `support/`
  - **改哪些文件**：
    1. [Design-Document.md:388-391](../../overall_documen/Design-Document.md#L388-L391)：把 3 个类并入 `service/support/` 清单，删除 `├── support/` 行。
    2. [Content-Architeche.md:42-47](../../Architecture/Content-Architeche.md#L42-L47)：同上。
    3. [content-index.md:48-53](../../index/content-index.md#L48-L53)：把 `support/` 行并入 `service/support/` 清单。
  - **已核对无需改**：[System-architecture-design.md:510](../../overall_documen/System-architecture-design.md#L510) 只引 `FeedRateLimitSupport#resolveClientIp` 类名与方法行号，不写包路径，挪包不影响；`docs/Study` 下对 `FeedItemAssembler` / `FeedRateLimitSupport` / `LikeRateLimitSupport` 的精确检索 0 命中，无待改链接。
  - **代价 / DDL**：纯文档 3 处；无代码、无 DDL、无配置。
  - **验收**：全库精确检索 `litislecontent.support` 与 `├── support/` 无残留（归档文件除外）；文档中的 `service/support/` 清单包含这 3 个类。
  - **风险**：不改逻辑、只挪包/改文档；3 个类当前已在 `service/support`，与同包其他类无循环依赖（`FeedRateLimitSupport` 依赖 `RedisRateLimiter`，`FeedItemAssembler` 只依赖 domain vo/entity，`LikeRateLimitSupport` 依赖 `RedisRateLimiter` 与 `ContentProperties`）。
  - **已拍板（2026-11-08｜第一批·零风险）**：先做文档同步，不改运行时行为；执行范围仍为上述 3 处文档。
  - ✅ 2026-11-08：已改 `Design-Document.md`、`Content-Architeche.md`、`content-index.md` 三处，把 3 个类并入 `service/support/` 清单并删除顶层 `support/` 行；`System-architecture-design.md:510` 与 `docs/Study` 按前述核对未改。文档检索无残留（归档与本条说明除外）。

### 15.2-B 把 `"unknown"` 提取为 common 侧常量（已完成 ✅）

- [x] 【P2｜15.2-B】把 `"unknown"` 提取为 common 侧常量
  - **`unknown` 实际使用点清单（精确扫描 2026-11-08，均指 IP 解析场景）**：
    - `litisle-auth/src/main/java/com/litisle/litisleauth/utils/IpUtil.java`：L22（javadoc）、L26（`request == null` 返回）、L30（XFF 校验）、L35（X-Real-IP 校验）、L40（最终兜底）。
    - `litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedRateLimitSupport.java`：L78（javadoc）、L87（XFF 校验）、L91（X-Real-IP 校验）、L94（最终兜底）。
    - `litisle-common/common-redis/src/main/java/com/litisle/commonredis/ratelimit/RateLimiterKeyBuilder.java`：L100（javadoc）、L109（无 web 环境返回）、L115（注释）、L126（最终兜底）、L129（异常兜底）、L134/L137（`isBlankOrUnknown` javadoc）、L140（比较）。
    - `litisle-common/common-redis/src/main/java/com/litisle/commonredis/idempotent/IdempotentAspect.java`：L209/L240（javadoc 中的 `"unknown"`）；代码已使用 `IdempotentConstant.UNKNOWN_IP`（L216/L232/L235/L243），无需改值。
    - `litisle-common/common-redis/src/main/java/com/litisle/commonredis/constant/IdempotentConstant.java`：L27 已定义 `UNKNOWN_IP = "unknown"`。
    - `litisle-common/common-core`、`common-security`：本次精确扫描 0 个 `"unknown"` 字面量；其他命中（`ArticlePublishServiceImpl.java:263` 的 errorCode、search `IndexDlqConsumer` 的队列来源）与 IP 无关，不纳入。
  - **候选落点**：首选在 `common-core` 的 `com.litisle.commoncore.constant` 下新增 `ClientIpConstant.UNKNOWN_IP = "unknown"`（content/auth/common-redis 都已依赖 common-core，语义通用）；次选复用 `IdempotentConstant.UNKNOWN_IP`（零新增类，但把「IP 未知」绑在幂等语义上，不推荐）。不放在 content 常量里。
  - **改法**：上述 `IpUtil`、`FeedRateLimitSupport`、`RateLimiterKeyBuilder` 的返回/比较改为引用常量；`IdempotentConstant` 若保留则改为引用新常量或标注为别名，避免两处值漂移。
  - **代价 / DDL**：约 4 个类的小改；无 DDL、无配置。
  - **验收**：全库精确检索 IP 解析相关的 `"unknown"` 字面量 0 残留（常量定义与测试除外）；既有行为不变。
  - **已拍板（2026-11-08｜第一批·零风险）**：先做常量提取与引用替换，不改 IP 解析行为；落点按候选首选 `common-core` 的 `ClientIpConstant`。
  - ✅ 2026-11-08：已新增 `common-core` 的 `ClientIpConstant.UNKNOWN_IP`；`IpUtil`、`RateLimiterKeyBuilder`、`FeedRateLimitSupport` 改用常量；`IdempotentConstant.UNKNOWN_IP` 保留旧名但值改为引用新常量（**单真源、改动面最小**：不改 `IdempotentAspect` 的 4 处引用）。全仓 java 中 IP 语义的 `"unknown"` 代码字面量仅剩常量定义，另两处非 IP 命中（发布 errorCode、DLQ 队列来源）未动。编译通过。

## 归档说明

- 本文件共归档 2 项（15.1、15.2-B）。
- **未归档**：15.2-A 仍为 `[~]`（代码已统一四级回退、限流与去重冒烟通过，但四头全缺的 `UNKNOWN_IP` 分支在真实 HTTP 下无法构造，仅代码评审确认，故未标完成），正文保留在主清单。
- 编号空缺说明：主清单批次十五的 15.1、15.2-B 空缺指向本文件；编号不重排。
