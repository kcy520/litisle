# System-architecture-design



我来为你详细设计 Common 模块及其他模块的架构文档。这是一个需要深入分析的任务，我会基于你现有的设计文档给出符合企业规范的详细架构设计。

## 一、litisle-common 模块架构设计

### 1.1 是否拆分子模块？

**推荐方案：拆分为 4 个子模块**（2026-11 按代码核对，较早期设计新增 `common-mq`）

```
litisle-common/
├── common-core/          # 核心基础（所有服务必须依赖）
├── common-redis/         # Redis 相关（大部分服务需要，但网关可选）
├── common-mq/            # MQ 基建（需要收发消息的服务引入）
└── common-security/      # 安全上下文（仅业务服务需要，网关不需要完整实现）
```

**拆分理由**：

| 场景            | 依赖需求                                     | 说明                                                         |
| --------------- | -------------------------------------------- | ------------------------------------------------------------ |
| litisle-gateway | common-core + common-security（部分）        | 网关只需 Sa-Token 登录校验与 Header 注入，不需要完整的 Redis 工具与 MQ 能力 |
| litisle-auth    | common-core + common-redis + common-security | 需要完整的 Redis、Security、DFA                              |
| 其他业务服务    | common-core + common-redis + common-security + common-mq | 按需引入 MQ 基建（不收发消息的服务可不依赖） |
| litisle-file    | common-core + common-redis（可选）           | 文件服务可能不需要 Security                                  |

**不拆分过细的原因**：

- 子模块过多会增加维护成本
- 4 个子模块已经能解决"少数模块不引入多余依赖"的问题
- 符合"约定优于配置"原则

### 1.2 各子模块详细设计

#### 1.2.1 common-core（核心基础）

> **包结构索引**：详见 [Design-Document.md](Design-Document.md) §2.1.1（唯一详述处，本文不再重复展开）。
>
> **能力**：统一响应与异常、公共常量/枚举、Jackson 与 MyBatis-Plus 配置、断言/JSON/分页工具、DTO 转换与查询参数解析（converter/query/resolver 包）。

> 【架构说明】`UserIdResolver` 接口定义在 common-core，`SaTokenUserIdResolver` 在 common-security 实现它；common-redis 的幂等切面按该接口取 userId，因此 common-redis 与 common-security 互不依赖，符合依赖倒置原则（DIP）。

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

> **包结构索引**：详见 [Design-Document.md](Design-Document.md) §2.1.1（唯一详述处，本文不再重复展开）。
>
> **能力**：Redis 工具与序列化配置、Redisson 分布式锁、布隆过滤器、接口幂等、限流降级、多端会话、Redis 健康守卫。

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

> **包结构索引**：详见 [Design-Document.md](Design-Document.md) §2.1.1（唯一详述处，本文不再重复展开）。
>
> **能力**：Sa-Token 自动装配与异常处理、用户ID解析、角色/权限编码、Feign 透传、traceId 过滤器、审计切面、数据脱敏、匿名路径白名单、XSS 过滤、加解密工具。

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
    
    <!-- Sa-Token（登录校验 + Redis 共享会话） -->
    <dependency>
        <groupId>cn.dev33</groupId>
        <artifactId>sa-token-spring-boot3-starter</artifactId>
    </dependency>

    <!-- Sa-Token 集成 Redis（Jackson 序列化） -->
    <dependency>
        <groupId>cn.dev33</groupId>
        <artifactId>sa-token-redis-jackson</artifactId>
    </dependency>

    <!-- 密码/摘要算法（BCrypt、AES-GCM、HMAC） -->
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-crypto</artifactId>
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

- `SaTokenUserIdResolver`：实现 common-core 的 `UserIdResolver`，从 Sa-Token 会话取当前用户ID（已取代早期 ThreadLocal `SecurityContext`）
- `SaTokenAutoConfiguration` + `SaTokenExceptionHandler`：Sa-Token 自动装配与未登录/无权限异常统一处理（各服务用 `SaTokenWebConfig` 注册拦截器）
- `StpInterfaceImpl` + `RoleCode`/`PermissionCode`：向 `@SaCheckRole`/`@SaCheckPermission` 提供角色与权限
- `FeignAuthInterceptor` 自动透传：从当前请求上下文读取 userId/traceId 注入下游 Header
- `@AuditLog` 注解 + 切面：自动记录审计日志
- `@Idempotent` 注解 + 切面（位于 common-redis）：基于 Redis SETNX 实现接口幂等，防止重复提交（如订单创建、点赞）
- `@Sensitive` 注解 + Jackson 序列化器：响应 JSON 时自动脱敏（手机号 138****8888、身份证 110***********1234）
- `@Anonymous` 注解 + `AnonymousPathScanner`：标记允许匿名访问的接口并汇总白名单（不构建匿名 SecurityContext）
- `XssFilter` + `XssHttpServletRequestWrapper` + `XssStringJsonDeserializer`：全局清洗请求参数中的 HTML 标签，防 XSS 攻击

**新增功能详细设计**：

**1. 接口幂等性（@Idempotent，位于 common-redis）**

- **原理**：`IdempotentAspect` 基于 Redis `setIfAbsent`（SETNX）写入幂等键，key 由 `IdempotentKeyBuilder` 按「用户ID + 方法签名 + 参数摘要」生成
- **流程**：请求进入 → 构建 key → SETNX → 成功则执行业务 → 失败则返回"请勿重复提交"
- **属性**：`expire`/`timeUnit`、`includeArgs`、`releaseOnError`、`message`
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

- **原理**：`XssFilter` 包装请求，重写 `getParameterValues`/`getInputStream` 清洗 HTML；JSON 请求体由 `XssStringJsonDeserializer` 反序列化时清洗
- **覆盖范围**：表单参数、JSON 请求体
- **配置**：引入 common-security 即由自动装配注册，开关/规则在 `XssProperties` 中配置

**4. 匿名认证（@Anonymous）**

- **原理**：`@Anonymous` 标记 Controller 类或方法；`AnonymousPathScanner` 启动时扫描并汇总为匿名路径集合，`AnonymousAccessHandler` 在业务服务侧放行匿名请求
- **两种配置方式**：
  1. **注解方式**（推荐）：在 Controller 方法或类上标注 `@Anonymous`，由扫描器自动收集
  2. **配置方式**：在 `application.yml` 中配置 `litisle.security.anonymous.paths`（`AnonymousProperties`）补充静态白名单
- **网关侧另有白名单**：`litisle.gateway.jwt.paths`（`GatewayJwtProperties`），两者独立维护，互不替代
- **使用场景**：登录、注册、公开内容浏览、验证码获取、健康检查
- **使用示例**：
  ```java
  @Anonymous
  @GetMapping("/public/article/{id}")
  public Result<ArticleVO> getPublicArticle(@PathVariable Long id) { ... }
  ```
- **匿名上下文**：不再构建匿名 `SecurityContext`；需要区分是否登录时，业务层用 `StpUtil.isLogin()` 判断

#### 1.2.4 common-mq（MQ 基建，2026-11 补充）

> 本节为文档补充：原文只设计了 common-core / common-redis / common-security 三个子模块，代码库实际已新增 `litisle-common/common-mq`（批次九/十落地），用于统一 MQ 收发口径，避免各业务服务自行处理 ACK 与幂等。

> **包结构索引**：详见 [Design-Document.md](Design-Document.md) §2.1.1（唯一详述处，本文不再重复展开）。
>
> **能力**：统一发送入口、手动 ACK 收口、消息级 traceId 透传、eventId 消费幂等、收发/重试/死信指标、自动装配。

**使用约定**：

- 事件发送统一走 `MqSendUtil`（业务侧禁止直接注入 `RabbitTemplate`；content 另经 `ContentEventProducer` 叠加本地 outbox 必达）。
- 消费端统一 `MqAckHelper` 手动 ACK，禁止自动 ACK；失败按需 requeue 或走延迟重投/DLQ。
- 消费幂等优先用 `MqIdempotentHelper`（eventId 维度），跨服务重复投递不重复生效。
- 指标统一 `MqMetrics` 打点，与业务模块的 Micrometer 指标共用同一 registry。

### 1.3 鉴权方案（Sa-Token）

早期设计为 Spring Security + JWT（网关 `JwtGlobalFilter` 验签、业务服务 `SecurityConfig` 完整配置）。**现状已改为 Sa-Token**：网关由 `SaReactorFilter` 统一做登录校验与白名单放行，`UserContextHeaderFilter` 注入内部 Header；业务服务以 `@SaCheckLogin` 收口。`common-security` 的 Sa-Token 自动装配、用户ID解析与会话能力作为服务内部工具保留。过滤器顺序与网关结构见 §2.1。

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

**放置位置**：`litisle-api` 的 `dfa` 包（跨模块共用：引擎与场景常量集中一处，各业务服务只实现词库加载与场景调用）

```
litisle-api/src/main/java/com/litisle/litisleapi/dfa/
├── DfaFacade.java             # 统一入口：按场景扫描文本并返回命中结果
├── TextScanEngine.java        # 扫描引擎接口
├── AcdatTextScanEngine.java   # AC-DAT 多模式匹配实现（基于快照，线程安全）
├── CharNormalizer.java        # 全角/半角、异体字符归一化（防绕过）
├── DfaScenes.java             # 场景常量（文章/评论/用户名/私信）
├── WordEntry.java             # 词条
├── DfaHitResult.java          # 命中结果
├── WordlibSnapshot.java       # 词库快照（原子替换）
├── WordLibraryLoader.java     # 词库加载接口（各服务实现，如 auth 的 RedisWordLibraryLoader）
└── WordlibChangedMessage.java # 词库变更消息
```

**热更新机制**：词库变更通过 `WordlibChangedMessage` 通知，各服务由 `WordLibraryLoader` 重新构建 `WordlibSnapshot` 并整体替换引用；扫描线程读取不可变快照，切换期间不阻塞请求（auth 侧由 `WordlibChangeSubscriber` 订阅并触发加载）。

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
        
        // 获取当前用户（Sa-Token）
        Long userId = StpUtil.getLoginIdDefaultNull() instanceof Long
                ? (Long) StpUtil.getLoginIdDefaultNull() : null;
        
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
└── src/main/java/com/litisle/litislegateway/
    ├── LitisleGatewayApplication.java
    ├── filter/                 # 过滤器与异常处理
    │   ├── TraceIdFilter.java              # TraceId 注入（WebFilter）
    │   ├── UserContextHeaderFilter.java    # 用户上下文 Header 注入（GlobalFilter，order=-90）
    │   └── GlobalExceptionHandler.java     # 统一 JSON 异常（401/403/500）
    └── config/                 # 配置
        ├── SaTokenGatewayConfig.java       # SaReactorFilter：登录校验 + 白名单放行
        ├── GatewayJwtProperties.java       # 白名单 paths / 鉴权相关配置
        └── GatewayFilterConfig.java        # 过滤器装配
```

> **网关实际结构（2026-11 核对）**：网关只保留 `TraceIdFilter`、`UserContextHeaderFilter`、`GlobalExceptionHandler` 与 3 个配置类；没有 JWT 全局过滤器、请求日志过滤器、Sentinel 网关过滤器，也没有独立的 CORS / 路由 / WebSocket 处理器。路由在 `application.yml` 静态配置（`litisle-auth` / `user` / `content` / `social` / `payment` / `shop` / `review` / `file` / `ai` / `notification` / `search`，另有 `block-review-punish-status` 本地断点），未走 Nacos 动态路由。

**过滤器执行顺序（实际）**：

```
请求进入 → TraceIdFilter（WebFilter，注入/回传 traceId）
         → SaReactorFilter(order=-100，白名单放行 + StpUtil.checkLogin)
         → UserContextHeaderFilter(order=-90，注入 X-User-Id / X-Roles 等内部 Header)
         → 路由转发（lb://litisle-*）
```

**请求日志现状**：网关未单独实现请求日志过滤器；访问链路由 `TraceIdFilter` 注入/回传 traceId，配合各服务应用日志排查。如需访问日志，可在网关追加 GlobalFilter（当前未做，不属于已实现能力）。

### 2.2 可观测性约定（2026-11 补充）

**现状（已落地）**：

- **指标端点**：auth（8082）、user（8081）、file（8083）、content（8085）、search（8086）、review（8084）六个业务服务均在业务端口暴露 `/actuator/prometheus`，依赖 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`；网关未引 actuator，不暴露该端点。
- **抓取与告警配置**：[prometheus.yml](../../docker/init/prometheus/prometheus.yml) 固定 `job_name: litisle-services`，`metrics_path: /actuator/prometheus` 抓取上述六个业务端口；[alert-rules.yml](../../docker/init/prometheus/alert-rules.yml) 声明 **4 个规则组**：`litisle-redis-guard` / `litisle-moderation` / `litisle-search` / `litisle-content`；[alertmanager.yml](../../docker/init/prometheus/alertmanager.yml) 仅 `dev-null` 接收器（落控制台不外发）。
- **部署现状**：compose 未包含 Prometheus / Alertmanager 服务，上述配置需独立启动；Grafana 面板未接入。

**命名与类型约定**：

| 约定 | 说明 |
|---|---|
| 命名 | `litisle_<模块>_<语义>`，计数用 `_total` 后缀（Counter），当前状态量用 Gauge |
| 0 值可观测 | 「当前状态」类指标（如对账差异 `litisle_content_stat_reconcile_diff`）**预注册 Gauge 并每轮写入 0**，保证「一切正常」与「采集坏了」可区分 |
| 标签 | 标签基数受控：`type`（对账 6 类）/ `queue`（积压）/ `result`（成功失败）/ `outcome`（检索结果），禁止把用户 ID、内容 ID 打进标签 |
| 打点收口 | 业务模块统一在各自 `*Metrics` 类中注册与打点（`ContentMetrics` / `SearchMetrics` / `MqMetrics`），`MeterRegistry` 未装配时静默空转 |

**已落地指标摘要**：

- content：`litisle_content_feed_*`（降级/快照/画像缓存/曝光与点击写失败 Counter）、`litisle_content_stat_reconcile_diff{type}`（Gauge，6 类）
- search：`litisle_search_requests_total{type,outcome}`、`litisle_search_es_errors_total{type}`、`litisle_search_heat_flush_round_total`、`litisle_search_heat_flush_docs_total{result}`、`litisle_search_index_dlq_total{originQueue}`、`litisle_search_index_backlog{queue}`、`litisle_search_cache_dirty_mark_total{result}`、`litisle_search_cache_evict_merge_total{result}`
- 完整清单见 [content-index.md](../index/content-index.md) §十五 与 [search-index.md](../index/search-index.md) §八。

### 2.3 数据库初始化结构（2026-11 补充）

**目录结构**：

```
docker/init/mysql/
├── 00-init-databases.sql     # 建库 + 授权（依赖 entrypoint 注入的 MYSQL_USER，见已知风险）
├── 01-schema.sql             # 入口 1：SET NAMES utf8mb4 + source schema/ 下 5 个模块 schema
├── 02-seata-schema.sql       # Seata 事务表
├── 03-seed.sql               # 入口 2：SET NAMES utf8mb4 + source seed/ 下各模块种子
├── 10-nacos-schema.sql       # 0 字节占位（nacos 配置库由 nacos 镜像自建，当前不执行任何内容）
├── 20-nacos-user.sql         # nacos 业务账号授权
├── schema/                   # content / user / review / file / search 五个模块建表
└── seed/                     # content / review / user 三个模块种子数据
```

**设计要点**：

- **两层入口 + 子目录 source**：MySQL 官方 entrypoint 只执行 `initdb.d` 顶层的 `*.sql`，不递归子目录；`01/03` 用绝对路径 `source /docker-entrypoint-initdb.d/schema|seed/xxx.sql` 显式引入，字典序 `01 < 02 < 03 < 10 < 20` 保证建表早于种子。
- **字符集**：`01-schema.sql`、`03-seed.sql` 入口统一 `SET NAMES utf8mb4`（容器内客户端默认 latin1，否则中文分类名/表注释会写成 mojibake），被 source 的文件继承同一会话字符集。
- **幂等**：schema 全部 `create database/table if not exists`，种子 `insert ignore` / `ON DUPLICATE KEY`，重复执行不报错不丢数据。
- **统计基线**：批次十一的 7 张统计/互动表已合并进 `schema/content-schema.sql`，新库一次建齐；存量库的统计重算**不参与初始化**，只能手工执行 [content-stat-backfill.sql](../../tools/db-repair/content-stat-backfill.sql)。

**已知问题（详见 [next_update.md](../next/next_update.md) 批次十二「已知风险」）**：

- `00-init-databases.sql` 的 GRANT 依赖 `MYSQL_USER` / `MYSQL_PASSWORD` 环境变量（账号非 SQL 创建）。
- `10-nacos-schema.sql` 为 0 字节空文件，保留为占位。
- 存量库 `article.category` / `book.category` 存在 latin1 双重编码，依赖分类名精确匹配的逻辑对存量行失效。

### 2.4 部署与反向代理（同源部署，2026-11 补充）

**同源部署关系**：前端静态资源与 `/api` 由同一个 Nginx server 块提供（同协议、同域名/端口），浏览器发出的 `/api/...` 属于同源请求；网关不配置 CORS（不引入 `globalcors`），跨域问题由部署拓扑消除。

**Nginx 参考配置**（要点示意，主机名/地址按实际编排替换；网关端口见 [application.yml](../../litisle-gateway/src/main/resources/application.yml) 的 `server.port=8080`）：

```nginx
server {
    listen 80;
    server_name litisle.example.com;

    root /usr/share/nginx/html;
    index index.html;

    # SPA 路由回退：非静态资源路径回 index.html，交给前端路由
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 接口反向代理到网关
    location /api/ {
        # 关键：proxy_pass 末尾不加斜杠，原样透传 /api/... 路径。
        # 若写成 http://litisle-gateway:8080/（末尾带斜杠），Nginx 会截掉 location 匹配到的 /api/ 前缀，
        # 网关收到的路径与路由断言 Path=/api/** 对不上；若确需截前缀，必须同步修改前端请求路径约定与网关路由。
        proxy_pass http://litisle-gateway:8080;

        proxy_set_header Host $host;
        # 透传真实客户端 IP：匿名限流按 IP 计数依赖这两个头，缺一则全站共用一份额度
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

**必须注意的副作用（匿名限流按 IP 计数）**：推荐流匿名频控在 content 侧 `FeedRateLimitSupport`（匿名 20 次/60 秒，登录 60 次/60 秒；登录按 userId、匿名按 IP，两类额度互不影响）。Nginx 反代后若不透传真实 IP，网关与 content 只能看到 Nginx / 网关的内网地址，全站匿名用户共用同一份额度，会被瞬间打满并误伤所有人。已确认取值顺序为 `X-Forwarded-For` 首段 > `X-Real-IP` > `remoteAddr`（`FeedRateLimitSupport#resolveClientIp`，源码 80-95 行），不是只读 `remoteAddr`；网关当前不做应用层限流，也不改写 IP 头，只按 `application.yml` 静态路由原样转发，因此「Nginx 必须透传两个头」是这条链路成立的前提。

**开发环境**：Vite `server.proxy` 把 `/api` 指向本地网关，前端统一写相对路径 `/api/...`，不写死网关地址：

```ts
// vite.config.ts
export default defineConfig({
  server: {
    proxy: {
      // 相对路径 /api/... 由 Vite 转发到本地网关，前端代码无需区分环境
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
});
```