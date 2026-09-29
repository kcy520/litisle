e:\NewJavaPro\LitIsle\docs\archive\20260929-implementation-records.md
# 实现记录归档（2026-09-29）

> 归档自 `docs/next_update.md` 的已完成事项，保留设计决策与实现细节供回溯查阅。
> 仍待办 / 待验证事项保留在 `next_update.md`；本文档只增不改，后续新归档条目直接追加。

---

## 一、限流故障降级 · 本地兑底限流（2026-09-28 定稿并完成，选型：自研）

### 1.1 背景

`RedisRateLimiter.increment` 已统一 fail-open（2026-09-28），但 fail-open 意味着
「Redis 挂 → 限流失效」，对防爆破类接口（登录/验证码）是防护缺口。定稿为「轻量企业版本地兑底」，
不引 Sentinel（common-redis 库模块轻量约束）；将来商城/规模化阶段在业务模块按需引 Sentinel，
两者形成「常态流控 + 故障兑底」双保险终态。

### 1.2 实现思路（三态熔断 + 配额分摊）

1. 新增 `LocalRateLimiter`：单机固定窗口计数，`ConcurrentHashMap<String, AtomicLong>`，
   槽位 key = `限流key#窗口序号`（对齐墙钟窗口，与 Redis 路径语义一致）；
   约每 1024 次操作 purge 一次清理过期槽位，内存有界。
2. `RedisRateLimiter` 内置轻量三态熔断：
   - CLOSED：正常走 Redis；【连接/超时类】失败达阈值 → OPEN（业务类异常如脚本返回 null 不算故障，不触发熔断）；
   - OPEN：冷却期内直接走本地，不碰 Redis（避免每请求都吃连接超时）；
   - HALF_OPEN：冷却到期后放少量探测请求试 Redis，成功→CLOSED，失败→回 OPEN
     （防冷却到期全量请求同时冲回 Redis 的惊群，也防一次侥幸成功就全量恢复）。
3. 配额分摊：本地阈值 = 集群阈值 ÷ 实例数（配置 `instance-count`，可从 Nacos 读），
   保证降级期「更严或等严」，绝不更松——否则 N 实例各跑全量阈值，故障期实际放行 阈值×N，
   Redis 故障变成攻击窗口。
4. 配置外置 `litisle.rate-limit.fallback.*`：`enabled`（默认 true）/ `cooldown-seconds`（默认 5）/
   `instance-count`；关闭则回退为纯 fail-open。
5. 改动清单：新增 `ratelimit/LocalRateLimiter.java`、`config/RateLimitFallbackProperties.java`；
   修改 `RedisRateLimiter`（熔断+兑底）、`CommonRedisAutoConfiguration`（注册新 Bean）。
   调用方（`RateLimiterAspect` / `FileGlobalQpsInterceptor`）increment 签名不变，零改动。

### 1.3 完成记录（2026-09-28，全量落地）

- 新增 `ratelimit/LocalRateLimiter.java`：单机固定窗口计数（`ConcurrentHashMap<String, AtomicLong>`）。
  - 槽位 key = `限流key#窗口结束时间戳`：与定稿「窗口序号」语义等价，改用绝对过期时间戳是因
    清理时拿不到每个 key 的窗口大小（不同 key 窗口可不同，1s/60s 并存），存绝对过期时间
    可让 `removeIf` 无需额外记录窗口尺寸。
  - 墙钟对齐窗口（窗口边界取 `windowSeconds` 整数倍墙钟时刻，非「首次访问+N」），与 Redis
    路径固定窗口切分点一致，降级/恢复切换时计数口径连续。
  - 约 1024 次操作 purge 一次（`& 0x3FF` 位运算取模），内存有界（≈ 活跃 key 数 × 2 槽位）。
- 新增 `config/RateLimitFallbackProperties.java`：`litisle.rate-limit.fallback.*`，
  `enabled` 默认 true / `cooldown-seconds` 默认 5 / `instance-count` 默认 1（Nacos 可覆盖）。
- 修改 `RedisRateLimiter`（三态熔断 + 兑底，签名不变）：
  - 状态机 `AtomicInteger`（CLOSED=0/OPEN=1/HALF_OPEN=2）+ `openAtMillis`(volatile) 冷却基准。
  - 故障口径：仅【连接/超时类】异常计入 `consecutiveFailures`（连续 3 次 → 熔断）；
    脚本返回 null 属业务类，返回 0 放行不算故障。取 3 而非 1：单次超时可能是抖动，误熔断
    会白白降级到精度更低的本地计数；取小值是为了快速切入降级少吃超时。
  - OPEN 冷却期内不碰 Redis（防每请求吃连接超时拖垮接口延迟）；冷却到期仅一个线程
    CAS(OPEN→HALF_OPEN) 发探测，防惊群；探测失败回 OPEN 重计冷却，成功恢复 CLOSED。
  - 日志仅在状态切换点打（探测失败/熔断打开 warn、恢复 info），天然节流防刷屏。
  - `Math.max(1, instanceCount)` 防误配 0 导致乘积恒 0、限流失效。
- 修改 `CommonRedisAutoConfiguration`：注册 `LocalRateLimiter`、`RateLimitFallbackProperties`，
  `redisRateLimiter` Bean 构造参数同步扩展。
- 配额分摊实现（与定稿「本地阈值 = 集群阈值 ÷ 实例数」等价）：
  `increment` 返回前做 本地原始计数 × instance-count = 集群等效计数，调用方仍拿返回值与
  集群阈值比较——数学上等价于本地阈值向下取整，只会更严不会更松；因 `increment` 拿不到
  集群阈值（阈值在切面/拦截器侧才比较），乘法变换是调用方零改动的唯一可行位。
- 调用方核实结论：`RateLimiterAspect` / `FileGlobalQpsInterceptor` increment 签名不变零改动；
  `@RateLimiter` 注解 5 属性、KeyType 枚举、切面拦截方式均未动，file/user/auth 三模块
  14 处注解使用方式零变更。

### 1.4 注意事项

- 单机口径 ≠ 集群精确：分摊依赖 `instance-count` 配准，实例扩缩容后需同步（后续可接 Nacos 服务发现动态算）。
- 日志只在熔断状态切换时打（进/出降级），天然节流防刷屏；Micrometer 指标埋点已落地
  （2026-09-29，见 §二·2），告警待接入 Prometheus 后配置。
- 安全关键型（登录/验证码）降级期防护减弱但仍存在（比 fail-open 强）；`LoginRetryLimiter`
  已定稿纳入本地兑底（2026-09-28，见 §二·2）。
- 停 Redis 验证【待验证，未执行】——保留在 `next_update.md` 待办区，未归档。

### 1.5 预留：将来引 Sentinel（触发条件满足任意一条再启动，当前不动）

1. 积分商城出现秒杀/抢购类接口，需按商品 ID 热点参数限流；
2. 促销需控制台动态调阈值（而非改 Nacos 配置）；
3. 接入服务 ≥ 6 个，需统一流控视图。

引入位置：业务模块层（sentinel 依赖已在 auth/file/user），不动 common-redis。
引 Sentinel 后自研 `LocalRateLimiter` 不作废：Sentinel 集群流控（Token Server）故障时的
官方降级同样是「退回单机本地限流」，本组件即终态双保险架构的兑底层。

---

## 二、待办决策定稿（2026-09-28）

### 2.1 Sentinel 规则落地方式：Nacos 数据源（定稿并完成，2026-09-28）

- 选型：`sentinel-datasource-nacos`——规则持久化 Nacos、热更新、重启不丢。
  企业规范：限流/熔断规则属「运营参数」，必须外置 + 可热更 + 重启不丢，
  与既有惯例（运营可调参数外置 yml/Nacos）同源。
- 不加代码层兜底规则（`loadRules` + `@PostConstruct`）：系统级防线已有
  `FileGlobalQpsInterceptor` + 本地兜底限流，Nacos 短暂不可用无裸奔风险。
- 前置动作：已由 nacos-yaml.md 共享模板覆盖（`spring.cloud.openfeign.sentinel.enabled: true`
  SCA 2025.0.0.0 新属性名 + dashboard + eager: true）。
- 覆盖范围：FileClient/UserClient/AuthClient 异常比例/慢调用比例熔断
  （FallbackFactory 已就位，规则补齐后避免降级风暴）；SeaweedFsClient put/get 熔断
  （存储后端保护，防连接超时打满工作线程）。
- Dashboard 热生效验证【待验证】——保留在 `next_update.md` 待办区，未归档。

### 2.2 LoginRetryLimiter 纳入本地兜底（定稿并完成，2026-09-28）

- 语义依据：安全关键组件降级「宁严勿松」（纯 fail-open 会给防爆破留攻击窗口），
  但不可 fail-closed（Redis 挂 = 全站无法登录）→ 与既定本地兜底方案同构，纳入。
- 实现路径：把 `RedisRateLimiter` 内嵌的三态熔断状态机抽取为独立组件
  `RedisGuard`（CLOSED/OPEN/HALF_OPEN + 冷却 + 单线程探测 + 连续故障计数），
  `RedisRateLimiter` 与 `LoginRetryLimiter` 共用，状态机单一事实来源，不复制。
- 已落地（2026-09-29）：状态切换点即 Micrometer 指标埋点位置，在 `RedisGuard` 层埋一次，
  两个使用方均受益（对应「限流降级超过 X 分钟需告警」诉求）。
- 停 Redis 合并验证【待验证，未执行】——保留在 `next_update.md` 待办区，未归档。