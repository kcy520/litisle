完整的 common 模块说明文档，[commom-des.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/commom-des.md)：

```markdown
e:\NewJavaPro\LitIsle\BaseDesign-document\commom-des.md
# LitIsle Common 模块功能说明文档

> 公共基础模块合集，为业务服务提供通用能力支撑。所有业务服务依赖 common 模块，避免重复造轮子。

---

## 一、模块总览

```
litisle-common/
├── common-core          # 基础工具：异常/响应/枚举/常量/工具类/配置
├── common-redis         # Redis 能力：序列化/分布式锁/布隆过滤器/幂等性/限流/黑名单/会话管理/登录重试限制
└── common-security      # 安全控制：上下文/角色校验/审计/脱敏/XSS/匿名认证/数据加密
```

### 模块依赖关系

```
common-core  ← 所有模块都依赖
↑
├── common-redis     （依赖 common-core）
├── common-security  （依赖 common-core）
└── common-redis ❌ 不依赖 common-security
common-security ❌ 不依赖 common-redis
（通过 common-core 的 UserIdResolver 接口解耦）
```

### 核心设计原则

1. **单一职责**：每个子模块只负责一类功能（core 工具、redis 存储、security 安全）
2. **模块隔离**：common-redis 和 common-security 互不依赖，避免强制传递依赖
3. **依赖倒置（DIP）**：通过 common-core 的 `UserIdResolver` 接口解耦
4. **声明式编程**：用注解驱动（@Idempotent、@Anonymous、@Sensitive、@RequireRole）
5. **Fail-Fast**：关键配置在启动时加载，错误立即暴露

---

## 二、common-core 模块

### 2.1 异常处理

| 类 | 类型 | 说明 |
|----|------|------|
| `ErrorCode` | 枚举 | 全局错误码定义（按模块分段：10000-通用、20000-用户、30000-内容...） |

**新增 JWT 错误码**：
| 错误码 | 枚举名 | 说明 |
|--------|--------|------|
| 10008 | `TOKEN_EXPIRED` | Token 已过期，需重新登录 |
| 10009 | `TOKEN_INVALID` | Token 无效（格式错误/签名不匹配/被篡改） |
| 10010 | `TOKEN_BLACKLISTED` | Token 已被加入黑名单（用户主动退出/管理员踢人） |
| `BizException` | 运行时异常 | 业务异常，携带 ErrorCode，由全局处理器捕获 |
| `GlobalExceptionHandler` | @RestControllerAdvice | 统一异常处理，转换为 Result 响应 |
| `Assert` | 工具类 | 业务断言工具，断言失败自动抛出 BizException |

**使用方式**：自动执行。业务代码抛 `BizException`，由 `GlobalExceptionHandler` 自动捕获转 `Result`。

```java
// 方式1：直接抛异常
throw new BizException(ErrorCode.USER_NOT_FOUND);

// 方式2：使用 Assert 简化判断（推荐）
Assert.notNull(user, ErrorCode.USER_NOT_FOUND);
Assert.notNull(user, ErrorCode.USER_NOT_FOUND, "用户ID=" + userId + "不存在");
Assert.isTrue(balance >= amount, ErrorCode.INSUFFICIENT_BALANCE);
Assert.isFalse(exists, ErrorCode.DATA_EXISTS, "用户名已存在");

// Controller 无需 try-catch，统一响应：
// { "code": 20001, "message": "用户不存在", "data": null }
```

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

| 类 | 说明 |
|----|------|
| `Result<T>` | 统一响应封装，含 code/message/data |
| `PageResult<T>` | 分页响应封装，含 records/total/page/size |

**使用方式**：手动调用静态工厂方法。

```java
return Result.success(userVO);
return Result.error(ErrorCode.USER_NOT_FOUND);
return Result.success(new PageResult<>(records, total, page, size));
```

### 2.3 常量与枚举

| 类 | 说明 |
|----|------|
| `CommonConstant` | 通用常量（缓存前缀、分隔符等） |
| `HeaderConstant` | HTTP Header 常量（X-User-Id、X-Username、X-Roles、X-User-Permissions、X-Token-Id、X-Trace-Id） |
| `JwtConstant` | JWT 常量（Claims 名称、Authorization Header 名称、Bearer 前缀等） |
| `DelFlagEnum` | 逻辑删除标志（0-正常、1-删除） |
| `StatusEnum` | 状态枚举（启用/禁用） |

### 2.4 工具类

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `AssertUtil` | 业务断言工具（断言失败抛 BizException） | 手动调用 `AssertUtil.notNull(obj, ErrorCode.XXX)` |
| `JsonUtil` | JSON 序列化/反序列化工具（封装 Jackson） | 手动调用 |
| `PageUtil` | 分页工具（Page 转换） | 手动调用 |

### 2.5 配置类

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `JacksonConfig` | Jackson 全局配置（Long 转字符串防精度丢失、日期格式、XSS 反序列化器注册） | **自动执行**，业务服务引入依赖即生效 |
| `MyBatisPlusConfig` | MyBatis-Plus 配置（分页插件、自动填充、逻辑删除） | **自动执行**，业务服务引入依赖即生效 |

### 2.6 JWT 令牌提供者

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `JwtConstant` | JWT 常量（Claims 名称、Header 名称、Bearer 前缀等） | 手动引用 |
| `JwtProperties` | JWT 配置属性（密钥、有效期等），绑定 `litisle.security.jwt.*` | **自动绑定**（@ConfigurationProperties） |
| `JwtTokenProvider` | JWT 令牌提供者（签发/验证/解析令牌） | **自动注入**（由 `JwtAutoConfiguration` 创建 Bean） |
| `JwtAutoConfiguration` | JWT 自动配置类（`@ConditionalOnProperty`，仅配置 secret 时创建） | **自动执行** |

**设计说明**：
- JWT 相关类放在 `common-core` 模块，而非 `common-security`，因为网关（WebFlux）无法依赖 `common-security`（后者依赖 Servlet API）
- `JwtTokenProvider` 使用 HMAC-SHA256（HS256）对称签名算法，无额外依赖
- 网关和 `litisle-auth` 共用同一密钥，网关校验、auth 签发

**使用方式**：

```java
// ====== litisle-auth：登录成功后签发令牌 ======
@Autowired
private JwtTokenProvider jwtTokenProvider;

public TokenVO login(LoginDTO dto) {
    // 验证用户名密码...
    String token = jwtTokenProvider.createToken(userId, username, roles, permissions, rememberMe);
    return new TokenVO(token, expiresIn);
}

// ====== 网关：JwtAuthGlobalFilter 自动校验令牌 ======
// 无需手动调用，由过滤器自动完成
```

**JWT 令牌结构**：
```json
{
  "iss": "LitIsle",
  "sub": "用户ID",
  "userId": "用户ID",
  "username": "用户名",
  "roles": "ADMIN,READER",
  "permissions": "article:publish,comment:delete",
  "jti": "UUID（令牌唯一标识）",
  "iat": 签发时间,
  "exp": 过期时间
}
```

**⚠️ 注意事项**：
- 密钥必须 `≥ 256 bit（32 字符）`，启动时校验（Fail-Fast）
- 网关和 auth 服务的 `litisle.security.jwt.secret` 必须一致，否则网关校验失败
- 每次签发生成唯一 `jti`（JWT ID），用于黑名单踢人

### 2.7 依赖倒置接口

| 类 | 说明 |
|----|------|
| `UserIdResolver` | 用户ID解析器接口，解耦 common-redis 与 common-security |

**设计说明**：common-redis 的幂等切面需要 userId，但不能依赖 common-security。通过此接口定义在 common-core，common-security 提供实现，实现依赖倒置。

---

## 三、common-redis 模块

### 3.1 Redis 配置

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `RedisConfig` | RedisTemplate 配置（Jackson 序列化、Key 前缀） | **自动执行** |
| `RedissonConfig` | Redisson 客户端配置（分布式锁基础） | **自动执行** |
| `RedisUtil` | Redis 操作工具类（String/Hash/List/Set/ZSet） | 手动调用 |
| `CacheConstant` | 缓存 Key 常量前缀（含 USER_PERMISSION、RATE_LIMIT 等） | 手动引用 |

### 3.2 分布式锁

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `DistributedLock` | 分布式锁接口 | —— |
| `RedissonLock` | Redisson 实现（可重入、watchdog 自动续期） | 手动调用 `redissonLock.tryLock(key, waitTime, leaseTime, supplier)` |

**面试亮点**：
- 基于 Redisson 的 `RLock`，支持可重入
- watchdog 机制自动续期（默认 30 秒，每 10 秒续一次）
- 业务执行完自动释放，避免死锁

### 3.3 布隆过滤器

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `BloomFilter` | 布隆过滤器工具（基于 Redisson） | 手动调用 `bloomFilter.add(key, value)` / `bloomFilter.contains(key, value)` |

**使用场景**：缓存穿透防护（查询前先过滤不存在的 key）、海量数据去重。

### 3.4 接口幂等性

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `Idempotent` | 幂等注解（标注在方法上） | 声明式 |
| `IdempotentAspect` | 幂等切面（Redis SETNX 实现） | **自动执行** |
| `IdempotentKeyBuilder` | 幂等 key 构建器（userId + 路径 + 参数 MD5） | 内部使用 |

**使用方式**：声明式，加注解即可。

```java
@Idempotent(expire = 5, message = "请勿重复提交")
@PostMapping("/order/create")
public Result<Void> createOrder(@RequestBody OrderDTO dto) { ... }
```

**实现原理**：
1. 构建 key：`idempotent:{userId}:{方法路径}:{参数MD5}`
2. Redis `setIfAbsent(key, "1", ttl)` 原子操作
3. 设置成功 → 放行；设置失败 → 抛 `BizException`

**⚠️ 注意事项**：
- 幂等切面通过 `UserIdResolver`（可选注入）获取 userId
- 业务服务必须同时引入 common-security（提供 `SecurityContextUserIdResolver` 实现），否则 userId 为 "anonymous"
- TTL 应略大于业务执行时间

### 3.5 接口限流

| 类 | 说明 | 使用方式 |
|----|------|--------|
| `RateLimiter` | 限流注解（支持 USER/IP/DEFAULT 三种 key 类型） | 声明式 |
| `RateLimiterAspect` | 限流切面（Redis + Lua 脚本实现） | **自动执行** |
| `RateLimiterKeyBuilder` | 限流 key 构建器（userId/IP/方法路径） | 内部使用 |

**使用方式**：声明式，加注解即可。

```java
// 示例1：登录接口，同一用户 1 分钟最多 5 次
@RateLimiter(time = 1, timeUnit = TimeUnit.MINUTES, count = 5, keyType = RateLimiter.KeyType.USER)
@PostMapping("/login")
public Result<LoginVO> login(@RequestBody LoginDTO dto) { ... }

// 示例2：短信发送，同一 IP 1 小时最多 10 次
@RateLimiter(time = 1, timeUnit = TimeUnit.HOURS, count = 10, keyType = RateLimiter.KeyType.IP)
@PostMapping("/sms/send")
public Result<Void> sendSms(@RequestParam String phone) { ... }

// 示例3：全局接口保护，1 秒最多 100 次
@RateLimiter(time = 1, count = 100, keyType = RateLimiter.KeyType.DEFAULT)
@GetMapping("/hot/articles")
public Result<List<ArticleVO>> hotArticles() { ... }
```

**实现原理（固定窗口计数法）**：
1. 构建 key：`rate_limit:{type}:{id}:{method}`
2. Redis 执行 Lua 脚本（原子操作）：
   - `INCR key`（计数 +1）
   - 首次访问（count == 1）才 `EXPIRE key seconds`
3. 返回当前计数：
   - `count <= maxCount`：放行
   - `count > maxCount`：抛 `BizException(TOO_MANY_REQUESTS)`

**Lua 脚本**：
```lua
local count = redis.call('incr', KEYS[1])
if count == 1 then
    redis.call('expire', KEYS[1], ARGV[1])
end
return count
```

**与 @Idempotent 的区别**：
- `@Idempotent`：防止“重复提交”（TTL 内只能执行一次）
- `@RateLimiter`：防止“频繁访问”（TTL 内允许 N 次）
- 两者可叠加使用：先限流再幂等

**⚠️ 注意事项**：
- 限流切面通过 `UserIdResolver`（可选注入）获取 userId，与幂等切面一致
- IP 类型限流需 web 环境（common-redis 的 pom.xml 已添加 `spring-boot-starter-web` provided）
- 固定窗口存在边界问题（窗口切换处可能出现 2 倍流量），高精度场景可演进为滑动窗口
- Lua 脚本保证 `INCR + EXPIRE` 原子性，避免并发下 key 永久计数

### 3.6 Token 黑名单

| 类 | 说明 | 使用方式 |
|----|------|--------|
| `TokenBlacklistService` | Token 黑名单服务（Redis SET + EXISTS） | **手动调用** |

**使用方式**：手动调用，由 litisle-auth（写入）与网关（校验）配合使用。

**Redis 数据结构**：

```
Key   ：auth:token:blacklist:{tokenId}（CacheConstant.TOKEN_BLACKLIST）
Value ："1"（占位标记）
TTL   ：JWT 剩余有效期（exp - now），过期自动回收，零运维
```

**核心机制**：

```
├── 退出登录：logout() → addToBlacklist(tokenId, ttl) → Token 立即失效
├── 踢人下线：DeviceSessionManager.doKick() → 联动加黑
├── 网关校验：JwtAuthGlobalFilter → isBlacklisted(tokenId) → 命中返回 401
└── 刷新令牌：refreshToken() → 旧 Token 加黑
```

**调用示例**：

```java
// AuthService.logout() 中：黑名单 TTL = Token 剩余有效期
String tokenId = jwtTokenProvider.getTokenIdFromToken(token);
long ttlSeconds = (expiration.getTime() - System.currentTimeMillis()) / 1000;
if (ttlSeconds > 0) {
    tokenBlacklistService.addToBlacklist(tokenId, ttlSeconds);
}

// 网关 JwtAuthGlobalFilter 中：解析 JWT 后校验黑名单
if (tokenBlacklistService.isBlacklisted(claims.getId())) {
    return unauthorized(exchange, ErrorCode.TOKEN_BLACKLISTED);
}
```

**实现原理**：
1. 加入黑名单：`SET auth:token:blacklist:{jti} "1" EX {剩余秒数}`
2. 网关校验：`EXISTS`（O(1) 毫秒级）
3. TTL 随 Token 剩余寿命同步衰减：Token 过期后黑名单条目自动消失，无需定时清理

**读写容错策略（非对称设计）**：
- 读（isBlacklisted）：Redis 异常 → 放行（可用性优先，宁可放行一个已拉黑 Token 也不让全站瘫痪，JWT 过期时间兜底）
- 写（addToBlacklist）：Redis 异常 → 抛 BizException（吊销失败必须可见，不能静默丢失）

**⚠️ 注意事项**：
- 网关为 WebFlux 环境，阻塞式 StringRedisTemplate 建议包在 `publishOn(boundedElastic)` 中调用，或后续演进为 ReactiveStringRedisTemplate
- 加入前校验 tokenId 非空、ttl > 0（已过期 Token 无需拉黑）

### 3.7 设备会话管理

| 类 | 说明 | 使用方式 |
|----|------|--------|
| `DeviceSession` | 设备会话 DTO（tokenId/设备/IP/登录时间/Token 过期时间） | 内部使用 |
| `DeviceSessionManager` | 会话管理器（多设备并发限制 + 踢人下线） | **手动调用** |

**使用方式**：手动调用，由 litisle-auth 在登录/退出/踢人流程中调用。

**Redis 数据结构**：

```
Key   ：auth:session:{userId}（CacheConstant.USER_SESSION）
类型  ：Hash
field ：tokenId（JWT 的 jti）
value ：DeviceSession 序列化后的 JSON
```

**核心机制**：

```
├── 懒清理：Hash 不支持 field 级 TTL，读取时按 tokenExpireTime 过滤并 HDEL
├── 多设备限制：超限时按 loginTime 升序挤下线最早设备（自动加黑名单）
├── 原子性：Redisson 用户级锁 lock:device_session:{userId}（单用户粒度）
└── 协作：踢人 = HDEL 会话 + TokenBlacklistService 加黑（两步缺一不可）
```

**调用示例**：

```java
// 1. 登录成功后注册会话（AuthService），返回被挤下线的设备列表
DeviceSession session = DeviceSession.builder()
        .tokenId(tokenId)
        .deviceType("WEB")
        .deviceName(parseUserAgent(request))
        .ip(clientIp)
        .loginTime(System.currentTimeMillis())
        .tokenExpireTime(expireTime)
        .build();
List<DeviceSession> kicked = deviceSessionManager.registerSession(userId, session, 2);

// 2. 退出登录：仅移除会话（Token 是否加黑由 logout 自行决定）
deviceSessionManager.removeSession(userId, tokenId);

// 3. 修改密码 / 管理员封禁：全端强制下线（逐个加黑）
deviceSessionManager.kickAllSessions(userId);
```

**API 一览**：

| 方法 | 场景 | 黑名单联动 |
|------|------|----------|
| `registerSession(userId, session, maxDevices)` | 登录成功 | ✅ 被挤设备加黑 |
| `removeSession(userId, tokenId)` | 退出登录 | ❌ 调用方决定 |
| `getOnlineSessions(userId)` | “在线设备管理”页 | — |
| `getOnlineSessionCount(userId)` | 在线设备计数 | — |
| `kickSession(userId, tokenId)` | 踢指定设备 | ✅ |
| `kickAllSessions(userId)` | 改密/封禁全端下线 | ✅ |

**关键设计说明**：
1. **踢人必须两步**：只删会话，被踢 Token 在自然过期前仍能通过网关（网关只查黑名单）；只加黑名单，会话列表残留脏数据。故 `doKick()` 封装 HDEL + 加黑
2. **懒清理**：Redis Hash 无 field 级 TTL 的标准补偿方案；所有 field 删除后 Hash key 自动回收
3. **锁粒度**：锁到单用户而非全局，多用户完全并行，兼顾正确性与吞吐
4. **可选依赖降级**：DistributedLock 经 ObjectProvider 注入，Redisson 不可用时无锁执行，仅损失多设备计数精度（与 IdempotentAspect 处理 UserIdResolver 方式一致）
5. **时间字段用 Long 时间戳**：规避 LocalDateTime 对 JavaTimeModule 的序列化依赖，排序直接比 long

**⚠️ 注意事项**：
- maxDevices 由 litisle-auth 配置传入（如 `auth.max-devices: 2`），common 层不感知业务配置
- 获取锁超时（5s）抛 BizException，提示稍后重试

---

### 3.8 登录重试限制

| 类 | 说明 | 使用方式 |
|-----|------|--------|
| `LoginRetryLimiter` | 登录重试限制器（暴力破解防护） | **手动调用** |

**使用方式**：手动调用，由 litisle-auth 在登录校验流程中调用。

**Redis 数据结构**：

```
┌─────────────────────────────────────────────────────┐
│ Key：login:fail:{identifier}                        │
│ 类型：String  值：连续失败次数（整数）    TTL：15min  │
├─────────────────────────────────────────────────────┤
│ Key：login:lock:{identifier}                        │
│ 类型：String  值："1"（仅占位标记）    TTL：15min    │
└─────────────────────────────────────────────────────┘
```

**核心机制**：

```
├── 原子递增：INCR 原子操作，首次失败自动设置 TTL（防止计数永不过期）
├── 双 Key 结构：login:fail → 计数，login:lock → 锁定标记（职责分离）
├── 自动解锁：锁定 key 到期后 Redis 自动删除，无需定时任务
└── 容错降级：Redis 异常时放行，不因缓存故障导致全站无法登录
```

**调用示例**：

```java
// 1. 登录前检查账户是否锁定
if (loginRetryLimiter.isLocked(username)) {
    long remainingSeconds = loginRetryLimiter.getLockRemainingSeconds(username);
    throw new BizException(ErrorCode.ACCOUNT_LOCKED,
            "账户已锁定，请 " + remainingSeconds + " 秒后重试");
}

// 2. 密码校验失败时记录失败次数
loginRetryLimiter.recordFailure(username);
int remaining = loginRetryLimiter.getRemainingAttempts(username);
// 返回给前端："密码错误，您还有 " + remaining + " 次尝试机会"

// 3. 登录成功后重置所有失败记录
loginRetryLimiter.resetFailure(username);
```

**API 一览**：

| 方法 | 场景 | 说明 |
|------|------|------|
| `recordFailure(identifier)` | 登录失败 | 递增计数，超限自动锁定（默认 5 次失败锁定 15 分钟） |
| `recordFailure(identifier, maxAttempts, lockDuration)` | 自定义阈值 | 可覆盖默认配置 |
| `isLocked(identifier)` | 登录前检查 | 锁定返回 true（Redis 异常容错返回 false） |
| `resetFailure(identifier)` | 登录成功 | 清除失败计数和锁定标记 |
| `getRemainingAttempts(identifier)` | 前端提示 | 返回剩余尝试次数（≥0） |
| `getLockRemainingSeconds(identifier)` | 锁定提示 | 返回剩余锁定秒数（未锁定返回 0） |
| `unlockAccount(identifier)` | 管理员手动解锁 | 清除锁定标记和失败计数 |

**关键设计说明**：
1. **INCR + EXPIRE 组合**：首次失败时设 TTL = 15 分钟，每次失败刷新 TTL，防止活跃攻击者计数永不过期
2. **双 Key 职责分离**：`login:fail` 仅计数，`login:lock` 仅标记锁定。查看锁定状态只需 EXISTS 一个 key（O(1)），无需 GET 计数再比较
3. **容错策略**：Redis 异常时全部降级放行，写 ERROR 日志——宁可让暴力破解者多试几次，也不能让全站用户无法登录
4. **identifier 灵活**：支持 username、phone、email 三种标识，由 litisle-auth 在调用时决定用哪种
5. **无锁设计**：INCR 本身就是原子操作，无需额外分布式锁；锁定检查不要求精确到毫秒，业务上可接受微小竞态

**⚠️ 注意事项**：
- maxAttempts 和 lockDurationSeconds 由 litisle-auth 配置传入（如 `auth.login.max-attempts: 5`），common 层不感知业务配置
- 修改密码成功后应调用 `resetFailure()` 清除旧密码的失败记录
- 管理员手动解锁后，用户应能立即重试（无需等待 TTL 到期）

### 4.1 安全上下文

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `SecurityContext` | 安全上下文（userId、username、roles、permissions、tokenId、traceId、authenticated） | 内部使用 |
| `SecurityContextHolder` | ThreadLocal 上下文持有者 | 手动调用 `getUserId()` / `getRoles()` / `getPermissions()` / `getTraceId()` / `getTokenId()` |
| `SecurityContextFilter` | Servlet Filter，从网关注入的 Header 读取用户信息，构建 SecurityContext 并写入 ThreadLocal | **自动执行**（`@Order(HIGHEST_PRECEDENCE + 10)`） |

**使用方式**：业务代码通过 `SecurityContextHolder` 获取当前登录用户。

```java
Long userId = SecurityContextHolder.getUserId();
Set<String> roles = SecurityContextHolder.getRoles();
String traceId = SecurityContextHolder.getTraceId();
String tokenId = SecurityContextHolder.getTokenId();
```

**SecurityContextFilter 工作流程**：
```
网关校验 JWT → 注入 Header（X-User-Id, X-Roles, ...）
  → 业务服务接收请求
    → SecurityContextFilter（@Order 最高优先级）
      ├─ Header 有用户信息 → 构建认证 SecurityContext（userId=xxx, roles=[ADMIN]）
      ├─ Header 无用户信息 → 构建匿名 SecurityContext（userId=null, roles=[ANONYMOUS]）
      └─ 写入 SecurityContextHolder（ThreadLocal）
        → 业务代码通过 SecurityContextHolder.getContext() 获取
          → finally 块中 SecurityContextHolder.clear() 清理 ThreadLocal
```

**⚠️ 注意事项**：
- 基于 ThreadLocal，Filter 的 `finally` 块中自动清理，无需业务代码关心
- 网关已校验 JWT，业务服务从 Header 读取用户信息构建上下文
- 匿名请求也构建上下文（`roles=[ANONYMOUS]`），业务代码可通过 `isAuthenticated()` 判断
- `tokenId` 用于踢人/黑名单场景，`traceId` 用于链路追踪

### 4.2 登录与权限校验

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `Login` | 登录校验注解（方法/类级别，仅需登录无需角色） | 声明式 |
| `RequireRole` | 角色校验注解（支持 AND/OR 逻辑） | 声明式 |
| `RequirePermission` | 权限校验注解（细粒度，如 user:delete） | 声明式 |
| `LoginAspect` | 登录校验切面（支持 @Anonymous 跳过） | **自动执行** |
| `RoleCheckAspect` | 角色校验切面 | **自动执行** |
| `PermissionCheckAspect` | 权限校验切面（细粒度权限） | **自动执行** |

**使用方式**：声明式。

```java
// 示例1：仅需登录（获取个人信息）
@Login
@GetMapping("/user/profile")
public Result<UserVO> getProfile() { ... }

// 示例2：类级登录校验（整个 Controller 都需登录）
@Login
@RestController
@RequestMapping("/api/user")
public class UserController { ... }

// 示例3：角色校验（ADMIN 或 EDITOR 可发布文章）
@RequireRole(value = {"ADMIN", "EDITOR"}, logical = Logical.OR)
@PostMapping("/article/publish")
public Result<Void> publish(@RequestBody ArticleDTO dto) { ... }

// 示例4：细粒度权限校验（需要 user:delete 权限）
@RequirePermission("user:delete")
@DeleteMapping("/user/{id}")
public Result<Void> deleteUser(@PathVariable Long id) { ... }
```

**⚠️ 注意事项**：
- `@Login`、`@RequireRole`、`@RequirePermission` 均依赖 `SecurityContextHolder` 中的上下文，业务服务必须在 Filter 中从 Header 解析用户信息并写入上下文
- `@RequirePermission` 需要 JWT 中携带 `permissions` 字段，由 litisle-auth 服务的 JwtAuthenticationFilter 解析后写入 `SecurityContext.permissions`
- 方法标注 `@Anonymous` 时，`@Login` 切面会跳过校验（匿名访问由网关白名单控制）
- 角色与权限的区别：角色是粗粒度（ADMIN），权限是细粒度（user:delete），一个角色可拥有多个权限

### 4.3 Feign 拦截器

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `FeignAuthInterceptor` | Feign 请求拦截器（透传 X-User-Id、X-Username、X-Roles、X-User-Permissions、X-Token-Id、X-Trace-Id） | **自动执行** |

**作用**：微服务间 Feign 调用时，自动透传用户上下文和链路追踪 ID。

**透传的 Header 列表**：
- `X-User-Id`：用户 ID
- `X-Username`：用户名
- `X-Roles`：角色列表（逗号分隔）
- `X-User-Permissions`：权限列表（逗号分隔）
- `X-Token-Id`：Token 唯一标识
- `X-Trace-Id`：链路追踪 ID

### 4.4 审计日志

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `AuditLog` | 审计日志注解（配置模块、操作名、是否记录参数等） | 声明式 |
| `AuditLogAspect` | 审计日志切面（@Around 环绕通知） | **自动执行** |

**使用方式**：声明式。

```java
@AuditLog(module = "文章管理", operation = "发布文章", saveRequestData = true)
@PostMapping("/article/publish")
public Result<Void> publish(@RequestBody ArticleDTO dto) { ... }
```

**⚠️ 注意事项**：当前审计日志通过 `log.info` 输出，生产环境建议改为 MQ 异步入库。

### 4.5 数据脱敏

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `Sensitive` | 脱敏注解（标注在 VO 字段上） | 声明式 |
| `SensitiveStrategy` | 脱敏策略枚举（PHONE/ID_CARD/EMAIL/BANK_CARD/NAME/ADDRESS） | 引用 |
| `SensitiveSerializer` | Jackson 自定义序列化器 | **自动执行** |

**使用方式**：声明式，标注在 VO 字段上，响应 JSON 时自动脱敏。

```java
public class UserVO {
    @Sensitive(strategy = SensitiveStrategy.PHONE)
    private String phone;        // 138****8888

    @Sensitive(strategy = SensitiveStrategy.EMAIL)
    private String email;        // ex***@xx.com
}
```

### 4.6 XSS 防护

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `XssFilter` | Servlet Filter（包装请求，清洗表单参数） | **自动执行** |
| `XssHttpServletRequestWrapper` | 请求包装器（清洗 HTML 标签） | 内部使用 |
| `XssStringJsonDeserializer` | Jackson 反序列化器（清洗 JSON 请求体） | **自动执行**（需 JacksonConfig 注册） |
| `XssProperties` | XSS 配置类（enabled、exclude-paths） | yml 配置 |

**使用方式**：**自动执行**，业务服务引入依赖即生效。富文本接口需在 yml 排除。

```yaml
# 业务服务的 application.yml
litisle:
  xss:
    enabled: true
    exclude-paths:
      - /api/content/article/publish   # 富文本接口不清洗
```

**⚠️ 注意事项**：
- 当前实现是"清除 HTML 标签"，富文本接口会被破坏，必须配置 exclude-paths
- 富文本接口建议后续用 Jsoup 白名单清洗（保留安全标签，移除 `<script>`）

### 4.7 匿名认证

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `Anonymous` | 匿名访问注解（标注在 Controller 方法或类上） | 声明式 |
| `AnonymousProperties` | 匿名访问 yml 配置（paths 白名单兜底） | yml 配置 |
| `AnonymousAccessHandler` | 白名单匹配 + 匿名上下文构建 | 手动调用（由业务 Filter） |
| `AnonymousPathScanner` | 注解路径扫描器（启动时扫描 @Anonymous） | **自动执行** |

**使用方式**：声明式，加注解即可。

```java
@Anonymous  // 加注解，自动加入白名单
@GetMapping("/article/{id}")
public Result<ArticleVO> getArticle(@PathVariable Long id) { ... }
```

**工作流程**：
1. 业务服务启动 → `AnonymousPathScanner` 扫描 `@Anonymous` 注解
2. 合并 yml 兜底白名单（`AnonymousProperties.paths`）
3. 业务服务在 Filter 中调用 `AnonymousAccessHandler.isAnonymous(path)`
4. 命中 → 构建 `SecurityContext`（userId=null, roles=[ANONYMOUS]）

**⚠️ 注意事项**：
- 网关（WebFlux）无法用扫描器，当前用 yml 配置白名单
- 后续优化：业务服务暴露 `/actuator/anonymous-paths` 端点，网关 Feign 收集

### 4.8 数据加密

#### 4.8.1 AES-256-GCM 对称加密（敏感字段存储加密）

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `Aes256GcmCrypto` | AES-256-GCM 加密工具类 | 手动调用 |
| `CryptoProperties` | 加密配置类（密钥配置） | **自动绑定**（@ConfigurationProperties） |
| `CryptoAutoConfiguration` | 加密自动配置类 | **自动执行** |

**算法说明**：
- **AES-256-GCM**：分组加密算法 + 认证标签，提供**保密性+完整性+真实性**三重保护
- **GCM 模式**：Galois/Counter Mode，支持并行计算，性能优于 CBC 模式，自带认证标签防篡改
- **密钥长度**：256-bit（32 字节），符合当前安全标准
- **随机 IV**：每次加密生成 12 字节随机 IV（NIST 推荐）
- **认证标签**：16 字节，确保密文未被篡改
- **输出格式**：`Base64(IV + 密文 + 认证标签)`，便于数据库存储

**适用场景**：
- 用户手机号（phone）加密存储
- 用户邮箱（email）加密存储
- 其他需要**可逆解密**的敏感字段加密

**依赖版本兼容性**：
- Spring Boot 3.5.x + JDK 25：使用标准 `javax.crypto` API（JDK 原生支持，无需额外依赖）
- 无需引入第三方加密库，JDK 标准 API 已支持 AES-256-GCM

**配置方式**：
```yaml
# 业务服务的 application.yml 或 Nacos 配置
litisle:
  security:
    crypto:
      # AES-256 密钥（必须 32 字符 = 256 bit）
      # 生产环境通过环境变量或配置中心加密注入，禁止硬编码
      key: ${LITISLE_CRYPTO_KEY:your-32-character-aes-256-key-here}
```

**使用示例**：

```java
@Autowired
private Aes256GcmCrypto crypto;

// 加密存储（用户注册时）
String plainPhone = "13812345678";
String encryptedPhone = crypto.encrypt(plainPhone);
// 结果：Base64 字符串，存储到数据库

// 解密查询（用户登录时）
String storedPhone = encryptedPhone;
String decryptedPhone = crypto.decrypt(storedPhone);
// 结果：13812345678

// 判断加密后的内容是否匹配（手机号登录比对）
String inputPhone = "13812345678";
String storedEncryptedPhone = "...";
boolean matches = crypto.matches(inputPhone, storedEncryptedPhone);
```

**安全设计要点**：
| 设计点 | 说明 |
|--------|------|
| 随机 IV | 相同明文每次加密得到不同密文，防止字典攻击 |
| GCM 认证标签 | 密文被篡改后解密立即失败，不返回明文 |
| 密钥长度 | 256-bit 对抗量子计算攻击比 128-bit 更安全 |
| 密钥管理 | 配置中心集中管理，支持热更新，不硬编码 |
| 异常处理 | 解密失败抛出 `BizException(ENCRYPTION_ERROR)`，Fail-Fast 设计 |

**为什么不用 ECB/CBC 模式**：
- ECB：不安全，相同明文块生成相同密文块，模式本身有缺陷
- CBC：需要填充（PKCS#5/PKCS#7），易受 Padding Oracle 攻击，无完整性保护
- GCM：AEAD 算法（Authenticated Encryption with Associated Data），一次完成加密+认证

#### 4.8.2 BCrypt 密码哈希（用户密码不可逆加密）

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `PasswordCrypto` | BCrypt 封装工具类 | 手动调用 |

**算法说明**：
- **BCrypt**：基于 Blowfish 算法的自适应密码哈希函数
- **自适应**：cost 因子可调，随着硬件性能提升可增加计算成本
- **自动加盐**：每个密码自动生成随机盐，存在哈希结果中（无需单独存储盐）
- **输出格式**：`$2a$10$...`，包含版本、cost、盐、哈希

**适用场景**：
- 用户登录密码（不可逆加密，只验不解密）

**依赖说明**：
- Spring Boot 3.5.x 使用 `org.springframework.security:spring-security-crypto`
- 不需要完整的 Spring Security，仅引入 `spring-security-crypto` 模块
- 版本与 Spring Boot 一致（由父 pom 管理），无版本冲突

**使用示例**：

```java
@Autowired
private PasswordCrypto passwordCrypto;

// 用户注册：加密密码
String rawPassword = "user123456";
String encodedPassword = passwordCrypto.encode(rawPassword);
// 结果示例：$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH
// 存储到数据库

// 用户登录：验证密码
String rawPassword = "user123456";
String storedEncodedPassword = "...";
boolean matches = passwordCrypto.matches(rawPassword, storedEncodedPassword);
if (!matches) {
    throw new BizException(ErrorCode.USER_PASSWORD_ERROR);
}
```

**安全设计要点**：
| 设计点 | 说明 |
|--------|------|
| 自动加盐 | 每个密码随机盐，相同密码得到不同哈希，防止彩虹表攻击 |
| 自适应 cost | 可随硬件升级提高计算成本，保持哈希慢生成特性 |
| 不可逆 | 无法从哈希还原出明文，即使数据库泄露也无法直接得到密码 |
| 防时序攻击 | BCrypt 实现内置恒定时间比较，防止时序攻击 |

**密码加密 vs 字段加密对比**：

| 对比项 | AES-256-GCM（手机号/邮箱） | BCrypt（密码） |
|--------|---------------------------|----------------|
| 可逆性 | ✅ 可逆（需要时可解密） | ❌ 不可逆（只验不解密） |
| 使用场景 | 需要查询展示的敏感字段 | 只验证不展示的密码 |
| 算法目的 | 加密存储，防拖库泄露 | 密码哈希，防止明文存储 |
| 密钥要求 | 需要保密密钥 | 不需要密钥（盐自动生成） |
| 性能 | 快（加密算法） | 慢（故意设计慢，防暴力破解） |

**业务使用建议**：
```
用户表 (user)
├── id                  (PK)
├── username            明文（用户名校验登录）
├── phone               AES-256-GCM 加密（需要发短信时解密）
├── email               AES-256-GCM 加密（需要发邮件时解密）
├── password_hash       BCrypt 哈希（验证密码，无法还原）
└── ...
```

### 4.9 依赖倒置实现

| 类 | 说明 |
|----|------|
| `SecurityContextUserIdResolver` | `UserIdResolver` 实现，从 `SecurityContextHolder` 获取 userId |

**作用**：为 common-redis 的幂等切面提供 userId 来源，实现 common-redis 与 common-security 解耦。

---

## 五、网关安全模块（litisle-gateway）

### 5.1 网关 JWT 认证过滤器

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `JwtAuthGlobalFilter` | Spring Cloud Gateway GlobalFilter，拦截所有请求进行 JWT 校验 | **自动执行**（`@Order(-200)`，最高优先级） |
| `GatewayJwtProperties` | 网关 JWT 配置属性，绑定 `litisle.security.anonymous.*` | **自动绑定** |
| `GatewayFilterConfig` | 网关过滤器配置，将匿名路径列表暴露为 Bean | **自动执行** |

**设计说明**：
- 网关是微服务架构的**统一入口**，所有请求首先经过网关
- `JwtAuthGlobalFilter` 在所有路由转发之前执行（`@Order(-200)`）
- 使用 `JwtTokenProvider`（来自 common-core）校验令牌，不重复实现 JWT 解析逻辑
- 白名单路径跳过 JWT 校验，由 `GatewayJwtProperties` 从 yml 读取

**执行流程**：
```
客户端请求 → JwtAuthGlobalFilter
  ├─ 白名单路径？ → 是 → 放行（追加 traceId）
  ├─ 否 → 提取 Authorization: Bearer xxx
  │   ├─ 无 Token → 返回 401 {"code":10002,"message":"缺少认证令牌"}
  │   └─ 解析 Token（JwtTokenProvider.parseToken）
  │       ├─ 解析失败 → 返回 401（TOKEN_EXPIRED / TOKEN_INVALID）
  │       └─ 解析成功 → 注入 Header（X-User-Id, X-Roles, ...）→ 放行
```

**注入下游 Header 列表**：
| Header | 来源 | 说明 |
|--------|------|------|
| `X-User-Id` | JWT Claims `userId` | 用户ID |
| `X-Username` | JWT Claims `username` | 用户名 |
| `X-Roles` | JWT Claims `roles` | 角色列表（逗号分隔） |
| `X-User-Permissions` | JWT Claims `permissions` | 权限列表（逗号分隔） |
| `X-Token-Id` | JWT `jti` | 令牌唯一标识 |
| `X-Trace-Id` | 请求头或新生成 | 链路追踪ID |

**网关 application.yml 配置示例**：
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: litisle-auth
          uri: lb://litisle-auth
          predicates:
            - Path=/api/auth/**
        # ... 其他路由

litisle:
  security:
    jwt:
      secret: ${JWT_SECRET:your-256-bit-secret-key-here}
      access-token-expire: 1800
      remember-me-expire: 604800
    anonymous:
      paths:
        - /api/auth/login
        - /api/auth/register
        - /api/auth/captcha
        - /actuator/**
```

**⚠️ 注意事项**：
- 网关使用 WebFlux（响应式），不依赖 Servlet API
- 匿名白名单通过 yml 配置，网关无法使用 `@Anonymous` 注解扫描（依赖 Spring MVC）
- 网关和 auth 服务的 `litisle.security.jwt.secret` 必须一致

---

## 六、OAuth2 第三方登录（litisle-auth）

> OAuth2 第三方登录是 litisle-auth 模块的子功能，采用**预留骨架 + 策略模式**设计，当前为骨架状态（代码已注释），需完成前置条件后方可启用。

### 6.1 整体架构

```
┌──────────────┐     Feign      ┌───────────────────┐
│ litisle-auth  │─────────────▶│  litisle-user      │
│  (OAuth 入口)  │               │  (OAuth 绑定存储)   │
│              │               │                   │
│  OAuth2Controller  ─── 路由    │  OAuthBinding      │
│  OAuth2LoginService ── 编排    │  (实体 / Mapper /   │
│              │               │   Service / Impl)   │
│  JustAuth ─── 第三方 SDK       │                   │
│  (微信/QQ/GitHub)            │  sys_oauth_binding  │
└──────────────┘               │  表（物理删除 + 审计）│
                                └───────────────────┘
```

### 6.2 代码分布

#### litisle-auth 模块（OAuth 入口）

| 文件 | 路径 | 状态 | 说明 |
|------|------|------|------|
| `OAuth2Controller` | controller/OAuth2Controller.java | **全部注释** | 暴露 4 个接口：获取授权地址 / 回调 / 绑定 / 解绑 |
| `OAuth2LoginService` | service/oauth2/OAuth2LoginService.java | **骨架（方法体全部 TODO）** | 核心编排逻辑：state 防 CSRF / 换取 token / 查询用户 / 签发 JWT |

**OAuth2Controller 接口一览**：

| 方法 | 路由 | 说明 | 认证要求 |
|------|------|------|---------|
| `authorizeUrl()` | `GET /v1/auth/oauth2/{provider}/authorize-url` | 获取第三方授权地址 | 无 |
| `callback()` | `GET /v1/auth/oauth2/{provider}/callback` | 第三方登录回调 | 无 |
| `bind()` | `POST /v1/auth/oauth2/bind` | 绑定第三方账号到当前用户 | 需登录 |
| `unbind()` | `DELETE /v1/auth/oauth2/unbind/{provider}` | 解绑第三方账号 | 需登录 |

#### litisle-api 模块（共享 DTO/VO/Feign）

| 文件 | 说明 |
|------|------|
| `OAuthAuthorizeVO` | 授权地址响应 VO（authorizeUrl + provider + state） |
| `OAuthBindDTO` | 绑定请求 DTO（provider + openId + unionId + authCode） |
| `UserClient` | Feign 客户端（预留 4 个 OAuth 接口，当前已注释） |

#### litisle-user 模块（OAuth 绑定存储）

| 文件 | 说明 |
|------|------|
| `OAuthBinding` | 实体类，映射 `sys_oauth_binding` 表（物理删除，@AuditLog 记录审计） |
| `OAuthBindingMapper` | MyBatis-Plus Mapper |
| `OAuthBindingService` | 接口，继承 IService |
| `OAuthBindingServiceImpl` | 实现类（当前为空，仅自动注入 Mapper） |

#### 数据库表 `sys_oauth_binding`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT (PK) | 主键 |
| `user_id` | BIGINT | 用户 ID |
| `provider` | VARCHAR(16) | OAuth 提供方（WECHAT / QQ） |
| `app_id` | VARCHAR(32) | 应用 ID（预留多应用扩展） |
| `open_id` | VARCHAR(64) | 第三方用户唯一标识 |
| `union_id` | VARCHAR(64) | 微信 unionId（可选，跨应用识别） |
| `create_time` | DATETIME | 绑定时间 |
| `update_time` | DATETIME | 更新时间 |
| UK | (provider, app_id, open_id) | 唯一约束，防止重复绑定 |

### 6.3 核心流程

**OAuth2 第三方登录完整流程**：

```
┌──────────┐         ┌──────────────┐         ┌─────────────────┐
│  前端     │         │  litisle-auth │         │ 第三方平台(微信)  │
│  (浏览器)  │         │  (OAuth2服务)  │         │  (OAuth2 Provider)│
└────┬─────┘         └──────┬───────┘         └────────┬────────┘
     │                      │                          │
     │ ① GET /authorize-url │                          │
     │─────────────────────▶│                          │
     │                      │                          │
     │ ② 返回 authorizeUrl  │                          │
     │◀─────────────────────│                          │
     │                      │                          │
     │ ③ 重定向到第三方登录页  │                          │
     │─────────────────────────────────────────────────▶│
     │                      │                          │
     │ ④ 用户授权           │                          │
     │◀─────────────────────────────────────────────────│
     │                      │                          │
     │ ⑤ 回调 callback     │                          │
     │  (GET /callback)     │                          │
     │─────────────────────▶│                          │
     │                      │ ⑥ 用 authCode 换取 token  │
     │                      │─────────────────────────▶│
     │                      │ ⑦ 返回 accessToken+openId│
     │                      │◀─────────────────────────│
     │                      │                          │
     │                      │ ⑧ 查询或创建用户         │
     │                      │  (Feign → litisle-user)  │
     │                      │                          │
     │                      │ ⑨ 签发 JWT Token         │
     │                      │  (JwtTokenProvider)      │
     │                      │                          │
     │ ⑩ 返回 TokenVO      │                          │
     │◀─────────────────────│                          │
     │                      │                          │
```

**流程说明**：
1. 前端请求获取授权地址 → 后端生成随机 state 并缓存到 Redis（5 分钟有效）
2. 后端返回第三方登录页面的 URL（含 state 参数）
3. 前端 redirect 到第三方登录页
4. 用户在第三方平台完成授权
5. 第三方回调到后端 callback 接口，携带 authCode + state
6. 后端校验 state（防止 CSRF），用 authCode 换取 accessToken
7. 使用 accessToken 获取用户信息（openId、昵称、头像等）
8. 查询 sys_oauth_binding 表：openId 已绑定 → 直接登录；未绑定 → 创建新用户或要求绑定已有账号
9. 使用 JwtTokenProvider 签发 JWT 双 Token
10. 返回 TokenVO 给前端，完成登录

### 6.4 安全设计

| 设计点 | 说明 |
|--------|------|
| **state 防 CSRF** | 生成随机 UUID 作为 state，缓存到 Redis（5 分钟 TTL），回调时校验并一次性删除 |
| **JustAuth SDK** | 封装 20+ 第三方 OAuth2 平台，统一 AuthRequest 接口，屏蔽平台差异 |
| **物理删除 + 审计** | 解绑时直接 DELETE，依赖 @AuditLog 切面记录操作日志，不保留历史 |
| **唯一约束** | (provider, app_id, open_id) 联合唯一索引，防止重复绑定 |
| **策略模式** | provider 参数路由到不同第三方平台，扩展新平台只需新增 provider 枚举 |

### 6.5 后续开发注意事项

#### ⚠️ 前置条件（必须完成）

1. **解除 OAuth2Controller 和 OAuth2LoginService 的注释**
   - 当前两个文件所有代码均被注释，仅保留骨架
   - 解除注释后，确保 `@Service`、`@RestController` 等注解生效

2. **实现 UserClient 中 OAuth 相关 Feign 接口**
   - 需在 UserClient 中取消注释以下 4 个接口：
     - `findByOAuth(provider, openId)` → 根据第三方标识查询已绑定的用户
     - `createUserByOAuth(OAuthUserDTO)` → 根据第三方信息创建新用户（自动注册）
     - `bindOAuth(userId, provider, openId, unionId)` → 绑定第三方账号
     - `unbindOAuth(userId, provider)` → 解绑第三方账号
   - 需在 litisle-user 模块实现对应的 Controller 和 Service 逻辑

3. **配置第三方应用信息**
   - 在 litisle-auth 的 `application.yml` 中添加 JustAuth 配置：
   ```yaml
   justauth:
     enabled: true
     type:
       WECHAT_OPEN:
         client-id: ${WECHAT_APP_ID}
         client-secret: ${WECHAT_APP_SECRET}
         redirect-uri: https://your-domain.com/v1/auth/oauth2/WECHAT_OPEN/callback
       QQ:
         client-id: ${QQ_APP_ID}
         client-secret: ${QQ_APP_KEY}
         redirect-uri: https://your-domain.com/v1/auth/oauth2/QQ/callback
   ```

4. **确认 sys_oauth_binding 表已初始化**
   - 检查初始化 SQL 中是否包含 `sys_oauth_binding` 表的建表语句
   - 已有初始化数据时需确认唯一索引正确

#### ⚠️ 建议关注的设计点

5. **OAuth2 登录后的设备会话管理**
   - 回调成功后，应调用 `DeviceSessionManager.registerSession()` 注册设备会话
   - 当前 OAuth2LoginService 中已预留 TODO 注释

6. **第三方用户信息映射**
   - 需要创建 `OAuthUserDTO`（或复用现有 DTO）承载第三方用户信息
   - 映射关系：openId → 新用户账号，nickname → 用户昵称，avatar → 头像

7. **绑定已有账号的用户体验**
   - 未绑定 openId 时，有两种策略：
     a. 自动创建新用户（静默注册，推荐）
     b. 返回临时 token，前端引导用户`OAuthUserDTO`（或复用现有 DTO）承载第三方用户信息\n   - 映射关系：openId → 新用户账号，nickname → 用户昵称，avatar → 头像\n\n7. **绑定已有账号的用户体验**\n   - 未绑定 openId 时，有两种策略：\n     a. 自动创建新用户（静默注册，推荐）\n     b. 返回临时 token，前端引导用户选择"绑定已有账号"或"注册新账号"\n   - 当前骨架采用策略 a（回调中直接创建用户），但预留了 bind() 方法支持策略 b\n\n8. **JustAuth 版本兼容性**\n   - 当前引入 JustAuth 1.16.6，需确认与 Spring Boot 3.5.x + JDK 25 的兼容性\n   - 如遇兼容性问题，可考虑升级 JustAuth 版本或自行实现 OAuth2 流程\n\n---\n\n## 七、配置文件说明\n\n### 7.1 common 模块的 yml 文件"}]

**重要**：common 模块的 yml 文件**已全部注释**，仅作配置参考模板。

**原因**（企业规范）：
- common 是公共库（jar），不是独立 Spring Boot 应用
- yml 配置应由业务服务或 Nacos 提供，避免配置冲突
- 公共库只提供"代码能力和默认值"（通过 @ConfigurationProperties 字段默认值）

### 7.2 业务服务必须配置的项

业务服务（如 litisle-content、litisle-auth）**必须**在自己的 `application.yml` 配置：

```yaml
# ====== 匿名访问白名单兜底（common-security） ======
litisle:
  security:
    anonymous:
      paths:
        - /actuator/**              # 健康检查
        - /static/**                # 静态资源（无法加注解的场景）

    # ====== JWT 令牌配置（common-core JwtTokenProvider） ======
    # 【说明】网关和 auth 服务的密钥必须一致，否则校验失败
    jwt:
      # HMAC-SHA256 密钥（至少 256 bit = 32 字符）
      # 生产环境通过环境变量 JWT_SECRET 注入
      secret: ${JWT_SECRET:your-256-bit-secret-key-here}
      # Access Token 有效期（秒），默认 30 分钟
      access-token-expire: 1800
      # 记住我模式有效期（秒），默认 7 天
      remember-me-expire: 604800

    # ====== 数据加密配置（common-security） ======
    crypto:
      # AES-256 密钥（必须 32 字符，生产环境从环境变量/配置中心获取）
      key: ${LITISLE_CRYPTO_KEY:change-this-to-32-character-key-in-prod}
      # BCrypt cost 因子（默认 10，范围 4-31，越大越安全但越慢）
      bcrypt-cost: 10

  # ====== XSS 过滤配置（common-security） ======
  xss:
    enabled: true                   # 默认 true，可省略
    exclude-paths:                  # 本服务的富文本接口
      - /api/content/article/publish
      - /api/content/comment/rich
```

### 7.3 各模块配置归属

| 配置项 | 归属模块 | 说明 |
|--------|---------|------|
| `litisle.security.jwt.*` | litisle-auth | JWT 密钥、有效期（auth 独有） |
| `litisle.security.session.*` | litisle-auth | 会话管理（auth 独有） |
| `litisle.security.login.*` | litisle-auth | 登录失败策略（auth 独有） |
| `litisle.security.anonymous.paths` | 各业务服务 + 网关 | 匿名白名单兜底 |
| `litisle.xss.*` | 各业务服务 | XSS 配置（按服务定制） |

---

## 八、使用方式总览

### 8.1 自动执行（引入依赖即生效）

| 功能 | 模块 | 自动机制 |
|------|------|---------|
| 统一异常处理 | common-core | `@RestControllerAdvice` 自动扫描 |
| Jackson 全局配置 | common-core | `@Configuration` 自动扫描 |
| MyBatis-Plus 配置 | common-core | `@Configuration` 自动扫描 |
| RedisTemplate 配置 | common-redis | `@Configuration` 自动扫描 |
| Redisson 配置 | common-redis | `@Configuration` 自动扫描 |
| Feign 用户上下文透传 | common-security | `@Component` 自动注册拦截器 |
| XSS 过滤 | common-security | `@Configuration` 自动注册 Filter |
| 加密工具配置 | common-security | `@Configuration` 自动配置 Aes256GcmCrypto、PasswordCrypto |
| 匿名白名单初始化 | common-security | `@PostConstruct` 兜底加载 |
| 匿白名单注解扫描 | common-security | `ContextRefreshedEvent` 自动扫描 |

### 8.2 声明式（加注解即生效）

| 注解 | 模块 | 作用 |
|------|------|------|
| `@Idempotent` | common-redis | 接口幂等性（防重复提交） |
| `@Login` | common-security | 登录校验（仅需登录无需角色） |
| `@RequireRole` | common-security | 角色校验（支持 AND/OR） |
| `@RequirePermission` | common-security | 权限校验（细粒度权限码） |
| `@AuditLog` | common-security | 审计日志记录 |
| `@Sensitive` | common-security | 数据脱敏（响应 JSON 自动脱敏） |
| `@Anonymous` | common-security | 匿名访问（自动加入白名单） |

### 8.3 手动调用

| 类 | 模块 | 场景 |
|----|------|------|
| `Result.success()/error()` | common-core | Controller 返回 |
| `AssertUtil` | common-core | 业务断言 |
| `JsonUtil` | common-core | JSON 转换 |
| `Aes256GcmCrypto` | common-security | AES-256-GCM 加密/解密手机号、邮箱 |
| `PasswordCrypto` | common-security | BCrypt 密码加密/验证 |
| `RedisUtil` | common-redis | 缓存操作 |
| `RedissonLock` | common-redis | 分布式锁 |
| `BloomFilter` | common-redis | 布隆过滤 |
| `SecurityContextHolder` | common-security | 获取当前用户 |

---

## 九、后续开发注意事项

### ⚠️ 待办事项1：网关匿名白名单收集

**现状**：网关（WebFlux）无法用 `AnonymousPathScanner`（依赖 Spring MVC 的 `RequestMappingHandlerMapping`），当前用 yml 配置兜底。

**后续方案**：
1. 业务服务暴露 `/actuator/anonymous-paths` 端点，返回本服务白名单
2. 网关启动时通过 Feign 调用各服务端点收集全局白名单
3. 结合 Nacos 服务发现动态感知服务上下线

### ⚠️ 待办事项2：litisle-auth 的 AuthProperties

**现状**：JWT/Session/Login 配置项已规划，但 `AuthProperties` 类尚未创建。

**后续**：开发 litisle-auth 模块时创建 `AuthProperties.java`，承载：
- `litisle.security.jwt.*`（密钥、access-token-expire、remember-me-expire）
- `litisle.security.session.*`（max-concurrent、session-timeout）
- `litisle.security.login.*`（max-fail-count、lock-minutes）

### ⚠️ 待办事项3：富文本 XSS 白名单清洗

**现状**：XSS 过滤对富文本接口是"直接放行"（exclude-paths 排除），存在 XSS 风险。

**后续方案**：article 模块用 Jsoup Cleaner 做白名单清洗：
```java
// 保留安全标签（p/img/strong/a），移除 <script>、onclick 等
Safelist safelist = Safelist.relaxed().addTags("p", "img", "a", ...);
String cleanHtml = Jsoup.clean(html, safelist);
```

### ⚠️ 待办事项4：审计日志异步入库

**现状**：审计日志通过 `log.info` 输出到控制台/日志文件。

**后续**：改为 MQ 异步发送到日志服务入库，避免阻塞主流程。

### ⚠️ 待办事项5：spring-boot-starter-security 依赖

**现状**：[common-security/pom.xml](file:///e:/NewJavaPro/LitIsle/litisle-common/common-security/pom.xml) 仍有 `spring-boot-starter-security`（provided）。

**风险**：业务服务引入时会触发 Spring Security 自动配置，拦截所有接口。

**建议**：用户已明确"不替换 Spring Security"，建议删除此依赖，避免自动配置冲突。

### ⚠️ 待办事项6：业务服务必须配置 yml

**现状**：common 模块 yml 已注释。

**要求**：业务服务必须在自己的 `application.yml` 配置匿名白名单和 XSS 排除路径，否则：
- 匿名白名单只有注解扫描结果（yml 兜底失效）
- 富文本接口会被 XSS 清洗（exclude-paths 为空）

---

## 十、面试亮点总结

### 8.1 架构设计

1. **模块隔离原则**：common-redis 和 common-security 互不依赖，避免强制传递依赖
2. **依赖倒置原则（DIP）**：通过 `UserIdResolver` 接口解耦，common-redis 依赖抽象，common-security 实现抽象
3. **单一职责原则**：每个子模块只负责一类功能

### 8.2 设计模式应用

| 模式 | 应用场景 |
|------|---------|
| 装饰器模式 | `XssHttpServletRequestWrapper` 包装原始 Request |
| 策略模式 | `SensitiveStrategy` 枚举定义多种脱敏策略 |
| 模板方法 | `BloomFilter`、`DistributedLock` 接口定义规范 |
| 观察者模式 | `AnonymousPathScanner` 监听 `ContextRefreshedEvent` |
| 工厂方法 | `Result.success()/error()` 静态工厂 |

### 8.3 企业规范实践

| 规范 | 实践 |
|------|------|
| Fail-Fast | `@PostConstruct` 启动时加载配置，错误立即暴露 |
| 声明式编程 | 注解驱动（@Idempotent、@Anonymous、@Sensitive） |
| 注解集中管理 | 所有自定义注解放 `annotation/` 包 |
| 配置分层 | 公共库提供默认值，业务服务配置实际值，Nacos 动态下发 |
| 防御性编程 | 白名单合并多来源（注解 + yml），互为兜底 |

---

## 十一、文件清单

### common-core（17 个文件）

```
common-core/src/main/java/com/litisle/commoncore/
├── config/
│   ├── JacksonConfig.java              # Jackson 全局配置
│   └── MyBatisPlusConfig.java          # MyBatis-Plus 配置
├── constant/
│   ├── CommonConstant.java             # 通用常量
│   └── HeaderConstant.java             # HTTP Header 常量
├── enums/
│   ├── DelFlagEnum.java                # 逻辑删除标志
│   └── StatusEnum.java                 # 状态枚举
├── exception/
│   ├── Assert.java                     # 业务断言工具
│   ├── BizException.java               # 业务异常
│   ├── ErrorCode.java                  # 全局错误码
│   └── GlobalExceptionHandler.java     # 全局异常处理器
├── resolver/
│   └── UserIdResolver.java             # 用户ID解析器接口（DIP）
├── result/
│   ├── PageResult.java                 # 分页响应封装
│   └── Result.java                     # 统一响应封装
└── utils/
    ├── AssertUtil.java                 # 业务断言工具（utils 包）
    ├── JsonUtil.java                   # JSON 工具
    └── PageUtil.java                   # 分页工具
```

### common-redis（15 个文件）

```
common-redis/src/main/java/com/litisle/commonredis/
├── blacklist/
│   └── TokenBlacklistService.java      # Token 黑名单服务
├── bloom/
│   └── BloomFilter.java                # 布隆过滤器
├── config/
│   ├── CommonRedisAutoConfiguration.java # Bean 集中注册（替代 @Component 扫描）
│   ├── RedisConfig.java                # RedisTemplate 配置
│   └── RedissonConfig.java             # Redisson 配置
├── constant/
│   └── CacheConstant.java              # 缓存常量
├── idempotent/
│   ├── Idempotent.java                 # 幂等注解
│   ├── IdempotentAspect.java           # 幂等切面
│   └── IdempotentKeyBuilder.java       # 幂等 key 构建器
├── lock/
│   ├── DistributedLock.java            # 分布式锁接口
│   └── RedissonLock.java               # Redisson 实现
├── session/
│   ├── DeviceSession.java              # 设备会话 DTO
│   └── DeviceSessionManager.java       # 设备会话管理器（多设备限制/踢人）
├── login/
│   └── LoginRetryLimiter.java          # 登录重试限制器（暴力破解防护）
└── util/
    └── RedisUtil.java                  # Redis 操作工具
```

### common-security（16 个文件）

```
common-security/src/main/java/com/litisle/commonsecurity/
├── annotation/
│   ├── Anonymous.java                  # 匿名访问注解
│   ├── RequirePermission.java          # 权限校验注解（预留）
│   └── RequireRole.java                # 角色校验注解
├── anonymous/
│   ├── AnonymousAccessHandler.java     # 匿名访问处理器
│   ├── AnonymousPathScanner.java       # 注解路径扫描器
│   └── AnonymousProperties.java        # 匿名配置类
├── aspect/
│   └── RoleCheckAspect.java            # 角色校验切面
├── audit/
│   ├── AuditLog.java                   # 审计日志注解
│   └── AuditLogAspect.java             # 审计日志切面
├── context/
│   ├── SecurityContext.java            # 安全上下文
│   └── SecurityContextHolder.java      # ThreadLocal 上下文持有者
├── desensitize/
│   ├── Sensitive.java                  # 脱敏注解
│   ├── SensitiveSerializer.java        # 脱敏序列化器
│   └── SensitiveStrategy.java          # 脱敏策略枚举
├── feign/
│   └── FeignAuthInterceptor.java       # Feign 用户上下文透传
├── resolver/
│   └── SecurityContextUserIdResolver.java  # UserIdResolver 实现
└── xss/
    ├── XssFilter.java                  # XSS 过滤器
    ├── XssHttpServletRequestWrapper.java   # 请求包装器
    ├── XssProperties.java              # XSS 配置类
    └── XssStringJsonDeserializer.java  # JSON 反序列化器
```

---

**文档版本**：v1.1
**最后更新**：2026-08-12
**维护者**：LitIsle 团队

**更新记录**：
- v1.1（2026-08-12）：
  - 新增 `Assert` 断言工具类（exception 包）
  - `GlobalExceptionHandler` 增强：支持 HttpMessageNotReadableException、MethodArgumentTypeMismatchException、ConstraintViolationException、MissingServletRequestParameterException
  - `BizException` 新增带 cause 的构造方法（支持异常链）
  - `HeaderConstant` 新增 USERNAME 常量，修复 PERMISSIONS/TOKEN_ID 修饰符
  - `SecurityContext` 新增 tokenId 字段
  - `SecurityContextHolder` 新增 getTraceId()、getTokenId() 方法
  - `FeignAuthInterceptor` 修复代码，透传完整 Header 列表
```

---

## 文档说明

### 文档结构

| 章节 | 内容 |
|------|------|
| 一、模块总览 | 模块结构、依赖关系、核心设计原则 |
| 二、common-core | 16 个文件的功能说明（异常/响应/常量/工具/配置/接口） |
| 三、common-redis | 9 个文件（Redis 配置/分布式锁/布隆过滤器/幂等性/限流/黑名单/会话管理/登录重试限制） |
| 四、common-security | 16 个文件（上下文/角色/Feign/审计/脱敏/XSS/匿名） |
| 五、配置文件说明 | yml 注释原因、业务服务必须配置项、配置归属 |
| 六、使用方式总览 | **自动执行/声明式/手动调用** 三类分类 |
| 七、后续开发注意事项 | 6 项待办事项（网关白名单/AuthProperties/富文本/审计日志/security 依赖/yml 配置） |
| 八、面试亮点总结 | 架构设计/设计模式/企业规范实践 |
| 九、文件清单 | 三个模块所有文件的树状结构 |

### 关键特性

1. **使用方式明确标注**：
   - **自动执行**：引入依赖即生效（@Configuration、@PostConstruct、ContextRefreshedEvent）
   - **声明式**：加注解即生效（@Idempotent、@Anonymous、@Sensitive 等）
   - **手动调用**：业务代码主动调用（Result、RedisUtil、SecurityContextHolder）

2. **后续注意事项**：6 项待办事项，每项都标注现状、风险/原因、后续方案

3. **面试导向**：第八章专门总结架构设计、设计模式、企业规范实践，可直接用于面试讲解


请将上述完整内容复制到 [commom-des.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/commom-des.md)（当前文件为空，直接覆盖即可）。

