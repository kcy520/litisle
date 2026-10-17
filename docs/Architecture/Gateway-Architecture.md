
# litisle-gateway 网关模块架构文档

> 版本：v1.0（2026-10-15 按当前实现对齐）

>
> 关联文档：[gateway-index.md](../index/gateway-index.md)（模块速查表）· [Auth-Architecture.md](./Auth-Architecture.md)（Sa-Token 会话 SSOT）· Api-Architecture.md（REST 总路由）

>
> 定位：**独立微服务**（端口 8080），系统唯一入口——路由转发 + 认证闸口 + 用户上下文注入 + traceId 贯穿

------

## 一、模块定位与边界

| 项目     | 说明                                                         |
| -------- | ------------------------------------------------------------ |
| 定位     | **独立微服务**（端口 8080），WebFlux 响应式网关，全部外部流量的唯一入口 |
| 依赖方   | 全部业务服务（经 Nacos 服务发现 `lb://` 转发）；auth/user（Redis 共享会话，网关只读校验 token） |
| 职责边界 | 只管**路由 + 认证 + 上下文传递 + 链路追踪 + 内部端点拦截**；登录/登出实现在 auth，业务权限判定在下游服务 |
| 不负责   | 业务逻辑、验证码、限流计数（下游各服务 @RateLimiter / Sentinel 自治） |
| 存储     | **无数据库**；Redis（6380 db0）仅读取与 auth/user 共享的 Sa-Token 会话 |

### 1.1 过滤链时序（核心机制）

```
TraceIdFilter（WebFilter，@Order(-200)）          生成/透传 traceId——早于认证，401 响应也带 traceId
  ↓
SaReactorFilter（order=-100，SaTokenGatewayConfig）认证闸口：addInclude("/**") + 白名单 exclude
  → 白名单外一律 StpUtil.checkLogin()（读 Redis 共享会话）
  → 异常回调 GlobalExceptionHandler → Result JSON
  ↓
UserContextHeaderFilter（GlobalFilter，GatewayFilterConfig 注册）认证后向下游注入 Header：
  HeaderConstant.USER_ID / USERNAME / USER_ROLES / PERMISSIONS（来自 common-core，禁硬编码）
  ↓
路由转发（lb:// Nacos 服务发现）
```

------

## 二、路由设计

### 2.1 业务路由（12 条，全 lb:// 服务发现）

| 路由 id | Path 断言 |
| --- | --- |
| litisle-auth / user / content / social / payment / shop | /api/auth · /api/user · /api/content · /api/social · /api/payment · /api/shop |
| litisle-review / file / ai / notification / search | /api/review · /api/file · /api/ai · /api/notification · /api/search |

全局 default-filters：RemoveResponseHeader=Server / X-Powered-By（安全加固）；`/api/feign/**` 无路由，天然不可达公网。

### 2.2 内部端点拦截路由（安全机制）

`block-review-punish-status`：Path=/api/review/punish/status → `no://op` + SetStatus=404——处罚权威态回查为服务间契约，公网直查可探测任意用户处罚态；**必须置于 litisle-review 之前**确保优先命中，且 404 不暴露端点存在性。同模式可复用于其他内部契约（如 /api/feign/auth/punish/speak-check/**）。

### 2.3 匿名白名单（litisle.gateway.jwt.paths → GatewayJwtProperties）

- 认证入口：/api/auth/login、/register、/refresh、/oauth/**、/captcha/image、/captcha/email
- 公开内容：/api/content/article/list、/article/*（Sa-Token 匹配器不支持 {id}，改用 *）、/article/hot、/category/**
- 公开查询：/api/user/public/**、/api/search/**、/api/file/download/**
- **显式不放行 /actuator/**：Prometheus 直连各服务端口抓指标不经网关；白名单若保留，将来网关引 actuator 或加路由时健康/指标端点将免登录裸奔公网

------

## 三、Sa-Token 配置（与 auth 严格一致，口径集中 Nacos litisle-shared.yaml）

| 项 | 值 | 项 | 值 |
| --- | --- | --- | --- |
| token-name / prefix | Authorization / Bearer | timeout / active-timeout | 86400 / 604800 |
| is-concurrent / max-login-count | true / 2 | is-write-header / is-log | true / true |

> 网关必须用 sa-token-reactor-spring-boot3-starter（WebFlux 专用），排除 Servlet 版依赖防 Bean 冲突；匿名白名单为 GatewayJwtProperties 自实现，不可复用 Servlet 版 AnonymousPathScanner。

------

## 四、配置说明

| 配置项 | 说明 |
| --- | --- |
| server.port=8080 | 系统唯一对外入口 |
| spring.main.web-application-type=reactive | 强制 WebFlux，禁 WebMVC 冲突 Bean |
| spring.config.import | optional:nacos:litisle-gateway.yaml + litisle-shared.yaml（Nacos 未就绪不阻塞启动） |
| litisle.nacos.* | 注册中心占位符（lb:// 依赖服务发现，环境特定值在 local/dev） |
| spring.data.redis | localhost:6380 db0（与 auth/user 同实例，共享会话只读） |
| logging | traceId 进 MDC（%X{traceId}）随控制台格式输出 |

------

## 五、安全与可靠性

1. **认证单点收口**：SaReactorFilter 拦 /**，白名单外一律 checkLogin；异常统一 Result JSON（GlobalExceptionHandler 单 Throwable 回调）
2. **内部端点防探测**：no://op + 404 拦截 + 路由顺序前置；/api/feign/** 不路由
3. **下游信任边界**：UserContextHeaderFilter 注入的身份 Header，下游防伪造依赖「仅信任网关网络边界」（下游服务不可暴露公网）
4. **信息隐藏**：Server/X-Powered-By 移除；/actuator 不路由不放行
5. **可观测性**：traceId 贯穿（-200 先于认证）；Nacos optional 导入保证注册中心未就绪不阻塞启动
6. **限流分工**：网关不做限流计数，由下游各服务 @RateLimiter / 全站拦截器 / Sentinel 自治

------

## 六、开发导航（按需求找落点）

| 我要…… | 落点 |
| --- | --- |
| 加/改路由 | application.yml routes（内部拦截路由须前置） |
| 调整匿名白名单 | litisle.gateway.jwt.paths（GatewayJwtProperties） |
| 改认证异常响应 | GlobalExceptionHandler |
| 改下游 Header | UserContextHeaderFilter + common-core HeaderConstant |
| 改 Sa-Token 口径 | Nacos litisle-shared.yaml（网关/auth/user 三处一致） |
| 拦截内部端点 | 仿 block-review-punish-status 模式 |
