# gateway-index · litisle-gateway 网关模块速查表

> 用途：写码/联调前查网关模块坐标，**免去逐个 Read**。
> 关联：`Api-Architecture.md`（REST 总路由）· `auth-index.md`（Sa-Token 会话 SSOT）· 各业务模块 index。
> 纪律：WebFlux 专属——只能用 sa-token-reactor-spring-boot3-starter（需排除 Servlet 版依赖防 Bean 冲突）；匿名白名单用 GatewayJwtProperties 自实现（不可复用 Servlet 版 AnonymousPathScanner）。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 服务名 / 端口 | `litisle-gateway` / `8080`（application.yml） |
| 定位 | 系统唯一入口：路由转发（lb:// 服务发现）+ Sa-Token 认证闸口 + 用户上下文注入 + traceId 贯穿 + 内部端点拦截 |
| 库 | **无数据库**；Redis（localhost:6380 db0）仅用于读取与 auth/user 共享的 Sa-Token 会话 |
| 关键依赖 | spring-cloud-starter-gateway（WebFlux）、sa-token-reactor-spring-boot3-starter、Nacos discovery/config、common-core（HeaderConstant/Result/ErrorCode） |
| 不负责 | 业务逻辑、登录/登出实现（auth）、验证码 |

## 二、包结构（`com.litisle.litislegateway`）

```text
config/
  GatewayFilterConfig          # 用户上下文过滤器 Bean 注册
  GatewayJwtProperties         # 匿名白名单绑定（prefix litisle.gateway.jwt → paths 列表）
  SaTokenGatewayConfig         # SaReactorFilter：addInclude("/**") + 白名单 exclude + 其余 StpUtil.checkLogin()
filter/
  TraceIdFilter                # @Order(-200) WebFilter：生成/透传 traceId，早于认证保证失败响应也可关联
  UserContextHeaderFilter      # GlobalFilter：认证后向下游注入 HeaderConstant（用户ID/用户名/角色/权限）
  GlobalExceptionHandler       # 认证异常 → Result JSON（由 SaReactorFilter.setError 回调，单 Throwable 参数）
LitisleGatewayApplication      # 启动类
```

> 过滤链时序：TraceIdFilter(-200) → SaReactorFilter(-100，认证闸口) → UserContextHeaderFilter（GlobalFilter，认证后注入下游 Header）。

## 三、路由表（application.yml，全部 lb:// 服务发现）

| 路由 id | Path 断言 | 目标 |
|---|---|---|
| litisle-auth | /api/auth/** | lb://litisle-auth |
| litisle-user | /api/user/** | lb://litisle-user |
| litisle-content | /api/content/** | lb://litisle-content |
| litisle-social | /api/social/** | lb://litisle-social |
| litisle-payment | /api/payment/** | lb://litisle-payment |
| litisle-shop | /api/shop/** | lb://litisle-shop |
| **block-review-punish-status** | /api/review/punish/status | `no://op` + SetStatus=404（**内部契约拦截**，置于 litisle-review 之前优先命中，防公网探测处罚态） |
| litisle-review | /api/review/** | lb://litisle-review |
| litisle-file | /api/file/** | lb://litisle-file |
| litisle-ai | /api/ai/** | lb://litisle-ai |
| litisle-notification | /api/notification/** | lb://litisle-notification |
| litisle-search | /api/search/** | lb://litisle-search |

> 全局 default-filters：RemoveResponseHeader=Server / X-Powered-By（安全加固）。
> `/api/feign/**` 无路由，天然不可达公网（服务间契约经 Nacos 直连）。

## 四、匿名白名单（litisle.gateway.jwt.paths，与 auth 限流联动）

| 前缀 | 说明 |
|---|---|
| /api/auth/login · /register · /refresh | 认证入口 |
| /api/auth/captcha/image · /email | 验证码（历史路径，代码实为 /img） |
| /api/auth/oauth/** | OAuth2 预留 |
| /api/content/article/list · /article/* · /article/hot · /category/** | 公开内容（* 通配因 Sa-Token 匹配器不支持 {id} 语法） |
| /api/user/public/** | 公开用户信息（作者主页） |
| /api/search/** | 公开搜索 |
| /api/file/download/** | 公开文件下载 |

**显式不放行**：`/actuator/**`——Prometheus 直连各服务端口抓指标不经网关；网关未引 actuator、路由表也无 /actuator 匹配，白名单保留会埋「将来加路由后免登录裸奔公网」的雷。

## 五、Sa-Token 配置（必须与 auth 严格一致）

| 项 | 值 | 说明 |
|---|---|---|
| token-name / token-prefix | Authorization / Bearer | 请求头规范 |
| timeout / active-timeout | 86400 / 604800 | 1 天 / 记住我 7 天 |
| is-concurrent / max-login-count | true / 2 | 多端登录，最多 2 台 |
| is-write-header / is-log | true / true | |
| 配置来源 | Nacos `litisle-shared.yaml` | 网关本地 yml 仅兜底，口径集中管理 |

## 六、配置要点

- `spring.main.web-application-type: reactive`：强制 WebFlux，禁 WebMVC 冲突 Bean
- Nacos：`optional:nacos:litisle-gateway.yaml` + `litisle-shared.yaml`（未就绪不阻塞启动）；注册中心用 `litisle.nacos.*` 占位符（lb:// 依赖服务发现）
- Redis：与 auth/user 同实例同库（共享会话，网关只读校验）
- logging：traceId 进 MDC（`%X{traceId}`）随日志格式输出

## 七、安全要点

1. **认证闸口单点收口**：SaReactorFilter 拦 /**，白名单外一律 checkLogin；异常经 GlobalExceptionHandler 统一返回 Result JSON
2. **内部端点防探测**：/api/review/punish/status 以 no://op + 404 拦截（不暴露存在性），路由顺序保证优先命中；同模式可复用于其他内部契约（如 /api/feign/auth/punish/speak-check/**，置于业务路由之前）
3. **下游上下文注入**：认证后注入 HeaderConstant 四 Header（用户ID/用户名/角色/权限），下游服务免二次查会话；伪造防护依赖下游仅信任网关网络边界
4. **信息隐藏**：Server/X-Powered-By 响应头移除；/actuator 不路由不放行
5. **traceId 全链路**：order -200 早于认证，401 响应也带 traceId 便于排障

## 八、开发导航（按需求找落点）

| 我要…… | 落点 |
|---|---|
| 加/改路由 | application.yml `spring.cloud.gateway.routes`（注意内部拦截路由须置于业务前缀路由之前） |
| 调整匿名白名单 | application.yml `litisle.gateway.jwt.paths`（绑定 GatewayJwtProperties） |
| 改认证异常响应 | `GlobalExceptionHandler`（SaReactorFilter.setError 回调） |
| 改下游 Header 注入 | `UserContextHeaderFilter` + common-core `HeaderConstant` |
| 改 Sa-Token 口径 | Nacos `litisle-shared.yaml`（网关/auth/user 三处一致） |
| 拦截内部端点公网访问 | 仿 block-review-punish-status：no://op + SetStatus=404 + 路由顺序前置 |