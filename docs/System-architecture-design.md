# System-architecture-design



我来为你详细设计 Common 模块及其他模块的架构文档。这是一个需要深入分析的任务，我会基于你现有的设计文档给出符合企业规范的详细架构设计。

## 一、litisle-common 模块架构设计

### 1.1 是否拆分子模块？

**推荐方案：拆分为 3 个子模块**

```
litisle-common/
├── common-core/          # 核心基础（所有服务必须依赖）
├── common-redis/         # Redis 相关（大部分服务需要，但网关可选）
└── common-security/      # Security 上下文（仅业务服务需要，网关不需要完整实现）
```

**拆分理由**：

| 场景            | 依赖需求                                     | 说明                                                         |
| --------------- | -------------------------------------------- | ------------------------------------------------------------ |
| litisle-gateway | common-core + common-security（部分）        | 网关只需 JWT 校验和 SecurityContext，不需要完整的 Redis 工具（Token 黑名单用 Redis 但可单独引入） |
| litisle-auth    | common-core + common-redis + common-security | 需要完整的 Redis、Security、DFA                              |
| 其他业务服务    | common-core + common-redis + common-security | 全量依赖                                                     |
| litisle-file    | common-core + common-redis（可选）           | 文件服务可能不需要 Security                                  |

**不拆分过细的原因**：

- 子模块过多会增加维护成本
- 3 个子模块已经能解决"少数模块不引入多余依赖"的问题
- 符合"约定优于配置"原则

### 1.2 各子模块详细设计

#### 1.2.1 common-core（核心基础）

```
common-core/
├── pom.xml
└── src/main/java/com/litisle/common/core/
    ├── exception/              # 异常处理
    │   ├── BizException.java           # 业务异常
    │   ├── ErrorCode.java              # 错误码枚举（集中维护）
    │   └── GlobalExceptionHandler.java # 全局异常处理器（@RestControllerAdvice）
    ├── result/                 # 统一响应
    │   ├── Result.java                 # 统一响应体 Result<T>
    │   └── PageResult.java             # 分页响应体 PageResult<T>
    ├── constant/               # 公共常量
    │   ├── CommonConstant.java         # 通用常量（如 UTF8、分页默认值）
    │   └── HeaderConstant.java         # Header 常量（X-User-Id、X-Roles、X-Trace-Id）
    ├── enums/                  # 公共枚举（仅跨服务共享的）
    │   ├── StatusEnum.java             # 通用状态枚举
    │   └── DelFlagEnum.java            # 删除标志枚举
    ├── util/                   # 工具类
    │   ├── AssertUtil.java             # 断言工具（抛 BizException）
    │   ├── JsonUtil.java               # Jackson 工具
    │   └── PageUtil.java               # 分页工具（MyBatis-Plus Page 转换）
    ├── resolver/               # 解析器接口（新增，依赖倒置）
    │   └── UserIdResolver.java         # 用户ID解析接口（解耦 common-redis 与 common-security）
    └── config/                 # 公共配置
        ├── JacksonConfig.java          # Jackson 全局配置（日期格式、空值处理）
        └── MyBatisPlusConfig.java      # MyBatis-Plus 配置（分页插件、乐观锁插件）
```

> 【架构说明】UserIdResolver 定义在 common-core，是为了解耦 common-redis（幂等切面需要 userId）与 common-security（持有 SecurityContext）。两者都依赖 common-core 的接口，互不依赖，符合依赖倒置原则（DIP）。

**依赖**：

```xml
<dependencies>
    <!-- Spring Web（提供 @RestControllerAdvice） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
        <scope>provided</scope> <!-- provided：由使用方提供 -->
    </dependency>
    
    <!-- MyBatis-Plus（提供分页插件） -->
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-boot-starter</artifactId>
        <scope>provided</scope>
    </dependency>
    
    <!-- Jackson -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    
    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

**设计要点**：

- `scope=provided`：避免强制引入不需要的依赖，由使用方决定版本
- `ErrorCode` 集中维护：所有服务的错误码统一在此定义，避免冲突
- `GlobalExceptionHandler` 自动生效：通过 `@RestControllerAdvice` 注解，引入即生效

#### 1.2.2 common-redis（Redis 工具）

```
common-redis/
├── pom.xml
└── src/main/java/com/litisle/common/redis/
    ├── config/
    │   ├── RedisConfig.java            # RedisTemplate 配置（序列化）
    │   └── RedissonConfig.java         # Redisson 配置（分布式锁）
    ├── util/
    │   └── RedisUtil.java              # Redis 操作工具类
    ├── lock/
    │   ├── DistributedLock.java        # 分布式锁接口
    │   └── RedissonLock.java           # Redisson 实现
    ├── bloom/
    │   └── BloomFilter.java            # 布隆过滤器封装
    ├── idempotent/                     # 接口幂等性（新增，面试高频）
    │   ├── @Idempotent.java            # 幂等注解
    │   ├── IdempotentKeyBuilder.java   # 幂等 key 构建器（接收 userId 参数）
    │   └── IdempotentAspect.java       # 幂等切面（通过 UserIdResolver 获取 userId）
    └── constant/
        └── CacheConstant.java          # Redis Key 前缀常量
```

> 【架构说明】idempotent 放在 common-redis 而非 common-security，因为幂等性强依赖 Redis。与分布式锁、布隆过滤器同级，都是"基于 Redis 的功能"，避免 common-security 被迫依赖 Redis。

**依赖**：

```xml
<dependencies>
    <!-- common-core -->
    <dependency>
        <groupId>com.litisle</groupId>
        <artifactId>common-core</artifactId>
    </dependency>
    
    <!-- Spring Data Redis -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    
    <!-- Redisson -->
    <dependency>
        <groupId>org.redisson</groupId>
        <artifactId>redisson-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

**设计要点**：

- `RedisUtil` 封装所有操作：String/Hash/Set/ZSet/BitMap
- `DistributedLock` 接口化：方便未来替换实现
- `CacheConstant` 集中管理 Key 前缀：避免硬编码

#### 1.2.3 common-security（安全上下文）

```
common-security/
├── pom.xml
└── src/main/java/com/litisle/common/security/
    ├── context/                        # 安全上下文（已实现）
    │   ├── SecurityContext.java        # 安全上下文（ThreadLocal）
    │   └── SecurityContextHolder.java  # 上下文持有者
    ├── annotation/                     # 自定义注解（已实现）
    │   ├── @RequireRole.java           # 角色校验注解
    │   ├── @RequirePermission.java     # 权限校验注解（预留）
    │   └── @Anonymous.java             # 匿名访问注解（新增）
    ├── aspect/                         # 切面（已实现）
    │   └── RoleCheckAspect.java        # 角色校验切面
    ├── feign/                          # Feign 拦截器（已实现）
    │   └── FeignAuthInterceptor.java   # Feign 拦截器（透传 userId/traceId）
    ├── audit/                          # 审计日志（已实现）
    │   ├── @AuditLog.java              # 审计日志注解
    │   └── AuditLogAspect.java         # 审计日志切面
    ├── resolver/                      # UserIdResolver 实现（新增，依赖倒置）
    │   └── SecurityContextUserIdResolver.java  # 从 SecurityContextHolder 获取 userId
    ├── desensitize/                    # 数据脱敏（新增，面试高频）
    │   ├── @Sensitive.java             # 脱敏注解
    │   ├── SensitiveStrategy.java      # 脱敏策略枚举
    │   └── SensitiveSerializer.java    # Jackson 序列化器
    ├── anonymous/                      # 匿名认证（新增，面试高频）
    │   ├── AnonymousAccessHandler.java # 匿名访问处理器（构建匿名 SecurityContext）
    │   └── AnonymousProperties.java    # 匿名访问配置（yml + JavaBean）
    └── xss/                            # XSS 防护（新增，企业必备）
        ├── XssHttpServletRequestWrapper.java  # 请求包装器
        ├── XssStringJsonDeserializer.java     # JSON 反序列化清洗
        └── XssFilter.java              # XSS 过滤器
```

> 【架构说明】idempotent（接口幂等性）已从 common-security 移至 common-redis，因为幂等性强依赖 Redis，放在 common-redis 与分布式锁、布隆过滤器同级，避免 common-security 被迫依赖 Redis。依赖关系通过 common-core 的 UserIdResolver 接口解耦（依赖倒置）。

**依赖**：

```xml
<dependencies>
    <!-- common-core -->
    <dependency>
        <groupId>com.litisle</groupId>
        <artifactId>common-core</artifactId>
    </dependency>
    
    <!-- common-redis（幂等性依赖 Redis） -->
    <dependency>
        <groupId>com.litisle</groupId>
        <artifactId>common-redis</artifactId>
    </dependency>
    
    <!-- Spring AOP（切面） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aop</artifactId>
    </dependency>
    
    <!-- Spring Web（XSS 过滤器依赖 HttpServletRequest） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
        <scope>provided</scope>
    </dependency>
    
    <!-- Spring Security（仅用于注解，不完整配置） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
        <scope>provided</scope> <!-- 由使用方提供 -->
    </dependency>
    
    <!-- OpenFeign（拦截器） -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-openfeign</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

**设计要点**：

- `SecurityContext` 基于 ThreadLocal：存储当前请求的用户信息
- `FeignAuthInterceptor` 自动透传：从 SecurityContext 读取 userId/traceId 注入 Header
- `@RequireRole` 注解 + 切面：方法级角色校验
- `@AuditLog` 注解 + 切面：自动记录审计日志
- `@Idempotent` 注解 + 切面：基于 Redis SETNX 实现接口幂等，防止重复提交（如订单创建、点赞）
- `@Sensitive` 注解 + Jackson 序列化器：响应 JSON 时自动脱敏（手机号 138****8888、身份证 110***********1234）
- `@Anonymous` 注解 + 匿名访问处理器：标记接口允许匿名访问，构建匿名 SecurityContext，未登录也能调用
- `XssFilter` + `XssHttpServletRequestWrapper`：全局清洗请求参数中的 HTML 标签，防 XSS 攻击

**新增功能详细设计**：

**1. 接口幂等性（@Idempotent）**

- **原理**：基于 Redis SETNX（SET IF NOT EXISTS）实现
- **流程**：请求进入 → 构建 key（userId + 接口路径 + 参数摘要） → SETNX → 成功则执行业务 → 失败则返回"请勿重复提交"
- **使用场景**：下单、支付、点赞、评论提交等 POST 接口
- **使用示例**：
  ```java
  @Idempotent(expire = 5, timeUnit = TimeUnit.SECONDS, message = "请勿重复提交")
  @PostMapping("/order/create")
  public Result<Void> createOrder(@RequestBody OrderDTO dto) { ... }
  ```

**2. 数据脱敏（@Sensitive）**

- **原理**：自定义 Jackson 序列化器，在序列化时根据策略脱敏
- **优势**：业务代码无感知，只需在 VO 字段加注解
- **使用示例**：
  ```java
  public class UserVO {
      @Sensitive(strategy = SensitiveStrategy.PHONE)
      private String phone;        // 138****8888
      
      @Sensitive(strategy = SensitiveStrategy.ID_CARD)
      private String idCard;       // 110***********1234
      
      @Sensitive(strategy = SensitiveStrategy.EMAIL)
      private String email;        // ex***@example.com
  }
  ```

**3. XSS 防护**

- **原理**：Servlet Filter 拦截请求，重写 getParameterValues/getInputStream 方法清洗 HTML 标签
- **覆盖范围**：表单参数、JSON 请求体
- **实现方式**：使用 hutool 的 HtmlUtil.cleanHtmlTag() 或 OWASP ESAPI
- **配置**：在业务服务的配置类中注册 XssFilter

**4. 匿名认证（@Anonymous）**

- **原理**：通过 `@Anonymous` 注解标记接口允许匿名访问，网关过滤器扫描后不强制要求 JWT
- **两种配置方式**：
  1. **注解方式**（推荐）：在 Controller 方法或类上标注 `@Anonymous`，由扫描器自动收集到白名单
  2. **配置方式**：在 `application.yml` 中配置 `litisle.security.anonymous-paths`，灵活控制
- **使用场景**：登录、注册、公开内容浏览、验证码获取、健康检查
- **使用示例**：
  ```java
  @Anonymous
  @GetMapping("/public/article/{id}")
  public Result<ArticleVO> getPublicArticle(@PathVariable Long id) { ... }
  ```
- **匿名上下文**：匿名访问时构建匿名 `SecurityContext`（userId=null，roles=[ANONYMOUS]），业务层可据此判断是否登录

### 1.3 Spring Security 放置位置

**关键原则：Spring Security 完整配置只在业务服务，网关只做 JWT 校验**

```
┌─────────────────────────────────────────────────────────────┐
│                    litisle-gateway                           │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ JwtGlobalFilter（Spring Cloud Gateway Filter）        │  │
│  │ - 校验 JWT 签名（RSA 公钥）                           │  │
│  │ - 解析 userId/roles                                   │  │
│  │ - 注入 X-User-Id / X-Roles Header                    │  │
│  │ - Token 黑名单校验（Redis）                           │  │
│  └──────────────────────────────────────────────────────┘  │
│  不引入 Spring Security，只用 Spring Cloud Gateway 过滤器   │
└─────────────────────────────────────────────────────────────┘
                            ↓ 透传 Header
┌─────────────────────────────────────────────────────────────┐
│              业务服务（如 litisle-user）                      │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ SecurityConfig（Spring Security 完整配置）             │  │
│  │ - 禁用 Session（STATELESS）                           │  │
│  │ - 禁用 CSRF                                           │  │
│  │ - 禁用默认登录页                                      │  │
│  │ - 自定义 JwtAuthenticationFilter（解析 Header）       │  │
│  │ - 配置 URL 权限（antMatchers）                        │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ JwtAuthenticationFilter（OncePerRequestFilter）       │  │
│  │ - 从 Header 读取 X-User-Id / X-Roles                 │  │
│  │ - 构建 Authentication 对象                            │  │
│  │ - 写入 SecurityContextHolder                          │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ common-security（SecurityContext）                    │  │
│  │ - 从 SecurityContextHolder 读取用户信息              │  │
│  │ - 封装为 SecurityContext 供业务代码使用               │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

**为什么这样设计**：

| 位置            | 职责                      | 原因                           |
| --------------- | ------------------------- | ------------------------------ |
| 网关            | JWT 校验 + 注入 Header    | 统一入口，避免每个服务重复校验 |
| 业务服务        | 解析 Header + 权限控制    | 细粒度权限控制（方法级）       |
| common-security | 提供 SecurityContext 工具 | 统一获取当前用户的方式         |

**网关不引入 Spring Security 的原因**：

- Spring Security 是 Servlet 体系，Gateway 是 WebFlux 体系，不兼容
- 网关只需 JWT 校验，用 JJWT 库即可，无需完整 Security
- 简化网关依赖，提高性能

### 1.4 公共配置管理

**配置分层策略**：

```
┌─────────────────────────────────────────────────────────────┐
│                    配置分层                                  │
├─────────────────────────────────────────────────────────────┤
│ 1. common-core 中的配置类（自动生效）                        │
│    - JacksonConfig：日期格式、空值处理                       │
│    - MyBatisPlusConfig：分页插件、乐观锁插件                 │
│    - 通过 @Configuration + @ConditionalOnClass 自动加载     │
├─────────────────────────────────────────────────────────────┤
│ 2. common-redis 中的配置类（引入即生效）                     │
│    - RedisConfig：RedisTemplate 序列化                       │
│    - RedissonConfig：Redisson 客户端                         │
│    - 通过 @Configuration 自动加载                            │
├─────────────────────────────────────────────────────────────┤
│ 3. 各服务的 application.yml（服务特有配置）                  │
│    - 数据库连接、Redis 地址、Nacos 地址等                    │
│    - 通过 Nacos 配置中心统一管理                             │
└─────────────────────────────────────────────────────────────┘
```

**避免多余依赖的方案**：

```java
// common-core 中的 MyBatis-Plus 配置
@Configuration
@ConditionalOnClass(MybatisPlusInterceptor.class) // 仅当引入 MyBatis-Plus 时生效
public class MyBatisPlusConfig {
    
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
```

**效果**：

- `litisle-file` 不引入 MyBatis-Plus → `MyBatisPlusConfig` 不加载 → 无报错
- `litisle-gateway` 不引入 Redis → `RedisConfig` 不加载 → 无报错

### 1.5 敏感词过滤（DFA）模块

**放置位置**：`common-core` 中的 `sensitive` 包

```
common-core/
└── src/main/java/com/litisle/common/core/sensitive/
    ├──dfa/
    │   ├── DfaFilter.java              # DFA 过滤器核心
    │   ├── DfaNode.java                # DFA 节点
    │   └── DfaBuilder.java             # DFA 构建器
    ├── service/
    │   ├── SensitiveWordService.java   # 敏感词服务接口
    │   └── SensitiveWordServiceImpl.java # 实现（MySQL + Redis）
    ├── strategy/
    │   ├── SensitiveStrategy.java      # 策略接口
    │   ├── ArticleStrategy.java        # 文章策略（替换为***）
    │   ├── CommentStrategy.java        # 评论策略（替换为***）
    │   ├── UsernameStrategy.java       # 用户名策略（拒绝）
    │   └── MessageStrategy.java        # 私信策略（替换为***）
    ├── hotload/
    │   └── SensitiveWordHotLoader.java # 双 buffer 热更新
    └── annotation/
        ├── @SensitiveFilter.java       # 注解
        └── SensitiveFilterAspect.java  # 切面
```

**热更新机制**：

```java
// 监听 Redis Pub/Sub
@RedisListener(channels = "sensitive:word:update")
public void onWordUpdate(String message) {
    // 1. 从 MySQL 加载最新词库
    List<String> words = sensitiveWordMapper.selectAll();
    
    // 2. 构建新 DFA 到 buffer2
    DfaFilter newDfa = DfaBuilder.build(words);
    
    // 3. 原子切换 buffer（volatile 保证可见性）
    currentDfa = newDfa;
}
```

### 1.6 审计日志模块

**放置位置**：`common-security` 中的 `audit` 包

```java
// 注解定义
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {
    String module() default "";           // 模块名
    String operation() default "";        // 操作类型
    boolean saveRequestData() default true;  // 是否保存请求参数
    boolean saveResponseData() default false; // 是否保存响应数据
}

// 切面实现
@Aspect
@Component
public class AuditLogAspect {
    
    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint point, AuditLog auditLog) throws Throwable {
        long startTime = System.currentTimeMillis();
        
        // 获取当前用户
        Long userId = SecurityContextHolder.getUserId();
        
        // 执行方法
        Object result = point.proceed();
        
        // 记录日志
        long costTime = System.currentTimeMillis() - startTime;
        AuditLogEntity logEntity = new AuditLogEntity();
        logEntity.setUserId(userId);
        logEntity.setModule(auditLog.module());
        logEntity.setOperation(auditLog.operation());
        logEntity.setRequestData(getRequestData(point));
        logEntity.setCostTime(costTime);
        logEntity.setCreateTime(LocalDateTime.now());
        
        // 异步写入数据库或日志文件
        auditLogService.saveAsync(logEntity);
        
        return result;
    }
}
```

---

## 二、其他模块架构设计

### 2.1 litisle-gateway 模块架构

```
litisle-gateway/
├── pom.xml
└── src/main/java/com/litisle/gateway/
    ├── filter/                 # 过滤器
    │   ├── TraceIdGlobalFilter.java    # TraceId 注入过滤器（最先执行）
    │   ├── JwtGlobalFilter.java        # JWT 校验 + 黑名单校验 + Header 注入
    │   ├── RequestLogGlobalFilter.java # 请求日志过滤器（最后执行，记录访问日志）
    │   └── SentinelGatewayFilter.java  # Sentinel 限流过滤器
    ├── config/                 # 配置
    │   ├── AuthProperties.java         # 白名单配置（从 Nacos 读取）
    │   ├── CorsConfig.java             # 跨域配置
    │   ├── RouteConfig.java            # 路由配置（从 Nacos 读取）
    │   └── SentinelConfig.java         # Sentinel 限流规则配置
    └── handler/                # 异常处理
        ├── GlobalExceptionHandler.java # 全局异常处理器（401/403/429）
        └── WsHandler.java             # WebSocket 路由处理
```

**过滤器执行顺序**：

```
请求进入 → TraceIdFilter(order=-100) → JwtGlobalFilter(order=-200)
         → RequestLogFilter(order=-300) → SentinelFilter → 路由转发
```

**新增 RequestLogGlobalFilter 设计**：

- **职责**：记录所有请求的访问日志（审计 + 排查）
- **记录内容**：traceId、userId、请求方法、路径、入参摘要、响应状态、耗时
- **异步写入**：使用 Reactor 的 asyncOperator 避免阻塞主流程
- **采样策略**：可根据配置采样记录（如只记录耗时 > 500ms 的慢请求）
- **输出方式**：log.info 输出到日志文件，未来可扩展到 ELK

**JwtGlobalFilter 增强点**：

- **Token 黑名单校验**：退出登录时 Token 加入 Redis 黑名单，TTL = Token 剩余寿命
- **多端登录校验**：校验 Token 中的 deviceId 是否在 Redis session 列表中
- **Header 注入**：注入 X-User-Id / X-Roles / X-Trace-Id / X-Device-Id
- **敏感 Header 过滤**：移除客户端伪造的 X-User-Id / X-Roles 等内部 Header
```