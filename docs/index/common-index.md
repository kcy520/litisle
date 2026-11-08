# common-index · litisle-common 公共库速查表

> 用途：写码前查公共组件坐标与签名，**免去逐个 Read**。
> 标记：✅ 已落地 ｜ 🆕 缺口。
> 纪律：库模块只引轻量依赖；各子模块经 `AutoConfiguration.imports` 自动装配。

## 一、模块坐标

| 模块 | 包前缀 | 定位 | 自动配置 |
|---|---|---|---|
| common-core | `com.litisle.commoncore` | 统一返回/错误码/异常/枚举/工具 | CommonCoreAutoConfiguration |
| common-mq | `com.litisle.commonmq` | 手动 ACK/幂等/trace/发送收口 | CommonMqAutoConfiguration |
| common-redis | `com.litisle.commonredis` | 限流/幂等/锁/熔断守卫 | RedisConfig·RedissonConfig·CommonRedisAutoConfiguration |
| common-security | `com.litisle.commonsecurity` | Sa-Token/权限/XSS/加解密/trace/审计 | XssFilter·CryptoAutoConfiguration·CommonSecurityAutoConfiguration·SaTokenAutoConfiguration |

## 二、common-core（`com.litisle.commoncore`）

- `result/`：`Result<T>`（统一返回体）、`PageResult<T>`
- `exception/`：`BizException(code,msg)`、`ErrorCode`（全局错误码枚举，分模块段：审核 70000+、文件 90000+）、`GlobalExceptionHandler`（**须加 @Hidden 防 Knife4j 失效**）
- `enums/`：`DictEnum`（name/code/desc 三值兼容）、`StatusEnum`/`DelFlag`/`UserStatus`/`AuditModule`/`AuditOperation`
- `constant/`：`CommonConstant`/`HeaderConstant`(TRACE_ID)/`PageConstant`/`SaTokenSessionKeyConstant`
- `query/PageQuery`、`converter/StringToDictEnumConverterFactory`、`resolver/UserIdResolver`
- `utils/`：`JsonUtil`/`UidGenerator`/`DesensitizeUtil`/`AssertUtil`/`PageUtil`
- 常用错误码：`TOO_MANY_REQUESTS(10005)`、`NO_PERMISSION(10014)`、`MQ_SEND_FAILED(10015)`

## 三、common-mq（`com.litisle.commonmq`）★3-1 核心依赖

- `send/MqSendUtil` ✅：`send(exchange,routingKey,event)` 尽力而为（失败仅记 error）；`sendCritical(...)` 必达（重试 1 次后抛 `MQ_SEND_FAILED`）。两档均注入 traceId 到 Header
- `consume/MqAckHelper.executeWithAck(queue,message,channel,Runnable)` ✅：成功 ack / `BizException` warn+ack 丢弃（防毒丸）/ 其他异常 nack(requeue=false) 入 DLQ
- `consume/MqAckHelper.executeWithRetry(queue,message,channel,maxRetryCount,baseDelayMs,retryPublisher,business)` ✅：`BizException` 同 executeWithAck（warn+ack 丢弃）；其他 Exception 走指数退避延迟重投（`baseDelayMs × 2^n`），重投失败或耗尽才 nack 入 DLQ。**可重试的环境异常不能抛成 `BizException`**，否则会被当业务失败直接丢弃、不进入重试
- `consume/MqTraceSupport.restore(message)`/`clear()` ✅：消费线程 MDC traceId 恢复与清理（**finally 必调**）
- `consume/MqIdempotentHelper` ✅：`tryAcquire`（降级放行）/`tryAcquireStrict`（降级拦截）；key=`litisle:mq:idempotent:{queue}:{eventId}`
- `metrics/MqMetrics` ✅：`mq_send_total{exchange,result}`/`mq_consume_total{queue,result}`，null 静默空转
- 自动配置：注册 `Jackson2JsonMessageConverter`（禁 Java 原生反序列化）+ `MqSendUtil`；拓扑声明留业务服务

## 四、common-redis（`com.litisle.commonredis`）★3-1/3-7 依赖

- `ratelimit/`：`@RateLimiter(time,timeUnit,count,keyType)` + 切面；`KeyType`(USER/IP/DEFAULT)；`RedisRateLimiter`/`LocalRateLimiter`（Redis 不可用本地兜底）
- `idempotent/`：`@Idempotent` + 切面；`IdempotentConstant`（占位值/匿名前缀）
- `lock/`：`DistributedLock` 接口 + `RedissonLock`（`tryLock(key,waitTime,unit)` 看门狗续期 / `tryLock(key,waitTime,leaseTime,unit)` 固定租期不续期 / `lock`/`unlock`）
- `lock/` **key 约定**：调用方必须传含 `lock:` 前缀的全名（如 `lock:content:feed:hot:refill`），工具内部不再补前缀；同一把锁的 `unlock` 必须与 `tryLock` 传完全相同的 key
- `lock/` **参数/异常口径**：三参的 `waitTime` 是等锁时长（不是租期），只有 `waitTime=0` 才抢不到立即返回 false，`waitTime>0` 会阻塞等待；四参 `leaseTime<=0` 或 `waitTime<0` 抛 `IllegalArgumentException`；`unlock` 不静默吞异常（未持有→warn 跳过、`IllegalMonitorStateException`→warn 忽略、其他→error 留痕）
- `guard/RedisGuardMetrics` ✅：熔断指标桥接（Gauge `redis.guard.degraded` + Counter `redis.guard.state.change`，状态机为 Resilience4j CircuitBreaker）
- `bloom/BloomFilter`、`util/RedisUtil`、`session/DeviceSession`
- `constant/`：`CacheConstant`（各业务 key 前缀，如 `user:info:`）、`IdempotentConstant`
- 配置：`litisle.rate-limit.fallback.{enabled,cooldownSeconds,instanceCount}`
- 归档：批次十三（`RedisGuard` → Resilience4j、`LoginRetryLimiter`/`RedisGuard` 删除）见 [common-redis-implemention-records.md](../archive/common-redis/common-redis-implemention-records.md)

## 五、common-security（`com.litisle.commonsecurity`）

- `satoken/`：`SaTokenAutoConfiguration`/`StpInterfaceImpl`（角色权限供给）/`SaTokenExceptionHandler`/`SaTokenUserIdResolver`/`SaTokenEventListener`
- `constant/`：`RoleCode`（READER/AUTHOR/LEGACY_AUTHOR/REVIEWER/ADMIN/SUPER_ADMIN）；`PermissionCode`
- `utils/SaRoleHelper` ✅：`isAuthor/isAdmin/isReviewer`、`checkAuthor/checkAdmin`、`checkNotSameOrHigherLevel(roleCodes)`（越权校验）
- `crypto/`：`Aes256GcmCrypto`/`HmacSha256Crypto`/`PasswordCrypto`（AES-256 需 32 字节 Base64 密钥）
- `xss/`：`XssFilter`/`XssHttpServletRequestWrapper`/`XssStringJsonDeserializer`/`XssProperties`
- `trace/TraceIdFilter`（HTTP 生成+MDC）、`audit/`（`@AuditLog`+切面）、`anonymous/`（匿名放行扫描）
- `feign/FeignAuthInterceptor`：HTTP 线程透传 userId/roles/permissions/token/traceId；MQ/定时任务等非 Web 线程无 SaToken 上下文时降级为只透传 `X-Trace-Id`（不抛异常、不伪造身份）
- `desensitize/`（`@Sensitive`+策略）、`enums/`（`RoleCodeEnum`/`RoleLevel`/`PermissionEnum`）

## 六、与 3-1 的依赖映射

| 3-1 步骤 | 依赖组件 |
|---|---|
| 3-1.3 骨架/ACK/traceId | `MqAckHelper`·`MqTraceSupport`·`MqIdempotentHelper` |
| 3-1.10 DFA 送审 | `DfaFacade`（litisle-api） |
| 3-1.12 发 result | `MqSendUtil` |
| 3-7 成本护栏 | `@RateLimiter`·`CircuitBreaker`·`LocalRateLimiter` |
| 观测埋点 | `MqMetrics`·`MeterRegistry`(optional) |
