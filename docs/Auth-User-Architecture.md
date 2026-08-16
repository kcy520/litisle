



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
- **状态化凭证**：JWT 本身无状态，但通过 Redis 维护会话列表与黑名单，实现"踢出设备""退出失效"等有状态能力。
- **多入口登录**：账密 + 手机验证码 + OAuth2（微信/QQ）三种方式统一归一化处理。

### 2.2 功能清单

| 功能              | 说明                                                         |
| ----------------- | ------------------------------------------------------------ |
| 账号密码注册/登录 | 用户名 + 密码（BCrypt 加密），图形验证码防刷                 |
| 邮箱验证码登录    | 邮箱 + 邮箱验证码，验证码有效期 5 分钟                       |
| OAuth2 第三方登录 | 微信 / QQ 扫码登录，绑定已有账号或直接注册新账号（暂不开发，跳过） |
| JWT 双 Token      | 短效 Token（30 分钟）+ 长效 Refresh Token（7 天），支持「记住我」 |
| 在线设备管理      | 同一账号最多 2 端在线，新设备登录自动踢出最旧设备            |
| 退出登录          | Token 加入 Redis 黑名单，清除 session                        |
| 风控策略          | 连续 5 次密码错误锁定 15 分钟                                |
| Token 刷新        | Refresh Token 换取新的 Access Token，旧 Refresh 失效         |
| 图形验证码        | 注册前下发图形验证码，Redis 缓存，防暴力破解（登录不需要，方便测试） |

### 2.3 代码架构（包结构）

```
litisle-auth
├── controller/        # AuthController / OAuth2Controller
│   ├── AuthController        # 注册/登录/退出/刷新/图形验证码
│   └── OAuth2Controller      # 微信/QQ OAuth2 登录入口
├── service/           # AuthService / SmsCodeService / CaptchaService
│   ├── AuthService           # 认证主流程（注册/登录/退出/刷新）
│   ├── SmsCodeService        # 短信验证码生成/校验/限流
│   └── CaptchaService        # 图形验证码生成/校验
├── domain/
│   ├── entity/        # （复用 user 库实体，无本服务独立实体）
│   ├── dto/           # LoginDTO / RefreshDTO / RegisterDTO / SmsLoginDTO
│   └── vo/            # TokenVO / CaptchaVO / LoginUserVO
├── enums/             # （本服务无私有枚举，使用 api 共享）
├── constant/          # AuthConstant（Token 有效期、黑名单 TTL、缓存 Key 前缀等）
├── security/          # JwtTokenProvider / SessionManager
│   ├── JwtTokenProvider      # JWT 签发/解析/校验
│   └── SessionManager       # 在线会话列表管理（踢出设备）
├── oauth/             # OAuth2LoginHandler（策略模式） / WechatHandler / QQHandler
│   ├── OAuth2LoginHandler    # 策略接口
│   ├── WechatHandler         # 微信扫码实现
│   ├── QQHandler             # QQ 扫码实现
│   └── OAuth2StateCache      # state 防 CSRF（Redis）
├── redis/             # TokenBlacklist / SessionCache / SmsCodeCache / CaptchaCache
│   ├── TokenBlacklist        # 退出 Token 黑名单（TTL=Token 剩余寿命）
│   ├── SessionCache          # 用户会话列表（Redis List）
│   ├── SmsCodeCache          # 短信验证码（Key=sms:code:{phone}:{scene}，TTL=5分钟）
│   ├── SmsCodeLimitCache     # 短信发送限流（60秒内不可重发，IP/手机号双维度）
│   ├── CaptchaCache          # 图形验证码（Key=captcha:{key}，TTL=2分钟，一次性）
│   └── LoginFailCounter     # 登录失败计数器（Redis）
└── config/            # AuthProperties（yml + JavaBean） / AuthConfig
    ├── AuthProperties       # @ConfigurationProperties 绑定
    └── AuthConfig           # Bean 装配（BCryptPasswordEncoder 等）
```



```

```

### 2.4 核心组件说明

| 组件                 | 类型       | 职责                                                         |
| -------------------- | ---------- | ------------------------------------------------------------ |
| `AuthController`     | Controller | 暴露注册/登录/退出/刷新/图形验证码接口                       |
| `OAuth2Controller`   | Controller | 暴露微信/QQ OAuth2 登录入口                                  |
| `AuthService`        | Service    | 认证主流程编排，协调 user Feign、JWT、Session、黑名单        |
| `SmsCodeService`     | Service    | 短信验证码生成、发送（对接短信网关）、校验、限流（基于 Redis，不落库） |
| `CaptchaService`     | Service    | 图形验证码生成（Base64）、Redis 缓存、一次性校验             |
| `JwtTokenProvider`   | Security   | JWT 签发（Access + Refresh）、解析 Claims、校验签名与过期    |
| `SessionManager`     | Security   | 基于 Redis List 维护用户在线设备列表，新登录踢出超额设备     |
| `OAuth2LoginHandler` | Strategy   | OAuth2 策略接口，统一定义微信/QQ 登录处理规范                |
| `WechatHandler`      | Strategy   | 微信扫码登录实现：换取 access_token → 获取 openId/userInfo   |
| `QQHandler`          | Strategy   | QQ 扫码登录实现                                              |
| `TokenBlacklist`     | Redis      | 退出时将 Token 加入黑名单，TTL = Token 剩余寿命，自动过期清理 |
| `SessionCache`       | Redis      | 用户会话列表存储（List 结构，按登录时间排序）                |
| `SmsCodeCache`       | Redis      | 短信验证码存储，Key=`sms:code:{phone}:{scene}`，TTL=5分钟，校验后立即删除 |
| `SmsCodeLimitCache`  | Redis      | 短信发送限流，60秒内同手机号/IP 不可重发，防刷                |
| `CaptchaCache`       | Redis      | 图形验证码存储，Key=`captcha:{key}`，TTL=2分钟，一次性使用    |
| `LoginFailCounter`   | Redis      | 登录失败次数计数，达到阈值后锁定账号                         |
| `AuthProperties`     | Config     | yml 配置绑定 JavaBean，集中管理 JWT/Session/Login/Anonymous 参数 |
| `AuthConfig`         | Config     | 装配 BCryptPasswordEncoder、JWT 解析器等 Bean                |

### 2.5 数据模型（复用 user 库 + Redis 临时态）

`litisle-auth` **无独立数据库、无独立表**，持久化数据复用 `litisleuser` 库，临时态数据走 Redis：

**持久化表（复用 user 库）**：

| 表名                | 来源          | 用途                                         |
| ------------------ | ------------- | -------------------------------------------- |
| `sys_user`         | user 模块管理 | 账号/密码/手机号/状态                        |
| `sys_oauth_binding`| user 模块管理 | OAuth2 绑定关系（auth 模块读写）             |

**Redis 临时态（不落库，TTL 自动过期）**：

| Key 模式                              | TTL     | 用途                                       |
| ------------------------------------- | ------- | ------------------------------------------ |
| `sms:code:{phone}:{scene}`            | 5 分钟  | 短信验证码（校验后立即 DEL）               |
| `sms:limit:{phone}` / `sms:limit:{ip}`| 60 秒   | 短信发送限流（防刷）                       |
| `captcha:{key}`                       | 2 分钟  | 图形验证码（一次性使用）                   |
| `session:{userId}` (List)             | 7 天    | 在线设备会话列表                           |
| `blacklist:token:{jti}`               | 剩余寿命 | 退出/踢出的 Token 黑名单                   |
| `login:fail:{username}`               | 15 分钟 | 登录失败计数                               |
| `login:lock:{username}`               | 15 分钟 | 账号锁定标志                               |

> **设计原因**：
> - auth 与 user 共享用户库，避免跨服务事务；
> - 验证码/会话/黑名单等临时态数据走 Redis，TTL 自动过期，无需定时清理任务；
> - 短信验证码不落库，避免写库压力与垃圾数据，如需审计可后续通过 MQ 异步入日志服务。

### 2.6 配置管理

#### 2.6.1 AuthProperties 配置项

使用 `@ConfigurationProperties(prefix = "litisle.security")` 将 yml 配置绑定到 JavaBean：

| 配置路径                                    | 字段                     | 默认值 | 说明                       |
| ------------------------------------------- | ------------------------ | ------ | -------------------------- |
| `litisle.security.jwt.secret`               | `jwt.secret`             | -      | JWT 签名密钥（≥256 bits）  |
| `litisle.security.jwt.access-token-expire`  | `jwt.accessTokenExpire`  | 1800   | Access Token 有效期（秒）  |
| `litisle.security.jwt.remember-me-expire`   | `jwt.rememberMeExpire`   | 604800 | 记住我模式 Token TTL（秒） |
| `litisle.security.jwt.refresh-token-expire` | `jwt.refreshTokenExpire` | 604800 | Refresh Token 有效期（秒） |
| `litisle.security.session.max-concurrent`   | `session.maxConcurrent`  | 2      | 同账号最大在线数           |
| `litisle.security.session.session-timeout`  | `session.sessionTimeout` | 604800 | 会话过期时间（秒）         |
| `litisle.security.login.max-fail-count`     | `login.maxFailCount`     | 5      | 连续失败阈值               |
| `litisle.security.login.lock-minutes`       | `login.lockMinutes`      | 15     | 锁定时长（分钟）           |
| `litisle.security.anonymous.paths`          | `anonymous.paths`        | []     | 匿名访问白名单（兜底）     |

#### 2.6.2 当前 yml 实现状态

[litisle-auth/src/main/resources/application.yml](file:///e:/NewJavaPro/LitIsle/litisle-auth/src/main/resources/application.yml) 当前已配置基础项（JWT secret、access-token-expire、remember-me-expire、session.max-concurrent、login.max-fail-count），其余项依赖 `AuthProperties` 字段默认值。

> **待办**：`AuthProperties` JavaBean 类尚未创建，开发 auth 模块时需先创建该类承载配置。

### 2.7 关键业务流程

#### 2.7.1 注册流程

```
客户端
  │ POST /auth/register（username + password + phone + captcha）
  ▼
AuthController
  │
  ▼
AuthService.register()
  ├─① CaptchaService.verify(captchaKey, captcha)   # 校验图形验证码
  ├─② DFA 校验用户名（敏感词过滤，复用 common-core）
  ├─③ UserFeign.queryByPhone(phone, delFlag=0)     # 检查手机号是否已注册
  │     └─ 存在 → 抛 USER_PHONE_EXISTS
  ├─④ 检查冷却期：查询 (phone, 1) 注销记录
  │     └─ cancel_time + 30天 > now() → 抛 USER_IN_COOLDOWN
  │     └─ 冷却期已过 → 删除旧注销记录
  ├─⑤ BCrypt 加密密码
  ├─⑥ UserFeign.createUser(SysUser)                # 落库 sys_user
  ├─⑦ 默认分配 READER 角色（UserFeign.bindDefaultRole）
  └─⑧ JwtTokenProvider.issueTokens(userId, roles)  # 签发双 Token
        └─ SessionManager.recordSession(userId, deviceId, token)
              └─ 超过 max-concurrent → 踢出最旧设备（加入黑名单）
  ▼
返回 TokenVO（accessToken + refreshToken + expiresIn）
```

#### 2.7.2 登录流程（三种方式归一化）

**方式一：账号密码登录**

```
POST /auth/login（username + password + captcha + rememberMe）
  ├─① CaptchaService.verify()
  ├─② LoginFailCounter.checkLocked(username)       # 检查是否锁定
  ├─③ UserFeign.queryByUsername(username)
  ├─④ BCrypt 校验密码
  │     └─ 失败 → LoginFailCounter.incr(username)
  │              └─ 达到 max-fail-count → 设置锁定 15 分钟
  ├─⑤ 状态校验：status != NORMAL → 抛 USER_FROZEN/USER_CANCELLED
  ├─⑥ UserFeign.queryRoles(userId)                 # 查角色列表
  └─⑦ 签发 Token（rememberMe=true → remember-me-expire，否则 access-token-expire）
```

**方式二：手机验证码登录**

```
POST /auth/sms-login（phone + smsCode）
  ├─① SmsCodeService.verify(phone, code, scene=LOGIN)
  │     └─ 失败/过期 → 抛 SMS_CODE_INVALID
  ├─② UserFeign.queryByPhone(phone, delFlag=0)
  │     └─ 不存在 → 抛 USER_NOT_FOUND（手机验证码登录不支持自动注册，需先注册）
  ├─③ 状态校验
  ├─④ 查角色
  └─⑤ 签发 Token
```

**方式三：OAuth2 登录（策略模式）**

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
```

#### 2.7.3 Token 刷新流程

```
POST /auth/refresh（refreshToken）
  ├─① JwtTokenProvider.parse(refreshToken)
  │     └─ 过期 → 抛 REFRESH_TOKEN_EXPIRED，前端跳登录
  ├─② TokenBlacklist.contains(refreshToken)        # 检查黑名单
  │     └─ 在黑名单 → 抛 TOKEN_INVALID
  ├─③ UserFeign.queryRoles(userId)                 # 重新查角色（可能变更）
  ├─④ 旧 refreshToken 加入黑名单（一次性使用）
  └─⑤ 签发新双 Token
```



#### 2.7.4 退出登录流程

```
POST /auth/logout（Authorization: Bearer xxx）
  ├─① JwtTokenProvider.parse(accessToken)
  ├─② TokenBlacklist.add(accessToken, ttl=剩余寿命)
  ├─③ TokenBlacklist.add(refreshToken, ttl=剩余寿命)
  └─④ SessionManager.removeSession(userId, deviceId)

```

#### 2.7.5 在线设备管理流程

```
SessionManager.recordSession(userId, deviceId, token)
  ├─① Redis List: session:{userId}  RPUSH {deviceId:token:loginTime}
  ├─② LLEN 检查长度
  ├─③ 超过 max-concurrent：
  │     └─ LPOP 取最旧会话
  │     └─ 该会话的 Token 加入黑名单（TTL=剩余寿命）
  │     └─ 发布踢出事件（可选：WebSocket 通知前端）
  └─④ 更新会话过期时间
```

#### 2.7.6 登录失败锁定流程

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
| Token 短效       | Access Token 30 分钟，降低泄露风险                      |
| Refresh 一次性   | 刷新即失效旧 Refresh Token，防止重放                    |
| 黑名单机制       | 退出/踢出的 Token 加入 Redis 黑名单，TTL=Token 剩余寿命 |
| OAuth2 防 CSRF   | state 参数随机生成 + Redis 缓存，校验后销毁             |
| 图形验证码       | 登录/注册强制，一次性使用，防止暴力破解                 |
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
| 注销账号       | 逻辑注销（`del_flag=1`），不可恢复，需二次确认，30 天冷却期  |
| 关注/粉丝计数  | Redis String 存储，异步落库                                  |
| 角色申请审核   | READER 申请 AUTHOR → review 审核 → 通过则绑定角色 + 清缓存   |
| 手机号明文查看 | 临时解密 + 审计日志（@AuditLog）                             |

### 3.3 代码架构（包结构）

```
litisle-user
├── controller/        # UserController / ProfileController
│   ├── UserController        # 用户简要信息查询、角色查询、注销
│   └── ProfileController     # 资料增改查、头像上传
├── service/           # UserService / RoleService / VipStateService
│   ├── UserService           # 账号增改查、注销、冷却期校验
│   ├── RoleService           # 角色绑定/解绑、叠加校验、角色缓存
│   └── VipStateService       # VIP 到期检查、冻结、到期提醒
├── mapper/            # SysUserMapper / SysRoleMapper / SysUserRoleMapper / SysUserProfileMapper / SysOAuthBindingMapper
├── domain/
│   ├── entity/        # SysUser / SysRole / SysUserRole / SysUserProfile / SysOAuthBinding（已实现）
│   ├── dto/           # UserUpdateDTO / RoleBindDTO / VipPurchaseDTO
│   └── vo/            # UserDetailVO / UserProfileVO / UserBriefVO / RoleVO
├── enums/             # UserStatus / AvatarColor / RoleType（已实现）
├── constant/          # UserBizConstant（VIP 时长、默认头像、冷却期天数等）
├── redis/             # RoleCache / VipCache / FollowCounter / FansCounter
└── config/            # UserConfig
```

### 3.4 核心组件说明

| 组件                | 类型       | 职责                                                |
| ------------------- | ---------- | --------------------------------------------------- |
| `UserController`    | Controller | 用户简要信息、角色查询、注销账号                    |
| `ProfileController` | Controller | 资料增改查、头像上传（对接 litisle-file）           |
| `UserService`       | Service    | 账号增改查、注销流程、冷却期校验、手机号加密/解密   |
| `RoleService`       | Service    | 角色绑定/解绑、叠加规则校验、角色缓存维护           |
| `VipStateService`   | Service    | VIP 到期检查（定时任务）、到期前 7 天提醒、到期冻结 |
| `RoleCache`         | Redis      | 用户角色列表缓存（Set 结构），角色变更时清除        |
| `VipCache`          | Redis      | VIP 状态缓存，避免频繁查库                          |
| `FollowCounter`     | Redis      | 关注计数（String incr/decr），异步落库              |
| `FansCounter`       | Redis      | 粉丝计数（String incr/decr），异步落库              |
| `UserConfig`        | Config     | 装配 Bean（密码加密器、手机号加密密钥等）           |

### 3.5 数据模型

#### 3.5.1 表清单

| 表名                | 已实现实体        | 说明                                     |
| ------------------- | ----------------- | ---------------------------------------- |
| `sys_user`          | ✅ SysUser         | 用户主表（账号/密码/手机号/状态）        |
| `sys_role`          | ✅ SysRole         | 角色表（6 角色初始化数据）               |
| `sys_user_role`     | ✅ SysUserRole     | 用户-角色关联表（多对多）                |
| `sys_user_profile`  | ✅ SysUserProfile  | 用户资料扩展表（笔名/创作方向/头像标识） |
| `sys_oauth_binding` | ✅ SysOAuthBinding | OAuth 绑定表（物理删除 + 审计）          |

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
│ avatar_color     │
│ version          │
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

**SysUser**（[查看代码](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/SysUser.java)）：

- 字段：id / username / password / phone / avatar / bio / vipExpireTime / frozenUntil / cancelTime / status / delFlag / version / createTime / updateTime
- `status` 用 `UserStatus` 枚举（DISABLED/NORMAL/CANCELLED），`@EnumValue` 映射
- `version` 用 `@Version` 乐观锁
- 校验注解：用户名/密码/手机号格式（jakarta.validation）

**SysUserProfile**（[查看代码](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/SysUserProfile.java)）：

- 字段：id / userId / penName / creationDir / masterpiece / avatarColor / version / createTime / updateTime
- `avatarColor` 用 `AvatarColor` 枚举（NONE/GOLD/WHITE），`@EnumValue` 映射
- **不设 del_flag**（删除状态以主表为准）

**SysOAuthBinding**（[查看代码](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/SysOAuthBinding.java)）：

- 字段：id / userId / provider / appId / openId / unionId / createTime / updateTime
- **物理删除**（解绑直接 DELETE），由 `@AuditLog` 记录审计

**SysRole**（[查看代码](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/SysRole.java)）：

- 字段：id / roleCode / roleName / roleType / createTime
- `roleType` 用 `RoleType` 枚举（SYSTEM/BUSINESS）

**SysUserRole**（[查看代码](file:///e:/NewJavaPro/LitIsle/lit-isle-user/src/main/java/com/litisle/litisleuser/domain/entity/SysUserRole.java)）：

- 复合主键（userId + roleId），无独立 id

#### 3.5.4 已实现的枚举

| 枚举          | 位置                                                         | 值                                     |
| ------------- | ------------------------------------------------------------ | -------------------------------------- |
| `UserStatus`  | [enums/UserStatus.java](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/enums/UserStatus.java) | DISABLED(0) / NORMAL(1) / CANCELLED(2) |
| `AvatarColor` | [enums/AvatarColor.java](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/enums/AvatarColor.java) | NONE(0) / GOLD(1) / WHITE(2)           |
| `RoleType`    | [enums/RoleType.java](file:///e:/NewJavaPro/LitIsle/litisle-user/src/main/java/com/litisle/litisleuser/enums/RoleType.java) | SYSTEM(1) / BUSINESS(2)                |

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
| `litisle-auth`    | Feign 被调 | 提供用户查询/创建、角色查询、OAuth 绑定                 |
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
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│           Redis（临时态，不落库）        │
│─────────────────────────────────────────│
│  sms:code:*        ← auth 独占（验证码）  │
│  captcha:*         ← auth 独占（图形码）  │
│  session:*         ← auth 独占（会话列表）│
│  blacklist:token:* ← auth 独占（黑名单）  │
│  login:fail/lock:* ← auth 独占（锁定）    │
└─────────────────────────────────────────┘
```

**共享规则**：
- 持久化表的"主管理者"负责实体定义与 DDL 维护
- auth 模块复用 user 的实体类（通过 litisle-api 共享或直接依赖）
- 短信验证码 / 图形验证码 / 会话 / 黑名单 / 登录失败计数均走 Redis，不落库，由 auth 独占管理
- 如需对短信发送记录做审计/运营商对账，后续通过 MQ 异步入日志服务，不影响主流程

### 4.2 Feign 调用链

````
litisle-auth                              litisle-user
    │                                          │
    │  UserFeignApi.queryByUsername(username)  │
    │─────────────────────────────────────────▶│ UserService.queryByUsername
    │                                          │
    │  UserFeignApi.queryByPhone(phone, del)   │
    │─────────────────────────────────────────▶│ UserService.queryByPhone
    │                                          │
    │  UserFeignApi.createUser(SysUser)        │
    │─────────────────────────────────────────▶│ UserService.create
    │                                          │
    │  UserFeignApi.queryRoles(userId)         │
    │─────────────────────────────────────────▶│ RoleService.queryByUserId
    │                                          │
    │  UserFeignApi.bindOAuth(binding)         │
    │─────────────────────────────────────────▶│ OAuthBindingService.bind
    │                                          │
    │  UserFeignApi.checkCooldown(phone)       │
    │─────────────────────────────────────────▶│ UserService.checkCooldown
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

