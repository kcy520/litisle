> 更说明**：相较 v1.0，移除 Sentinel Dashboard / Zipkin / Prometheus / Grafana / SkyWalking 五个独立中间件容器；限流熔断改用 Sentinel SDK（无 Dashboard，规则代码内联）、链路追踪用 MDC traceId（日志即可）、监控用 Spring Boot Actuator + 日志即可。文件存储统一为 SeaweedFS。

---

# 第一部分 · 总体架构

## 1.1 架构模式

**"微服务 + 单服务内简化三层"** 的混合架构：

- **宏观**：按业务能力垂直切分的微服务（Spring Cloud Alibaba 生态），每服务独立数据库（Database-per-service）。
- **微观**：每个服务内部采用简化三层——`controller / service / mapper`，配合 `domain(entity/dto/vo)`、`enums`、`constant`、`redis`、`config` 等辅助包，避免过度抽象。
- **共享层**：`litisle-common`（技术基建）+ `litisle-api`（跨服务契约）+ `litisle-file`（文件接入）。
- **边界**：`litisle-gateway` 为唯一外部入口。

## 1.2 总体架构图

```
                          ┌──────────────────────────────────────────┐
                          │         客户端（Web / H5 / 小程序）         │
                          └─────────────────────┬────────────────────┘
                                                │ HTTPS
                          ┌─────────────────────▼────────────────────┐
                          │           litisle-gateway（网关）           │
                          │  JWT校验 · 路由 · Sentinel限流 · WS透传      │
                          └──┬──────────┬──────────┬──────────┬──────┘
                             │  OpenFeign│(同步)    │ WebSocket │
        ┌────────────┬───────┼──────────┼──────────┼──────────┐
        │            │       │          │          │          │
   litisle-auth  litisle-user  litisle-content  litisle-social  litisle-ai
   (认证/JWT)    (账号/角色)    (文章/书籍/草稿)  (关注/赞/评/私信)  (小月/小审)
        │            │       │          │          │          │
        │            │       │   ┌──────▼──────┐   │          │
        │            │       │   │litisle-review│  │          │
        │            │       │   │(审核/举报/申诉)│  │          │
        │            │       │   └─────────────┘   │          │
        │            │       │          │          │     ┌────▼─────┐
        │            │       │   ┌──────▼──────┐    │     │litisle-  │
        │            │       │   │litisle-search│    │     │notification│
        │            │       │   │(ES 全文+向量) │    │     │(WS+站内信)│
        │            │       │   └─────────────┘    │     └──────────┘
        │       ┌────▼───────▼─────────────────────▼─────┐
        │       │            litisle-payment              │
        │       │  (VIP / 小硬币 / 赞赏 / 提现)            │
        │       │       └──────────┬───────────────────────┘
        │       │                  │
        │       │       ┌──────────▼───────────────────────┐
        │       │       │         litisle-shop               │
        │       │       │  (积分商城 / 商品 / 订单 / 运单)    │
        │       │       └───────────────────────────────────┘
        │                            │
   ┌────▼────────────────────────────▼──────────────────────────┐
   │                       共享层                                  │
   │  litisle-common (DFA SDK / 审计AOP / 安全上下文 / Redis工具)   │
   │  litisle-api    (Feign 客户端 / DTO / 事件 schema / 错误码)    │
   │  litisle-file   (SeaweedFS 接入：上传/直链/鉴权)               │
   └──────────────────────────────────────────────────────────────┘

   ┌──────────────────── 基础设施 ────────────────────────────────┐
   │ Nacos(注册+配置) · Redis(缓存/锁) · MySQL(持久化)              │
   │ Elasticsearch(全文+向量) · RabbitMQ(Confirm/ACK/DLQ/延迟)      │
   │ Seata AT(积分商城事务) · Sentinel SDK(限流/熔断，无Dashboard)    │
   │ SeaweedFS(Master×3+Volume×3+Filer×1，文件存储)                │
   └──────────────────────────────────────────────────────────────┘
```

## 1.3 技术栈选型

| 领域       | 选型                                   | 选型理由                                                     |
| ---------- | -------------------------------------- | ------------------------------------------------------------ |
| 运行时     | JDK 25 + Spring Boot 3.5.15            | 现有 pom 已锁定；Boot 3.5 ↔ Cloud 2025.0.x ↔ SCA 2025.0.0.0 兼容 |
| 微服务     | Spring Cloud 2025.0.3 + SCA 2025.0.0.0 | Nacos / Gateway / OpenFeign / Sentinel / Seata 一站式        |
| 安全       | Spring Security 6 + OAuth2 + JWT       | 层次化角色用 `RoleHierarchy` 实现                            |
| ORM        | MyBatis-Plus 3.5.7                     | 乐观锁、逻辑删除、配置加密                                   |
| 缓存       | Redis + Redisson 3.52.0                | Set/String/Hash/BitMap + 分布式锁                            |
| 搜索       | Elasticsearch 8.18.8                   | 全文检索 + dense_vector KNN                                  |
| MQ         | RabbitMQ                               | Confirm + 手动 ACK + DLQ + 延迟消息                          |
| 分布式事务 | Seata AT                               | 仅积分商城一处跨服务事务                                     |
| 限流熔断   | Sentinel SDK（无 Dashboard）           | 规则代码内联 + Nacos 动态规则，无需独立容器                  |
| AI         | LangChain4j                            | 单服务多 Agent（小月有状态 / 小审无状态）+ RAG 知识库        |
| 向量存储   | Elasticsearch dense_vector             | 复用现有 ES 集群，KNN 检索百万级 chunk 够用，零新增中间件    |
| 敏感词     | sensitive-word                         | DFA + MySQL 词库 + Redis Pub 热更新                          |
| 文件存储   | **SeaweedFS**                          | 海量小文件 + 卷级扩容 + S3 兼容                              |
| 文档       | Knife4j 4.5.0                          | 网关聚合各服务文档                                           |
| 链路追踪   | MDC + traceId（日志即可）              | 不引入 Zipkin，日志带 traceId 足够排查                       |
| 监控       | Spring Boot Actuator + 日志            | 不引入 Prometheus/Grafana，actuator 端点 + 日志告警          |

## 1.4 中间件落地矩阵（精简版）

| 中间件        | 落地方式                                      | 持久化/HA                      | 必要性               |
| ------------- | --------------------------------------------- | ------------------------------ | -------------------- |
| **SeaweedFS** | docker-compose: Master×3 + Volume×3 + Filer×1 | Volume 副本（replication=001） | P0 唯一独立文件存储  |
| Nacos         | docker 独立容器 + MySQL 后端                  | MySQL 持久化 + 集群 HA         | 基础设施             |
| Redis         | docker 单实例（未来可升集群）                 | RDB + AOF                      | 基础设施             |
| MySQL         | docker 单实例（未来可主从）                   | binlog                         | 基础设施             |
| Elasticsearch | docker 单节点（未来可集群）                   | 持久化卷                       | 基础设施             |
| RabbitMQ      | docker 单实例（未来可镜像队列）               | 持久化卷                       | 基础设施             |
| Seata Server  | docker 独立容器                               | file/DB 模式                   | 基础设施（仅积分商城用）     |

> **移除项说明**：Sentinel Dashboard（规则改为代码内联+Nacos动态规则）、Zipkin（改用MDC traceId）、Prometheus+Grafana（改用Actuator+日志）、SkyWalking（不启用）。

## 1.5 通信协议

| 通信类型   | 协议/技术                      | 适用场景                                              |
| ---------- | ------------------------------ | ----------------------------------------------------- |
| 外部入口   | HTTPS + REST                   | 客户端 ↔ Gateway                                      |
| 服务间同步 | OpenFeign + HTTP               | 强依赖即时返回（如发帖校验作者身份）                  |
| 服务间异步 | RabbitMQ（Topic/Direct + DLQ） | 解耦、削峰、最终一致（审核流转/通知/索引同步/AI触发） |
| 实时推送   | WebSocket（Gateway 透传）      | 私信在线推送、通知实时触达                            |
| 分布式事务 | Seata AT                       | 仅积分商城（payment ↔ shop）                            |
| 配置/注册  | Nacos（gRPC + HTTP）           | 服务发现、配置下发、词库热更新通知                    |
| 文件       | S3 API over HTTP               | 业务 ↔ SeaweedFS Filer                                |
| 链路       | MDC traceId（日志透传）        | 跨服务排查（Gateway 注入，Feign 透传）                |

## 1.6 数据流模式

四类核心数据流：

1. **命令同步流**：写操作走 Gateway → 业务服务 → MySQL，同时发 MQ 事件异步更新 ES/缓存/计数。
2. **审核编排流**（事件驱动状态机）：Content 发 `content.submitted`（事件体不含正文，Review 经 ContentClient 回查）→ Review 消费端切片/机审（DFA+LLM）→ 据 risk 路由 → 发 `moderation.result` 统一回写 → Content CAS 消费改状态 + 通知作者。
3. **读放大流**：读请求先查 Redis（热点/计数/关注关系），未命中查 DB 回填，布隆过滤器防穿透、随机 TTL 防雪崩、互斥锁防击穿。
4. **事务一致性流**：积分商城下单 = payment 扣用户币(AT 分支) + shop 创建订单/扣库存(AT 分支) + 生成运单，TC 全局提交/回滚。赞赏改为 payment 单库本地事务，无需 Seata。
5. **RAG 知识库流**：Content 发 `content.published` → AI 服务接收 → 质量门控（字数/角色/可见性/DFA） → 正文清洗切片 → Embedding 向量化 → 写入 ES dense_vector 索引。文章下线时监听 `content.unpublished` 移除对应向量。

## 1.7 模块全景（13 模块）

| 类型      | 模块                                                         | 职责                             |
| --------- | ------------------------------------------------------------ | -------------------------------- |
| 共享·库   | `litisle-common` / `litisle-api` / `litisle-file`            | 技术基建 / 跨服务契约 / 文件接入 |
| 边界·服务 | `litisle-gateway`                                            | JWT/路由/限流/WS透传             |
| 业务·服务 | `litisle-auth` / `litisle-user` / `litisle-content` / `litisle-social` / `litisle-review` / `litisle-ai` / `litisle-payment` / `litisle-shop` / `litisle-search` / `litisle-notification` | 10 个业务微服务                  |

---

# 第二部分 · 分模块详细架构

## 2.1 共享层

### 2.1.1 litisle-common（技术基建库）

**模块拆分**：拆分为 3 个子模块，避免"少数模块引入多余依赖"。

```
litisle-common
├── common-core/      # 核心基础（所有服务必须依赖）
│   ├── exception/    # 全局异常处理 + BizException + ErrorCode
│   ├── result/       # Result<T> / PageResult<T> 统一响应
│   ├── constant/     # CommonConstant / HeaderConstant
│   ├── enums/        # StatusEnum / DelFlagEnum
│   ├── util/         # AssertUtil / JsonUtil / PageUtil
│   ├── config/       # JacksonConfig / MyBatisPlusConfig
│   └── sensitive/    # DFA 敏感词引擎（多场景策略 + 双 buffer 热更新）
├── common-redis/     # Redis 工具（大部分服务需要，网关可选）
│   ├── config/       # RedisConfig / RedissonConfig
│   ├── util/         # RedisUtil（String/Hash/Set/ZSet/List）
│   ├── lock/         # DistributedLock 接口 + RedissonLock 实现
│   ├── bloom/        # BloomFilter 布隆过滤器
│   └── constant/     # CacheConstant（Redis Key 前缀）
└── common-security/  # 安全上下文（业务服务需要，网关不需要完整实现）
    ├── context/      # SecurityContext + SecurityContextHolder（ThreadLocal）
    ├── annotation/   # @RequireRole / @RequirePermission
    ├── aspect/       # RoleCheckAspect（角色校验切面）
    ├── feign/        # FeignAuthInterceptor（透传 userId/traceId）
    ├── audit/        # @AuditLog + AuditLogAspect（审计日志）
    ├── idempotent/   # @Idempotent + IdempotentAspect（接口幂等性）
    ├── desensitize/  # @Sensitive + SensitiveSerializer（数据脱敏）
    └── xss/          # XssFilter + XssHttpServletRequestWrapper（XSS 防护）
```

**核心能力**：

- DFA 敏感词过滤（文章/评论/用户名/私信四策略）
- 审计切面（@AuditLog 自动记录操作日志）
- JWT 上下文透传（Feign 调用自动透传用户信息）
- 统一异常响应（GlobalExceptionHandler + ErrorCode）
- Redis 工具（六大数据结构 + 分布式锁 + 布隆过滤器）
- **接口幂等性**（@Idempotent 基于 Redis SETNX，防重复提交）
- **数据脱敏**（@Sensitive 基于 Jackson 序列化器，手机号/身份证/邮箱自动脱敏）
- **XSS 防护**（全局过滤器清洗 HTML 标签，防跨站脚本攻击）

### 2.1.2 litisle-api（跨服务契约库）

```
litisle-api
├── feign/            # UserFeignApi / ContentFeignApi / AiFeignApi ...
├── dto/              # UserBriefDTO / ArticleCardDTO / ReviewResultDTO ...（跨服务 Feign 出入参）
├── vo/               # UserProfileVO / ApiResultVO ...（跨服务视图对象，响应给前端）
├── event/            # ArticlePublishedEvent / ReviewDecidedEvent ...（MQ schema）
├── enums/            # RoleEnum / ZoneEnum / RiskLevelEnum / SceneEnum / OrderStatusEnum ...
├── constant/         # MqConstant / CacheConstant / FeignConstant ...（跨服务常量）
└── result/           # ErrorCode 枚举集中维护
```

**核心约束**：业务服务之间**只通过本模块的 Feign 接口或 MQ 事件通信**，禁止直接 import 对方 internal 包。

### 2.1.3 litisle-file（SeaweedFS 接入库）

```
litisle-file
├── client/           # SeaweedFsClient（S3 兼容 SDK，对接 Filer）
├── service/          # FileService（上传/直链/预签名/删除）
├── thumbnail/        # ThumbnailGenerator（按需缩略图）
└── enums/            # BizTypeEnum / AlignEnum / AccessLevelEnum
```

**核心能力**：分片上传、预签名短时效 URL、图片对齐信息存储、sha256 去重、私有文件鉴权下载。

---

## 2.2 网关层

### 2.2.1 litisle-gateway

```
litisle-gateway
├── filter/           # JwtGlobalFilter / SentinelGatewayFilter
├── config/           # AuthProperties（白名单）/ CorsConfig / RouteConfig
└── handler/          # GlobalExceptionHandler / WsHandler
```

| 项       | 内容                                                         |
| -------- | ------------------------------------------------------------ |
| 主要职责 | 唯一外部入口：JWT 校验、路由、限流、WS 透传、Knife4j 聚合    |
| 核心功能 | 路由分发；白名单（登录/注册/公开内容）；Token 解析 → 注入 `X-User-Id`/`X-Roles` header；Sentinel 限流；WS 路由透传 |
| 关键组件 | `JwtGlobalFilter`、`AuthProperties`、`SentinelGatewayFilter`、路由配置(Nacos) |
| 数据模型 | 无（路由配置存 Nacos）                                       |
| 外部依赖 | spring-cloud-gateway、Sentinel SDK、Nacos、Redis（Token 黑名单） |
| API      | 透传所有下游 `/api/{service}/**`                             |
| 流程     | 请求 → 白名单判断 → JWT 校验 → 注入用户上下文 → Sentinel 限流 → 路由转发 |
| 错误处理 | 401（未登录）/403（无权限）/429（限流）统一响应体            |
| 安全     | Token 黑名单校验（Redis，TTL=Token 剩余寿命）；多端登录校验（同账号最多 N 端，N 由 yml 配置）；敏感 Header 过滤（移除客户端伪造的 X-User-Id/X-Roles）；CORS 白名单 |
| 审计     | RequestLogGlobalFilter 记录：traceId、userId、请求方法、路径、入参摘要、响应状态、耗时 |
| 性能     | 响应式（Netty）；路由缓存；限流维度=接口+用户；请求日志异步写入 |

**AuthProperties 配置（白名单 + 匿名访问）**：

网关从 Nacos 读取白名单配置，支持两种匿名访问控制方式：

1. **配置方式**：`litisle.security.anonymous.paths` 配置路径模式
2. **注解方式**：业务服务 Controller 标注 `@Anonymous`，网关通过 Feign 调用各服务的 `/actuator/anonymous-paths` 端点动态收集白名单（启动时加载，变更时刷新）

两种方式合并为最终白名单，匹配命中的请求跳过 JWT 校验，直接路由转发。

---

## 2.3 业务服务层

### 2.3.1 litisle-auth（认证服务）

```
litisle-auth
├── controller/        # AuthController / OAuth2Controller
├── service/           # AuthService / SmsCodeService / CaptchaService
├── domain/
│   ├── entity/        # （复用 user 库实体，无本服务独立实体）
│   ├── dto/           # LoginDTO / RefreshDTO
│   └── vo/            # TokenVO / CaptchaVO
├── enums/             # （本服务无私有枚举，使用 api 共享）
├── constant/          # AuthConstant（Token 有效期、黑名单 TTL 等）
├── security/          # JwtTokenProvider / SessionManager
├── oauth/             # WechatHandler / QQHandler
├── redis/             # TokenBlacklist / SessionCache
└── config/            # AuthProperties（yml + JavaBean） / AuthConfig
```

| 项       | 内容                                                         |
| -------- | ------------------------------------------------------------ |
| 主要职责 | 注册/登录/退出/OAuth2(微信/QQ)/图形验证码/JWT 签发与刷新/在线设备管理 |
| 核心功能 | 账密+手机验证码+OAuth2 三方式；JWT 双 Token；同账号最多 N 端在线；记住我；退出加 Redis 黑名单 |
| 关键组件 | `AuthService`、`JwtTokenProvider`、`SmsCodeService`、`CaptchaService`、`OAuth2LoginHandler`、`SessionManager`、`AuthProperties` |
| 数据模型 | 复用 user 库的 `sys_user`；新增 `sms_code`、`oauth_binding`（在 user 库） |
| 外部依赖 | spring-security、JWT、Redis、短信网关、UserFeign、litisle-common(DFA 校验用户名) |
| API      | `POST /auth/register`、`POST /auth/login`、`POST /auth/logout`、`POST /auth/refresh`、`GET /auth/captcha`、`POST /auth/oauth/{provider}` |
| 流程     | 登录：校验验证码 → UserFeign 查账号 → BCrypt 校验 → 签 JWT → 写 Redis session(踢出超额设备) → 返回 Token |
| 错误处理 | 账号冻结/注销返回特定码；连续失败 5 次锁 15 分钟             |
| 安全     | 密码 BCrypt；Token 短效 + Refresh；黑名单 TTL=Token 剩余寿命；OAuth2 防 state CSRF |
| 性能     | JWT 无状态签发；session 走 Redis；验证码图片缓存             |

**认证配置（yml + JavaBean）**：

使用 `@ConfigurationProperties` 将 yml 配置绑定到 JavaBean，集中管理认证相关参数。

```yaml
# application.yml
litisle:
  security:
    # JWT 配置
    jwt:
      secret: ${JWT_SECRET:litisle-secret-key-at-least-256-bits-for-hs256}
      # 记住我模式：长效 Token（默认 7 天）
      remember-me-expire: 604800
      # 普通模式：短效 Token（默认 30 分钟）
      access-token-expire: 1800
      # Refresh Token（默认 7 天）
      refresh-token-expire: 604800
    # 会话配置
    session:
      # 同账号最大同时在线数（踢出最旧的）
      max-concurrent: 2
      # 会话过期时间（秒）
      session-timeout: 604800
    # 登录失败锁定策略
    login:
      # 连续失败阈值
      max-fail-count: 5
      # 锁定时长（分钟）
      lock-minutes: 15
    # 匿名访问白名单
    anonymous:
      paths:
        - /api/auth/login
        - /api/auth/register
        - /api/auth/captcha
        - /api/auth/oauth/**
        - /api/content/article/list
        - /api/content/article/{id}
```

```java
// AuthProperties.java（JavaBean 绑定）
@Data
@Component
@ConfigurationProperties(prefix = "litisle.security")
public class AuthProperties {
    private Jwt jwt = new Jwt();
    private Session session = new Session();
    private Login login = new Login();
    private Anonymous anonymous = new Anonymous();
    
    @Data
    public static class Jwt {
        private String secret;
        private long rememberMeExpire = 604800;
        private long accessTokenExpire = 1800;
        private long refreshTokenExpire = 604800;
    }
    
    @Data
    public static class Session {
        private int maxConcurrent = 2;
        private long sessionTimeout = 604800;
    }
    
    @Data
    public static class Login {
        private int maxFailCount = 5;
        private int lockMinutes = 15;
    }
    
    @Data
    public static class Anonymous {
        private List<String> paths = new ArrayList<>();
    }
}
```

**关键能力说明**：

- **限制用户同时登录最大数**：`SessionManager` 基于 Redis List 维护用户会话列表，新登录时检查 `max-concurrent`，超出则踢出最旧的会话（加入 Token 黑名单）
- **记住我模式**：登录接口接收 `rememberMe` 参数，为 `true` 时使用 `remember-me-expire` 作为 Token TTL，否则使用 `access-token-expire`
- **匿名认证**：配合 common-security 的 `@Anonymous` 注解，网关扫描注解 + yml 白名单双重控制匿名访问

### 2.3.2 litisle-user（用户服务）

```
litisle-user
├── controller/        # UserController / ProfileController
├── service/           # UserService / RoleService / VipStateService
├── mapper/            # SysUserMapper / SysRoleMapper
├── domain/
│   ├── entity/        # SysUser / SysRole
│   ├── dto/           # UserUpdateDTO
│   └── vo/            # UserDetailVO / UserProfileVO
├── enums/             # UserStatusEnum（NORMAL / FROZEN / LOGGED_OFF）
├── constant/          # UserBizConstant（VIP 时长、默认头像等）
├── redis/             # RoleCache / VipCache
└── config/            # UserConfig
```

| 项       | 内容                                                         |
| -------- | ------------------------------------------------------------ |
| 主要职责 | 账号资料、角色体系(READER/AUTHOR/LEGACY_AUTHOR + REVIEWER/ADMIN/SUPER_ADMIN)、VIP 状态、注销 |
| 核心功能 | 资料增改查；角色叠加校验（组合规则）；VIP 到期冻结；头像金/白标识计算；逻辑注销 |
| 关键组件 | `UserService`、`RoleService`、`VipStateService`              |
| 数据模型 | `sys_user`(已有+ALTER补phone/bio/vip_expire_time)、`sys_role`(已有+补4角色)、`sys_user_role`、`sys_user_profile`、`sys_oauth_binding` |
| 外部依赖 | MyBatis-Plus、Redis、litisle-file(头像)、litisle-api、litisle-common |
| API      | `GET/PUT /user/profile`、`GET /user/{id}/brief`、`POST /user/logout-account`、`GET /user/{id}/roles` |
| 流程     | 角色叠加：申请 AUTHOR → review 审核 → 通过则 `sys_user_role` insert + 清角色缓存 → 标识变金 |
| 错误处理 | 注销不可恢复 → 二次确认 + 软删 `del_flag=1`；角色冲突(LEGACY+AUTHOR)拒绝 |
| 安全     | 手机/邮箱脱敏；明文查看需临时解密+审计；SUPER_ADMIN 仅 dev profile 启用 |
| 性能     | 角色标识缓存；粉丝/关注计数用 Redis String                   |


补充内容：

```
注销用户 del_flag=1 后，手机号不再跟活跃用户 (phone, 0) 抢唯一索引位，同一手机号可以重新注册。
冷却期机制：

注销时写入 cancel_time = NOW()

重新注册时业务层判断 cancel_time + 30天 < NOW() 才放行

冷却期内 → 拒绝注册

冷却期过 → 清除旧 (phone, 1) 记录 → 允许注册

openId 不会被"已注销"记录占位，同一个微信/QQ 可以随时重新绑定新账号。审计不靠数据库日志表，靠 AOP 统一切面记录操作人、时间、参数。
```




### 2.3.3 litisle-content（内容服务）

```
litisle-content
├── controller/        # ArticleController / BookController / DraftController
├── service/           # ArticleService / BookService / DraftService / FeedService
├── mapper/            # ArticleMapper / BookMapper
├── domain/
│   ├── entity/        # Article / Book / ArticleDraft / BookChapter / ArticleImage
│   ├── dto/           # ArticleCreateDTO / DraftSaveDTO
│   └── vo/            # ArticleDetailVO / BookCardVO
├── enums/             # （本服务无私有枚举，使用 api 共享）
├── constant/          # ContentBizConstant（推荐流长度等；切片字数上限已归 review 侧 llm.max-text-chars）
├── mq/                # ContentEventProducer（submitted/published/unpublished）
└── config/            # ContentConfig
```

| 项       | 内容                                                         |
| -------- | ------------------------------------------------------------ |
| 主要职责 | 文章/书籍/合集/草稿、分区(普通/作家/推荐流)、图片插入与对齐、大篇幅送审（切片/聚合收口 review 消费端） |
| 核心功能 | 草稿自动保存；图片任意位置插入+5 种对齐；分区互斥；普通→作家升级申请；合集章节目录+阅读历史；送审仅发单条 `content.submitted`（事件体不含正文，切片禁静默截断由 review 侧兑底） |
| 关键组件 | `ArticleService`、`BookService`、`CollectionService`、`DraftService`、`ZoneRouter`、`ContentEventProducer`、`FeedService`、`FavoriteService` |
| 数据模型 | `article`、`article_image`、`article_draft`、`book`、`book_chapter`、`reading_history`、`favorite`（一期收藏临时承载） |
| 章节阅读编排（阶段17） | BookService 三读方法（getDetail/pageChapters/getChapterDetail）：visibilities 按查看者计算（作者全可见/匿名仅公开/关注者回查降级），章节正文复用文章判定链，BookReadSupport 承接浏览计数+书籍历史（fail-safe）；出参 BookDetailVO（首尾章+lastRead*+favorited）/ BookChapterDetailVO（prev/next 导航） |
| 外部依赖 | MyBatis-Plus、Redis、litisle-file、litisle-api(AI/Review/User Feign)、MQ |
| API      | `POST /content/article`、`PUT /content/article/{id}`、`POST /content/draft/save`、`POST /content/book`、`GET /content/feed`、`POST /content/upgrade-apply` |
| 流程     | 发布：校验+落库（CAS 0/3→1）→ afterCommit 发单条 `content.submitted`（切片/聚合收口 review 消费端）→ `moderation.result` 事件驱动回写状态 → 已发布/驳回 |
| 错误处理 | 草稿保存幂等；切片失败重试不静默截断（review 消费端兑底）；分区冲突拒绝                     |
| 安全     | 作家分区跳 DFA 全量人工；图片走 file 鉴权                    |
| 性能     | 推荐流预计算+缓存；正文大字段延迟加载；切片并行(线程池)      |





### 2.3.4 litisle-social（社交服务）

```
litisle-social
├── controller/        # FollowController / LikeController / CommentController / MessageController
├── service/           # FollowService / LikeService / CommentService / MessageService
├── mapper/            # FollowMapper / CommentMapper / MessageMapper
├── domain/
│   ├── entity/        # Follow / Comment / Message / Conversation / FavoriteFolder
│   ├── dto/           # MessageSendDTO / CommentCreateDTO
│   └── vo/            # FollowVO / CommentTreeVO / ConversationVO
├── enums/             # ConversationStateEnum / MessageSettingEnum
├── constant/          # SocialBizConstant（关注上限、陌生人 quota 等）
├── redis/             # FollowSet / LikeSet / StrangerQuota
├── mq/                # SocialEventProducer
└── config/            # SocialConfig
```

| 项       | 内容                                                         |
| -------- | ------------------------------------------------------------ |
| 主要职责 | 关注/点赞/评论(三级折叠)/收藏夹(一期 content favorite 临时承载单层收藏，立项后迁移/合并)/佳句佳段收藏/私信(陌生人3条限制+确认机制) |
| 核心功能 | 关注上限(500/2000)；点赞防重+计数；评论三级；收藏分组；佳句带出处+备注；私信关系策略(互关/单向/陌生人/关闭) |
| 关键组件 | `FollowService`、`LikeService`、`CommentService`、`FavoriteService`、`QuoteFavoriteService`、`MessageService`、`StrangerQuotaService`、`MessageConfirmService` |
| 数据模型 | `follow`、`like_record`、`comment`、`favorite_folder`、`favorite_item`、`quote_favorite`、`conversation`、`message`、`message_blacklist`、`message_setting` |
| 外部依赖 | Redis、Redisson、MQ、litisle-api(User/Content Feign)、litisle-common(DFA+小审私信) |
| API      | `POST /social/follow`、`POST /social/like`、`POST /social/comment`、`POST /social/favorite`、`POST /social/quote`、`POST /social/message/send`、`POST /social/message/confirm` |
| 流程     | 陌生人发首条：校验 quota→发(1/3)→DFA+小审→对方收通知[确认/忽略/拉黑]→确认建通道/忽略不可续/拉黑永久禁 |
| 错误处理 | quota 用尽提示；被拉黑提示"对方已关闭私信"；点赞幂等         |
| 安全     | 私信实时过 DFA+骚扰检测；评论走审核链路；佳句备注≤200 字     |
| 性能     | 关注/点赞纯 Redis；计数 String incr；私信长连接走 notification |

### 2.3.5 litisle-review（审核服务）

```
litisle-review
├── controller/        # ReviewTaskController / ReportController / AppealController / SensitiveWordController
├── service/           # ReviewTaskService(状态机) / ReportService / AppealService / ViolationScoreService
├── mapper/            # ReviewTaskMapper / SensitiveWordMapper
├── domain/
│   ├── entity/        # ReviewTask / Report / Appeal / ViolationScore / SensitiveWord
│   ├── dto/           # ReviewDecideDTO / ReportCreateDTO
│   └── vo/            # ReviewTaskVO / ReportDetailVO
├── enums/             # ReviewStateEnum / RiskLevelEnum
├── constant/          # ReviewBizConstant（违规分阈值、任务超时时间等）
├── mq/                # ReviewEventConsumer / ReviewEventProducer
├── dfa/               # WordLibraryLoader（订阅 Redis 热更新）
└── config/            # ReviewConfig
```

| 项       | 内容                                                         |
| -------- | ------------------------------------------------------------ |
| 主要职责 | Layer3 人工二审工作流、举报处理、申诉裁决、违规分累积、作家申请审核、敏感词库运营管理 |
| 核心功能 | 待审队列；REVIEWER 认领/通过/驳回(附修改意见)；举报→小审初审→转人工；申诉裁决；违规分阈值触发禁言/封禁；词库增删改+热更新通知 |
| 关键组件 | `ReviewTaskService`(状态机)、`ReportService`、`AppealService`、`ViolationScoreService`、`SensitiveWordLibraryService` |
| 数据模型 | `review_task`、`report`、`appeal`、`violation_score`、`sensitive_word_library`、`author_apply` |
| 外部依赖 | MyBatis-Plus、Redis、MQ、litisle-api(AI/Content/User Feign)、litisle-common(DFA) |
| API      | `GET /review/tasks`、`POST /review/task/{id}/decide`、`POST /review/report`、`POST /review/appeal/{id}/judge`、`POST /review/sensitive-word` |
| 流程     | 收 `content.submitted` → ContentClient 回查正文 → 切片+机审三分态 → 发 `moderation.result` 统一回写（不跨库直写业务表）→ SUSPICIOUS 进人审，人审终态同样经 result 事件回写 → 通知作者 |
| 错误处理 | 任务超时自动转超时队列；决策幂等(乐观锁)                     |
| 安全     | 决策记审计不可改；REVIEWER 只看自己认领/未认领；词库改动留痕 |
| 性能     | 任务池分片；DFA 本地执行免网络；小审异步回调                 |

### 2.3.6 litisle-ai（AI 服务）

```
litisle-ai
├── controller/        # XiaoyueController / XiaoshenController / SessionController
├── service/           # XiaoyueAgent / XiaoshenAgent / SessionStore / TokenQuotaService
├── domain/
│   ├── entity/        # AiSession / AiMessage / AiTokenUsage
│   ├── dto/           # XiaoyueRequestDTO / XiaoshenRequestDTO
│   └── vo/            # XiaoyueResponseVO / JudgeResultVO
├── enums/             # AgentTypeEnum
├── constant/          # AiBizConstant（Session 上限、Token 配额等）
├── llm/               # LangChain4j Client 配置
├── rag/               # RAG 知识库（ES dense_vector）
│   ├── ingest/        # KnowledgeBaseIngestService（文章发布自动入库）
│   ├── retrieval/     # KnowledgeBaseRetriever（TopK 检索 + 相似度阈值）
│   ├── chunk/         # ContentChunker（正文清洗 + 切片 500 字/段 重叠 50 字）
│   └── embedding/     # EmbeddingClient（调用 Embedding API）
├── redis/             # SessionMemory / LRU 淘汰
├── sentinel/          # AiLimiter（线程隔离）
├── mq/                # RagIngestConsumer（监听 content.published / content.unpublished）
└── config/            # AiConfig
```

| 项       | 内容                                                         |
| -------- | ------------------------------------------------------------ |
| 主要职责 | 小月(有状态对话：润色/结构/标签/总结/深度分析/相似推荐+RAG知识库检索)、小审(无状态判定输出结构化JSON)、RAG知识库管理、会话管理、Token 统计与限流 |
| 核心功能 | 单服务多 Agent(agent_type)；小月 LRU 暂存(10/30)+已保存(100/200)；小审输出 `{risk,reason,exempt}`；Token 配额(VIP 不限/普通计数)；RAG 知识库自动摄入+检索+引用展示 |
| 关键组件 | `XiaoyueAgent`(LangChain4j ChatAgent+Memory+RAG)、`XiaoshenAgent`(无状态+结构化输出)、`KnowledgeBaseIngestService`、`KnowledgeBaseRetriever`、`ContentChunker`、`SessionStore`、`TokenQuotaService`、`AiLimiter` |
| 数据模型 | `ai_session`、`ai_message`、`ai_token_usage`；ES 索引 `article_chunks`（向量+元数据） |
| 外部依赖 | LangChain4j、LLM Provider、Embedding API、Redis、MySQL、ES(dense_vector)、Sentinel(线程隔离)、litisle-api(Content/Search Feign) |
| API      | `POST /ai/xiaoyue/polish`、`POST /ai/xiaoyue/analyze-structure`、`POST /ai/xiaoyue/summary`、`POST /ai/xiaoshen/judge`、`GET /ai/sessions`、`POST /ai/xiaoyue/chat`（带 RAG 引用） |
| 流程     | 小审：Content/Review Feign 调 `judge` → 组 prompt → LLM → 解析 JSON → 返回 risk；小月：带 session_id+Memory → LLM → 持久化；RAG 摄入：监听 `content.published` → 质量门控 → 切片 → Embedding → 写 ES；RAG 检索：路由判断 → Embedding → TopK → 阈值过滤 → 组 prompt → LLM → 附引用卡片 |
| 错误处理 | LLM 超时/限流降级返回"服务繁忙"；结构化解析失败重试1次兜底低风险；RAG 向量库不可用降级为纯 LLM 回答 |
| 安全     | Prompt 注入防护；小审结果需复核；Token 防刷；RAG 仅摄入公开文章（visibility=PUBLIC） |
| 性能     | 小审无状态可水平扩展；小月 Memory 用 Redis；Sentinel 线程隔离防拖垮；RAG 摄入异步解耦不阻塞发布；检索路由判断节省 Embedding 调用 |

### 2.3.7 litisle-payment（支付服务）

```
litisle-payment
├── controller/        # VipController / CoinController / RewardController / WithdrawController / CoinDeductionController
├── service/           # VipOrderService / CoinService / RewardService / WithdrawService / CoinDeductionService
├── mapper/            # CoinAccountMapper / TransactionMapper
├── domain/
│   ├── entity/        # CoinAccount / CoinTransaction / VipOrder / RewardRecord / WithdrawRequest
│   ├── dto/           # RechargeDTO / WithdrawDTO
│   └── vo/            # CoinBalanceVO / TransactionVO
├── enums/             # TransactionTypeEnum / WithdrawStatusEnum
├── constant/          # PaymentBizConstant（汇率、抽成比例、提现门槛等）
├── seata/             # Seata 配置（供 shop 调用的 AT 分支参与）
├── mq/                # PaymentEventProducer
├── util/              # MicroCoinConverter（BIGINT↔BigDecimal）
└── config/            # PaymentConfig
```

| 项       | 内容                                                         |
| -------- | ------------------------------------------------------------ |
| 主要职责 | VIP 套餐购买、小硬币充值、赞赏(本地事务)、提现、积分商城扣币(AT 分支)、双层币种(BIGINT 微币/展示小硬币) |
| 核心功能 | 套餐→赠币；1元=100币；赞赏平台抽10%；提现≥1000币起；展示层 BigDecimal/100，存储运算 BIGINT；对外提供扣币 Feign 接口供 shop 调用（AT 分支） |
| 关键组件 | `VipOrderService`、`CoinService`、`RewardService`、`WithdrawService`、`CoinDeductionService`(AT 分支)、`MicroCoinConverter` |
| 数据模型 | `vip_order`、`coin_account`、`coin_transaction`、`reward_record`、`withdraw_request`、`undo_log`(Seata，仅供积分商城 AT 分支使用) |
| 外部依赖 | MyBatis-Plus(乐观锁)、litisle-api(User Feign)、MQ、Seata(仅积分商城扣币参与 AT 分支) |
| API      | `POST /payment/vip/buy`、`POST /payment/coin/recharge`、`POST /payment/reward`、`POST /payment/withdraw`、`POST /payment/coin/deduct`（Feign 内部，供 shop 调用） |
| 流程     | 赞赏：CoinService 本地事务 → 扣读者币 → 加作者币(同库 coin_account) → 写流水 → 发消息通知 → 结束。无需 Seata。 |
| 错误处理 | 余额不足回滚；提现审核走 review；并发用乐观锁；赞赏无 Seata 依赖更简洁 |
| 安全     | 金额校验；提现二次审核；流水不可篡改；扣币接口仅允许 shop 服务调用（Feign 内部鉴权） |
| 性能     | 赞赏本地事务无网络开销；币运算 BIGINT 避精度                 |

### 2.3.8 litisle-shop（积分商城服务）

```
litisle-shop
├── controller/        # ShopAdminController（商品管理）/ ShopOrderController（用户下单）
├── service/           # ProductService / OrderService / ShippingService / PurchaseService(@GlobalTransactional)
├── mapper/            # ShopProductMapper / ShopOrderMapper / ShippingMapper
├── domain/
│   ├── entity/        # ShopProduct / ShopOrder / Shipping
│   ├── dto/           # OrderCreateDTO / ShippingDTO
│   └── vo/            # ProductVO / OrderDetailVO
├── enums/             # OrderStatusEnum / ShippingStatusEnum
├── constant/          # ShopBizConstant（库存超时释放 TTL、限购数量等）
├── seata/             # Seata 配置（全局事务发起方）
├── mq/                # ShopEventProducer
├── redis/             # StockCache（库存预扣 Lua）
└── config/            # ShopConfig
```

| 项       | 内容                                                         |
| -------- | ------------------------------------------------------------ |
| 主要职责 | 积分商城：管理员上架/下架商品、用户下单购买（限时限量）、订单流转、运单管理 |
| 核心功能 | 商品 CRUD（管理员）；限时限量抢购（库存预扣+超时释放）；关注粉丝数≥1 资格校验；小硬币支付（跨服务 AT 全局事务）；订单状态机（已支付→已发货→已送达→已完成）；运单关联 |
| 关键组件 | `ProductService`、`OrderService`、`ShippingService`、`PurchaseService`(@GlobalTransactional)、`StockCache`(Redis 预扣) |
| 数据模型 | `shop_product`（商品：名称/描述/价格/库存/总库存/图片/开售时间/截止时间/状态）、`shop_order`（订单：订单号/用户/商品/数量/总价/状态[PENDING/PAID/SHIPPED/DELIVERED/CANCELLED/REFUNDED]）、`shipping`（运单：订单号/快递公司/快递单号/收件人/电话/地址/状态） |
| 外部依赖 | MyBatis-Plus、Redis(库存预扣+限购防重)、Seata(全局事务发起方)、litisle-api(SocialFeign 查粉丝数、PaymentFeign 扣币) |
| API      | `POST /shop/admin/product`（管理员上架）、`PUT /shop/admin/product/{id}`（管理员编辑）、`POST /shop/order/create`（用户下单）、`POST /shop/order/{id}/cancel`（取消）、`GET /shop/order/list`（订单列表）、`POST /shop/admin/shipping`（管理员发货）、`GET /shop/admin/order/page`（管理后台订单分页） |
| 流程     | 下单：校验粉丝数≥1(调 SocialFeign) → 校验商品在售+库存足够(Redis) → 开 Seata 全局事务 → 预扣库存(shop本地) → 调 PaymentFeign 扣币(AT分支) → 创建订单+运单待发货(shop本地) → TC 提交 → 异步通知用户。若扣币失败，全局回滚包含库存释放。 |
| 错误处理 | 库存不足/资格不符/余额不足均返回明确错误码；超时未支付库存自动释放（定时任务+Redis TTL）；全局事务异常自动回滚+DLQ 对账 |
| 安全     | 商品上下架仅 ADMIN 角色；下单资格实时校验粉丝数；扣币接口防重（订单幂等令牌） |
| 性能     | 库存预扣 Redis（Lua 脚本原子操作）避免超卖；商品详情缓存；订单列表分页 |

### 2.3.9 litisle-search（搜索服务）

```
litisle-search
├── controller/        # SearchController / SimilarController
├── service/           # SearchService / VectorService / KnnRecommender
├── domain/
│   ├── entity/        # （无 MySQL 实体，ES 索引文档）
│   ├── dto/           # SearchRequestDTO
│   └── vo/            # SearchResultVO / ArticleDocVO / BookDocVO / UserDocVO
├── enums/             # SearchSortEnum / SearchTypeEnum
├── constant/          # SearchBizConstant（索引名称、权重配置等）
├── es/                # ElasticsearchClient 配置
├── mq/                # IndexSyncConsumer
└── config/            # SearchConfig
```

| 项       | 内容                                                         |
| -------- | ------------------------------------------------------------ |
| 主要职责 | ES 索引同步、文章/书籍/用户多维度检索、dense_vector KNN 相似推荐、分类筛选 |
| 核心功能 | 标题(最高权重)→标签→摘要→全文；用户笔名模糊；相似推荐=标签重合+向量+行为加权 |
| 关键组件 | `IndexSyncConsumer`(MQ)、`SearchService`、`VectorService`、`KnnRecommender` |
| 数据模型 | ES 索引：`article_idx`(title^4,tags^3,summary^2,content,vec_dense)、`book_idx`、`user_idx`（无 MySQL 表） |
| 外部依赖 | Elasticsearch 8.18、MQ、litisle-api                          |
| API      | `GET /search?q=&type=&sort=`、`GET /search/similar/{articleId}` |
| 流程     | 收 `content.published`/`social.liked` → 增量更新索引与热度向量 → 查询走 bool+function_score |
| 错误处理 | ES 不可用降级查 DB；索引失败入 DLQ 重试                      |
| 安全     | 查询参数转义防注入                                           |
| 性能     | 索引异步；向量预计算；热点查询缓存                           |

### 2.3.10 litisle-notification（通知服务）

```
litisle-notification
├── controller/        # NotificationController / SettingController
├── service/           # NotificationService / WsPushService / UnreadCounter
├── mapper/            # NotificationMapper
├── domain/
│   ├── entity/        # Notification / NotificationSetting
│   ├── dto/           # （内部使用，无复杂入参）
│   └── vo/            # NotificationVO / UnreadCountVO
├── enums/             # NotificationTypeEnum
├── constant/          # NotificationBizConstant（通知保留天数、分页大小等）
├── mq/                # NotificationConsumer（订阅全业务事件）
├── ws/                # WebSocketSessionRegistry
└── config/            # NotificationConfig
```

| 项       | 内容                                                         |
| -------- | ------------------------------------------------------------ |
| 主要职责 | 5 类通知(社交/审核/举报申诉/私信/系统)聚合、WS 在线推送、离线站内信、通知设置 |
| 核心功能 | 订阅全业务事件 → 分类落库 → 在线 WS 推/离线存站内信；未读计数；通知偏好设置 |
| 关键组件 | `NotificationConsumer`(MQ)、`NotificationService`、`WsPushService`、`UnreadCounter`(Redis) |
| 数据模型 | `notification`、`notification_setting`                       |
| 外部依赖 | MQ、Redis、WebSocket(Gateway 透传)                           |
| API      | `GET /notification/list`、`POST /notification/read/{id}`、`GET /notification/unread-count`、`PUT /notification/setting` |
| 流程     | 业务发事件 → 消费 → 生成通知 → 查用户在线(WS session 表) → 在线直推/离线入库 → 更新未读计数 |
| 错误处理 | WS 推送失败转离线；MQ 消费幂等(biz_id 去重)                  |
| 安全     | 通知内容不含敏感明文；WS 鉴权                                |
| 性能     | 未读计数 Redis；批量推送合并                                 |

---

## 2.4 模块依赖关系图

```
                        litisle-common ◄──── (所有服务依赖)
                        litisle-api    ◄──── (所有业务服务依赖)
                        litisle-file   ◄──── content/user/payment/review 依赖

  gateway ──(路由)──► auth / user / content / social / ai / payment / shop / search / review / notification

  auth        ──Feign──► user（校验账号/角色）
  content     ──Feign──► user（作者身份）、file（图片）、ai（小审/标签）、review（送审）
  social      ──Feign──► user（关注关系/VIP）、content（评论关联）、file
  review      ──Feign──► content（回写状态）、user（违规分/封禁）、ai（小审）
  ai          ──Feign──► user（VIP额度）、content（章节摘要）、search（相似推荐向量）
  payment     ──Feign──► user（VIP状态/币余额）
  shop        ──Feign──► social（粉丝数校验）、payment（扣币，AT 分支）
  search      ──MQ──►   content/social/user（索引同步事件）
  notification──MQ──►   全服务（订阅各业务事件）
```

**异步事件总线**：

| 事件                     | 生产者           | 消费者                                    |
| ------------------------ | ---------------- | ----------------------------------------- |
| `content.submitted`      | content          | review                                    |
| `content.published`      | content          | search、notification、ai(RAG 摄入)        |
| `content.unpublished`    | content          | search、ai(RAG 移除向量)；覆盖删除/转私密/复审驳回下架/封禁连带下架 |
| `social.liked/commented` | social           | notification、search(热度)                |
| `moderation.result`      | review           | content(自筛 bizType)、notification、file(仅 bizType=6) |
| `moderation.appeal.result` | review         | content(自筛 bizType=1)、评论模块(自筛 bizType=2) |
| `payment.rewarded`       | payment          | notification                              |
| `shop.order.created`     | shop             | notification（通知用户下单成功）          |
| `shop.order.shipped`     | shop             | notification（通知用户已发货）            |
| `user.banned`            | user（review 仅发 punish.result） | content(下架)、social(禁言)、ai(封禁会话) |
| `sensitiveword.updated`  | review(运营后台) | 全服务(订阅刷新本地 DFA)                  |

---

## 2.5 跨模块一致规范

### 编码规范

- **包结构**（每个业务服务统一）：`controller`、`service`、`mapper`、`domain(entity/dto/vo)`、`enums`、`constant`、`redis`、`mq`、`config`。
- **共享层规范**：`litisle-api` 统一放 `enums`/`constant`/`dto`/`vo`/`feign`/`event` 六个包；`litisle-common` 统一放 `result`/`exception`/`constant` 等技术基建包。
- **命名**：Controller `XxxController`、Service `XxxService`+`XxxServiceImpl`、Feign `XxxFeignApi`、DTO `XxxDTO`、事件 `XxxEvent`、枚举 `XxxEnum`。
- **统一响应**：`Result<T>{code,msg,data}` + `PageResult<T>`；错误码 `ErrorCode` 枚举集中管理。
- **日志**：SLF4J + 占位符，关键操作带 MDC traceId（Gateway 注入，Feign 透传）。

### 设计模式

| 模式         | 应用                                              |
| ------------ | ------------------------------------------------- |
| 状态机       | review 任务流转、私信对话状态                     |
| 策略         | DFA 多场景策略、赞赏/充值支付策略、推荐流排序策略 |
| 责任链       | 三层审核链路（DFA→小审→人工）                     |
| 模板方法     | 搜索/通知统一骨架                                 |
| 观察者(事件) | MQ 事件总线                                       |
| 适配器       | litisle-file 适配 SeaweedFS S3                    |
| 双 buffer    | DFA 词库热更新                                    |

### 模块交互一致模式

- **同步**：Feign + FallbackFactory + 超时 + Sentinel。
- **异步**：MQ + 幂等(biz_id) + DLQ + 重试退避。
- **上下文**：Gateway 注入 `X-User-Id/X-Roles`，common 的 `FeignAuthInterceptor` 透传，业务用 `SecurityContext` 读取。
- **事务**：单服务本地事务；跨服务仅积分商城用 Seata AT（shop 发起全局事务，payment 参与 AT 分支）；其余最终一致 + 对账。

---

## 2.6 RAG 知识库设计

**设计目标**：让已发布的公开文章自动成为小月的知识库，用户提问时小月能引用站内内容回答并标明出处。

**存储选型决策**：

| 方案 | 优势 | 劣势 | 结论 |
|------|------|------|------|
| PostgreSQL + pgvector | 独立向量库，百万级 chunk 够用 | 新增中间件，增加运维复杂度 | ❌ 不采用 |
| Elasticsearch dense_vector | 复用现有 ES 集群，零新增中间件 | 向量检索性能略逊专用向量库 | ✅ 采用 |
| Milvus | 专业向量数据库，性能最优 | 新增中间件，实习项目过度设计 | ❌ 不采用 |

**结论**：复用现有 Elasticsearch 8.18.8 的 `dense_vector` 字段 + KNN 检索，百万级 chunk 足够，零新增中间件。

### 2.6.1 功能 1：文章发布自动进知识库

**一句话**：已发布的公开文章自动被小月"读过"，无需人工干预。

| 维度 | 描述 |
|------|------|
| 触发时机 | 文章审核通过并发布后（监听 `content.published` 事件） |
| 入库条件 | 仅 `visibility=PUBLIC`；**审核终态 PASS（含人审）**；正文 ≥ 200 字；非水帖；DFA 未命中高风险；作家角色优先；**入库前回查文章当前 status=2 且 visibility=PUBLIC**（不纯信事件，防乱序/下架残留） |
| 处理动作 | 正文清洗 → 切片（~500 字/段，重叠 50 字）→ Embedding 向量化 → 写入 ES `article_chunks` 索引 |
| 更新处理 | 文章修改重发 → 删旧块写新块（幂等，按 `articleId` 覆盖） |
| 删除处理 | 文章删除/转私密/**复审驳回下架/作者封禁连带下架** → 监听 `content.unpublished` 事件 → 从 ES 移除对应 chunk（**下架路径必须同发此事件**，见 §2.4 事件矩阵——否则违规/封禁内容 chunk 永久残留向量库，违反隐私红线） |
| 用户感知 | 无。作者不操作，读者下次问小月就能被引用到 |

**入库质量门控**：

| 条件 | 阈值 | 说明 |
|------|------|------|
| 可见性 | `visibility=PUBLIC` | 私密/密码访问/草稿永不进向量库 |
| 正文字数 | ≥ 200 字 | 过滤水帖和短内容 |
| 作者角色 | AUTHOR / LEGACY_AUTHOR 优先 | 作家文章权重更高 |
| DFA 风险 | 未命中高风险词 | 避免污染知识库 |

**ES 索引设计**（`article_chunks`）：

```json
{
  "mappings": {
    "properties": {
      "chunk_id": { "type": "keyword" },
      "article_id": { "type": "long" },
      "author_id": { "type": "long" },
      "title": { "type": "text" },
      "author_name": { "type": "text" },
      "chunk_text": { "type": "text" },
      "chunk_vector": { 
        "type": "dense_vector", 
        "dims": 1536, 
        "index": true, 
        "similarity": "cosine" 
      },
      "zone": { "type": "integer" },
      "visibility": { "type": "integer" },
      "publish_time": { "type": "date" },
      "ingest_time": { "type": "date" }
    }
  }
}
```

### 2.6.2 功能 2：小月对话 + 站内检索 + 引用展示

**一句话**：用户问小月，小月优先用站内内容回答，并标明出处。

| 维度 | 描述 |
|------|------|
| 触发入口 | 现有小月对话界面，无新页面 |
| 检索条件 | 用户问题 → Embedding → ES KNN TopK（默认 5 条）→ **强制 filter `visibility=PUBLIC`** → 相似度 ≥ 0.65 才采用；引用卡片展示前回查文章当前状态（非 PUBLIC/已下架则不展示，防删除事件丢失残留 chunk 被越权引用） |
| 路由判断 | 寒暄/纯润色/短消息 → 不查 RAG，直接 LLM 回答 |
| 回答呈现 | LLM 生成回答 + 末尾附引用卡片（文章标题、作者、点击跳转） |
| 无结果处理 | 站内无相关内容 → 纯 LLM 回答，不展示引用区 |
| 降级兜底 | ES 向量检索超时/不可用 → 自动降级为纯 LLM 对话，用户无感知 |
| 用户感知 | 回答下方出现 📎 引用卡片（文章标题、作者、点击跳转原文） |

**检索路由规则**（节省 Embedding API 调用）：

| 问题类型 | 是否查 RAG | 判断规则 |
|---------|-----------|---------|
| 寒暄问候 | ❌ 不查 | 包含"你好"/"在吗"/"嗨"等关键词 |
| 纯润色请求 | ❌ 不查 | 包含"帮我润色"/"修改一下"等指令 |
| 短消息 | ❌ 不查 | 问题长度 < 10 字 |
| 知识问答 | ✅ 查 | 其他情况，调用 Embedding → KNN 检索 |

**LangChain4j 集成要点**：

| 要点 | 说明 |
|------|------|
| Embedding 模型 | 使用 LangChain4j 的 `EmbeddingModel` 接口，可对接 OpenAI / 本地模型 |
| 向量存储 | 复用现有 ES 集群，`dense_vector` 字段 + KNN 检索 |
| 检索器 | 实现 `ContentRetriever` 接口，封装 `KnowledgeBaseRetriever` |
| RAG 管道 | `DefaultRetrievalAugmentor` 串联：Query → Embedding → TopK → 阈值过滤 → Prompt 组装 |
| 引用溯源 | 检索结果携带 `articleId` / `title` / `authorId` 元数据，LLM 回答后附引用卡片 |

**成本控制**：

- 检索路由判断（寒暄/润色/短消息不查 RAG）节省 Embedding API 调用费用
- 批量发布时合并请求调 Embedding API，避免限流和重复调用
- 相似度阈值 0.65 过滤低质量结果，减少无效 Prompt 拼接

**隐私红线**：

- 仅 `visibility=PUBLIC` 的已发布文章进向量库
- 私密/密码访问/草稿/审核驳回内容永远不进向量库
- 文章删除/转私密/复审驳回下架/封禁连带下架 → 监听 `content.unpublished` 事件 → 从 ES 移除对应 chunk（下架路径必须同发此事件）
- **检索侧纵深防御**：KNN 查询强制 filter `visibility=PUBLIC`；引用卡片展示前回查文章当前态，防删除事件丢失/乱序致残留 chunk 被引用
- **RAG 内容视为不可信数据**：站内文章正文是 UGC，拼入小月 prompt 时按不可信数据包裹、**不拼入 system 段**，防恶意作者在正文埋 prompt 注入指令经检索命中污染小月（与审核模块「间接注入」同纪律）

### 2.6.3 RAG 架构设计要点

| 类别 | 注意事项 |
|------|----------|
| 入库质量 | 不是所有文章都进。设门槛（字数、角色、可见性、DFA 风险），避免水帖污染知识库 |
| 分层索引 | 作家文章/热门内容优先进核心层；普通读者文章降权；私密/草稿永不进 |
| 异步解耦 | 摄入走 MQ（`content.published` 事件），不阻塞发布链路；失败可重试，重试耗尽进 DLQ + 告警（防文章永久漏入库无感知） |
| 批量发布 | 消费端合并请求调 Embedding API，避免限流和重复调用 |
| 幂等覆盖 | 同一 `articleId` 重复发布不会重复建索引，按 `articleId` 删除旧 chunk 后写入新 chunk |
| 乱序防御 | ingest 消费前回查文章当前可见性/状态（非 PUBLIC 或已下架则跳过写入），防 `unpublished` 先于 `published` 到达时已删 chunk 被写回 |
| 检索路由 | 简单问题（寒暄/润色）不查 RAG，规则判断即可，不需要 LLM 分类 |
| 相似度阈值 | TopK 结果低于 0.65 阈值直接丢弃，不塞进 prompt |
| 降级策略 | ES 向量检索不可用 → 纯 LLM 回答；Embedding 失败 → 延迟重试（耗尽进 DLQ + 告警） |
| 存储选型 | 复用现有 ES 集群的 `dense_vector`，百万级 chunk 够用；后期数据量激增再考虑 Milvus |
| 隐私红线 | 仅自己可见/密码访问/草稿/审核驳回内容永远不进向量库 |
| 成本控制 | Query Embedding 不是每次都调；路由判断省掉的不仅是 QPS，还有 API 费用 |

---

## 2.7 SeaweedFS 落地方案

**部署拓扑**（docker-compose）：

```
Master×3 (Raft HA)  ←  Volume Server×3  ←  Filer×1 (S3 API + 路径)  ←  litisle-file
```

- **Master**：3 节点 Raft，管理卷映射，保证 HA。
- **Volume Server**：实际存储小文件，按 collection 划分（`avatar`/`cover`/`article`/`license`），副本数 `replication=001`。
- **Filer**：提供 S3 兼容 API + 路径语义，业务层只对接 Filer。
- **litisle-file** 封装 S3 SDK，对外暴露 `upload/sign/delete`。

**集成关键点**：

1. 图片对齐信息不存 SeaweedFS，存 content 的 `article_image` 表（fileId + position + align）。
2. 私有文件（授权文件/纪念作家授权）走预签名短时效 URL；公共文件（头像/封面）走直链。
3. sha256 去重：上传前查 `file_meta`，命中直接复用 fileId。
4. Volume 副本数按业务重要性配置。

限制图片大小

---

## 文章存储设计



---

#### 一、存储选型对比：MySQL vs Elasticsearch

##### 1.1 角色定位

| 维度         | MySQL（`article` 表）                      | Elasticsearch（`article_idx` 索引）                          |
| ------------ | ------------------------------------------ | ------------------------------------------------------------ |
| **角色**     | 系统之记录（Source of Truth）              | 加速检索的二级索引                                           |
| **存储内容** | 完整文章数据（含 `content_html` LONGTEXT） | 检索字段（`title^4`, `summary^2`, `content`, `tags`）+ `dense_vector` |
| **写入时机** | 用户操作即时写入（同步事务）               | MQ 异步消费 `content.published` 事件后写入                   |
| **一致性**   | ACID 强一致                                | 最终一致（秒级延迟）                                         |
| **查询场景** | 文章详情页、编辑回显、列表分页、权限校验   | 全文搜索、语义相似推荐、标签筛选                             |
| **可靠性**   | 事务回滚、binlog 恢复                      | 索引重建、数据丢失可重刷                                     |

---

##### 1.2 为什么不只用 ES？

- **ES 不是 OLTP 数据库**，不支持事务和复杂关联查询（如 JOIN 用户表查角色权限）
- **ES 写入是最终一致**，发布后立刻查详情页可能返回空
- **ES 的段合并在大规模更新时会有性能抖动**
- **文章编辑需要乐观锁**（`version` 字段防并发覆盖），ES 无原生乐观锁机制

##### 1.3 为什么不只用 MySQL？

- **MySQL 的 `LIKE '%keyword%'` 全文索引性能远不如 ES**
- **语义相似度搜索**（`dense_vector` KNN）MySQL 需要额外插件（如 pgvector），而 ES 原生支持
- **ES 的 `function_score`** 可以实现复杂的排序（相关度 × 时间衰减 × 热度加权）

##### 1.4 存储策略总览图

```
┌────────────┐
│  用户写入   │
└─────┬──────┘
      ▼
┌──────────────────────────────────────┐
│  MySQL（article 表）                 │  ← Source of Truth
│  • 完整正文 LONGTEXT                 │
│  • 事务保证                         │
│  • 乐观锁 version 防并发覆盖         │
└─────┬────────────────────────────────┘
      │ binlog / MQ
      ▼
┌──────────────────────────────────────┐
│  Elasticsearch（article_idx）        │  ← 检索加速层
│  • title^4 + summary^2 + content    │
│  • tags 倒排索引                     │
│  • dense_vector KNN 语义搜索         │
│  • function_score 综合排序           │
└──────────────────────────────────────┘
```

---

#### 二、文章正文中的图片存储方案

### 2.1 核心问题

> 富文本编辑器中，图片可以插入在正文的**任意位置**（段落之间、文字中间），且支持 **5 种对齐方式**。如何存储才能保证图片位置不丢失、对齐方式可还原？

### 2.2 整体方案

> **SeaweedFS 存文件 + MySQL `article_image` 表存元数据 + HTML 占位符嵌入**

---

### 2.3 图片文件存储 → SeaweedFS

- 图片文件本体存储在 **SeaweedFS Volume Server** 中
- 上传后获取 `file_id`（如 `3,01637037d6`）
- `litisle-file` 模块的 `file_meta` 表记录文件元数据（sha256 去重、mime、尺寸等）

---

### 2.4 图片位置与对齐 → MySQL `article_image` 表

#### 为什么独立建表，而不把对齐信息存 HTML 属性里？

| 方案                                          | ✅ 优点                                                       | ❌ 缺点                                                       |
| --------------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **存 HTML 属性**（如 `<img align="center">`） | 简单，渲染时直接读取                                         | 正文 HTML 被污染；对齐信息难以单独查询（如"找出所有居中的图片"）；更换编辑器时解析逻辑需重写 |
| **独立 `article_image` 表**                   | 对齐信息与正文**解耦**；支持"图片管理"视图（按文章汇总所有图片）；方便批量修改排版 | 渲染时需要 JOIN 查询                                         |

**✅ 结论：独立 `article_image` 表**，正文中只存 `file_id` 占位符，渲染时根据 `position_order` 排序后替换为带对齐样式的 `<img>` 标签。

---

#### 2.5 正文 HTML 中的图片占位符设计

#### 占位符格式

```
{img:file_id}
```

#### 示例正文

```html
<p>这是第一段文字。</p>
{img:3,01637037d6}
<p>这是图片后面的文字。</p>
{img:3,01637037d7}
<p>又一段文字。</p>
```

#### 渲染流程

```
1. 从 DB 读取 article.content（含占位符的 HTML）
2. 解析所有 {img:file_id} 占位符
3. 批量查询 article_image 表，获取 file_id → URL + align 映射
4. 按 position_order 排序
5. 逐个替换占位符为带对齐样式的 <img> 标签
6. 输出最终 HTML 给前端
```

#### 生成的 HTML 示例

```html
<p>这是第一段文字。</p>
<div class="img-align-center">
  <img src="https://cdn.example.com/3,01637037d6" alt="" />
</div>
<p>这是图片后面的文字。</p>
```

---

#### 2.6 五种对齐方式的 CSS 映射

| `align` 值 | 含义   | CSS 实现                                                     |
| :--------: | ------ | ------------------------------------------------------------ |
|   **1**    | 左对齐 | `.img-align-left { text-align: left; }`                      |
|   **2**    | 左偏中 | `.img-align-left-center { text-align: left; margin-left: 25%; }` |
|   **3**    | 居中   | `.img-align-center { text-align: center; }`                  |
|   **4**    | 中偏右 | `.img-align-right-center { text-align: right; margin-right: 25%; }` |
|   **5**    | 右对齐 | `.img-align-right { text-align: right; }`                    |

---

#### 2.7 草稿中的图片处理

用户在编辑草稿时也可能插入图片，此时图片尚未关联到正式文章。处理方式如下：

| 阶段              | 行为                                                         |
| ----------------- | ------------------------------------------------------------ |
| **草稿编辑阶段**  | 图片上传 SeaweedFS，`article_image` 中 `article_id` 暂时为空（或指向草稿表中的记录） |
| **发布时**        | 将 `article_image` 中的图片关联到正式 `article_id`           |
| **草稿取消/删除** | 定时任务清理未关联的图片（`article_id IS NULL` 且超过 N 天） |

---

#### 2.8 图片去重（sha256）

- 上传前计算文件 **SHA256**，查询 `file_meta` 表
- **命中** → 直接复用已有 `file_id`，不再上传 SeaweedFS
- **未命中** → 新图片写入 `file_meta` 表 + 上传 SeaweedFS

---

### 三、汇总



---

#### 3.2 文章存储方案核心结论

| 问题                             | 结论                                                         |
| -------------------------------- | ------------------------------------------------------------ |
| **文章正文存哪里？**             | MySQL `LONGTEXT`（主存储，系统之记录）+ ES 索引（搜索引擎，异步同步） |
| **图片存哪里？**                 | SeaweedFS（文件本体）+ MySQL `article_image` 表（位置/对齐元数据） |
| **图片在正文中如何定位？**       | 占位符 `{img:file_id}` 嵌入 HTML，渲染时根据 `article_image.position_order` 替换为带对齐样式的 `<img>` 标签 |
| **为什么图片对齐信息独立建表？** | 解耦正文与排版，支持"图片管理"视图，更换编辑器无需重写解析逻辑 |

---

### 四、文档整合建议

> 你可以在以下位置补充上述设计思路：

| 目标文档                   | 位置                                     | 补充内容                                   |
| -------------------------- | ---------------------------------------- | ------------------------------------------ |
| **`Design-Document.md`**   | `2.3.3 litisle-content` 部分的"核心功能" | 文章存储方案的设计思路（§1 + §2）          |
| **`Function-Document.md`** | `7. litisle-content` 部分                | 图片存储机制、占位符渲染流程、对齐方式说明 |

---



