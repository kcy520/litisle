



## 认证模块+user模块




我已经收集了足够的信息（设计文档、SQL 文档、功能文档、common 模块说明，以及 litisle-user 已实现的实体类、litisle-auth 的配置）。现在为你生成详细的架构文档。

由于我处于 Chat 模式（只读权限），无法直接创建文件，请将以下内容保存为 `e:\NewJavaPro\LitIsle\BaseDesign-document\Auth-User-Architecture.md`：

```markdown

```

e:\NewJavaPro\LitIsle\BaseDesign-document\Auth-User-Architecture.md

# LitIsle 认证模块与用户模块架构文档

> 本文档详细描述 `litisle-auth`（认证服务）与 `litisle-user`（用户服务）两个核心业务模块的功能实现、代码架构、数据模型、关键流程及协同关系，作为后续编码实现的蓝本。
>
> **说明**：本文档只描述架构与设计，不包含具体代码实现。

---

## 一、文档概述

### 1.1 文档目的

- 明确 `litisle-auth` 与 `litisle-user` 两个模块的职责边界，避免功能重叠。
- 规划两模块的包结构、核心组件、数据模型与关键流程，指导后续编码。
- 阐明两模块之间的协同关系（数据库共享、Feign 调用、安全上下文透传）。

### 1.2 设计依据

- [Design-Document.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/Design-Document.md) §2.3.1 / §2.3.2
- [Function-Document.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/Function-Document.md) §5 / §6
- [Sql-Document.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/Sql-Document.md) §一
- [commom-des.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/commom-des.md)（公共基建能力）

### 1.3 模块在整体架构中的位置

                          ┌──────────────────────────────────┐
                          │     客户端（Web / H5 / 小程序）    │
                          └──────────────┬───────────────────┘
                          ┌──────────────▼───────────────────┐
                          │       litisle-gateway（网关）       │
                          │   JWT 校验 · 路由 · 注入 X-User-Id  │
                          └──┬───────────────────────────┬───┘
                             │                           │
                ┌────────────▼──────────┐    ┌───────────▼────────────┐
                │   litisle-auth        │    │   litisle-user          │
                │   认证 / JWT / OAuth2  │───▶│   账号 / 角色 / VIP      │
                │   验证码 / 会话管理     │ Feign│   资料 / 注销 / 计数    │
                └────────────┬──────────┘    └───────────┬────────────┘
                             │                           │
                             └───────────┬───────────────┘
                                         ▼
                              ┌─────────────────────┐
                              │   数据库 litisleuser  │
                              │ sys_user / sys_role   │
                              │ sys_user_role         │
                              │ sys_user_profile      │
                              │ sys_oauth_binding     │
                              └─────────────────────┘

  说明：短信验证码 / 图形验证码 / Token 黑名单 / 会话列表
        均走 Redis，不落库。

```

```

### 1.4 两模块核心职责对比

| 维度     | litisle-auth                     | litisle-user                                    |
| -------- | -------------------------------- | ----------------------------------------------- |
| 核心职责 | 身份认证与凭证管理               | 账号资料与角色体系管理                          |
| 关注点   | "你是谁"（验证身份）             | "你能做什么"（角色权限）+ "你的资料"            |
| 数据库   | 共享 litisleuser 库（无独立库）  | litisleuser 库（主管理者）                      |
| 独立表   | 无（验证码走 Redis 不落库）      | 57张表（用户/角色/权限/关联*2/资料/OAuth 绑定） |
| 对外能力 | 登录/注册/退出/Token 刷新/OAuth2 | 资料增改查/角色叠加/VIP/注销/计数               |
| 调用方向 | 调用 user 的 Feign 查账号信息    | 被 auth / 其他服务通过 Feign 调用               |
| 凭证管理 | JWT 双 Token + Redis 黑名单      | 无（不签发凭证）                                |

---

## 二、litisle-auth 认证模块

### 2.1 模块定位

**定位**：负责用户身份认证、注册登录、OAuth2 第三方登录、JWT 签发与刷新、在线设备管理。

**核心特征**：

- **无独立数据库**：复用 `litisleuser` 库，无独立表（验证码/会话/黑名单均走 Redis）。
- **无独立实体**：用户实体复用 `litisle-user` 模块的 `SysUser`，OAuth 绑定复用 `SysOAuthBinding`。
- **状态化凭证**：登录态由 **Sa-Token** 服务端会话管理（token 存 Redis），天然支持"踢出设备""退出失效"等有状态能力，无需自建 Token 黑名单。
- **多入口登录**：账密 + 邮箱验证码 + OAuth2（微信/QQ）三种方式统一归一化处理。

### 2.2 功能清单

| 功能              | 说明                                                         |
| ----------------- | ------------------------------------------------------------ |
| 账号密码注册/登录 | 用户名 + 密码（BCrypt 加密），图形验证码防刷                 |
| 邮箱验证码登录    | 邮箱 + 邮箱验证码，验证码有效期 5 分钟                       |
| OAuth2 第三方登录 | 微信 / QQ 扫码登录，绑定已有账号或直接注册新账号（暂不开发，跳过） |
| Sa-Token 登录态   | Sa-Token 会话管理（Token 1 天 + 记住我 7 天），无需自建 JWT   |
| 在线设备管理      | 同一账号最多 2 端在线（max-login-count=2），超出自动踢出最旧设备 |
| 退出登录          | StpUtil.logout() 服务端会话失效即下线，并清理 refreshToken/设备会话 |
| 风控策略          | 连续 5 次密码错误锁定 15 分钟                                |
| Token 刷新        | Refresh Token 换取新的 Access Token，旧 Refresh 失效         |
| 图形验证码        | 注册前下发图形验证码，Redis 缓存，防暴力破解（登录不需要，方便测试） |

### 2.3 代码架构（包结构）

```
litisle-auth
├── controller/        # AuthController / CaptchaController / DeviceController / feign 提供方
│   ├── AuthController        # 注册/账密登录/邮箱验证码登录/退出/刷新/GET /me
│   ├── CaptchaController     # 图形验证码下发校验 + 邮箱验证码发送校验
│   ├── DeviceController      # 在线设备列表 / 踢出指定设备
│   ├── OAuth2Controller      # 第三方登录入口（未实现，已注释保留）
│   ├── SmsController         # 短信验证码（未实现，已注释保留）
│   └── feign/AuthFeignProviderController  # 实现 AuthClient 契约：forceLogout / verifyChangeEmail
├── service/
│   ├── AuthService           # 认证主流程（注册/登录/退出/刷新/me/强制下线）
│   ├── CaptchaService        # 图形验证码 + 邮箱验证码（发送/校验/防爆破阈值）
│   ├── DeviceSessionService  # 在线设备会话管理（基于 Sa-Token 会话）
│   └── oauth2/OAuth2LoginService  # 第三方登录（未实现，预留）
├── domain/
│   ├── dto/           # RegisterRequestDTO / PasswordLoginDTO / EmailLoginDTO / RefreshTsuccessenDTO / Verify*DTO
│   └── vo/            # TsuccessenVO / CaptchaImgVO / DeviceSessionVO / LoginUserInfoVO
├── enums/             # EmailCodeUsage / EmailRedisKey（邮箱验证码用途与 Redis Key）
├── constant/          # AuthCacheKey / CaptchaConstant / MailConstant / RateLimitConstant
├── utils/             # CaptchaCodeUtil / IpUtil
└── config/            # SaTokenWebConfig / AuthProperties / CaptchaProperties
    ├── SaTokenWebConfig      # SaInterceptor 登录校验拦截器
    ├── AuthProperties       # @ConfigurationProperties 绑定（认证参数）
    └── CaptchaProperties    # 验证码参数绑定
```



```

```

### 2.4 核心组件说明

> **⚠️ 登录态实现变更**：登录态由 **Sa-Token**（`StpUtil.login / logout / logoutByTokenValue`）管理，服务端会话失效即下线，**无需自建 Token 黑名单**。原设计的 `JwtTokenProvider` / `SessionManager` / `TokenBlacklist` / `SessionCache` 等自研 JWT 组件已废弃，不再实现。

| 组件                           | 类型       | 职责                                                         |
| ------------------------------ | ---------- | ------------------------------------------------------------ |
| `AuthController`               | Controller | 注册 / 账密登录 / 邮箱验证码登录 / 退出 / 刷新 Token / GET /me |
| `CaptchaController`            | Controller | 图形验证码下发与校验、邮箱验证码发送与校验                    |
| `DeviceController`             | Controller | 在线设备列表、踢出指定设备                                    |
| `OAuth2Controller`             | Controller | 第三方登录入口（**未实现，已注释保留**）                      |
| `SmsController`                | Controller | 短信验证码（**未实现，已注释保留**）                          |
| `AuthFeignProviderController`  | Controller | 实现 AuthClient 契约：forceLogout / verifyChangeEmail        |
| `AuthService`                  | Service    | 认证主流程编排：注册校验、登录、Sa-Token 登录态、聚合用户信息 |
| `CaptchaService`               | Service    | 图形验证码（Base64/一次性）+ 邮箱验证码（发送/校验/失败阈值自动失效强制重发） |
| `DeviceSessionService`         | Service    | 在线设备会话管理，基于 Sa-Token 会话实现设备列表与踢出逻辑    |
| `OAuth2LoginService`           | Service    | 第三方登录（**未实现，预留**）                                |
| `SaTokenWebConfig`             | Config     | SaInterceptor 登录校验拦截器（白名单路径放行）                |
| `AuthProperties`               | Config     | yml 配置绑定 JavaBean，集中管理认证/会话/锁定参数             |
| `CaptchaProperties`            | Config     | 验证码参数绑定（TTL、长度、防爆破阈值等）                     |
| `AuthCacheKey` / `EmailRedisKey` | Constant | Redis Key 规范（验证码、失败计数等临时态 Key）                |
| `MailConstant` / `CaptchaConstant` / `RateLimitConstant` | Constant | 邮件模板与签名 / 验证码常量 / 限流阈值常量                |

### 2.5 数据模型（复用 user 库 + Redis 临时态）

`litisle-auth` **无独立数据库、无独立表**，持久化数据复用 `litisleuser` 库，临时态数据走 Redis：

**持久化表（复用 user 库）**：

| 表名                | 来源          | 用途                                         |
| ------------------ | ------------- | -------------------------------------------- |
| `sys_user`         | user 模块管理 | 账号/密码/手机号/状态                        |
| `sys_oauth_binding`| user 模块管理 | OAuth2 绑定关系（auth 模块读写）             |

**Redis 临时态（不落库，TTL 自动过期）**：

| Key 模式                                              | TTL    | 用途                                            |
| ---------------------------------------------------- | ------ | ----------------------------------------------- |
| `ms:user:email:{usage}:{email}`                      | 5 分钟 | 邮箱验证码（一次性，连续错 3 次即失效强制重发） |
| `ms:user:email:limit:email:{email}` / `limit:ip:{ip}` | 60 秒 | 邮箱验证码发送限流（防刷）                      |
| `captcha:{key}`（Key 常量在 common-redis CacheConstant） | 3 分钟 | 图形验证码（一次性使用）                     |
| `auth:refresh:{refreshToken}`                        | 7 天   | refreshToken → userId 映射（轮换一次性使用）    |
| `auth:refresh:user:{userId}`                         | 7 天   | userId → refreshToken 映射（退出时清理）        |
| `auth:device:{deviceId}` (Hash)                      | 随会话 | 设备会话 {userId, tokenValue, deviceName, ip…}  |
| `auth:user:devices:{userId}` (Set)                   | 随会话 | 用户在线设备集合                                |

> Sa-Token 会话本体（token、登录设备列表、踢出标记）由框架托管（timeout=86400 / max-login-count=2），不在业务 Key 范围内。

> **设计原因**：
> - auth 与 user 共享用户库，避免跨服务事务；
> - 验证码/会话/黑名单等临时态数据走 Redis，TTL 自动过期，无需定时清理任务；
> - 邮箱验证码不落库，避免写库压力与垃圾数据，如需审计可后续通过 MQ 异步入日志服务。

### 2.6 配置管理

#### 2.6.1 配置项（CaptchaProperties / AuthProperties / sa-token）

**`@ConfigurationProperties(prefix = "litisle.captcha")` → CaptchaProperties**：

| 配置路径                           | 字段             | 默认值 | 说明                                   |
| ---------------------------------- | ---------------- | ------ | -------------------------------------- |
| `litisle.captcha.img-ttl`          | `imgTtl`         | 180    | 图形验证码 TTL（秒）                   |
| `litisle.captcha.email-ttl`        | `emailTtl`       | 300    | 邮箱验证码 TTL（秒）                   |
| `litisle.captcha.limit-ttl`        | `limitTtl`       | 60     | 防刷窗口 TTL（秒）                     |
| `litisle.captcha.limit-max`        | `limitMax`       | 5      | 窗口内最大发送次数                     |
| `litisle.captcha.max-verify-fails` | `maxVerifyFails` | 3      | 邮箱码连续校验失败上限（防穷举爆破）   |

**`@ConfigurationProperties(prefix = "litisle.auth")` → AuthProperties**：

| 配置路径                            | 字段                 | 默认值 | 说明                      |
| ----------------------------------- | -------------------- | ------ | ------------------------- |
| `litisle.auth.refresh-token-expire` | `refreshTokenExpire` | 604800 | refreshToken 有效期（秒） |

**`sa-token.*` → Sa-Token 框架配置**（application.yml）：

| 配置项               | 当前值                  | 说明                       |
| -------------------- | ----------------------- | -------------------------- |
| `token-name/prefix`  | Authorization / Bearer  | 请求头与前缀               |
| `timeout`            | 86400                   | Token 有效期（秒，1 天）   |
| `active-timeout`     | 604800                  | 记住我有效期（秒，7 天）   |
| `is-concurrent`      | true                    | 允许同账号多端登录         |
| `max-login-count`    | 2                       | 同账号最大登录数，超出踢最旧 |

#### 2.6.2 当前 yml 实现状态

[litisle-auth/src/main/resources/application.yml](file:///e:/NewJavaPro/LitIsle/litisle-auth/src/main/resources/application.yml) 当前已配置：sa-token 全套参数、`litisle.captcha.*`（CaptchaProperties）、`litisle.auth.refresh-token-expire`（AuthProperties）、AES 加密密钥（common-security `crypto.key`）、邮件 SMTP（QQ 邮箱）。

> **待办**：`sa-token.jwt-secret-key` 当前为占位值，生产环境需通过环境变量 `LITISLE_JWT_SECRET` 注入；环境特定值（Nacos/Redis/邮箱）在 application-local 中维护。

### 2.7 关键业务流程

#### 2.7.1 注册流程

```
客户端
  │ POST /auth/register（username + password + phone/email + captcha）
  ▼
AuthController
  │
  ▼
AuthService.register()
  ├─① CaptchaService.verifyImgCode()               # 校验图形验证码（一次性）
  ├─② DFA 校验用户名（敏感词过滤，复用 common-core）
  └─③ UserClient.createUser(RegisterUserDTO)       # 以下全部在 user 端一步完成：
        ├─ 用户名/手机号/邮箱唯一性校验
        ├─ AES-256-GCM 加密手机号/邮箱
        ├─ BCrypt 加密密码，落库
        └─ 默认分配 READER 角色
  ▼
返回（注册成功不签发 Token，引导用户重新登录）
```

#### 2.7.2 登录流程（三种方式归一化）

**方式一：账号密码登录**

```
POST /auth/login（username + password）
  ├─① UserClient.findByAccount(username)           # 兼容用户名/手机号/邮箱聚合查询（含密码哈希/状态/角色/权限）
  ├─② BCrypt 校验密码（common-security）
  ├─③ 状态校验：status != NORMAL → 抛 USER_FROZEN/USER_CANCELLED
  └─④ StpUtil.login(userId)                        # Sa-Token 登录（见下方统一登录动作）
```

**方式二：手机验证码登录**

```
POST /auth/login/sms（email + emailCode，实际为邮箱验证码登录，路径名待调整）
  ├─① CaptchaService.verifyEmailCode(usage=LOGIN)  # 邮箱验证码，一次性
  │     └─ 失败/过期/连续错 3 次 → 抛验证码无效
  ├─② UserClient.findByEmail(email)
  │     └─ 不存在 → 抛 USER_NOT_FOUND（邮箱验证码登录不支持自动注册，需先注册）
  ├─③ 状态校验
  └─④ StpUtil.login(userId)
```

**方式三：OAuth2 登录（策略模式，未实现，保留设计）**

```
GET /auth/oauth/{provider}?code=xxx&state=xxx
  ├─① OAuth2StateCache.verify(state)               # 防 CSRF
  ├─② OAuth2LoginHandlerFactory.get(provider)      # 路由到 WechatHandler/QQHandler
  ├─③ handler.exchangeToken(code)                  # 换 access_token
  ├─④ handler.getUserInfo(accessToken)             # 获取 openId/unionId/userInfo
  ├─⑤ UserFeign.queryByOAuth(provider, openId)
  │     ├─ 已绑定 → 直接登录，签发 Token
  │     └─ 未绑定 → 返回临时 token，前端引导绑定/注册新账号
  │                  └─ POST /auth/oauth/bind（tempToken + userId/phone）
  └─⑥ 签发 Token

> **统一登录动作（三种方式共用）**：`StpUtil.login(userId)` → 同账号超 `max-login-count=2` 时框架自动踢出最旧会话 → SaSession 写入角色/权限等登录态 → 生成 refreshToken（`auth:refresh:*`，7 天，可轮换）→ 返回 `TsuccessenVO`（accessToken + refreshToken + expiresIn）。
```

#### 2.7.3 Token 刷新流程

```
POST /auth/refresh（refreshToken）
  ├─① 查 Redis auth:refresh:{refreshToken} → userId
  │     └─ 不存在（过期或已轮换）→ 抛 REFRESH_TOKEN_EXPIRED，前端跳登录
  ├─② UserClient.getAuthUserInfo(userId)           # 重新查角色/权限（可能变更）
  ├─③ 删除旧 refreshToken，生成新 refreshToken 写回（轮换，一次性使用）
  └─④ 返回 TsuccessenVO（accessToken 为 Sa-Token 当前会话 token）
```



#### 2.7.4 退出登录流程

```
POST /auth/logout（Authorization: Bearer xxx）
  ├─① 删除 auth:refresh:user:{userId} 与 auth:refresh:{refreshToken}
  ├─② StpUtil.logout()                             # 服务端会话失效，token 立即不可用，无需黑名单
  └─③ 清理设备会话（auth:device:{deviceId} / auth:user:devices:{userId}）

```

#### 2.7.5 在线设备管理流程

```
登录成功后 DeviceSessionService 记录设备会话
  ├─① SADD auth:user:devices:{userId} {deviceId}
  ├─② HSET auth:device:{deviceId} {userId, tokenValue, deviceName, ip, loginTime, lastActiveTime}
  ├─③ 同账号并发数由 Sa-Token 托管（max-login-count=2）：
  │     └─ 超出时框架自动踢出最旧会话
  └─④ 踢出指定设备 → logoutByTokenValue(tokenValue) + 移除设备会话记录
```

#### 2.7.6 登录失败锁定流程（设计保留，暂未落地）

```
LoginFailCounter.incr(username)
  ├─① Redis INCR login:fail:{username}
  ├─② 首次失败 → EXPIRE 15分钟（锁定窗口）
  ├─③ 达到 max-fail-count（5）：
  │     └─ SET login:lock:{username} 1 EX 900（锁定15分钟）
  └─④ 成功登录 → DEL login:fail:{username}

```

### 2.8 安全策略

| 安全项           | 实现方式                                                |
| ---------------- | ------------------------------------------------------- |
| 密码存储         | BCrypt 加盐哈希（cost=10），不存明文                    |
| Token 短效       | Sa-Token timeout=86400（1 天），active-timeout=604800（记住我 7 天） |
| Refresh 轮换     | 刷新即删除旧 refreshToken 并生成新值（auth:refresh:* 一次性使用），防重放 |
| 会话注销机制     | 退出/踢出走 Sa-Token 服务端会话注销（logout / logoutByTokenValue），无需黑名单 |
| OAuth2 防 CSRF   | state 参数随机生成 + Redis 缓存，校验后销毁（未实现，保留设计） |
| 图形验证码       | 注册强制、登录按需，一次性使用，防止暴力破解            |
| 接口限流         | @RateLimiter 统一参数（RateLimitConstant，10 秒窗口/IP 维度） |
| 登录失败锁定     | 连续 5 次失败锁定 15 分钟，Redis 计数                   |
| 手机号脱敏       | 响应 VO 通过 `@Sensitive(strategy=PHONE)` 自动脱敏      |
| 敏感 Header 过滤 | 网关层移除客户端伪造的 X-User-Id/X-Roles                |
| JWT 密钥管理     | 通过环境变量 `JWT_SECRET` 注入，不硬编码                |

### 2.9 与其他模块的交互

| 交互对象          | 方式  | 内容                                                    |
| ----------------- | ----- | ------------------------------------------------------- |
| `litisle-user`    | Feign | 查询/创建用户、查角色、绑定 OAuth、检查冷却期           |
| `litisle-gateway` | HTTP  | 网关校验 JWT 后注入 X-User-Id/X-Roles，auth 不重复校验  |
| `litisle-common`  | 依赖  | DFA 用户名校验、@AuditLog 审计、@Sensitive 脱敏、Result |
| Redis             | 直连  | Token 黑名单、会话列表、验证码、登录失败计数            |
| 短信网关          | HTTP  | 发送短信验证码（阿里云/腾讯云）                         |
| 微信/QQ 开放平台  | HTTP  | OAuth2 code 换 token、获取用户信息                      |

---

## 三、litisle-user 用户模块

### 3.1 模块定位

**定位**：管理用户账号资料、角色体系（6 角色）、VIP 状态、注销流程、关注/粉丝计数。

**核心特征**：

- **用户库主管理者**：`litisleuser` 库由 user 模块主导，auth 模块共享使用。
- **角色叠加体系**：同一用户可拥有多角色，存在互斥规则（LEGACY_AUTHOR 与 AUTHOR 互斥）。
- **删除状态唯一真相源**：`sys_user.del_flag` 是用户删除状态的唯一来源，扩展表不设 del_flag。
- **资料与账号分离**：主表 `sys_user` 保留高频鉴权字段，扩展资料独立 `sys_user_profile` 表。

### 3.2 功能清单

| 功能           | 说明                                                         |
| -------------- | ------------------------------------------------------------ |
| 用户资料管理   | 昵称、头像（SeaweedFS）、手机号（加密存储）、邮箱、简介、笔名 |
| 角色体系       | 6 角色：READER / AUTHOR / LEGACY_AUTHOR / REVIEWER / ADMIN / SUPER_ADMIN |
| 角色叠加校验   | 同一用户可同时拥有多个角色，组合规则（如 LEGACY_AUTHOR 与 AUTHOR 互斥） |
| VIP 状态       | 购买 VIP 后到期时间自动计算，到期前 7 天站内信提醒，到期后自动冻结 VIP 权益 |
| 头像标识       | 普通用户无标识、作家金色头像框、纪念作家白色头像框           |
| 注销账号       | 逻辑注销（`del_flag=1`），不可恢复，需二次确认，30 天冷却期（控制器未实现） |
| 关注/粉丝计数  | Redis String 存储，异步落库（控制器未实现）                  |
| 角色申请审核   | READER 申请 AUTHOR → review 审核 → 通过则绑定角色 + 清缓存   |
| 手机号/邮箱明文查看 | 临时解密 + 审计日志（@AuditLog，UserSensitiveController） |
| 管理员用户管理 | /api/admin/users：分页/详情/改状态/角色分配/注销用户（UserAdminController） |
| 封禁/解封      | 写 ban_log + frozen_until，联动 AuthClient.forceLogout 强制下线；到期 UserUnbanTask 自动解封 |
| RBAC 权限      | sys_permission / sys_role_permission 权限体系，角色-权限关联查询 |
| UID 搜索       | sys_user.uid 对外唯一标识，按 UID/用户名搜索用户（UserSearchController） |

### 3.3 代码架构（包结构）

```
litisle-user
├── controller/
│   ├── UserProfileController        # 资料增改查、绑定/改绑邮箱
│   ├── UserRoleController           # 角色列表、我的角色、查用户角色
│   ├── UserAdminController          # /api/admin/users：分页/详情/改状态/封禁/解封/角色分配/注销用户
│   ├── UserSearchController         # 按 UID/用户名搜索用户
│   ├── UserSensitiveController      # 手机号/邮箱明文查看（@AuditLog 审计）
│   ├── UserFeignProviderController  # 实现 UserClient 契约（getUserById/createUser/findByAccount/findByEmail/getAuthUserInfo）
│   ├── AccountCancelController      # 注销账号（未实现，已注释保留）
│   ├── VipController                # VIP 状态（未实现，已注释保留）
│   └── FollowController             # 关注/粉丝计数（未实现，已注释保留）
├── service/
│   ├── UserService           # 注册（唯一性校验/AES 加密/READER 分配）、账号查询聚合
│   ├── UserProfileService    # 资料管理
│   ├── UserRoleService       # 用户角色查询/分配
│   ├── UserAdminService      # 管理员侧：封禁/解封（联动 AuthClient.forceLogout）、注销用户
│   ├── RoleService / PermissionService / RolePermissionService  # RBAC 角色-权限
│   ├── OAuthBindingService   # OAuth 绑定（未实现，预留）
│   └── UserBanLogService / UserCancelLogService  # 封禁/注销审计日志
├── mapper/            # 9 张实体表对应 Mapper
├── domain/
│   ├── entity/        # User / UserProfile / Role / UserRole / OAuthBinding / Permission / RolePermission / UserBanLog / UserCancelLog（已实现）
│   ├── dto/           # RegisterUserDTO / 管理员管理系列 DTO / Verify*DTO
│   └── vo/            # 用户详情/简要/角色/权限 VO
├── enums/             # AvatarColor / DelFlag / RoleType / PermTypeEnum / PermStatusEnum / UserBanAction / UserBanReason / UserCancelSource（UserStatus 已迁 common-core）
├── constant/          # UserInfoConstant / UserLimitConstant
├── utils/             # RoleUtils（角色聚合/判定工具）
├── task/              # UserUnbanTask（封禁到期自动解封定时任务）
└── config/            # AuditMetaObjectHandler（create_time/update_time 自动填充）
```

### 3.4 核心组件说明

| 组件                            | 类型       | 职责                                                         |
| ------------------------------- | ---------- | ------------------------------------------------------------ |
| `UserProfileController`         | Controller | 资料增改查、绑定/改绑邮箱                                     |
| `UserRoleController`            | Controller | 角色列表、我的角色、查用户角色                                |
| `UserAdminController`           | Controller | 管理员侧：分页/详情/改状态/封禁/解封/角色分配/注销用户        |
| `UserSearchController`          | Controller | 按 UID/用户名搜索用户                                         |
| `UserSensitiveController`       | Controller | 手机号/邮箱明文查看（@AuditLog 审计）                         |
| `UserFeignProviderController`   | Controller | 实现 UserClient 契约五个方法                                  |
| `UserService`                   | Service    | 注册（唯一性校验/AES 加密/READER 分配）、账号查询聚合         |
| `UserAdminService`              | Service    | 封禁/解封（写 ban_log + frozen_until，联动 forceLogout）、注销用户（写 cancel_log） |
| `RoleService` / `PermissionService` / `RolePermissionService` | Service | RBAC：角色、权限、角色-权限关联                    |
| `UserBanLogService` / `UserCancelLogService` | Service | 封禁/注销审计日志落库                        |
| `RoleUtils`                     | Utils      | 角色聚合与判定工具                                            |
| `UserUnbanTask`                 | Task       | 封禁到期自动解封定时任务                                      |
| `AuditMetaObjectHandler`        | Config     | MyBatis-Plus create_time/update_time 自动填充                 |

### 3.5 数据模型

#### 3.5.1 表清单

| 表名                  | 已实现实体      | 说明                                        |
| --------------------- | --------------- | ------------------------------------------- |
| `sys_user`            | ✅ User          | 用户主表（账号/密码/uid/手机号/邮箱/状态/头像标识/封禁截止） |
| `sys_role`            | ✅ Role          | 角色表（6 角色初始化数据）                  |
| `sys_user_role`       | ✅ UserRole      | 用户-角色关联表（多对多，独立 id 主键）     |
| `sys_user_profile`    | ✅ UserProfile   | 用户资料扩展表（笔名/创作方向，avatarColor 已迁主表） |
| `sys_oauth_binding`   | ✅ OAuthBinding  | OAuth 绑定表（物理删除 + 审计，未实现预留） |
| `sys_permission`      | ✅ Permission    | RBAC 权限表（permCode/permType/path/component，树形菜单） |
| `sys_role_permission` | ✅ RolePermission | 角色-权限关联表                          |
| `sys_user_ban_log`    | ✅ UserBanLog    | 封禁审计表（动作/原因/截止时间/操作人）     |
| `sys_user_cancel_log` | ✅ UserCancelLog | 注销审计表（来源/原因/操作人）              |

#### 3.5.2 实体关系图

```
┌──────────────┐     ┌──────────────┐
│   sys_user   │     │   sys_role   │
│──────────────│     │──────────────│
│ id (PK)      │     │ id (PK)      │
│ username     │     │ role_code    │
│ password     │     │ role_name    │
│ phone (加密)  │     │ role_type    │
│ email        │     │ create_time  │
│ avatar       │     └──────┬───────┘
│ uid          │            │
│ avatar_color │            │
│ bio          │            │
│ vip_expire   │            │ M:N
│ frozen_until │            │
│ cancel_time  │            ▼
│ status       │     ┌──────────────┐
│ del_flag     │◀───▶│ sys_user_role│
│ version      │     │──────────────│
│ create_time  │     │ user_id (PK) │
│ update_time  │     │ role_id (PK) │
└──────┬───────┘     └──────────────┘
       │ 1:1
       ▼
┌──────────────────┐
│ sys_user_profile  │
│──────────────────│
│ id (PK)          │
│ user_id (UQ)     │
│ pen_name         │
│ creation_dir     │
│ masterpiece      │
│ version          │

> 注：avatar_color 已从 profile 表迁入 sys_user 主表。
└──────────────────┘

       │ 1:N
       ▼

┌────────────────────┐
│ sys_oauth_binding   │
│────────────────────│
│ id (PK)            │
│ user_id            │
│ provider           │
│ app_id             │
│ open_id            │
│ union_id           │
│ create_time        │
│ update_time        │
│ UK(provider,app_id,│
│    open_id)        │
└────────────────────┘
```

#### 3.5.3 已实现的实体字段映射（与 SQL 对齐）

**User**（[查看代码](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/User.java)）：

- 字段：id / username / **uid（对外唯一标识，随机生成永久不变）** / password / phone / email / avatar / **avatarColor** / bio / vipExpireTime / frozenUntil / cancelTime / status / delFlag / version / createTime / updateTime
- `status` 用 `UserStatus` 枚举（**已迁 common-core**），`delFlag` 用 `DelFlag` 枚举（user 模块），`@EnumValue` 映射
- `avatarColor` 用 `AvatarColor` 枚举（NONE/GOLD/WHITE），**已从 profile 表迁入主表**
- `version` 用 `@Version` 乐观锁；`createTime/updateTime` 由 `AuditMetaObjectHandler` 自动填充
- 校验注解：用户名/密码格式、手机号 @Pattern、邮箱 @Email（jakarta.validation）

**UserProfile**（[查看代码](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/UserProfile.java)）：

- 字段：id / userId / penName / creationDir / masterpiece / version / createTime / updateTime
- `avatarColor` 已移除（迁入主表）；**不设 del_flag**（删除状态以主表为准）

**Role**（[查看代码](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/Role.java)）：

- 字段：id / roleCode / roleName / roleType / createTime

**UserRole**（[查看代码](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/UserRole.java)）：

- 字段：id / userId / roleId（**独立 id 主键**，非复合主键）

**OAuthBinding**（[查看代码](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/OAuthBinding.java)）：

- 字段：id / userId / provider / appId / openId / unionId / createTime / updateTime
- **物理删除**（解绑直接 DELETE），由 `@AuditLog` 记录审计

**Permission**（[查看代码](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/Permission.java)）：

- 字段：id / parentId / permCode / permName / permType / path / component / icon / sort / status / createTime / updateTime
- RBAC 权限表，parentId 支持树形菜单结构

**RolePermission**（[查看代码](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/RolePermission.java)）：

- 字段：roleId / permissionId（角色-权限关联）

**UserBanLog**（[查看代码](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/UserBanLog.java)）：

- 字段：id / userId / username / roleCode / action / reason / frozenUntil / operatorId / operatorName / createTime

**UserCancelLog**（[查看代码](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/UserCancelLog.java)）：

- 字段：id / userId / username / roleCode / source / reason / operatorId / operatorName / createTime

#### 3.5.4 已实现的枚举

| 枚举               | 位置             | 值                                     |
| ------------------ | ---------------- | -------------------------------------- |
| `UserStatus`       | **common-core**  | DISABLED(0) / NORMAL(1) / CANCELLED(2) |
| `DelFlag`          | user 模块 enums  | 0-未删除 / 1-已删除                    |
| `AvatarColor`      | user 模块 enums  | NONE(0) / GOLD(1) / WHITE(2)           |
| `RoleType`         | user 模块 enums  | SYSTEM(1) / BUSINESS(2)                |
| `PermTypeEnum`     | user 模块 enums  | 权限类型（详见代码）                   |
| `PermStatusEnum`   | user 模块 enums  | 权限状态（详见代码）                   |
| `UserBanAction`    | user 模块 enums  | 封禁动作（详见代码）                   |
| `UserBanReason`    | user 模块 enums  | 封禁原因（详见代码）                   |
| `UserCancelSource` | user 模块 enums  | 注销来源（详见代码）                   |

### 3.6 关键业务流程

#### 3.6.1 角色叠加校验流程

```
用户申请 AUTHOR 角色
  │
  ▼
RoleService.applyRole(userId, roleCode=AUTHOR)
  ├─① 查询当前角色列表（先查 RoleCache，未命中查库）
  ├─② 叠加规则校验：
  │     ├─ 已有 LEGACY_AUTHOR → 拒绝（互斥）
  │     ├─ 已有 AUTHOR → 幂等返回
  │     └─ READER → 允许申请
  ├─③ 发送审核事件到 review 服务（MQ: author.apply）
  ├─④ Review 审核（REVIEWER 决策）
  └─⑤ 审核通过：
        ├─ INSERT sys_user_role (userId, AUTHOR_ROLE_ID)
        ├─ 更新 sys_user_profile.avatar_color = GOLD(1)
        ├─ 清除 RoleCache（用户角色列表）
        └─ 发站内信通知
```

**角色互斥规则**：

| 已有角色      | 可叠加            | 互斥                                   |
| ------------- | ----------------- | -------------------------------------- |
| READER        | AUTHOR / REVIEWER | -                                      |
| AUTHOR        | REVIEWER          | LEGACY_AUTHOR                          |
| LEGACY_AUTHOR | -                 | AUTHOR（已是纪念作家，不再升普通作家） |
| REVIEWER      | -                 | -                                      |
| ADMIN         | -                 | -                                      |
| SUPER_ADMIN   | -                 | 仅 dev profile 启用                    |

#### 3.6.2 VIP 状态管理流程

```
购买 VIP（payment 服务发起）
  │
  ▼
VipStateService.activateVip(userId, days)
  ├─① 查当前 vip_expire_time
  │     ├─ 未过期 → vip_expire_time += days（续费）
  │     └─ 已过期/未开通 → vip_expire_time = now() + days
  ├─② 更新 sys_user.vip_expire_time（乐观锁）
  ├─③ 清除 VipCache
  └─④ 设置到期前 7 天提醒任务（延迟 MQ）

定时任务（每天 00:30 扫描）
  ├─① 查 vip_expire_time BETWEEN now() AND now()+7天
  │     └─ 发站内信「VIP 即将到期」
  └─② 查 vip_expire_time < now()
        └─ 标记 VIP 失效（清 VipCache，发事件通知其他服务降级）
```

#### 3.6.3 头像标识计算流程

```
avatar_color 计算规则：
  ├─ 拥有 LEGACY_AUTHOR 角色 → WHITE(2) 白色头像框
  ├─ 拥有 AUTHOR 角色 → GOLD(1) 金色头像框
  └─ 其他 → NONE(0) 无标识

触发时机：
  ├─ 角色绑定时（applyRole 通过后）
  ├─ 角色解绑时
  └─ 纪念作家授予时（LEGACY_AUTHOR 由管理员手动授予）
```

#### 3.6.4 注销与冷却期流程

**注销流程**：

````
POST /user/logout-account（二次确认密码）
  ├─① 校验密码（二次确认）
  ├─② 事务内：
  │     ├─ UPDATE sys_user SET del_flag=1, status=CANCELLED, cancel_time=NOW()
  │     ├─ sys_user_profile 保留（不删除，便于审计）
  │     └─ 解绑所有 OAuth（DELETE sys_oauth_binding WHERE user_id=xxx）
  ├─③ 清除所有 Redis 缓存（RoleCache/VipCache/Session）
  ├─④ 该用户所有 Token 加入黑名单（调 auth 服务或直接操作 Redis）
  └─⑤ 发注销事件（MQ: user.cancelled）通知其他服务清理数据

```

````

**冷却期机制**（手机号重新注册）：

```
注册时检查手机号
  ├─① 查 (phone, del_flag=0) 存在 → 拒绝（手机号已被活跃用户使用）
  ├─② 查 (phone, del_flag=1) 存在：
  │     ├─ cancel_time + 30天 > now() → 拒绝（冷却期内）
  │     └─ 冷却期已过 → 删除旧注销记录 → 允许注册
  └─③ 无记录 → 直接允许注册
```

**关键设计点**：

- 联合唯一索引 `uk_phone_del(phone, del_flag)`：活跃用户 `(phone, 0)` 与已注销 `(phone, 1)` 互不冲突
- NULL 语义：phone 允许为空，多个 `(NULL, 0)` 不冲突
- OAuth 表物理删除：openId 不被"已注销"记录占位，同一微信/QQ 可随时重新绑定

#### 3.6.5 关注/粉丝计数流程

```
用户 A 关注用户 B
  │
  ▼
FollowService.follow(A, B)（social 服务发起）
  ├─① Redis INCR follow:cnt:{A}（A 的关注数 +1）
  ├─② Redis INCR fans:cnt:{B}（B 的粉丝数 +1）
  ├─③ 写 follow 关系表（social 库）
  └─④ 异步落库（定时任务同步 Redis 计数到 sys_user 冗余字段）

查询时：
  └─ 优先读 Redis，未命中读库并回填
```

#### 3.6.6 管理员封禁/解封流程

```
管理员封禁（POST /api/admin/users/{id}/ban）
  ├─① 写 sys_user_ban_log（action/reason/frozenUntil/operator）
  ├─② UPDATE sys_user SET frozen_until=截止时间, status=DISABLED（乐观锁）
  ├─③ 调 AuthClient.forceLogout(userId) 强制下线全部在线会话
  └─④ 到期自动解封：UserUnbanTask 定时扫描 frozen_until < now()
        └─ 恢复 status=NORMAL、清 frozen_until、补写解封 ban_log

注销用户（管理员侧）：写 sys_user_cancel_log（source=ADMIN）+ 逻辑注销 + 强制下线
```

### 3.7 安全策略

| 安全项           | 实现方式                                                     |
| ---------------- | ------------------------------------------------------------ |
| 手机号加密存储   | AES 加密存储，明文查看需临时解密 + `@AuditLog` 审计          |
| 手机号脱敏       | 响应 VO 通过 `@Sensitive(strategy=PHONE)` 自动脱敏（138****8888） |
| 邮箱脱敏         | 响应 VO 通过 `@Sensitive(strategy=EMAIL)` 自动脱敏           |
| 注销二次确认     | 需输入密码确认，不可恢复                                     |
| SUPER_ADMIN 限制 | 仅 dev profile 启用，生产环境禁用                            |
| 角色变更审计     | 角色绑定/解绑通过 `@AuditLog` 记录操作人、时间、参数         |
| 注销冷却期       | 30 天内同手机号不可重新注册                                  |
| 乐观锁           | sys_user / sys_user_profile 用 `@Version` 防并发修改         |
| 头像鉴权         | 头像走 litisle-file 私有文件鉴权下载                         |

### 3.8 与其他模块的交互

| 交互对象          | 方式       | 内容                                                    |
| ----------------- | ---------- | ------------------------------------------------------- |
| `litisle-auth`    | Feign 双向 | user 提供 UserClient（查询/创建/聚合查询）；user 调 auth 的 AuthClient（forceLogout 强制下线/verifyChangeEmail 验码） |
| `litisle-content` | Feign 被调 | 提供作者信息、头像标识                                  |
| `litisle-social`  | Feign 被调 | 提供用户简要信息（关注/粉丝列表展示）                   |
| `litisle-payment` | Feign 被调 | VIP 激活通知（payment 购买 VIP 后调用 VipStateService） |
| `litisle-review`  | MQ         | 角色申请审核结果回调（review 通过后通知 user 绑定角色） |
| `litisle-file`    | Feign      | 头像上传/下载                                           |
| `litisle-common`  | 依赖       | @AuditLog、@Sensitive、SecurityContextHolder、Result    |
| Redis             | 直连       | 角色缓存、VIP 缓存、关注/粉丝计数                       |

---

## 四、两模块协同关系

### 4.1 数据库共享

```
┌─────────────────────────────────────────┐
│           数据库 litisleuser（持久化）     │
│─────────────────────────────────────────│
│  sys_user          ← user 管理（主）     │
│  sys_role          ← user 管理（主）     │
│  sys_user_role     ← user 管理（主）     │
│  sys_user_profile  ← user 管理（主）     │
│  sys_oauth_binding ← user 管理实体       │
│                    ← auth 读写绑定关系   │
│  sys_permission / sys_role_permission ← user 管理 │
│  sys_user_ban_log / sys_user_cancel_log ← user 管理 │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│           Redis（临时态，不落库）        │
│─────────────────────────────────────────│
│  ms:user:email:*   ← auth 独占（邮箱验证码+限流）│
│  captcha:*         ← auth 独占（图形码，Key 在 common-redis）│
│  auth:refresh:*    ← auth 独占（refreshToken 轮换）│
│  auth:device:*     ← auth 独占（设备会话）│
│  （Sa-Token 会话本体由框架托管）          │
└─────────────────────────────────────────┘
```

**共享规则**：
- 持久化表的"主管理者"负责实体定义与 DDL 维护
- auth 模块复用 user 的实体类（通过 litisle-api 共享或直接依赖）
- 邮箱验证码 / 图形验证码 / refreshToken / 设备会话均走 Redis，不落库，由 auth 独占管理
- 如需对验证码发送记录做审计，后续通过 MQ 异步入日志服务，不影响主流程

### 4.2 Feign 调用链

````
litisle-auth                              litisle-user
    │                                          │
    │  UserClient.getUserById(userId)          │
    │─────────────────────────────────────────▶│ UserFeignProviderController
    │  UserClient.createUser(RegisterUserDTO)  │
    │─────────────────────────────────────────▶│ 唯一性校验+AES 加密+READER 分配
    │  UserClient.findByAccount(account)       │
    │─────────────────────────────────────────▶│ 聚合认证信息（密码哈希/状态/角色/权限）
    │  UserClient.findByEmail(email)           │
    │─────────────────────────────────────────▶│ 邮箱验证码登录查账号
    │  UserClient.getAuthUserInfo(userId)      │
    │─────────────────────────────────────────▶│ /auth/me 聚合（角色/权限/作家资料）
    │                                          │
    │  AuthClient.forceLogout(userId)          │
    │◀─────────────────────────────────────────│ UserAdminService（封禁/注销用户）
    │  AuthClient.verifyChangeEmail(dto)       │
    │◀─────────────────────────────────────────│ UserProfileService（改绑邮箱）
    ▼                                          ▼

```



````

> **契约位置**：Feign 接口定义在 [litisle-api/feign](file:///e:/NewJavaPro/LitIsle/litisle-api/src/main/java/com/litisle/litisleapi/) 模块，DTO/VO 在 [litisle-api/dto](file:///e:/NewJavaPro/LitIsle/litisle-api/src/main/java/com/litisle/litisleapi/) 共享。

### 4.3 安全上下文透传

```客户端请求
  │
  ▼
litisle-gateway
  ├─ JwtGlobalFilter 校验 JWT
  ├─ 解析 userId / roles
  └─ 注入 Header: X-User-Id / X-Roles / X-Trace-Id
  │
  ▼
litisle-auth / litisle-user（业务服务）
  ├─ SecurityContextHolderFilter 读取 Header
  ├─ 构建 SecurityContext（ThreadLocal）
  ├─ 业务代码通过 SecurityContextHolder.getUserId() 获取
  │
  │  Feign 调用其他服务时
  ├─ FeignAuthInterceptor 自动透传 X-User-Id / X-Trace-Id
  │
  └─ 请求结束清理 ThreadLocal
```

### 4.4 注销协同流程

```
用户注销（user 服务发起）
  │
  ▼
UserService.cancelAccount(userId)
  ├─① 软删 sys_user（del_flag=1）
  ├─② 解绑 OAuth
  ├─③ 清除 user 侧缓存
  ├─④ 调用 AuthFeignApi.revokeAllTokens(userId)  # 踢出所有设备
  │     └─ auth 服务：遍历 SessionCache，所有 Token 加入黑名单
  └─⑤ 发 MQ 事件 user.cancelled
        ├─ social：清理关注关系
        ├─ content：文章标记为"纪念作家"或下架
        └─ notification：发送注销确认通知

```

---

## 五、当前实现状态

### 5.1 litisle-user 已实现

| 内容                          | 状态     | 位置                                                         |
| ----------------------------- | -------- | ------------------------------------------------------------ |
| SysUser 实体                  | ✅ 已实现 | [domain/entity/SysUser.java](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/SysUser.java) |
| SysRole 实体                  | ✅ 已实现 | [domain/entity/SysRole.java](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/SysRole.java) |
| SysUserRole 实体              | ✅ 已实现 | [domain/entity/SysUserRole.java](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/SysUserRole.java) |
| SysUserProfile 实体           | ✅ 已实现 | [domain/entity/SysUserProfile.java](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/SysUserProfile.java) |
| SysOAuthBinding 实体          | ✅ 已实现 | [domain/entity/SysOAuthBinding.java](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/SysOAuthBinding.java) |
| UserStatus 枚举               | ✅ 已实现 | [enums/UserStatus.java](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/enums/UserStatus.java) |
| AvatarColor 枚举              | ✅ 已实现 | [enums/AvatarColor.java](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/enums/AvatarColor.java) |
| RoleType 枚举                 | ✅ 已实现 | [enums/RoleType.java](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/enums/RoleType.java) |
| application.yml               | ✅ 已配置 | [application.yml](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/resources/application.yml) |
| Controller / Service / Mapper | ⏳ 待实现 | -                                                            |

### 5.2 litisle-auth 已实现

| 内容                                            | 状态     | 位置                                                         |
| ----------------------------------------------- | -------- | ------------------------------------------------------------ |
| LitisleAuthApplication                          | ✅ 已实现 | [LitisleAuthApplication.java](file:///e:/NewJavaPro/LitIsle/litisle-auth/src/main/java/com/litisle/litisleauth/LitisleAuthApplication.java) |
| application.yml（基础配置）                     | ✅ 已配置 | [application.yml](file:///e:/NewJavaPro/LitIsle/litisle-auth/src/main/resources/application.yml) |
| AuthProperties JavaBean                         | ⏳ 待实现 | -                                                            |
| Controller / Service / Security / OAuth / Redis | ⏳ 待实现 | -                                                            |
| Redis 验证码/会话/黑名单 Key 设计                | ⏳ 待实现 | -                                                            |

### 5.3 公共基建已实现（两模块共用）

| 模块            | 关键能力                                                     | 状态     |
| --------------- | ------------------------------------------------------------ | -------- |
| common-core     | 异常处理 / 统一响应 / 工具类 / Jackson 配置 / MyBatis-Plus 配置 | ✅ 已实现 |
| common-redis    | Redis 工具 / 分布式锁 / 布隆过滤器 / 接口幂等                | ✅ 已实现 |
| common-security | 安全上下文 / 角色校验 / 审计日志 / 脱敏 / XSS / 匿名认证     | ✅ 已实现 |

---

## 六、后续开发注意事项

### 6.1 litisle-auth 开发优先级

1. **创建 `AuthProperties` JavaBean**：承载 `litisle.security.*` 配置（参考 [commom-des.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/commom-des.md) §七·待办2）
2. **实现 `JwtTokenProvider`**：JWT 签发/解析/校验（JJWT 库）
3. **实现 `SessionManager`**：Redis List 维护在线设备，踢出超额设备
4. **实现 `TokenBlacklist`**：退出/踢出 Token 黑名单
5. **实现 `AuthService`**：注册/登录/退出/刷新主流程
6. **实现 `SmsCodeService` + `CaptchaService`**：验证码生成与校验
7. **实现 OAuth2 策略**：`OAuth2LoginHandler` 接口 + 微信/QQ 实现
8. **设计 Redis Key 规范**：短信验证码 `sms:code:{phone}:{scene}`（TTL 5分钟）、发送限流 `sms:limit:{phone}/{ip}`（TTL 60秒）、图形验证码 `captcha:{key}`（TTL 2分钟，一次性），均不落库

### 6.2 litisle-user 开发优先级

1. **实现 Mapper 层**：5 个 Mapper 接口（继承 BaseMapper）
2. **实现 `UserService`**：账号增改查、注销流程、冷却期校验、手机号加密
3. **实现 `RoleService`**：角色绑定/解绑、叠加规则校验、角色缓存
4. **实现 `VipStateService`**：VIP 到期检查、定时任务、到期提醒
5. **实现 Controller 层**：UserController / ProfileController
6. **对接 litisle-file**：头像上传/下载
7. **实现 Redis 缓存层**：RoleCache / VipCache / FollowCounter / FansCounter

### 6.3 跨模块协同开发

1. **在 litisle-api 定义 `UserFeignApi`**：auth 调用 user 的契约
2. **在 litisle-api 定义共享 DTO/VO**：UserBriefDTO / UserRoleDTO 等
3. **角色枚举集中管理**：`RoleEnum` 定义在 [litisle-api/enums](file:///e:/NewJavaPro/LitIsle/litisle-api/src/main/java/com/litisle/litisleapi/)，避免两模块重复定义
4. **注销事件 schema**：`UserCancelledEvent` 定义在 [litisle-api/event](file:///e:/NewJavaPro/LitIsle/litisle-api/src/main/java/com/litisle/litisleapi/)

### 6.4 注意事项

- **XSS 富文本接口排除**：user 模块无富文本接口，无需配置 exclude-paths
- **匿名白名单**：user 模块的 `GET /user/{id}/brief` 等公开接口需加 `@Anonymous` 注解
- **手机号加密密钥**：通过环境变量注入，不硬编码
- **SUPER_ADMIN 仅 dev profile**：通过 `@Profile("dev")` 控制
- **审计日志异步入库**：当前 `@AuditLog` 通过 `log.info` 输出，生产环境改 MQ 异步入库（参考 [commom-des.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/commom-des.md) §七·待办4）
- **spring-boot-starter-security 依赖**：common-security 仍有此依赖（provided），建议删除避免自动配置冲突（参考 [commom-des.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/commom-des.md) §七·待办5）

---

## 七、架构亮点总结

### 7.1 设计模式应用

| 模式       | 应用场景                                                     |
| ---------- | ------------------------------------------------------------ |
| 策略模式   | `OAuth2LoginHandler` 接口 + 微信/QQ 实现，支持扩展第三方登录 |
| 模板方法   | `AuthService` 编排认证流程，子流程可替换                     |
| 单例模式   | `JwtTokenProvider`、`SessionManager` 等 Bean 单例            |
| 工厂方法   | `Result.success()/error()`、`OAuth2LoginHandlerFactory`      |
| 观察者模式 | 注销事件 MQ 广播，多服务响应清理                             |
| 装饰器模式 | common-security 的 `XssHttpServletRequestWrapper`            |

### 7.2 架构原则

| 原则       | 实践                                                         |
| ---------- | ------------------------------------------------------------ |
| 单一职责   | auth 管认证，user 管资料，职责清晰                           |
| 依赖倒置   | common-redis 与 common-security 通过 `UserIdResolver` 解耦   |
| Fail-Fast  | `@PostConstruct` 启动时加载配置，错误立即暴露                |
| 声明式编程 | `@AuditLog`、`@Sensitive`、`@Anonymous` 注解驱动             |
| 配置分层   | 公共库提供默认值，业务服务配置实际值，Nacos 动态下发         |
| 防御性编程 | 白名单多来源合并（注解 + yml），手机号联合唯一索引防注销占位 |

### 7.3 企业规范实践

| 规范       | 实践                                                      |
| ---------- | --------------------------------------------------------- |
| 数据库设计 | 删除状态唯一真相源（sys_user.del_flag），避免双写不一致   |
| 安全合规   | 密码 BCrypt、手机号加密、敏感数据脱敏、操作审计           |
| 性能优化   | 角色缓存、计数 Redis 异步落库、JWT 无状态签发             |
| 可扩展性   | OAuth2 策略模式支持新增第三方、app_id 预留多应用扩展      |
| 容错性     | Token 黑名单 TTL 自动过期、登录失败锁定、冷却期防恶意注册 |

```

```

---

## 文档说明

我已经基于以下资料生成了完整的架构文档：

**资料来源**：

- [Design-Document.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/Design-Document.md) §2.3.1 / §2.3.2 的模块设计
- [Function-Document.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/Function-Document.md) §5 / §6 的功能清单
- [Sql-Document.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/Sql-Document.md) §一 的数据库设计
- [commom-des.md](file:///e:/NewJavaPro/LitIsle/BaseDesign-document/commom-des.md) 的公共基建能力
- 实际代码中已实现的实体类（SysUser/SysRole/SysUserProfile/SysOAuthBinding/SysUserRole）和枚举（UserStatus/AvatarColor/RoleType）

**文档结构**（共七章）：

1. **文档概述** — 目的、依据、架构位置图、两模块职责对比
2. **litisle-auth 认证模块** — 定位、功能清单、包结构、核心组件、数据模型、配置管理、6 大关键流程（注册/登录/Token 刷新/退出/在线设备管理/登录失败锁定）、安全策略、模块交互
3. **litisle-user 用户模块** — 定位、功能清单、包结构、核心组件、数据模型（含 ER 图）、5 大关键流程（角色叠加/VIP/头像标识/注销冷却期/关注计数）、安全策略、模块交互
4. **两模块协同关系** — 数据库共享、Feign 调用链、安全上下文透传、注销协同
5. **当前实现状态** — 已实现/待实现的清单（含代码链接）
6. **后续开发注意事项** — 开发优先级、跨模块协同、风险点
7. **架构亮点总结** — 设计模式、架构原则、企业规范

**关键设计点已突出说明**：

- auth 与 user 共享 `litisleuser` 库的边界划分
- `sys_user.del_flag` 作为删除状态唯一真相源的设计
- `uk_phone_del(phone, del_flag)` 联合唯一索引解决注销占位
- OAuth 表物理删除 + `@AuditLog` 审计的设计
- JWT 双 Token + Redis 黑名单/会话列表的有状态增强
- 角色互斥规则表（LEGACY_AUTHOR 与 AUTHOR 互斥）

文档已通过 Code Reference 链接了所有已实现的实体和枚举，方便你后续开发时快速跳转。请将代码块内容保存为 `e:\NewJavaPro\LitIsle\BaseDesign-document\Auth-User-Architecture.md`。

