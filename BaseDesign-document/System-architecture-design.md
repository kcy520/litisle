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
    └── config/                 # 公共配置
        ├── JacksonConfig.java          # Jackson 全局配置（日期格式、空值处理）
        └── MyBatisPlusConfig.java      # MyBatis-Plus 配置（分页插件、乐观锁插件）
```

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
    └── constant/
        └── CacheConstant.java          # Redis Key 前缀常量
```

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
    ├── context/
    │   ├── SecurityContext.java        # 安全上下文（ThreadLocal）
    │   └── SecurityContextHolder.java  # 上下文持有者
    ├── annotation/
    │   ├── @RequireRole.java           # 角色校验注解
    │   └── @RequirePermission.java     # 权限校验注解（预留）
    ├── aspect/
    │   └── RoleCheckAspect.java        # 角色校验切面
    ├── feign/
    │   └── FeignAuthInterceptor.java   # Feign 拦截器（透传 userId/traceId）
    └── audit/
        ├── @AuditLog.java              # 审计日志注解
        └── AuditLogAspect.java         # 审计日志切面
```

**依赖**：

```xml
<dependencies>
    <!-- common-core -->
    <dependency>
        <groupId>com.litisle</groupId>
        <artifactId>common-core</artifactId>
    </dependency>
    
    <!-- Spring AOP（切面） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aop</artifactId>
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
    │   ├── JwtGlobalFilter.java        # JWT 校验过滤器
    │   ├── SentinelGatewayFilter.java  # Sentinel 限流过滤器
    │   └── TraceIdFilter.java          # TraceId 注入过滤器
    ├── config/                 # 配置
    │   ├── AuthProperties.java         # 白名单配置
    │   ├── CorsConfig.java             # 跨域配置
    │   └
```