# common-redis 批次十三：自研 RedisGuard 熔断降级切换 Resilience4j（归档）

> **归档日期**：2026-11-08
> **来源**：迁移自 [next_update.md](../../next/next_update.md) 的「批次十三」整节（含背景、方案、任务项、待决策项、验收标准、执行注意项、工期），条目原样搬运，仅将 13.10 状态回填为「已完成」并补充 13.11 的验证边界说明；**编号不重排**。
> **归属说明**：批次十三的主体是 `litisle-common/common-redis`（`RedisGuard`/`RedisRateLimiter`/自动配置/测试）及配套 Prometheus 抓取与告警联动，属于基础设施公共组件改造，不归属 content/search/review/file 任一业务模块；故归档落点新建 `docs/archive/common-redis/`，按模块归属而非业务域归档。
> **互链**：活跃待办见 [next_update.md](../../next/next_update.md)；模块速查见 [common-index.md](../../index/common-index.md)。

> 来源：用户 2026-11-07 提出；**只读核查已于同日完成，结论直接沿用，不重新调研**。切换前必须先完成「四、待决策项」第 1 条，未拍板前本批次不进入实施。**2026-11-07 用户已拍板执行 13.1~13.9；13.10 后续经用户明确同意执行删除（见 13.10）。**
> 板块归属：**common-redis 为主**（组件本体与自动配置），联动 litisle-auth（Redis 熔断指标的首个灰度验证服务）与文档层；auth/user/file/content/search/review 六个服务均已引 actuator 并暴露 prometheus 端点，content / file / review 等模块只涉及注释与指标消费，不改逻辑。
> 范围边界：32 个直接持有 `RedisTemplate` / `StringRedisTemplate` / `RedissonClient` 的类**按设计绕开熔断**，不在本次范围（`ReactiveRedisTemplate` 全项目 0 处）。

## 一、背景与现状（迁自只读核查，2026-11-07）

- 组件本体：`litisle-common/common-redis` 的 `RedisGuard`，手写三态（`int` 常量 `CLOSED=0` / `OPEN=1` / `HALF_OPEN=2`），`AtomicInteger state` + `AtomicInteger consecutiveFailures` + `volatile long openAtMillis`，无锁 CAS 实现。
- 失败口径：**连续 3 次失败**跳闸（`FAILURE_THRESHOLD=3`）；半开只放行 1 个探测线程；冷却默认 5 秒（`litisle.rate-limit.fallback.cooldown-seconds`）。
- 指标：自建 Gauge `redis.guard.degraded`（1=降级，**无 label**）+ Counter `redis.guard.state.change`（tag `from`/`to`）；`docker/init/prometheus/alert-rules.yml` 的 `RedisGuardDegraded` 告警直接依赖 `redis_guard_degraded == 1`。
- 入口：状态机唯一承载点 `RedisRateLimiter`（`acquire()` 3 处、`onSuccess`/`onFailure` 共 10 处），向上被 6 个类消费，再往上 77 处 `@RateLimiter` 注解、37 个文件全走切面。
- 测试：组件**零单元测试**。
- 现状疑点：`LoginRetryLimiter` **全项目没有任何业务类注入**，等于这套熔断当前实际只服务「限流」一个场景。（2026-11-07：已随 13.10 级联删除移除该组件）

## 二、方案（4 条，含 1 条硬性要求）

1. **等价映射必须显式配置（硬性要求，不是建议）**：`failureRateThreshold=100`、`slidingWindowSize=3`、`minimumNumberOfCalls=3`。原因：手写是「连续 3 次失败」，Resilience4j 默认是「窗口失败率 50% / 最小 100 次调用」；直接用默认值会出现 **Redis 连挂三次也不跳闸**，故障期继续吃连接超时。
2. **三态映射**：`CLOSED/OPEN/HALF_OPEN` 直接对应 `CircuitBreaker.State`；`acquire()` 改用 `CircuitBreaker#tryAcquirePermission()`，`Path.PROBE` 语义重写为「半开许可」，不再手写探测线程标记。
3. **指标策略**：优先**保留同名 Gauge 桥接**（`redis.guard.degraded` 由 `CircuitBreaker#getState` 派生），告警规则可不动；若确要改名，指标与规则必须同一提交内切换，不允许只改一边。
4. **本地兜底不动**：`Path.LOCAL` 仍走 `LocalRateLimiter`（含 `instanceCount` 分摊），不得简化成「OPEN 就直接返回」。

## 三、任务项（11 项：P0 9 项 + 处置 1 项 + 收口 1 项）

- [x] 【P0｜13.1】`RedisGuard` 退化为薄适配层：`acquire()` / `onSuccess` / `onFailure` / `resetFailure` 全部改为委托 `CircuitBreaker#tryAcquirePermission` / `onSuccess` / `onError` / `getState`；保留 `Path` 枚举（REDIS / PROBE / LOCAL）与「业务异常不算故障」的判定口径
  - 关键：`resetFailure` 不得再借 `onSuccess` 把 OPEN 直接打回 CLOSED（现状会绕过半开探测），改为「仅 CLOSED 下清零计数」或显式 `transitionToClosedState`
  - 验收：三处调用点（`RedisRateLimiter#increment`、`LoginRetryLimiter` 的探测与复位）签名不变、行为等价
  - ✅ 2026-11-07：`RedisGuard` 改为持有 `CircuitBreaker` + `RateLimitFallbackProperties`；`acquire()` 按「enabled 开关 → OPEN 直接 LOCAL → tryAcquirePermission 拒绝则 LOCAL → 申请瞬间 HALF_OPEN 为 PROBE」判定；`onSuccess(isProbe)` 探测成功上报并记恢复日志、非探测成功仅在 CLOSED 下上报（OPEN/HALF_OPEN 一律忽略，即 `resetFailure` 复位路径不再绕过半开探测）；`onFailure` 委托 `onError` 并只在真实状态切换点打日志。注：原 `RedisGuard` 并无 `resetFailure` 方法（复位在 `LoginRetryLimiter#resetFailure` 内以 `onSuccess(false)` 表达），本轮按「不在适配层新增死方法」处理
- [x] 【P0｜13.2】`RedisRateLimiter`：注入类型 `RedisGuard` → `CircuitBreaker`；`Path.PROBE` 改为半开许可语义；`localIncrement` 兜底路径原样保留（`instanceCount` 分摊逻辑不动）｜前置 13.1
  - ✅ 2026-11-07：字段/构造参数改为 `CircuitBreaker`；`increment` 直接 `getState` + `tryAcquirePermission` + `onSuccess/onError`（半开探测由「申请瞬间 state==HALF_OPEN」判定）；`enabled=false` 走 `tryRedis(..., reportToBreaker=false)` 不申请许可也不上报；`localIncrement` 与 `instanceCount` 分摊零改动
- [x] 【P0｜13.3】`CommonRedisAutoConfiguration`：`redisGuard` Bean 换成 `CircuitBreakerRegistry` + `CircuitBreakerConfig`，显式写入 `failureRateThreshold=100`、`slidingWindowSize=3`、`minimumNumberOfCalls=3`、`waitDurationInOpenState=cooldownSeconds`、`permittedNumberOfCallsInHalfOpenState=1`、`automaticTransitionFromOpenToHalfOpenEnabled=true`
  - 关键：**`ObjectProvider<MeterRegistry>` 必须保留**，不得改为构造注入强依赖（未引 actuator 的服务会启动失败）
  - 验收：content / file / review 启动正常（三者均已引 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`，原「未接 actuator」口径已按代码核对纠正；`ObjectProvider` 仍为网关等未引 actuator 的模块保留）
  - ✅ 2026-11-07：新增 `CircuitBreakerConfig`（静态工厂 `createCircuitBreakerConfig`，供测试直接断言）、`CircuitBreakerRegistry`（实例名 `redisGuard`）、`CircuitBreaker` 三个 Bean；`MeterRegistry` 仍由 `ObjectProvider` 可选传入，null 时指标静默跳过；`RedisGuard`/`RedisRateLimiter` 注入同一 `CircuitBreaker` 单例。未引 actuator 服务的启动验证属运行态项（随 13.8 一起，见下）
- [x] 【P0｜13.4】`RateLimitFallbackProperties`：`cooldownSeconds` 映射到 `waitDurationInOpenState`；`enabled`、`instanceCount` 保留（本地兜底仍需要）；前缀 `litisle.rate-limit.fallback` 不变（全项目无 yml 显式配置，走默认值）｜前置 13.3
  - ✅ 2026-11-07：字段与默认值不变（`cooldownSeconds=5L` 由自动配置映射为 `waitDurationInOpenState`），仅补齐映射关系与用途注释；前缀零改动
- [x] 【P0｜13.5】`docker/init/prometheus/alert-rules.yml`：指标名联动；首选保留 `redis.guard.degraded` 同名桥接、规则零改动；若改名则 `expr` 与 `for` 同提交调整｜前置 13.3
  - ✅ 2026-11-07：按首选方案执行——`RedisGuardMetrics` 用同名 Gauge `redis.guard.degraded`（由 `CircuitBreaker#getState` 派生）与同名 Counter `redis.guard.state.change`（tag `from`/`to`）桥接；`alert-rules.yml` 的 `RedisGuardDegraded`（`expr: redis_guard_degraded == 1`、`for: 5m`）**零改动**
- [x] 【P0｜13.6】上游 6 个消费者与 2 处类注释同步：`RateLimiterAspect`、`FileGlobalQpsInterceptor`、`LikeRateLimitSupport`、`FeedRateLimitSupport`、`ModerationModelQpsGuard`、`ModerationGlobalQpsInterceptor` 注释里的 "RedisGuard" 字样；`RedisRateLimiter`、`LocalRateLimiter` 类注释同步｜前置 13.1/13.2
  - ✅ 2026-11-07：全仓 java 精准检索确认 6 个消费者**均无 "RedisGuard"/"三态"/"熔断" 字样**（其注释只写「故障降级/本地兜底」，语义仍准确），故无需改动；已同步 `RedisRateLimiter`（改为「Resilience4j 熔断 + 编程式 API，禁止注解」并保留原降级口径说明）与 `LocalRateLimiter`（补「与熔断器实现的关联：只做单机计数，不参与状态判定」）两处类注释
- [x] 【P0｜13.7】新增 3 条测试：①降级（连续 3 次失败 → OPEN）②半开（冷却到期放行且仅 1 个 permit）③恢复（探测成功回 CLOSED / 探测失败重新 OPEN）
  - 验收：3 条用例通过；断言覆盖 `permittedNumberOfCallsInHalfOpenState=0` 这种配置错误不会上线
  - ✅ 2026-11-07：新增 `RedisGuardCircuitBreakerTest`（纯内存、真实 CircuitBreaker），含 5 条用例：①连续 3 次失败打开 ②1s 冷却到期自动半开且仅 1 个 PROBE ③探测成功回 CLOSED / 再探测失败回 OPEN ④无许可复位在 OPEN/HALF_OPEN 下不打回 CLOSED、不吃探测许可 ⑤生产配置值断言（100/3/3、半开许可 1、自动转半开 true，防 `permittedNumberOfCallsInHalfOpenState=0` 上线）。`mvn -pl litisle-common/common-redis -am -Dtest=RedisGuardCircuitBreakerTest test`：**Tests run: 5, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**
  - 注（2026-11-07 13.10 后同步）：删除 `LoginRetryLimiter` 后该测试已改写为直接针对 `CircuitBreaker` 与 `RedisRateLimiter` 降级路径，上述原④「无许可复位不进探测」用例因无被测对象已移除，⑤新增 `RedisRateLimiter` 降级/恢复用例；详见 13.10
- [x] 【P0｜13.8】灰度验证：auth 为首个验证服务（auth/user/file/content/search/review 六个服务实际都已暴露 `health,prometheus`，本轮只在 auth 做）(停 Redis → 观察状态切换、`redis_guard_degraded` 取值、告警触发与恢复)｜前置 13.3/13.5
  - ✅ 2026-11-07：实测通过（auth 8082 + Prometheus 9090，两轮）。①基线 `redis_guard_degraded=0`、无 `state.change`；②`docker stop litisle-redis`（其余容器保持 Up）→ 4 个并发请求后 `degraded=1`、`from=CLOSED,to=OPEN=1`，接口仍返回 200（走本地兜底）；③`docker start` 后等 8s 再请求 → 探测成功 `degraded=0`、`from=HALF_OPEN,to=CLOSED=1`，日志「Redis 熔断恢复: 探测成功，由本地兜底切回 Redis」
  - 告警链路：长停一轮（约 10 分钟）`RedisGuardDegraded` 由 inactive → pending → **firing**，恢复后回到 inactive；短停一轮只到 pending（未满 5m 即恢复）。验证结束 Redis 恢复 Up、auth 进程已停止，容器状态与验证前一致
  - 偏差与遗留：**超限不是 HTTP 429，而是 HTTP 200 + body `code=10005`**（全局异常处理把 BizException 包成 200；15 并发实测 10 个 `code=0`、5 个 `code=10005`）；仅验证 auth，content/file/review 未启动；另核查确认六个业务服务（auth/user/file/content/search/review）均已引 actuator 并暴露 prometheus，原「仅 auth 暴露」口径有误；另发现 Redis 停约 10 分钟后恢复时 auth 的 Lettuce 连接未自愈（持续 `Unable to write command into connection!`），探测一直失败、`degraded` 停在 1，重启 auth 后正常——秒级短停可正常半开恢复
- [x] 【P0｜13.9】文档联动：`docs/Architecture/commom-des.md`（组件表、故障降级章节、目录树、变更记录）、`docs/Architecture/File-Architecture.md`（限流体系与配置表）、`docs/overall_documen/Design-Document.md`（`guard/` 目录说明与 Redis 工具表述）｜前置 13.1~13.3
  - ✅ 2026-11-07：`commom-des.md` 组件表两行、故障降级章节三条、Micrometer 章节标题与说明、guard/ 目录树（新增 `RedisGuardMetrics.java`）、版本号 v2.1→v2.2 与变更记录；`File-Architecture.md` 配置表与「Redis 故障降级」条目；`Design-Document.md` `guard/` 目录说明与 Redis 工具表述
- [x] 【已完成｜13.10】`LoginRetryLimiter` 处置：核查发现全项目无业务类注入 → 用户选定 **③ 直接删除**（2026-11-07 执行）
  - ✅ 2026-11-07：删除 `LoginRetryLimiter.java`（429 行）与级联孤岛 `RedisGuard.java`（215 行）；`CommonRedisAutoConfiguration` 去掉 `loginRetryLimiter`/`redisGuard` 两个 Bean 与 2 个 import，保留 `CircuitBreaker`/`CircuitBreakerRegistry`/`CircuitBreakerConfig`/`RedisGuardMetrics` 四个 Bean 与 `ObjectProvider<MeterRegistry>` 可选注入；`CacheConstant` 删除 `LOGIN_FAIL_CNT`/`LOGIN_LOCK` 两个 key 常量；`RedisRateLimiter` 已直接注入 `CircuitBreaker`，无残留引用，`RateLimitFallbackProperties` 的 `enabled`/`instanceCount` 保留
  - 测试改写：`RedisGuardCircuitBreakerTest` 改为直接针对 `CircuitBreaker` 配置与 `RedisRateLimiter` 降级路径，5 条用例实跑通过；原「复位不进探测」用例依赖已删的 `LoginRetryLimiter#resetFailure`，无被测对象，已移除（改由「生产配置值断言」补位）
  - 文档同步：`commom-des.md`（v2.3）、`Design-Document.md`、`File-Architecture.md`、`common-index.md`、`System-architecture-design.md`、`temporarily-02-verify.md`；`docs/archive/` 作为历史记录未改动
  - 处置结论：①`LoginRetryLimiter` 与 `RedisGuard` 已删除；②测试已重写并实跑通过；③删除理由为全项目无业务调用方（唯一真实调用链已随 content 文章密码校验链路删除而移除，见 `docs/archive/content/content-09-temp-checklist.md`）；删除后 `redis.guard.degraded` / `redis.guard.state.change` 指标与 `RedisGuardDegraded` 告警链路不受影响（指标桥接由 `RedisGuardMetrics` 从 `CircuitBreaker#getState` 派生）
- [x] 【收口｜13.11】批次十三闭环补齐（2026-11-08）：①`docker/init/prometheus/prometheus.yml` 的 `litisle-services.targets` 增 `host.docker.internal:8084  # litisle-review`；②修正 `RateLimitFallbackProperties.enabled` 注释（实际语义为「只跳过熔断器调度，恒走 Redis，异常时仍本地计数兜底」，非纯 fail-open），`RedisRateLimiter` 对应分支注释已核对无需改；③review 抓取验证
  - 已验证：`/-/reload` 因未启用 lifecycle 返回 403，改为重启 `litisle-prometheus` 容器生效；8084 目标 UP；`moderation_alert_threshold` 6 条阈值序列 + `moderation_backlog` 3 条 + `moderation_dlq_backlog` + `redis_guard_degraded` 均可在 Prometheus 查到；6 条 Moderation 规则 API 状态均 `inactive` / `health=ok`
  - 未验证 / 验证边界：其中①`suspicious-backlog`、③`dlq-backlog` 有实际样本参与判定；②`claim-timeout-repooled`（认领超时）、④`moderation-fail-rate`（失败率高）、⑤`pass-rate-drop`（通过率下降）、⑥`cache-hit-drop`（缓存命中率下降）因无审核流量，`moderation_total` / `moderation_cache_hit` / `moderation_claim_timeout_repool_total` 尚无样本，仍属 no-data。**按「有流量自然生效」登记，不记为已验证通过**；产生真实审核流量后需复核这 4 条规则
  - 验证环境：review 实例验证后已停止、8084 已释放；Prometheus 保持运行并加载新配置

## 四、待决策项（2 条，历史决策记录）

1. **是否真的切换**：收益 = 免维护自研状态机 + 标准熔断指标语义；成本 = 3~4 人天；行为上无提升（三态与连续失败口径已能满足现有需求）。**已选执行（2026-11-07）**
2. **`LoginRetryLimiter` 怎么处理**：①跟着换（保留共用状态机，继续无调用方）②接线后再换（先确认登录防爆破确实要接入）③直接删除（**需用户明确同意删文件**，且要确认没有外部依赖）。**已选 ③ 直接删除（2026-11-07 执行，见 13.10）**

## 五、验收标准

- 连续 3 次连接/超时失败后进入 OPEN；冷却到期后首个请求进入半开且**仅放行 1 个**；探测成功回 CLOSED，探测失败重新 OPEN。
- 降级期间 `RedisRateLimiter` 走本地兜底，阈值与现状一致（`instanceCount` 分摊不丢失，不得实质放宽 N 倍）。
- `redis.guard.degraded` 与 `redis.guard.state.change` 在 `litisle-auth` 的 `/actuator/prometheus` 可查；`RedisGuardDegraded` 告警触发与恢复各验证一次。
- 3 条测试通过；全服务（含未引 actuator 的模块）启动无报错。

## 六、执行注意项（6 条，每条都写清后果）

1. **只能用编程式 API**：`RedisRateLimiter#increment` 内部 `this.tryRedis(...)` 属自调用，不走 AOP；挂 `@CircuitBreaker` 会**静默失效且不报错**。
2. **告警会静默消失**：指标名变更后 `expr: redis_guard_degraded == 1` 返回 **no data 而不是报错**；必须同名 Gauge 桥接，或指标与规则同时切换。
3. **半开不自动转**：`automaticTransitionFromOpenToHalfOpenEnabled` 默认 `false`；`permittedNumberOfCallsInHalfOpenState=0` 会导致降级后**永不恢复**。
4. **兜底口径漂移**：不能把 `Path.LOCAL` 简化成「OPEN 就直接返回」，否则丢掉 `LocalRateLimiter` 的 `instanceCount` 分摊，故障期阈值实质放宽 N 倍。
5. **`ObjectProvider<MeterRegistry>` 必须保留**：未引 actuator 的服务改用构造注入强依赖会启动失败。
6. **`resetFailure` 触发 `onSuccess`** 会让 OPEN 直接回 CLOSED，绕过半开探测——迁移时必须单独处理这条路径。

## 七、工期

3~4 人天，落在「入口统一但需重做指标、告警、补测试」这一档。依据：入口已收敛（3 处 `acquire()` + 10 处回调），**不需要先做收口**；否则属「多入口分散改造」档，工期 15~25 人天。

## 八、归档结论

- **功能闭环**：13.1~13.11 全部完成；代码、配置、文档与主清单指针已收口。
- **可观测闭环**：8084 抓取已补，`moderation_*` 与 `moderation_alert_threshold` 已可在 Prometheus 查询；6 条 Moderation 告警中 2 条有样本可判定，4 条待真实审核流量后复核，**不作为已验证通过**。
- **本轮未覆盖**：auth/user/file/content/search 五个服务未启动，抓取验证仅覆盖 review；Alertmanager 的 firing/resolved 链路未重新触发。
