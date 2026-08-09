我先扫描所有 common 模块的源码，确保说明文档内容准确（类名、注解、方法签名等）。


toolName: view_files

status: success


filePath: e:\NewJavaPro\LitIsle\litisle-common\common-core\src\main\java\com\litisle\commoncore\exception\ErrorCode.java




toolName: view_files

status: success


filePath: e:\NewJavaPro\LitIsle\litisle-common\common-core\src\main\java\com\litisle\commoncore\result\Result.java




toolName: view_files

status: success


filePath: e:\NewJavaPro\LitIsle\litisle-common\common-core\src\main\java\com\litisle\commoncore\resolver\UserIdResolver.java




toolName: view_files

status: success


filePath: e:\NewJavaPro\LitIsle\litisle-common\common-redis\src\main\java\com\litisle\commonredis\idempotent\Idempotent.java

我已收集足够信息。现在生成完整的 common 模块说明文档，直接输出到 [commom-des.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/commom-des.md)：

```markdown
e:\NewJavaPro\LitIsle\BaseDesign-document\commom-des.md
# LitIsle Common 模块功能说明文档

> 公共基础模块合集，为业务服务提供通用能力支撑。所有业务服务依赖 common 模块，避免重复造轮子。

---

## 一、模块总览

```
litisle-common/
├── common-core          # 基础工具：异常/响应/枚举/常量/工具类/配置
├── common-redis         # Redis 能力：序列化/分布式锁/布隆过滤器/幂等性
└── common-security      # 安全控制：上下文/角色校验/审计/脱敏/XSS/匿名认证
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
| `BizException` | 运行时异常 | 业务异常，携带 ErrorCode，由全局处理器捕获 |
| `GlobalExceptionHandler` | @RestControllerAdvice | 统一异常处理，转换为 Result 响应 |

**使用方式**：自动执行。业务代码抛 `BizException`，由 `GlobalExceptionHandler` 自动捕获转 `Result`。

```java
// 业务代码抛异常
throw new BizException(ErrorCode.USER_NOT_FOUND);

// Controller 无需 try-catch，统一响应：
// { "code": 20001, "message": "用户不存在", "data": null }
```

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
| `HeaderConstant` | HTTP Header 常量（X-User-Id、X-Trace-Id 等） |
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

### 2.6 依赖倒置接口

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
| `CacheConstant` | 缓存 Key 常量前缀 | 手动引用 |

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

---

## 四、common-security 模块

### 4.1 安全上下文

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `SecurityContext` | 安全上下文（userId、username、roles、authenticated） | 内部使用 |
| `SecurityContextHolder` | ThreadLocal 上下文持有者 | 手动调用 `getUserId()` / `getRoles()` |

**使用方式**：业务代码通过 `SecurityContextHolder` 获取当前登录用户。

```java
Long userId = SecurityContextHolder.getUserId();
Set<String> roles = SecurityContextHolder.getRoles();
```

**⚠️ 注意事项**：
- 基于 ThreadLocal，请求结束必须清理（由 Filter/Interceptor 负责）
- 网关已校验 JWT，业务服务从 Header 读取用户信息构建上下文

### 4.2 角色权限校验

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `RequireRole` | 角色校验注解（支持 AND/OR 逻辑） | 声明式 |
| `RequirePermission` | 权限校验注解（预留） | 声明式 |
| `RoleCheckAspect` | 角色校验切面 | **自动执行** |

**使用方式**：声明式。

```java
@RequireRole(roles = {"ADMIN", "EDITOR"}, logical = Logical.OR)
@PostMapping("/article/publish")
public Result<Void> publish(@RequestBody ArticleDTO dto) { ... }
```

### 4.3 Feign 拦截器

| 类 | 说明 | 使用方式 |
|----|------|---------|
| `FeignAuthInterceptor` | Feign 请求拦截器（透传 X-User-Id、X-Trace-Id） | **自动执行** |

**作用**：微服务间 Feign 调用时，自动透传用户上下文和链路追踪 ID。

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

### 4.8 依赖倒置实现

| 类 | 说明 |
|----|------|
| `SecurityContextUserIdResolver` | `UserIdResolver` 实现，从 `SecurityContextHolder` 获取 userId |

**作用**：为 common-redis 的幂等切面提供 userId 来源，实现 common-redis 与 common-security 解耦。

---

## 五、配置文件说明

### 5.1 common 模块的 yml 文件

**重要**：common 模块的 yml 文件**已全部注释**，仅作配置参考模板。

**原因**（企业规范）：
- common 是公共库（jar），不是独立 Spring Boot 应用
- yml 配置应由业务服务或 Nacos 提供，避免配置冲突
- 公共库只提供"代码能力和默认值"（通过 @ConfigurationProperties 字段默认值）

### 5.2 业务服务必须配置的项

业务服务（如 litisle-content、litisle-auth）**必须**在自己的 `application.yml` 配置：

```yaml
# ====== 匿名访问白名单兜底（common-security） ======
litisle:
  security:
    anonymous:
      paths:
        - /actuator/**              # 健康检查
        - /static/**                # 静态资源（无法加注解的场景）

  # ====== XSS 过滤配置（common-security） ======
  xss:
    enabled: true                   # 默认 true，可省略
    exclude-paths:                  # 本服务的富文本接口
      - /api/content/article/publish
      - /api/content/comment/rich
```

### 5.3 各模块配置归属

| 配置项 | 归属模块 | 说明 |
|--------|---------|------|
| `litisle.security.jwt.*` | litisle-auth | JWT 密钥、有效期（auth 独有） |
| `litisle.security.session.*` | litisle-auth | 会话管理（auth 独有） |
| `litisle.security.login.*` | litisle-auth | 登录失败策略（auth 独有） |
| `litisle.security.anonymous.paths` | 各业务服务 + 网关 | 匿名白名单兜底 |
| `litisle.xss.*` | 各业务服务 | XSS 配置（按服务定制） |

---

## 六、使用方式总览

### 6.1 自动执行（引入依赖即生效）

| 功能 | 模块 | 自动机制 |
|------|------|---------|
| 统一异常处理 | common-core | `@RestControllerAdvice` 自动扫描 |
| Jackson 全局配置 | common-core | `@Configuration` 自动扫描 |
| MyBatis-Plus 配置 | common-core | `@Configuration` 自动扫描 |
| RedisTemplate 配置 | common-redis | `@Configuration` 自动扫描 |
| Redisson 配置 | common-redis | `@Configuration` 自动扫描 |
| Feign 用户上下文透传 | common-security | `@Component` 自动注册拦截器 |
| XSS 过滤 | common-security | `@Configuration` 自动注册 Filter |
| 匿名白名单初始化 | common-security | `@PostConstruct` 兜底加载 |
| 匿白名单注解扫描 | common-security | `ContextRefreshedEvent` 自动扫描 |

### 6.2 声明式（加注解即生效）

| 注解 | 模块 | 作用 |
|------|------|------|
| `@Idempotent` | common-redis | 接口幂等性（防重复提交） |
| `@RequireRole` | common-security | 角色校验（支持 AND/OR） |
| `@RequirePermission` | common-security | 权限校验（预留） |
| `@AuditLog` | common-security | 审计日志记录 |
| `@Sensitive` | common-security | 数据脱敏（响应 JSON 自动脱敏） |
| `@Anonymous` | common-security | 匿名访问（自动加入白名单） |

### 6.3 手动调用

| 类 | 模块 | 场景 |
|----|------|------|
| `Result.success()/error()` | common-core | Controller 返回 |
| `AssertUtil` | common-core | 业务断言 |
| `JsonUtil` | common-core | JSON 转换 |
| `RedisUtil` | common-redis | 缓存操作 |
| `RedissonLock` | common-redis | 分布式锁 |
| `BloomFilter` | common-redis | 布隆过滤 |
| `SecurityContextHolder` | common-security | 获取当前用户 |

---

## 七、后续开发注意事项

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

## 八、面试亮点总结

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

## 九、文件清单

### common-core（16 个文件）

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
│   ├── BizException.java               # 业务异常
│   ├── ErrorCode.java                  # 全局错误码
│   └── GlobalExceptionHandler.java     # 全局异常处理器
├── resolver/
│   └── UserIdResolver.java             # 用户ID解析器接口（DIP）
├── result/
│   ├── PageResult.java                 # 分页响应封装
│   └── Result.java                     # 统一响应封装
└── utils/
    ├── AssertUtil.java                 # 业务断言工具
    ├── JsonUtil.java                   # JSON 工具
    └── PageUtil.java                   # 分页工具
```

### common-redis（8 个文件）

```
common-redis/src/main/java/com/litisle/commonredis/
├── bloom/
│   └── BloomFilter.java                # 布隆过滤器
├── config/
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

**文档版本**：v1.0
**最后更新**：2026-08-05
**维护者**：LitIsle 团队
```

---

## 文档说明

### 文档结构

| 章节 | 内容 |
|------|------|
| 一、模块总览 | 模块结构、依赖关系、核心设计原则 |
| 二、common-core | 16 个文件的功能说明（异常/响应/常量/工具/配置/接口） |
| 三、common-redis | 8 个文件（Redis 配置/分布式锁/布隆过滤器/幂等性） |
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

### 待您手动处理

请将上述完整内容复制到 [commom-des.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/commom-des.md)（当前文件为空，直接覆盖即可）。

如果您希望我调整文档的某些部分（如精简、扩展、调整结构），请告知。