e:\NewJavaPro\LitIsle\docs\commom-des.md

# LitIsle Common 模块功能说明文档

> 公共基础模块合集，为业务服务提供通用能力支撑。所有业务服务依赖 common 模块，避免重复造轮子。

***

## 一、模块总览

```
litisle-common/
├── common-core          # 基础工具：异常/响应/枚举/常量/工具类/配置/接口
├── common-redis         # Redis 能力：序列化/分布式锁/布隆过滤器/幂等性/限流/登录重试限制
├── common-security      # 安全控制：Sa-Token 集成/审计/脱敏/XSS/匿名认证/数据加密/Feign 透传
└── common-mq            # MQ 机制层：消息转换/两档发送/手动 ACK/traceId 恢复/幂等/指标（拓扑留各服务）
```

### 模块依赖关系

```
common-core  ← 所有模块都依赖
↑
├── common-redis     （依赖 common-core）
├── common-security  （依赖 common-core）
├── common-mq        （依赖 common-core；不依赖 litisle-api，业务事件契约由业务服务自引）
└── common-redis ❌ 不依赖 common-security
common-security ❌ 不依赖 common-redis
common-mq ❌ 不依赖 common-redis / common-security
（通过 common-core 的 UserIdResolver 接口解耦）
```

### 核心设计原则

1. **单一职责**：每个子模块只负责一类功能（core 工具、redis 存储、security 安全）
2. **模块隔离**：common-redis 和 common-security 互不依赖，避免强制传递依赖
3. **依赖倒置（DIP）**：通过 common-core 的 `UserIdResolver` 接口解耦
4. **声明式编程**：用注解驱动（@Idempotent、@Anonymous、@Sensitive、@SaCheckLogin）
5. **Fail-Fast**：关键配置在启动时加载，错误立即暴露

***

## 二、common-core 模块

### 2.1 异常处理

| 类                        | 说明                                           |
| ------------------------ | -------------------------------------------- |
| `ErrorCode`              | 全局错误码定义（按模块分段：10000-通用、20000-用户、30000-内容...） |
| `BizException`           | 运行时异常，携带 ErrorCode，由全局处理器捕获                  |
| `GlobalExceptionHandler` | @RestControllerAdvice 统一异常处理，转换为 Result 响应   |
| `AssertUtil`             | 业务断言工具，断言失败自动抛出 BizException                 |

**Token 相关错误码**（Sa-Token 同样适用）：

| 错误码   | 枚举名                 | 说明                       |
| ----- | ------------------- | ------------------------ |
| 10008 | `TOKEN_EXPIRED`     | Token 已过期，需重新登录          |
| 10009 | `TOKEN_INVALID`     | Token 无效（格式错误/签名不匹配/被篡改） |
| 10010 | `TOKEN_BLACKLISTED` | Token 已被吊销（用户主动退出/管理员踢人） |

**GlobalExceptionHandler 支持的异常类型**：

- `BizException`：业务异常（主动抛出）
- `MethodArgumentNotValidException`：@Valid 参数校验失败
- `BindException`：参数绑定失败
- `HttpMessageNotReadableException`：请求体 JSON 解析失败
- `MethodArgumentTypeMismatchException`：URL 参数类型不匹配
- `ConstraintViolationException`：@Validated 类级校验失败
- `MissingServletRequestParameterException`：缺少必需参数
- `Exception`：兜底处理所有未捕获异常

### 2.2 统一响应

| 类               | 说明                                   |
| --------------- | ------------------------------------ |
| `Result<T>`     | 统一响应封装，含 code/message/data           |
| `PageResult<T>` | 分页响应封装，含 pageNum/pageSize/total/list |

**PageResult 派生只读字段**：

- `totalPages`：总页数（= ceil(total / pageSize)，pageSize<=0 时为 0，避免除零）
- `hasNext`：是否还有下一页（客户端可据此判断是否触发“加载更多”，简化前端分页逻辑）

分页体系由 `PageQuery`（2.3）、`PageConstant`（2.4）、`PageUtil`（2.4）共同构成。

### 2.3 分页查询基类

| 类           | 说明                                                                                  |
| ----------- | ----------------------------------------------------------------------------------- |
| `PageQuery` | 分页查询参数基类，供查询 DTO 继承（如 `UserQuery extends PageQuery`），含 pageNo/pageSize/sortBy/isAsc |

**PageQuery 设计要点**：

- 默认值：pageNo=1、pageSize=10，经 `normalizePageNo/normalizePageSize` 兜底钳制，防御 null/越界覆盖默认值，大小上限 100
- 排序三级优先级：代码显式 `OrderItem` > 前端 sortBy > 默认按 `update_time` 降序
- 排序走 OrderItem，不拼接 SQL 字符串，规避注入

### 2.4 常量与枚举

| 类                           | 说明                                                                                                                                   |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `CommonConstant`            | 通用常量（缓存前缀、分隔符等）                                                                                                                      |
| `HeaderConstant`            | HTTP Header 常量（X-User-Id、X-Username、X-Roles、X-User-Permissions、X-Trace-Id）                                                           |
| `PageConstant`              | 分页公共常量（默认页码=1、默认每页条数=10、上限=100、常用排序列 update\_time/create\_time）                                                                      |
| `SaTokenSessionKeyConstant` | Sa-Token Session 自定义键常量（roles/permissions/username），统一写入方（litisle-auth）与读取方（StpInterfaceImpl/FeignAuthInterceptor）的键名，避免硬编码拼写不一致静默失效 |
| `DelFlag`                   | 逻辑删除标志枚举（0-未删除、1-已删除；@EnumValue 入库映射 tinyint，@JsonValue 序列化为 desc）                                                                   |
| `StatusEnum`                | 状态枚举（启用/禁用）                                                                                                                          |
| `UserStatus`                | 用户状态枚举（0-禁用、1-正常、2-注销；@EnumValue 入库映射，@JsonValue/@JsonCreator 兼容 desc 与 code 双向反序列化）                                                 |
| `AuditModule`               | 审计模块枚举                                                                                                                               |
| `AuditOperation`            | 审计操作类型枚举                                                                                                                             |

### 2.5 工具类

| 类              | 说明                                                                                       |
| -------------- | ---------------------------------------------------------------------------------------- |
| `AssertUtil`   | 业务断言工具（断言失败抛 BizException）                                                               |
| `JsonUtil`     | JSON 序列化/反序列化工具（封装 Jackson）                                                              |
| `PageUtil`     | 分页工具（`IPage` → `PageResult` 转换）                                                          |
| `UidGenerator` | 对外 UID 生成器（前段相对秒时间戳 32 位 + 后段 SecureRandom 随机序列 17 位，最多 15 位十进制；对外只暴露 UID 不暴露自增主键，防遍历爬取） |

> 备注：`DesensitizeUtil` 文件存在但类体已整体注释——脱敏统一走 common-security 的 `@Sensitive` 注解，此类暂作预留，不参与编译逻辑。

### 2.6 配置类

| 类                             | 说明                                       | 生效方式 |
| ----------------------------- | ---------------------------------------- | ---- |
| `JacksonConfig`               | Jackson 全局配置（Long 转字符串、日期格式、XSS 反序列化器注册） | 自动   |
| `MyBatisPlusConfig`           | MyBatis-Plus 配置（分页插件、自动填充、逻辑删除）          | 自动   |
| `CommonCoreAutoConfiguration` | common-core Bean 集中注册                    | 自动   |

### 2.7 依赖倒置接口

| 类                | 说明                                          |
| ---------------- | ------------------------------------------- |
| `UserIdResolver` | 用户ID解析器接口，解耦 common-redis 与 common-security |

**设计说明**：common-redis 的幂等切面需要 userId，但不能依赖 common-security。通过此接口定义在 common-core，common-security 提供 `SaTokenUserIdResolver` 实现，实现依赖倒置。

***

## 三、common-redis 模块

### 3.1 Redis 配置

| 类                              | 说明                                                               | 生效方式 |
| ------------------------------ | ---------------------------------------------------------------- | ---- |
| `RedisConfig`                  | RedisTemplate 配置（Jackson 序列化、Key 前缀）                             | 自动   |
| `RedissonConfig`               | Redisson 客户端配置（分布式锁基础）                                           | 自动   |
| `CommonRedisAutoConfiguration` | Bean 集中注册（替代 @Component 扫描）                                      | 自动   |
| `RedisUtil`                    | Redis 操作工具类（String/Hash/List/Set/ZSet）                           | 手动调用 |
| `CacheConstant`                | 缓存 Key 常量前缀                                                      | 手动引用 |
| `IdempotentConstant`           | 幂等业务常量（SETNX 占位值、匿名用户前缀 anonymous:、未知 IP 标识、X-Forwarded-For 分隔符） | 内部使用 |

### 3.2 分布式锁

| 类                 | 说明                             | 生效方式 |
| ----------------- | ------------------------------ | ---- |
| `DistributedLock` | 分布式锁接口                         | 注入使用 |
| `RedissonLock`    | Redisson 实现（可重入、watchdog 自动续期） | 注入使用 |

**面试亮点**：基于 Redisson 的 `RLock`，支持可重入；watchdog 机制自动续期（默认 30s，每 10s 续一次）；业务执行完自动释放，避免死锁。

### 3.3 布隆过滤器

| 类             | 说明                   | 生效方式 |
| ------------- | -------------------- | ---- |
| `BloomFilter` | 布隆过滤器工具（基于 Redisson） | 手动调用 |

**使用场景**：缓存穿透防护、海量数据去重。

### 3.4 接口幂等性

| 类                      | 说明                               | 生效方式 |
| ---------------------- | -------------------------------- | ---- |
| `Idempotent`           | 幂等注解（标注在方法上）                     | 声明式  |
| `IdempotentAspect`     | 幂等切面（Redis SETNX 实现）             | 自动   |
| `IdempotentKeyBuilder` | 幂等 key 构建器（userId + 路径 + 参数 MD5） | 内部使用 |

**使用方式**：

```java
@Idempotent(expire = 5, message = "请勿重复提交")
@PostMapping("/order/create")
public Result<Void> createOrder(@RequestBody OrderDTO dto) { ... }
```

**实现原理**：构建 key → `idempotent:{userId}:{方法路径}:{参数MD5}` → Redis `setIfAbsent` 原子操作 → 设置成功放行，失败抛 BizException。

**⚠️ 注意**：幂等切面通过 `UserIdResolver`（可选注入）获取 userId，未引入 common-security 时 userId 为 "anonymous"。

### 3.5 接口限流

| 类                             | 说明                                                                               | 生效方式                                            |
| ----------------------------- | -------------------------------------------------------------------------------- | ----------------------------------------------- |
| `RateLimiter`                 | 限流注解（支持 USER/IP/DEFAULT 三种 key 类型）                                               | 声明式                                             |
| `RateLimiterAspect`           | 限流切面（计数委托给 RedisRateLimiter）                                                     | 自动                                              |
| `RateLimiterKeyBuilder`       | 限流 key 构建器                                                                       | 内部使用                                            |
| `RedisRateLimiter`            | Redis 固定窗口限流器（Lua 脚本 SSOT + 三态熔断 + 本地兜底）                                         | 注入使用                                            |
| `LocalRateLimiter`            | 本地兜底限流器（Redis 故障期单机固定窗口计数）                                                       | 内部使用                                            |
| `RedisGuard`                  | Redis 三态熔断守护（CLOSED/OPEN/HALF\_OPEN，与 LoginRetryLimiter 共用）                      | 内部使用                                            |
| `RedisGuard` 内置指标             | Micrometer 埋点（`redis.guard.state.change` Counter / `redis.guard.degraded` Gauge） | 自动（需 actuator + micrometer-registry-prometheus） |
| `RateLimitFallbackProperties` | 降级配置（yml: litisle.rate-limit.fallback）                                           | yml 配置                                          |

**使用方式**：

```java
@RateLimiter(time = 1, timeUnit = TimeUnit.MINUTES, count = 5, keyType = RateLimiter.KeyType.USER)
@PostMapping("/login")
public Result<LoginVO> login(@RequestBody LoginDTO dto) { ... }
```

**实现原理（固定窗口计数法）**：Redis Lua 脚本原子执行 `INCR + EXPIRE`，`count > maxCount` 时抛 BizException(TOO\_MANY\_REQUESTS)。

**Lua 脚本 SSOT（单一事实来源）**：脚本抽取到 `RedisRateLimiter`，注解式限流（@RateLimiter 切面）与系统级限流（如 FileGlobalQpsInterceptor 全站 QPS 拦截器）共用同一脚本，避免两处脚本漂移。

**故障降级（三态熔断 + 本地兜底）**：纯 fail-open 意味着「Redis 挂 → 限流失效」，对防爆破类接口（登录/验证码）是防护缺口，故引入轻量三态熔断：

- **CLOSED**：正常走 Redis；连接/超时类失败连续达 3 次 → OPEN（业务类异常如脚本返回 null 不算故障）
- **OPEN**：冷却期内直接走本地兜底，不碰 Redis（避免每请求都吃连接超时）
- **HALF\_OPEN**：冷却到期后仅放单个探测请求试 Redis，成功 → CLOSED，失败 → 回 OPEN（防惊群 + 防侥幸恢复）
- 熔断状态机下沉为公共组件 `RedisGuard`（guard 包），`RedisRateLimiter` 与 `LoginRetryLimiter` 共用，避免降级语义漂移

**本地兜底（LocalRateLimiter）**：ConcurrentHashMap 槽位单机计数，窗口与 Redis 路径墙钟对齐（降级切换时口径连续）；返回计数 × instanceCount 折算为集群等效计数（等效「本地阈值 = 集群阈值 ÷ 实例数」），保证降级期「更严或等严」；约每 1024 次操作清理一次过期槽位，内存有界。仅由 RedisRateLimiter 内部调用，业务方不直接使用。

**降级配置**（`litisle.rate-limit.fallback`，Nacos 可覆盖）：

| 配置项                | 默认值  | 说明                                    |
| ------------------ | ---- | ------------------------------------- |
| `enabled`          | true | 兜底总开关；false 回退为纯 fail-open（仅建议本地调试关闭） |
| `cooldown-seconds` | 5    | 熔断 OPEN 冷却期（秒），到期进入 HALF\_OPEN 放探测请求  |
| `instance-count`   | 1    | 集群实例数（本地配额分摊基数）；扩缩容后需人工同步             |

**Micrometer 监控指标**（`RedisGuard` 状态切换点埋点，限流与登录防爆破均受益）：

| 指标                         | 类型                     | 说明                                                    |
| -------------------------- | ---------------------- | ----------------------------------------------------- |
| `redis.guard.state.change` | Counter（tags: from/to） | 熔断状态切换次数（如 CLOSED→OPEN），观测故障频率与恢复速度                   |
| `redis.guard.degraded`     | Gauge（1=降级中/0=正常）      | 告警规则示例：`redis_guard_degraded == 1 for 5m`（降级超 5 分钟告警） |

- 埋点位置与日志同点（熔断打开/探测失败/恢复成功），天然节流防刷屏
- `MeterRegistry` 可选注入：业务服务引 `spring-boot-starter-actuator` +
  `micrometer-registry-prometheus`（两者缺一不可：actuator 提供端点框架，
  registry-prometheus 提供 /actuator/prometheus 端点与 Prometheus 格式输出）指标自动暴露；
  未引入则静默停用，状态机零影响
- common-redis 对 `micrometer-core` 为 provided 依赖，不强制传递给消费方
- 告警通道（Prometheus/Alertmanager）延后到部署侧环境就绪后配置，零代码改动

**与 @Idempotent 的区别**：

- `@Idempotent`：防止"重复提交"（TTL 内只能执行一次）
- `@RateLimiter`：防止"频繁访问"（TTL 内允许 N 次）

### 3.6 设备会话数据模型

| 类               | 说明                                      | 生效方式 |
| --------------- | --------------------------------------- | ---- |
| `DeviceSession` | 设备会话 DTO（tokenId/设备/IP/登录时间/Token 过期时间） | 数据模型 |

**说明**：设备会话的**管理逻辑**（注册/踢人/多设备限制）已移交 Sa-Token 框架处理。`DeviceSession` 保留作为数据模型，供业务侧记录设备信息到 Sa-Token Session 时使用。

### 3.7 登录重试限制

| 类                   | 说明              | 生效方式 |
| ------------------- | --------------- | ---- |
| `LoginRetryLimiter` | 登录重试限制器（暴力破解防护） | 手动调用 |

**Redis 数据结构**：

```
login:fail:{identifier}  → 连续失败次数（String，TTL 15min）
login:lock:{identifier}  → 锁定标记（String，TTL 15min）
```

**核心机制**：INCR 原子递增 + 首次失败设 TTL；双 Key 职责分离。

**Redis 故障降级**：接入 `RedisGuard` 三态熔断（与 RedisRateLimiter 共用同一状态机），降级期走组件自有本地兜底判断（宁严勿松，锁定/失败计数判定不因 Redis 故障而放行）。

***

## 四、common-security 模块

### 4.1 Sa-Token 认证集成（替代原 JWT 体系）

| 类                          | 说明                                              | 生效方式 |
| -------------------------- | ----------------------------------------------- | ---- |
| `SaTokenAutoConfiguration` | Sa-Token 自动配置（注册 StpInterface + UserIdResolver） | 自动   |
| `StpInterfaceImpl`         | Sa-Token 角色/权限数据源（从 Session 读取）                 | 自动   |
| `SaTokenUserIdResolver`    | UserIdResolver 实现（从 StpUtil 获取 userId）          | 自动   |

**架构说明**：

- 原 JWT 体系（`JwtTokenProvider`、`SecurityContext`、`SecurityContextHolder`、`SecurityContextFilter`）已全部删除
- 原注解权限校验（`@Login`、`@RequireRole`、`@RequirePermission`）已全部删除，改用 Sa-Token 原生注解（`@SaCheckLogin`、`@SaCheckRole`、`@SaCheckPermission`）
- 原设备会话管理（`DeviceSessionManager`）已删除，由 Sa-Token 框架接管
- 原 Token 黑名单（`TokenBlacklistService`）已删除，由 Sa-Token 踢人下线机制替代

**StpInterfaceImpl 设计**：从 Sa-Token Session 读取角色/权限（登录时由 litisle-auth 写入），避免每次鉴权查 DB，性能优先。

**超级管理员放权**：

- `getRoleList`：Session 的 roles 含 `SUPER_ADMIN` 时，返回全部角色编码（Sa-Token 角色校验不支持 `*` 通配，只能全量返回）
- `getPermissionList`：Session 的 roles 含 `SUPER_ADMIN` 时，返回 `*` 通配符（Sa-Token 权限校验原生支持）
- 性能：`getPermissionList` 仅一次获取 Session，同时判断超级管理员与权限列表，不重复查询
- 前提：登录时 Session 的 roles 必须真实包含 `SUPER_ADMIN`，否则放权不生效

**SaTokenUserIdResolver 设计**：通过 `StpUtil.getLoginIdDefaultNull()` 获取 userId，未登录返回 null，供 common-redis 幂等/限流切面使用。

**Sa-Token 依赖**：

```xml
<!-- common-security/pom.xml -->
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-spring-boot3-starter</artifactId>
</dependency>
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-redis-jackson</artifactId>
</dependency>
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-jwt</artifactId>
</dependency>
```

**Sa-Token 配置模板**（业务服务使用）：

```yaml
sa-token:
  token-name: Authorization
  token-prefix: Bearer
  jwt-secret-key: ${LITISLE_JWT_SECRET:your-32-character-jwt-secret-key}
  timeout: 1800
  active-timeout: 604800
  is-concurrent: true
  max-login-count: 2
  is-log: true
```

### 4.1.1 Sa-Token 全局异常处理

| 类                         | 说明                                                                     | 生效方式 |
| ------------------------- | ---------------------------------------------------------------------- | ---- |
| `SaTokenExceptionHandler` | Sa-Token 异常全局处理（`@RestControllerAdvice`），将 Sa-Token 异常转为 `Result` 统一格式 | 自动扫描 |

### 4.1.2 Sa-Token 会话事件监听器

| 类                      | 说明                                  | 生效方式                          |
| ---------------------- | ----------------------------------- | ----------------------------- |
| `SaTokenEventListener` | 监听登录/登出/踢人/顶人/封禁/解封等认证事件，输出统一格式审计日志 | `SaTokenAutoConfiguration` 注册 |

**监听的事件及审计价值**：

| 事件       | 回调方法             | 触发时机                         | 审计价值           |
| -------- | ---------------- | ---------------------------- | -------------- |
| 登录       | `doLogin`        | `StpUtil.login()` 成功后        | 记录谁、何时、用什么设备登录 |
| 登出       | `doLogout`       | `StpUtil.logout()` 调用后       | 记录用户主动退出       |
| 被踢下线     | `doKickout`      | `StpUtil.kickout()` 调用后      | 记录管理员强制下线操作    |
| 被顶下线     | `doReplaced`     | 新设备登录导致旧设备下线                 | 记录多设备登录冲突      |
| 被封禁      | `doDisable`      | `StpUtil.disable()` 调用后      | 记录封禁操作及时长      |
| 解封       | `doUntieDisable` | `StpUtil.untieDisable()` 调用后 | 记录解封恢复操作       |
| Token 续期 | `doRenewTimeout` | Token timeout 到期前自动续期        | 记录 Token 生命周期  |

**与 AuditLogAspect 的关系**：

- `AuditLogAspect`：业务操作审计（@AuditLog 注解驱动，记录 CRUD 操作）
- `SaTokenEventListener`：认证事件审计（框架事件驱动，记录登录/登出/封禁等）
- 两者互补，覆盖"业务操作"和"认证事件"两个维度，形成完整的审计日志体系

**日志格式示例**：

```json
{"event":"LOGIN","loginId":"10001","loginType":"login","time":"2026-08-17 12:00:00","device":"PC","isRemember":true,"token":"abc12345...xyz9"}
{"event":"KICKOUT","loginId":"10001","loginType":"login","time":"2026-08-17 12:30:00","token":"abc12345...xyz9"}
```

**处理的异常**：

| 异常                        | 错误码                   | 说明                                           |
| ------------------------- | --------------------- | -------------------------------------------- |
| `NotLoginException`       | `UNAUTHORIZED(10002)` | 未登录/Token过期/Token无效/被顶下线/被踢下线（5种子类型，返回不同提示语） |
| `NotPermissionException`  | `FORBIDDEN(10003)`    | 缺少权限                                         |
| `NotRoleException`        | `FORBIDDEN(10003)`    | 缺少角色                                         |
| `DisableServiceException` | `USER_FROZEN(20004)`  | 账号已被封禁                                       |

**设计说明**：

- 与 `GlobalExceptionHandler`（common-core）职责分离：前者处理通用异常，本类处理认证鉴权异常
- `@Order(Ordered.HIGHEST_PRECEDENCE)` 确保优先于 Sa-Token 内置异常处理器和 GlobalExceptionHandler 的 `Exception` 兜底
- 所有异常统一返回 `Result` 格式，前端无需适配两套格式

### 4.2 角色与权限枚举

| 类                | 说明                                                               |
| ---------------- | ---------------------------------------------------------------- |
| `RoleCodeEnum`   | 用户角色枚举（READER/AUTHOR/LEGACY\_AUTHOR/REVIEWER/ADMIN/SUPER\_ADMIN） |
| `RoleLevel`      | 角色层级枚举（NORMAL/BUSINESS/REVIEWER/ADMIN/SUPER\_ADMIN）              |
| `PermissionEnum` | 系统权限点枚举（user:read、article:publish 等）                             |

**角色规则**：

- READER 是基础角色，所有人默认拥有
- AUTHOR 与 LEGACY\_AUTHOR 互斥（二选一）
- 业务角色与管理角色不叠加
- SUPER\_ADMIN 不与其他角色叠加

**配套常量类**（注解/代码中引用，禁止硬编码字符串；新增码必须 MR Review，与对应枚举一一同步维护）：

| 类                | 说明                                                                                     |
| ---------------- | -------------------------------------------------------------------------------------- |
| `RoleCode`       | 角色码常量（READER/AUTHOR/LEGACY\_AUTHOR/REVIEWER/ADMIN/SUPER\_ADMIN，与 RoleCodeEnum 一一对应）    |
| `PermissionCode` | 权限码常量（user:read、sensitive:phone:read、role:assign、article:publish 等，格式 resource:action） |

### 4.2.1 角色辅助工具 SaRoleHelper

| 类              | 说明                                                    | 生效方式 |
| -------------- | ----------------------------------------------------- | ---- |
| `SaRoleHelper` | 角色判断/校验/越权防护静态工具（封装 StpUtil + RoleCodeEnum/RoleLevel） | 手动调用 |

**能力一览**：

- `isAuthor()` / `checkAuthor()`：作者身份判断/校验（AUTHOR 或 LEGACY\_AUTHOR 任一满足）
- `isAdmin()` / `checkAdmin()`：管理身份判断/校验（ADMIN 或 SUPER\_ADMIN 任一满足）
- `maxRoleLevel(roleCodes)`：计算一组角色编码的最高 RoleLevel（用于越权比较）
- `checkNotSameOrHigherLevel(targetRoleCodes)`：越权校验——禁止对同级或更高级目标用户执行管理操作（防越权提权），违规抛 `BizException(FORBIDDEN)`

### 4.3 Feign 拦截器

| 类                      | 说明                                                          | 生效方式 |
| ---------------------- | ----------------------------------------------------------- | ---- |
| `FeignAuthInterceptor` | Feign 请求拦截器，透传用户上下文（userId/roles/permissions/token/traceId） | 自动   |

**透传的 Header 列表**：`X-User-Id`、`X-Username`、`X-Roles`、`X-Permissions`、`Authorization`、`X-Trace-Id`

**实现方式**：通过 Sa-Token 的 `StpUtil` API 获取用户信息（`StpUtil.getLoginId()`、`StpUtil.getRoleList()`、`StpUtil.getPermissionList()` 等），替代原 `SecurityContextHolder`。

### 4.4 审计日志

| 类                | 说明                       | 生效方式 |
| ---------------- | ------------------------ | ---- |
| `AuditLog`       | 审计日志注解（配置模块、操作名、是否记录参数等） | 声明式  |
| `AuditLogAspect` | 审计日志切面（@Around 环绕通知）     | 自动   |

**使用方式**：

```java
@AuditLog(module = "文章管理", operation = "发布文章", saveRequestData = true)
@PostMapping("/article/publish")
public Result<Void> publish(@RequestBody ArticleDTO dto) { ... }
```

**操作人信息来源**：通过 Sa-Token 的 `StpUtil.getLoginIdDefaultNull()` 获取 userId，`StpUtil.getSession().get("username")` 获取用户名。

### 4.5 数据脱敏

| 类                     | 说明                                                   | 生效方式 |
| --------------------- | ---------------------------------------------------- | ---- |
| `Sensitive`           | 脱敏注解（标注在 VO 字段上）                                     | 声明式  |
| `SensitiveStrategy`   | 脱敏策略枚举（PHONE/ID\_CARD/EMAIL/BANK\_CARD/NAME/ADDRESS） | 引用   |
| `SensitiveSerializer` | Jackson 自定义序列化器                                      | 自动   |

**使用方式**：

```java
public class UserVO {
    @Sensitive(strategy = SensitiveStrategy.PHONE)
    private String phone;        // 138****8888

    @Sensitive(strategy = SensitiveStrategy.EMAIL)
    private String email;        // ex***@xx.com
}
```

### 4.6 XSS 防护

| 类                              | 说明                             | 生效方式   |
| ------------------------------ | ------------------------------ | ------ |
| `XssFilter`                    | Servlet Filter（包装请求，清洗表单参数）    | 自动     |
| `XssHttpServletRequestWrapper` | 请求包装器（清洗 HTML 标签）              | 内部使用   |
| `XssStringJsonDeserializer`    | Jackson 反序列化器（清洗 JSON 请求体）     | 自动     |
| `XssProperties`                | XSS 配置类（enabled、exclude-paths） | yml 配置 |

**配置方式**：

```yaml
litisle:
  xss:
    enabled: true
    exclude-paths:
      - /api/content/article/publish   # 富文本接口不清洗
```

### 4.7 匿名认证

| 类                        | 说明                           | 生效方式   |
| ------------------------ | ---------------------------- | ------ |
| `Anonymous`              | 匿名访问注解（标注在 Controller 方法或类上） | 声明式    |
| `AnonymousProperties`    | 匿名访问 yml 配置（paths 白名单兜底）     | yml 配置 |
| `AnonymousAccessHandler` | 白名单匹配                        | 手动调用   |
| `AnonymousPathScanner`   | 注解路径扫描器（启动时扫描 @Anonymous）    | 自动     |

**工作流程**：

1. 业务服务启动 → `AnonymousPathScanner` 扫描 `@Anonymous` 注解
2. 合并 yml 兜底白名单（`AnonymousProperties.paths`）
3. 业务服务调用 `AnonymousAccessHandler.isAnonymous(path)` 判断

**⚠️ 注意**：匿名模块与认证框架（JWT/Sa-Token）解耦，只做路径匹配，Sa-Token 迁移后无需调整。

### 4.8 数据加密

#### 4.8.1 AES-256-GCM 对称加密

| 类                         | 说明                                     | 生效方式 |
| ------------------------- | -------------------------------------- | ---- |
| `Aes256GcmCrypto`         | AES-256-GCM 加密工具类                      | 注入使用 |
| `CryptoProperties`        | 加密配置属性（绑定 `litisle.security.crypto.*`） | 自动绑定 |
| `CryptoAutoConfiguration` | 加密自动配置类                                | 自动   |

**算法**：AES-256-GCM（AEAD），提供保密性+完整性+真实性三重保护。随机 IV + GCM 认证标签，输出格式 `Base64(IV + 密文 + 认证标签)`。

**适用场景**：手机号、邮箱等需要可逆解密的敏感字段。

#### 4.8.2 BCrypt 密码哈希

| 类                | 说明                           | 生效方式 |
| ---------------- | ---------------------------- | ---- |
| `PasswordCrypto` | BCrypt 封装工具类（encode/matches） | 注入使用 |

**算法**：BCrypt 自适应哈希，自动加盐，不可逆。适用于用户登录密码。

**密码加密 vs 字段加密对比**：

| 对比项  | AES-256-GCM（手机号/邮箱） | BCrypt（密码）   |
| ---- | ------------------- | ------------ |
| 可逆性  | ✅ 可逆（需要时可解密）        | ❌ 不可逆（只验不解密） |
| 使用场景 | 需要查询展示的敏感字段         | 只验证不展示的密码    |
| 密钥要求 | 需要保密密钥              | 不需要密钥（盐自动生成） |

#### 4.8.3 HMAC-SHA256 确定性摘要

| 类                  | 说明                                                | 生效方式 |
| ------------------ | ------------------------------------------------- | ---- |
| `HmacSha256Crypto` | HMAC-SHA256 确定性摘要工具（`digest(明文)` → 64 位小写 hex 指纹） | 注入使用 |

**用途**：`phone_hash` / `email_hash` 等摘要列的唯一索引、查重、换绑校验。AES-GCM 因随机 IV 每次密文不同，无法支撑唯一索引比对；HMAC 确定性输出（同一明文恒定同一结果）正好补位。

**为什么必须 HMAC 而非纯 SHA-256**：手机号/常见邮箱取值空间小，纯哈希可被彩虹表反推；HMAC 引入密钥后，攻击者拿到库但无密钥则无法反推明文。

**与 AES-256-GCM 的分工**：

- AES-256-GCM：密文入 phone/email 列（可解密展示、脱敏）
- HMAC-SHA256：摘要入 phone\_hash/email\_hash 列（唯一索引、查重）

**HMAC Bean 密钥来源（CryptoAutoConfiguration）**：

1. 优先使用独立配置的 `litisle.security.crypto.hash-key`
2. 未配置时从 AES 主密钥确定性派生子密钥（主密钥对固定标签 `litisle:hmac-subkey:v1` 做一次 HMAC，HKDF 简化思路）
3. 与 AES Bean 绑定同一创建条件（`litisle.security.crypto.key` 已配置），有就都有、没有都没有；HMAC 密钥不足 32 字节拒绝启动（Fail-Fast），启动日志会打印实际走的密钥路径

### 4.9 配置注册

| 类                                 | 说明                                            | 生效方式 |
| --------------------------------- | --------------------------------------------- | ---- |
| `CommonSecurityAutoConfiguration` | 注册纯安全组件（Feign 拦截器、审计切面、匿名扫描器）                 | 自动   |
| `SaTokenAutoConfiguration`        | 注册 Sa-Token 集成组件（StpInterface、UserIdResolver） | 自动   |
| `CryptoAutoConfiguration`         | 注册加密组件（Aes256GcmCrypto、PasswordCrypto）        | 自动   |

**自动配置注册方式**：通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件注册，确保作为库模块时 Bean 一定被 Spring 发现。

```
# AutoConfiguration.imports 内容
com.litisle.commonsecurity.xss.XssFilter
com.litisle.commonsecurity.crypto.CryptoAutoConfiguration
com.litisle.commonsecurity.config.CommonSecurityAutoConfiguration
com.litisle.commonsecurity.satoken.SaTokenAutoConfiguration
```

***

## 五、网关安全模块（litisle-gateway）

### 5.1 网关认证

**架构变更**：网关从原 JWT 手动校验 → Sa-Token JWT 模式无状态校验。

**网关依赖**（需添加）：

```xml
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-reactor-spring-boot3-starter</artifactId>  <!-- WebFlux 专用 -->
</dependency>
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-jwt</artifactId>
</dependency>
```

**网关配置**（`application.yml`）：

```yaml
sa-token:
  token-name: Authorization
  token-prefix: Bearer
  jwt-secret-key: ${LITISLE_JWT_SECRET:LitIsle-Default-JWT-Secret-Key-Change-In-Production!}
  timeout: 1800
  active-timeout: 604800
  is-log: true

litisle:
  security:
    anonymous:
      paths:
        - /api/auth/login
        - /api/auth/register
        - /api/auth/captcha
        - /api/auth/oauth/**
        - /api/auth/refresh
        - /api/content/article/list
        - /api/content/article/{id}
        - /actuator/**
```

**⚠️ 注意**：网关的 `JwtAuthGlobalFilter` 需要改为 Sa-Token 的 `SaReactorFilter`，这是下一步迁移工作。

***

## 六、配置文件说明

### 6.1 common 模块的 yml 文件

**重要**：common 模块的 yml 文件**已全部注释**，仅作配置参考模板。

**原因**（企业规范）：

- common 是公共库（jar），不是独立 Spring Boot 应用
- yml 配置应由业务服务或 Nacos 提供，避免配置冲突
- 公共库只提供"代码能力和默认值"（通过 @ConfigurationProperties 字段默认值）

### 6.2 业务服务必须配置的项

```yaml
# ====== Sa-Token 配置（litisle-auth / litisle-gateway） ======
sa-token:
  token-name: Authorization
  token-prefix: Bearer
  jwt-secret-key: ${LITISLE_JWT_SECRET:your-32-character-jwt-secret-key}
  timeout: 1800
  active-timeout: 604800
  is-concurrent: true
  max-login-count: 2
  is-log: true

# ====== 匿名访问白名单（各业务服务） ======
litisle:
  security:
    anonymous:
      paths:
        - /actuator/**
        - /static/**

    # ====== 数据加密配置 ======
    crypto:
      key: ${LITISLE_CRYPTO_KEY}
      # 可选：HMAC 独立密钥（≥32 字节 Base64）；不配则从主密钥确定性派生子密钥
      # hash-key: ${LITISLE_CRYPTO_HASH_KEY}
      bcrypt-cost: 10

  # ====== XSS 过滤配置 ======
  xss:
    enabled: true
    exclude-paths:
      - /api/content/article/publish
```

### 6.3 各模块配置归属

| 配置项                                | 归属模块                           | 说明                                             |
| ---------------------------------- | ------------------------------ | ---------------------------------------------- |
| `sa-token.*`                       | litisle-auth + litisle-gateway | Sa-Token 认证配置（auth 签发，网关校验）                    |
| `litisle.security.anonymous.paths` | 各业务服务 + 网关                     | 匿名白名单                                          |
| `litisle.security.crypto.*`        | 各业务服务                          | AES-256 密钥 + BCrypt cost                       |
| `litisle.xss.*`                    | 各业务服务                          | XSS 配置（按服务定制）                                  |
| `litisle.rate-limit.fallback.*`    | 各业务服务（common-redis）            | 限流本地兜底：enabled/cooldown-seconds/instance-count |

***

## 七、使用方式总览

### 7.1 自动执行（引入依赖即生效）

| 功能                             | 模块              | 自动机制                           |
| ------------------------------ | --------------- | ------------------------------ |
| 统一异常处理                         | common-core     | `@RestControllerAdvice` 自动扫描   |
| Jackson 全局配置                   | common-core     | `@Configuration` 自动扫描          |
| MyBatis-Plus 配置                | common-core     | `@Configuration` 自动扫描          |
| RedisTemplate 配置               | common-redis    | `@Configuration` 自动扫描          |
| Redisson 配置                    | common-redis    | `@Configuration` 自动扫描          |
| Sa-Token 自动配置                  | common-security | `AutoConfiguration.imports` 注册 |
| Feign 用户上下文透传                  | common-security | `@Configuration` Bean 注册       |
| XSS 过滤                         | common-security | `AutoConfiguration.imports` 注册 |
| 加密工具配置                         | common-security | `AutoConfiguration.imports` 注册 |
| 匿名白名单初始化                       | common-security | `@PostConstruct` 兜底加载          |
| 匿名白名单注解扫描                      | common-security | `ContextRefreshedEvent` 自动扫描   |
| Trace ID 链路承接                  | common-security | `FilterRegistrationBean` 注册    |
| MQ Converter/RabbitTemplate 定制 | common-mq       | `AutoConfiguration.imports` 注册 |
| MQ 幂等消费辅助                      | common-mq       | `@ConditionalOnClass` 可选注册     |
| MQ 收发指标埋点                      | common-mq       | `@ConditionalOnClass` 可选注册     |

### 7.2 声明式（加注解即生效）

| 注解                   | 模块              | 作用           |
| -------------------- | --------------- | ------------ |
| `@Idempotent`        | common-redis    | 接口幂等性（防重复提交） |
| `@RateLimiter`       | common-redis    | 接口限流         |
| `@SaCheckLogin`      | Sa-Token（内置）    | 登录校验         |
| `@SaCheckRole`       | Sa-Token（内置）    | 角色校验         |
| `@SaCheckPermission` | Sa-Token（内置）    | 权限校验         |
| `@AuditLog`          | common-security | 审计日志记录       |
| `@Sensitive`         | common-security | 数据脱敏         |
| `@Anonymous`         | common-security | 匿名访问         |

### 7.3 手动调用

| 类                          | 模块              | 场景                                         |
| -------------------------- | --------------- | ------------------------------------------ |
| `Result.success()/error()` | common-core     | Controller 返回                              |
| `AssertUtil`               | common-core     | 业务断言                                       |
| `JsonUtil`                 | common-core     | JSON 转换                                    |
| `UidGenerator`             | common-core     | 对外 UID 生成                                  |
| `StpUtil`                  | Sa-Token（内置）    | 登录/鉴权/会话管理                                 |
| `Aes256GcmCrypto`          | common-security | AES-256-GCM 加解密                            |
| `HmacSha256Crypto`         | common-security | HMAC-SHA256 确定性摘要（phone\_hash/email\_hash） |
| `SaRoleHelper`             | common-security | 角色/权限便捷校验、越权防护                             |
| `PasswordCrypto`           | common-security | BCrypt 密码加密/验证                             |
| `RedisUtil`                | common-redis    | 缓存操作                                       |
| `RedissonLock`             | common-redis    | 分布式锁                                       |
| `BloomFilter`              | common-redis    | 布隆过滤                                       |
| `RedisRateLimiter`         | common-redis    | 固定窗口计数（@RateLimiter 切面与系统级限流共用）            |
| `LoginRetryLimiter`        | common-redis    | 登录重试限制                                     |

***

## 八、后续开发注意事项

### ⚠️ 待办事项1：网关 JwtAuthGlobalFilter → SaReactorFilter

**现状**：网关的 `JwtAuthGlobalFilter` 仍使用旧 JWT 校验逻辑，需改为 Sa-Token 的 `SaReactorFilter`。

**方案**：引入 `sa-token-reactor-spring-boot3-starter`，用 `SaReactorFilter` 替代 `JwtAuthGlobalFilter`。

### ✅ 已完成2：Sa-Token 全局异常处理适配

**方案**：新建 `SaTokenExceptionHandler`（`@RestControllerAdvice`），将 `NotLoginException`/`NotPermissionException`/`NotRoleException`/`DisableServiceException` 统一转为 `Result` 格式。详见 4.1.1 节。

### 📋 指南3：业务服务注册 SaInterceptor（路由拦截器）

**说明**：业务服务必须注册 `SaInterceptor` 才能让 `@SaCheckLogin` 等注解生效。common-security 不自动注册（不知道各服务的路径规则）。

**标准注册模板**（各业务服务在自己的 `WebMvcConfigurer` 中配置）：

```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 认证接口（登录/注册/验证码）
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/auth/captcha",
                        "/api/auth/refresh",
                        "/api/auth/oauth/**",
                        // 公开内容接口
                        "/api/content/article/list",
                        "/api/content/article/{id}",
                        // Knife4j 文档
                        "/doc.html",
                        "/v3/api-docs/**",
                        "/webjars/**",
                        // 健康检查
                        "/actuator/**"
                );
    }
}
```

**关键说明**：

- `new SaInterceptor(handle -> StpUtil.checkLogin())`：校验所有匹配路径的登录状态
- `excludePathPatterns`：放行匿名路径（登录、注册、公开资源、文档、健康检查）
- 路径规则与 `litisle.security.anonymous.paths` 保持一致（双保险：网关 + 业务服务各校验一次）
- 若需细粒度权限校验，在 Controller 方法上用 `@SaCheckPermission` / `@SaCheckRole` 替代全局 `checkLogin()`

### ⚠️ 待办事项4：富文本 XSS 白名单清洗

**现状**：XSS 过滤对富文本接口是"直接放行"（exclude-paths 排除），存在 XSS 风险。

**方案**：用 Jsoup Cleaner 做白名单清洗（保留安全标签，移除 `<script>`）。

### ⚠️ 待办事项5：审计日志异步入库

**现状**：审计日志通过 `log.info` 输出。

**方案**：改为 MQ 异步发送到日志服务入库。

***

## 九、面试亮点总结

### 9.1 架构设计

1. **模块隔离原则**：common-redis 和 common-security 互不依赖，避免强制传递依赖
2. **依赖倒置原则（DIP）**：通过 `UserIdResolver` 接口解耦
3. **单一职责原则**：每个子模块只负责一类功能
4. **框架升级**：从自研 JWT + ThreadLocal 体系升级为 Sa-Token 工业级框架

### 9.2 设计模式应用

| 模式    | 应用场景                                              |
| ----- | ------------------------------------------------- |
| 装饰器模式 | `XssHttpServletRequestWrapper` 包装原始 Request       |
| 策略模式  | `SensitiveStrategy` 枚举定义多种脱敏策略                    |
| 模板方法  | `BloomFilter`、`DistributedLock` 接口定义规范            |
| 观察者模式 | `AnonymousPathScanner` 监听 `ContextRefreshedEvent` |
| 工厂方法  | `Result.success()/error()` 静态工厂                   |

### 9.3 企业规范实践

| 规范                        | 实践                                      |
| ------------------------- | --------------------------------------- |
| Fail-Fast                 | `@PostConstruct` 启动时加载配置，错误立即暴露         |
| 声明式编程                     | 注解驱动（@Idempotent、@Anonymous、@Sensitive） |
| 配置分层                      | 公共库提供默认值，业务服务配置实际值，Nacos 动态下发           |
| 防御性编程                     | 白名单合并多来源（注解 + yml），互为兜底                 |
| AutoConfiguration.imports | 库模块配置注册，避免 @Component 扫描失效              |

***

## 十、链路追踪 Trace ID（跨模块）

### 10.1 设计目标

全链路日志追踪：一次请求从网关到各业务服务，通过同一个 `traceId` 串联所有日志，便于问题定位与性能分析。

### 10.2 完整链路

```
客户端
  │ 请求（无 traceId）
  ▼
【litisle-gateway】TraceIdFilter（WebFilter，order=-200）
  │ 生成/复用 traceId → 注入 X-Trace-Id Header + 响应 Header + MDC
  ▼
【业务服务A】TraceIdFilter（OncePerRequestFilter，common-security）
  │ 读 Header 写 MDC → 服务A 日志带 traceId
  ▼ Feign 调用
【common-security】FeignAuthInterceptor
  │ 从 MDC 读 traceId 透传 X-Trace-Id Header
  ▼
【业务服务B】TraceIdFilter（OncePerRequestFilter，common-security）
  │ 读 Header 写 MDC → 服务B 日志带 traceId
  ▼
响应（X-Trace-Id 回传客户端，可关联）

—— MQ 异步边界（common-mq 补齐，2026-10-03）——
【发送服务】MqSendUtil
  │ MDC traceId → 注入消息 Header（HeaderConstant.TRACE_ID，与 HTTP 同口径）
  ▼ broker（跨线程/跨进程，MDC 不会自动跨越，需 Header 显式携带）
【消费服务】MqTraceSupport.restore(message)
  │ Header → MDC 恢复，消费日志贯通；finally clear() 防线程池复用串扰
```

### 10.3 相关文件清单（含模块）

| 文件                                | 所在模块                      | 职责                                               | 状态             |
| --------------------------------- | ------------------------- | ------------------------------------------------ | -------------- |
| `TraceIdFilter`                   | litisle-gateway（filter 包） | 链路入口：生成/复用 traceId，注入下游 Header + 响应 Header + MDC | 新增             |
| `TraceIdFilter`                   | common-security（trace 包）  | 链路承接：读 Header 写 MDC                              | 新增             |
| `FeignAuthInterceptor`            | common-security（feign 包）  | 链路透传：从 MDC 读 traceId 透传下游                        | 已有（见 4.3）      |
| `MqSendUtil`                      | common-mq（send 包）         | 发送侧：MDC traceId 注入消息 Header                      | 新增（2026-10-03） |
| `MqTraceSupport`                  | common-mq（consume 包）      | 消费侧：Header 恢复 MDC，finally 清理                     | 新增（2026-10-03） |
| `HeaderConstant`                  | common-core（constant 包）   | 定义 `X-Trace-Id` Header 名                         | 已有（见 2.4）      |
| `CommonSecurityAutoConfiguration` | common-security（config 包） | 注册 TraceIdFilter（FilterRegistrationBean，最高优先级）   | 修改             |

### 10.4 各文件详细说明

#### 10.4.1 网关 TraceIdFilter（litisle-gateway）

- 类型：`WebFilter`（WebFlux 响应式）
- order：`-200`（早于 SaReactorFilter 的 -100，认证失败时也能关联 traceId）
- 逻辑：
  1. 复用上游传入的 `X-Trace-Id`，缺失则生成 UUID（去横线）
  2. 注入下游请求 Header + 响应 Header（客户端可关联）
  3. 写入 MDC（WebFlux 线程模型下尽力而为，核心目的是注入下游 Header）

#### 10.4.2 业务服务 TraceIdFilter（common-security）

- 类型：`OncePerRequestFilter`（Servlet）
- 注册：`CommonSecurityAutoConfiguration` 通过 `FilterRegistrationBean` 注册，`Ordered.HIGHEST_PRECEDENCE` 最高优先级，`/*` 全路径
- 逻辑：
  1. 读请求 Header `X-Trace-Id`，缺失则本地生成（直接访问/定时任务场景）
  2. 写入 MDC（`FeignAuthInterceptor` 从此读取透传）
  3. 响应 Header 回写 traceId
  4. finally 中移除 MDC，避免线程池复用污染

#### 10.4.3 FeignAuthInterceptor（common-security，已有）

- 从 MDC 读取 `traceId`，透传 `X-Trace-Id` Header 给下游服务（详见 4.3 节）

### 10.5 日志输出（已实现）

MDC 中的 `traceId` 通过日志 pattern 输出，在全链路日志中可见。已在各业务服务 `application.yml` 中配置（无需额外 logback 文件）：

```yaml
logging:
  pattern:
    # 控制台输出：%X{traceId} 从 MDC 读取链路追踪 ID
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{50} - %msg%n"
    # 文件输出（如需落盘日志）
    file: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{50} - %msg%n"
```

**已配置服务**：litisle-gateway、litisle-auth、litisle-user、litisle-content

**说明**：

- `%X{traceId}` 从 MDC 读取 traceId（由 TraceIdFilter 写入）
- 未配置时 traceId 为空，日志显示 `[]`，不影响正常运行
- 若需滚动归档、异步输出等复杂策略，再引入 `logback-spring.xml`

## 十一、common-mq 模块（MQ 公共能力抽取）

> 机制层收口：消息转换/发送/ACK 模板统一，业务服务禁止裸调 `RabbitTemplate.convertAndSend`、禁止自建 Converter（防复制漂移）。拓扑层（Exchange/Queue/Binding/DLX 声明、yml 连接、prefetch）仍留各服务。

### 11.1 设计边界（防过度设计）

- 只抽机制不抽拓扑：拓扑声明与连接配置留各服务，本地拓扑零变更（不触发 PRECONDITION\_FAILED）
- ACK 模板为轻量静态工具，不做注解/切面/基类继承
- 依赖方向仅 common-mq → common-core；不并入 common-core（避免 gateway 等无 MQ 需求模块传递 amqp 依赖）

### 11.2 组件清单

| 类                           | 说明                                                                           | 生效方式                           |
| --------------------------- | ---------------------------------------------------------------------------- | ------------------------------ |
| `CommonMqAutoConfiguration` | Jackson2JsonMessageConverter（事件体 JSON 统一口径，禁 Java 原生反序列化）+ RabbitTemplate 定制 | `AutoConfiguration.imports` 自动 |
| `MqSendUtil`                | 两档发送语义 + traceId 注入 Header                                                   | 手动调用                           |
| `MqTraceSupport`            | 消费侧 traceId 恢复/清理                                                            | 手动调用（restore/clear）            |
| `MqAckHelper`               | 手动 ACK 模板（SSOT，防毒丸）                                                          | 手动调用                           |
| `MqIdempotentHelper`        | SETNX 幂等消费辅助（预置工具）                                                           | 自动 Bean（可选）                    |
| `MqMetrics`                 | mq\_send\_total / mq\_consume\_total 埋点（预置工具）                                | 自动 Bean（可选）                    |

### 11.3 MqSendUtil 两档发送语义

| 方法             | 语义                                                 | 适用                                         |
| -------------- | -------------------------------------------------- | ------------------------------------------ |
| `send`         | 尽力而为：失败仅记 error 不抛                                 | 失败有自然兑底（image.submit → safe\_flag 占位 + 对账） |
| `sendCritical` | 必达：静默重试 1 次后抛 `BizException(MQ_SEND_FAILED/10015)` | 失败破坏一致性（content.unpublished）               |

两档均从 MDC 取 traceId 注入消息 Header；MDC 无值（定时任务直投）不写脏 Header。

### 11.4 MqAckHelper ACK 纪律（SSOT）

| 分支             | 处理                                  | 理由                                                                                         |
| -------------- | ----------------------------------- | ------------------------------------------------------------------------------------------ |
| 业务成功           | `basicAck(tag, false)`              | 正常确认                                                                                       |
| `BizException` | warn 后 ack 丢弃                       | 重试无意义，重投即毒丸                                                                                |
| 其他 Exception   | error 后 `nack(requeue=false)` 入 DLQ | 环境故障交 DLQ 兑底；Jackson 失败的 `MessageConversionException` 也走此路径——改动 catch 结构前必读类头【毒丸防护·隐式依赖路径】 |

标准用法：`MqTraceSupport.restore(message)` → `try { MqAckHelper.executeWithAck(...) } finally { MqTraceSupport.clear() }`

### 11.5 optional/provided 零强制依赖（验收口径）

- redis starter（provided）：未接 Redis 时 MqIdempotentHelper 两档降级——`tryAcquire` 放行（业务兑底）/ `tryAcquireStrict` 拦截（宁漏勿重，error 留痕人工补偿）；key = `litisle:mq:idempotent:{queue}:{eventId}`（服务端派生），TTL 外置 `litisle.mq.idempotent.dedup-ttl-seconds` 默认 86400
- micrometer-core（provided）：MeterRegistry 缺失时 MqMetrics 静默空转；tag 值（exchange/queue）禁动态串防基数爆炸
- 未接 Redis/Micrometer 的消费方引 common-mq 不受影响

### 11.6 业务服务接入约束

发事件一律走 MqSendUtil（按一致性影响选档）；消费一律 MqTraceSupport + MqAckHelper 组合；禁止再自建 Converter / 手写 ack-nack 模板。暂不做（附触发条件）：Publisher Confirm、事务性发件箱（Seata AT 已覆盖）、批量消费、常量抽取（契约 SSOT 在 litisle-api）。

***

## 十二、文件清单

### common-mq 文件清单

```
common-mq/src/main/java/com/litisle/commonmq/
├── config/
│   └── CommonMqAutoConfiguration.java
├── consume/
│   ├── MqAckHelper.java
│   ├── MqIdempotentHelper.java
│   └── MqTraceSupport.java
├── metrics/
│   └── MqMetrics.java
└── send/
    └── MqSendUtil.java
```

### common-core 文件清单

```
common-core/src/main/java/com/litisle/commoncore/
├── config/
│   ├── CommonCoreAutoConfiguration.java
│   ├── JacksonConfig.java
│   └── MyBatisPlusConfig.java
├── constant/
│   ├── CommonConstant.java
│   ├── HeaderConstant.java
│   ├── PageConstant.java
│   └── SaTokenSessionKeyConstant.java
├── enums/
│   ├── AuditModule.java
│   ├── AuditOperation.java
│   ├── DelFlag.java
│   ├── StatusEnum.java
│   └── UserStatus.java
├── exception/
│   ├── BizException.java
│   ├── ErrorCode.java
│   └── GlobalExceptionHandler.java
├── query/
│   └── PageQuery.java
├── resolver/
│   └── UserIdResolver.java
├── result/
│   ├── PageResult.java
│   └── Result.java
└── utils/
    ├── AssertUtil.java
    ├── DesensitizeUtil.java
    ├── JsonUtil.java
    ├── PageUtil.java
    └── UidGenerator.java
```

### common-redis 文件清单

```
common-redis/src/main/java/com/litisle/commonredis/
├── bloom/
│   └── BloomFilter.java
├── config/
│   ├── CommonRedisAutoConfiguration.java
│   ├── RateLimitFallbackProperties.java
│   ├── RedisConfig.java
│   └── RedissonConfig.java
├── constant/
│   ├── CacheConstant.java
│   └── IdempotentConstant.java
├── guard/
│   └── RedisGuard.java
├── idempotent/
│   ├── Idempotent.java
│   ├── IdempotentAspect.java
│   └── IdempotentKeyBuilder.java
├── lock/
│   ├── DistributedLock.java
│   └── RedissonLock.java
├── login/
│   └── LoginRetryLimiter.java
├── ratelimit/
│   ├── LocalRateLimiter.java
│   ├── RateLimiter.java
│   ├── RateLimiterAspect.java
│   ├── RateLimiterKeyBuilder.java
│   └── RedisRateLimiter.java
├── session/
│   └── DeviceSession.java
└── util/
    └── RedisUtil.java
```

### common-security 文件清单

```
common-security/src/main/java/com/litisle/commonsecurity/
├── anonymous/
│   ├── Anonymous.java
│   ├── AnonymousAccessHandler.java
│   ├── AnonymousPathScanner.java
│   └── AnonymousProperties.java
├── audit/
│   ├── AuditLog.java
│   └── AuditLogAspect.java
├── config/
│   └── CommonSecurityAutoConfiguration.java
├── constant/
│   ├── PermissionCode.java
│   └── RoleCode.java
├── crypto/
│   ├── Aes256GcmCrypto.java
│   ├── CryptoAutoConfiguration.java
│   ├── CryptoProperties.java
│   ├── HmacSha256Crypto.java
│   └── PasswordCrypto.java
├── desensitize/
│   ├── Sensitive.java
│   ├── SensitiveSerializer.java
│   └── SensitiveStrategy.java
├── enums/
│   ├── PermissionEnum.java
│   ├── RoleCodeEnum.java
│   └── RoleLevel.java
├── feign/
│   └── FeignAuthInterceptor.java
├── satoken/
│   ├── SaTokenAutoConfiguration.java
│   ├── SaTokenEventListener.java
│   ├── SaTokenExceptionHandler.java
│   ├── SaTokenUserIdResolver.java
│   └── StpInterfaceImpl.java
├── trace/
│   └── TraceIdFilter.java
├── utils/
│   └── SaRoleHelper.java
├── xss/
│   ├── XssFilter.java
│   ├── XssHttpServletRequestWrapper.java
│   ├── XssProperties.java
│   └── XssStringJsonDeserializer.java
└── test.java
```

***

**文档版本**：v2.1
**最后更新**：2026-09-29
**维护者**：LitIsle 团队

**更新记录**：

- **v2.1（2026-09-29）**：限流组件重构
  - 新增：`RedisRateLimiter`（Lua 脚本 SSOT 抽取，切面与系统级限流共用）、`LocalRateLimiter`（本地兜底计数）、`RedisGuard`（三态熔断状态机，限流与登录重试限制共用）、`RateLimitFallbackProperties`（litisle.rate-limit.fallback 配置）
  - 更新：`RateLimiterAspect` 计数逻辑委托 `RedisRateLimiter`；`LoginRetryLimiter` 接入 `RedisGuard` 三态熔断
  - 新增：`RedisGuard` 状态切换点 Micrometer 埋点（`redis.guard.state.change`/`redis.guard.degraded`），common-redis 引 micrometer-core（provided）
- **v2.0（2026-08-17）**：**重大重构**
  - 认证框架从自研 JWT + SecurityContext 体系迁移至 Sa-Token
  - 删除：`JwtTokenProvider`、`JwtProperties`、`JwtAutoConfiguration`、`JwtConstant`（common-core）
  - 删除：`TokenBlacklistService`、`DeviceSessionManager`（common-redis）
  - 删除：`SecurityContext`、`SecurityContextHolder`、`SecurityContextFilter`、`@Login`、`@RequireRole`、`@RequirePermission`、`LoginAspect`、`RoleCheckAspect`、`PermissionCheckAspect`、`SecurityContextUserIdResolver`（common-security）
  - 新增：`SaTokenAutoConfiguration`、`StpInterfaceImpl`、`SaTokenUserIdResolver`、`SaTokenExceptionHandler`、`SaTokenEventListener`（common-security/satoken/）
  - 更新：`AuditLogAspect`、`FeignAuthInterceptor` 改用 Sa-Token `StpUtil` API
  - 更新：`CacheConstant` 删除 `TOKEN_BLACKLIST` / `USER_SESSION` 常量
  - 更新：网关配置从 `litisle.security.jwt` 迁移至顶级 `sa-token`
  - 权限校验改用 Sa-Token 原生注解（`@SaCheckLogin`、`@SaCheckRole`、`@SaCheckPermission`）
- v1.1（2026-08-12）：新增 Assert 断言、GlobalExceptionHandler 增强、HeaderConstant 完善

```

---

### 📊 变更对比

| 对比项 | 旧版 (v1.1) | 新版 (v2.0) |
|--------|-------------|-------------|
| common-core 文件数 | 17 | 18（删除 JWT 4 个 + 新增 AuditModule/Operation 等） |
| common-redis 文件数 | 15 | 16（删除 2 个 + 新增 RateLimiter 相关） |
| common-security 文件数 | 16 | 27（删除 8 个旧类 + 新增 satoken 5 个 + 枚举 3 个） |
| 认证框架 | 自研 JWT + ThreadLocal | Sa-Token 1.45.0 |
| 权限校验 | 自定义 @Login/@RequireRole | Sa-Token @SaCheckLogin/@SaCheckRole |
| 会话管理 | 自研 DeviceSessionManager | Sa-Token Session |
| Token 黑名单 | 自研 TokenBlacklistService | Sa-Token 踢人下线 |

请将上方代码块复制到 `e:\NewJavaPro\LitIsle\docs\commom-des.md`。
```

