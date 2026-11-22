
# litisle-auth 认证模块架构文档

> 版本：v1.0（2026-10-15 按当前实现对齐）

>
> 关联文档：[auth-index.md](../index/auth-index.md)（模块速查表）· [User-Architecture.md](User-Architecture.md)（用户数据 SSOT）· [UserClient 契约](Api-Architecture.md)

>
> 定位：**独立微服务**（端口 8082），承载注册/登录认证 + Token 管理 + 验证码 + 设备会话 + 处罚联动消费 + 用户名 DFA 闸门

------

## 一、模块定位与边界

| 项目     | 说明                                                         |
| -------- | ------------------------------------------------------------ |
| 定位     | **独立微服务**（端口 8082），认证与会话的唯一入口            |
| 依赖方   | gateway（路由 /api/auth/**）；user（注册/登录经 UserClient 落库与三路账号匹配）；review（词库快照/拒绝取证） |
| 职责边界 | 只管**认证凭证 + 会话 + 验证码 + 处罚联动**；用户数据/角色在 user（SSOT），词库在 review |
| 存储     | **无独立数据库**：登录态/RefreshToken/设备会话/处罚快照全存 Redis（6380 db0，与 user 共享会话） |

> 代码结构与包坐标详见 [auth-index.md](../index/auth-index.md) §二；SmsController / OAuth2Controller 整体注释保留（二期预留，OAuth2 当前调用抛 SYSTEM_ERROR 暂未开放）。

------

## 二、功能设计

### 2.1 功能清单

| 功能 | 核心机制 |
| --- | --- |
| 注册 | A1 空内容校验 → A2 DFA 同步闸门 → A3 拒绝取证/放行（时序铁律，经 UserClient.createUser） |
| 密码登录 | findByAccount 三路 HMAC 匹配 → BCrypt → 状态检查 → Sa-Token 会话 + RefreshToken + 设备 Hash 登记 |
| 邮箱验证码登录 | `/login/sms`（历史命名）EmailCodeUsage.LOGIN 校验 → findByEmail → 会话 |
| Token 管理 | Sa-Token JWT（timeout 1d）；Refresh Token 存 Redis 7 天，三段判断轮换（墓碑防重放）、设备指纹绑定、令牌族可全族吊销 |
| 验证码 | 图形码 120×40/TTL 180s；邮箱码 6 位/TTL 300s 按用途前缀隔离；连续失败≤3 |
| 设备会话 | Redis Hash（设备/ip/登录/活跃时间），单设备踢下线（禁踢当前设备） |
| 处罚联动 | 消费 punish.result：封禁→全设备 forceLogout + 快照回写；单消费者串行防乱序 |
| DFA 闸门 | 词库快照 fail-fast 装载 + 广播热更（version 乐观比对）+ 拒绝取证与拦截结论解耦 |

### 2.2 常量与缓存键

- `RateLimitConstant`：窗口 10s 按 IP——注册/登录 10、图形码获取 10、邮箱码发送 5、校验 30
- `AuthCacheKey`：`auth:refresh:` / `auth:refresh:used:`（重放墓碑，TTL 与 Refresh 同周期）/ `auth:refresh:user:`（userId:deviceId 维度）/ `auth:device:` / `auth:user:devices:` / `auth:punish:snapshot:`（TTL 300s ±10% 抖动）
- `EmailCodeUsage`：reg / login / bind / reset / cancel——验证码键前缀按用途隔离
- `MailConstant`：邮件统一签名 + 免责声明

### 2.3 核心流程

**注册（A1→A2→A3 时序铁律）**：A1 空内容校验最先执行 → A2 DfaFacade 命中抛 SENSITIVE_WORD_DETECTED → A3 拒绝时 UsernameRejectRecordAdapter 落 review 取证行（不建 review_task、不带用户名明文，取证失败仅留痕不影响拦截结论）；放行走 createUser。

**密码登录**：三路匹配 → BCrypt → 状态检查（USER_LOGGED_OFF / USER_FROZEN 附解封时间）→ 会话 + RefreshToken + 设备登记。

**刷新令牌（三段判断 + 墓碑防重放 + 指纹比对，2026-11-19 落地）**：① 查墓碑（auth:refresh:used:{token}）命中 = 已轮换票被重放 = 盗用 → 审计告警 + 吊销该用户全部令牌族（复用 forceLogout 链路）+ 拒绝；② 查有效 key（auth:refresh:{token}，JSON 载荷 userId/familyId/deviceId/fingerprint）未命中 → 拒绝；③ 均通过 → 原子认领（DEL 唯一成功端签发，防并发双签发）→ 写墓碑（TTL 与 Refresh 同周期）→ 设备指纹比对（UA+Accept-Language SHA-256，不一致吊销该族 + 拒绝）→ 同族轮换签发（familyId/deviceId 沿用）。铁律：墓碑先查后写防并发窗口；吊销同时覆盖 Access 会话与 Refresh 正反向两条 key。

**令牌族（familyId）**：登录每设备签发独立 familyId（userId + deviceId 维度反向 key）；重放命中 / 封禁 / 强制下线按族吊销该用户全部 Refresh 与会话（误伤半径多端隔离，单端指纹异常仅吊销单端）；改密码等未来高危链路落地时复用 forceLogout 即获得全族吊销能力。

**处罚消费**：单消费者串行 + Jackson JSON（防毒丸）→ 封禁全设备踢下线 + 快照回写 → 重试指数退避（base 30s）耗尽入 DLQ 由双向对账兑底。

------

## 三、接口设计

### 3.1 REST（Result<T>，匿名按 IP 限流 10s 窗口）

| 方法 | 路径 | 权限 | 限流 | 说明 |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/register` | 匿名 | 10 | 注册（A1→A2→A3） |
| POST | `/api/auth/login` | 匿名 | 10 | 密码登录 |
| POST | `/api/auth/login/sms` | 匿名 | 10 | 邮箱验证码登录 |
| POST | `/api/auth/logout` | 登录 | — | 登出 |
| POST | `/api/auth/refresh` | 匿名 | — | 刷新 Token |
| GET | `/api/auth/me` | 登录 | — | 当前用户（@Sensitive 脱敏） |
| GET | `/api/auth/captcha/img` | 匿名 | 10 | 图形验证码 |
| POST | `/api/auth/captcha/email` | 匿名 | 5 | 发送邮箱验证码（QQ SMTP 465） |
| POST | `/api/auth/captcha/img/verify` · `/email/verify` | 匿名 | 30 | 验证码校验 |
| GET | `/api/auth/devices` | 登录 | — | 设备会话列表 |
| DELETE | `/api/auth/devices/{deviceId}` | 登录 | — | 踢出设备（禁踢当前） |

### 3.2 Feign 契约（AuthClient，Provider：AuthFeignProviderController，网关不路由）

- `forceLogout(userId)`：强制下线（user 处罚对账正向补踢复用）
- `verifyChangeEmail`：改绑邮箱验证码校验（user 链路回调）
- `getPunishAudit` / `refreshPunishSnapshot`：处罚审计与快照（fail-fast）
- `checkSpeak`：禁言校验（fail-fast，禁静默降级假放行）

> 处罚对账端点全部 fail-fast：静默降级会漏踢漏禁（宁严勿松）。

------

## 四、数据模型（无库，Redis 键空间）

| 键模式 | 值结构 | 说明 |
| --- | --- | --- |
| auth:refresh:{token} | JSON | Refresh Token 载荷 {userId, familyId, deviceId, fingerprint}（TTL 7d，三段判断轮换） |
| auth:refresh:used:{token} | String(userId) | 重放墓碑（TTL 与 Refresh 同周期；命中 = 已轮换票被重放 = 盗用） |
| auth:refresh:user:{userId}:{deviceId} | token | 用户+设备维度反向索引（多设备独立族；登出/按族吊销定位） |
| auth:device:{token} | Hash | 单会话设备信息（device/ip/loginTime/lastActiveTime） |
| auth:user:devices:{userId} | Hash/Set | 用户设备索引（全设备踢下线） |
| auth:punish:snapshot:{userId} | JSON | 处罚快照（TTL 300s ±10% 抖动） |
| 验证码键（EmailCodeUsage 前缀） | String | 邮箱验证码（TTL 300s）+ 失败计数（≤3） |

------

## 五、配置说明

| 配置项 | 说明 |
| --- | --- |
| `litisle.auth.refresh-token-expire=604800` | Refresh Token 7 天 |
| `litisle.captcha.*` | img-ttl=180 / email-ttl=300 / limit-ttl=60 / limit-max=5 / max-verify-fails=3 |
| `litisle.moderation.punish.retry.*` | max-count=5 / base-delay-ms=30000 / snapshot-ttl-seconds=300 |
| `sa-token.*` | JWT（timeout 1d / active-timeout 7d），集中于 Nacos litisle-shared.yaml |
| `litisle.security.crypto.*` | AES-256 key（LITISLE_CRYPTO_KEY 优先）+ bcrypt-cost=10 |
| `spring.mail.*` / `justauth.*` | QQ SMTP 465 SSL；OAuth2 三平台预留 |
| Sentinel / Actuator | 规则外置 Nacos；prometheus 指标带 application 标签 |

------

## 六、异常与错误码（common-core ErrorCode，已按实现核对）

| 常量 | 触发场景 |
| --- | --- |
| SENSITIVE_WORD_DETECTED | 注册用户名命中 DFA 闸门 |
| USER_NOT_FOUND | 账号三路匹配未命中 / findByEmail 查无 |
| USER_PASSWORD_ERROR | BCrypt 校验失败 |
| USER_LOGGED_OFF / USER_FROZEN | 登录态检查：已注销 / 冻结（附解封时间） |
| CAPTCHA_ERROR | 图形/邮箱验证码错误、过期、失败超限 |
| MAIL_SEND_ERROR | 邮件发送异常 |
| TOO_MANY_REQUESTS | @RateLimiter 触发 |
| TOKEN_INVALID | Refresh Token 无效/过期 |
| PARAM_ERROR / NOT_FOUND / FORBIDDEN | 入参校验 / 设备不存在 / 无权操作设备（禁踢当前设备） |
| REMOTE_SERVICE_ERROR | UserClient.createUser 失败（注册回滚） |
| SYSTEM_ERROR | OAuth2 二期预留暂未开放 |

------

## 七、安全与可靠性

1. **匿名防爆破**：按 IP 限流（@RateLimiter + IpUtil）覆盖注册/登录/验证码全链路；验证码连续失败计数封顶
2. **Token 双轨 + 防盗用纵深**：短效 JWT 网关校验 + Refresh Token 存 Redis 可吊销；刷新三段判断（墓碑防重放 + 原子认领防并发双签发）+ 设备指纹绑定 + familyId 令牌族全族吊销（重放/封禁全端下线，指纹异常限单端）
3. **处罚联动 fail-fast**：checkSpeak/forceLogout/快照刷新禁止静默降级；快照缺失保守处理宁严勿松
4. **单消费者串行**：处罚事件防乱序覆盖；显式 Jackson 转换器禁 Java 原生反序列化防毒丸
5. **DFA 词库 fail-fast**：空词库拒绝启动（防 default-allow 放行）；广播热更 version 乐观比对防乱序/重复
6. **注册时序铁律**：A1→A2→A3 固定顺序；拦截结论与取证解耦（宁严勿松）
7. **可观测性**：Actuator + Prometheus（application 标签）；Sentinel 熔断规则外置 Nacos 热更新落库与三路账号匹配）；review（词库快照/拒绝取证） |
| 职责边界 | 只管**认证凭证 + 会话 + 验证码 + 处罚联动**；用户数据/角色在 user（SSOT），词库数据在 review |
| 不负责   | 用户资料与 RBAC 管理（user）、词库增删改（review）、文件存储（file） |
| 存储     | **无独立数据库**：登录态/RefreshToken/设备会话/处罚快照全部存 Redis（localhost:6380 db0，与 user 共享会话） |

### 1.1 服务协作

```
gateway ──路由──> auth：/api/auth/**（匿名验证码/注册登录 + 登录态 /me /devices）
auth ──Feign(UserClient)──> user：createUser（注册落库）/ findByAccount（登录三路匹配）/ getAuthUserInfo（/me 聚合）
auth ──Feign(ReviewClient)──> review：词库快照装载 / 用户名拒绝取证（取证失败仅留痕不影响拦截结论）
auth <──MQ── review：punish.result 处罚事件消费（封禁全设备踢下线 + 快照回写）
```

### 1.2 代码结构

```
litisle-auth
├── config/           # AuthProperties（refresh-token-expire）/ CaptchaProperties / SaTokenWebConfig
├── constant/         # AuthCacheKey / CaptchaConstant / MailConstant（邮件签名+免责声明）/ RateLimitConstant
├── controller/       # AuthController（认证域）/ CaptchaController（验证码域）/ DeviceController（设备域）
│                     #   SmsController / OAuth2Controller 整体注释保留（二期预留）
│   └── feign/        # AuthFeignProviderController（implements AuthClient）
├── dfa/              # AuthDfaConfig（DfaFacade Bean）/ RedisWordLibraryLoader / WordlibChangeSubscriber / UsernameRejectRecordAdapter
├── domain/           # dto（7 个）/ vo（4 个）
├── enums/            # EmailCodeUsage（验证码用途前缀）/ EmailRedisKey（邮箱验证码键）
├── mq/               # PunishMqConfig / PunishConsumeConfig（单消费者串行+Jackson）/ PunishResultConsumer
├── service/          # AuthService / CaptchaService / DeviceSessionService / PunishAuditService / PunishSnapshotService
│                     #   + impl；oauth2/OAuth2LoginService（预留，当前抛 SYSTEM_ERROR 暂未开放）
└── utils/            # CaptchaCodeUtil / IpUtil
```

------

## 二、功能设计

### 2.1 功能清单

| 功能 | 说明 | 核心机制 |
| --- | --- | --- |
| 注册 | 用户名注册（user 落库） | A1 空内容校验 → A2 DFA 同步闸门 → A3 拒绝取证/放行（时序铁律，见 §2.3） |
| 密码登录 | 账号=用户名/手机/邮箱 | UserClient.findByAccount 三路 HMAC 匹配 → BCrypt 校验 → 状态检查 → Sa-Token 会话 |
| 邮箱验证码登录 | `/login/sms`（历史命名，实走邮箱） | EmailCodeUsage.LOGIN 验证码校验 → findByEmail → 会话 |
| Token 管理 | JWT 短令牌 + Refresh Token | Sa-Token JWT（timeout 1d）；Refresh Token 存 Redis 7 天可吊销，刷新轮换 |
| 验证码 | 图形 + 邮箱双通道 | 图形码 120×40/TTL 180s；邮箱码 6 位/TTL 300s，按用途前缀隔离；连续失败≤3 封顶 |
| 设备会话 | 多端登录管理 | Redis Hash（设备名/ip/登录时间/活跃时间），支持单设备踢下线（禁止踢当前设备） |
| 处罚联动 | 消费 punish.result | 封禁→全设备 forceLogout + 快照回写；单消费者串行防乱序覆盖 |
| 用户名 DFA 闸门 | 注册前置拦截 | 词库快照 fail-fast 装载 + 广播热更（version 乐观比对）+ 拒绝取证解耦 |
| OAuth2 / SMS | 二期预留 | JustAuth 三平台配置保留；当前调用抛 SYSTEM_ERROR「暂未开放」 |