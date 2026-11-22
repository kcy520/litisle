
# auth-index · litisle-auth 认证模块速查表

> 用途：写码/联调前查 auth 模块坐标，**免去逐个 Read**。

> 关联：`Auth-Architecture.md`（架构全貌）· `user-index.md`（用户数据 SSOT）· `Api-Architecture.md`（AuthClient/UserClient 契约）。

> 纪律：无独立数据库——用户数据在 user、词库快照在 review，auth 自身状态全部存 Redis。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 服务名 / 端口 | `litisle-auth` / `8082`（application.yml） |
| 定位 | 独立微服务：注册/登录认证 + Token 管理 + 验证码（图形/邮箱）+ 设备会话管理 + 处罚联动消费 + 用户名 DFA 闸门 |
| 库 | **无独立库**（无 DB 依赖）；登录态/RefreshToken/设备会话/处罚快照存 Redis（localhost:6380 db0，与 user 共享会话） |
| 关键依赖 | common-core / common-redis / common-security、litisle-api（UserClient/AuthClient/ReviewClient/DfaFacade）、Sa-Token（JWT）、spring-mail（QQ SMTP 465）、AMQP（处罚消费）、JustAuth（OAuth2 预留） |
| 不负责 | 用户资料/RBAC（user 的 SSOT）、词库管理（review） |

## 二、包结构（`com.litisle.litisleauth`）

```text
config/           AuthProperties（refresh-token-expire）/ CaptchaProperties / SaTokenWebConfig
constant/         AuthCacheKey（Redis 键） / CaptchaConstant / MailConstant（邮件签名+免责声明） / RateLimitConstant
controller/       AuthController（认证域）/ CaptchaController（验证码域）/ DeviceController（设备域）
                  SmsController / OAuth2Controller 整体注释保留（二期预留）
controller/feign/ AuthFeignProviderController（implements AuthClient）
dfa/              AuthDfaConfig（DfaFacade Bean+初始化+广播容器）/ RedisWordLibraryLoader / WordlibChangeSubscriber / UsernameRejectRecordAdapter
domain/dto/       RegisterRequestDTO / PasswordLoginDTO / EmailLoginDTO / RefreshTsuccessenDTO / VerifyImgCodeDTO / VerifyEmailCodeDTO / BindPhoneDTO
domain/vo/        TsuccessenVO / LoginUserInfoVO / DeviceSessionVO / CaptchaImgVO
enums/            EmailCodeUsage（验证码用途前缀）/ EmailRedisKey（邮箱验证码键）
mq/               PunishMqConfig / PunishConsumeConfig（单消费者串行容器工厂+Jackson）/ PunishResultConsumer
service/          AuthService / CaptchaService / DeviceSessionService / PunishAuditService / PunishSnapshotService + impl；oauth2/OAuth2LoginService（预留）
utils/            CaptchaCodeUtil / IpUtil

```

## 三、常量与缓存键

- `RateLimitConstant`：窗口 10s；注册/密码登录/邮箱登录 10 次、图形码获取 10 次、邮箱验证码发送 5 次、验证码校验 30 次（匿名接口按 IP 限流）

- `CaptchaConstant`：图形码 120×40/4 位/5 干扰线；邮箱验证码 6 位

- `AuthCacheKey`：`auth:refresh:`（JSON 载荷） / `auth:refresh:used:`（重放墓碑） / `auth:refresh:user:`（userId:deviceId 维度） / `auth:device:` / `auth:user:devices:` / `auth:punish:snapshot:`（快照 TTL 300s ±10% 抖动）

- `EmailCodeUsage`：`REGISTER("reg")` / `LOGIN("login")` / `CHANGE_EMAIL("bind")` / `RESET_PASSWORD("reset")` / `CANCEL_ACCOUNT("cancel")`——验证码 Redis 键前缀，按用途隔离

## 四、REST 接口（返回 `Result<T>`）

| 前缀 | 方法 | 路径 | 权限 | 限流(10s/IP) | 说明 |
|---|---|---|---|---|---|
| /api/auth | POST | `/register` | 匿名 | 10 | 注册（A1 空内容→A2 DFA 闸门→A3 取证/放行，经 UserClient.createUser） |
| | POST | `/login` | 匿名 | 10 | 密码登录（账号=用户名/手机/邮箱，user 侧 HMAC 匹配） |
| | POST | `/login/sms` | 匿名 | 10 | 邮箱验证码登录（EmailLoginDTO，历史命名） |
| | POST | `/logout` | @SaCheckLogin | — | 登出 |
| | POST | `/refresh` | 匿名 | — | 刷新 Token（refresh-token-expire=7d） |
| | GET | `/me` | @SaCheckLogin | — | 当前登录用户（手机/邮箱 @Sensitive 脱敏） |
| /api/auth/captcha | GET | `/img` | 匿名 | 10 | 获取图形验证码（CaptchaImgVO，120×40） |
| | POST | `/email` | 匿名 | 5 | 发送邮箱验证码（QQ SMTP 465，邮件含签名+免责声明） |
| | POST | `/img/verify` | 匿名 | 30 | 校验图形验证码 |
| | POST | `/email/verify` | 匿名 | 30 | 校验邮箱验证码（连续失败≤3 防爆破） |
| /api/auth/devices（@SaCheckLogin） | GET | — | 登录 | — | 设备会话列表（DeviceSessionVO） |
| | DELETE | `/{deviceId}` | 登录 | — | 踢出指定设备会话 |

## 五、Feign 提供端（`AuthFeignProviderController implements AuthClient`）

- ✅ `forceLogout`：强制下线（user 处罚对账正向补踢复用此契约）
- ✅ `verifyChangeEmail`：改绑邮箱验证码校验（user 改绑邮箱链路回调）
- ✅ `getPunishAudit`：处罚审计查询（fail-fast）
- ✅ `refreshPunishSnapshot`：处罚快照刷新（fail-fast）
- ✅ `checkSpeak`：禁言校验（fail-fast，禁止静默降级返回假放行）

## 六、Service 能力

- `AuthService`：register（A1 空内容校验→A2 DFA 同步闸门→A3 拒绝取证/放行，时序铁律）/ login / loginByEmail / logout（按族吊销）/ refresh（三段判断：墓碑防重放 + 原子认领 + 指纹比对）/ forceLogout（全族吊销）/ currentLoginUser
- `CaptchaService`：图形码生成/校验 + 邮箱验证码发送/校验（EmailCodeUsage 前缀按用途隔离）
- `DeviceSessionService`：设备会话 Hash（字段：userId/tokenValue/deviceName/ip/loginTime/lastActiveTime，见 AuthCacheKey.DeviceHashField）
- `PunishSnapshotService`：处罚快照读写（TTL 300s ±10% 抖动防雪崩）
- `PunishAuditService`：封禁/禁言处罚联动审计

## 七、DFA 用户名词库（词库 SSOT 在 review，auth 无本地词表）

- 启动装载：Redis 快照 `review:wordlib-snapshot:{scene}` → 快照不可用或为空抛 IllegalStateException 拒构建空引擎（default-allow 防线 fail-fast）
- 热更新：订阅 `review:wordlib:change` 广播 → `DfaFacade.rebuild`（内置 version 乐观比对，乱序/重复广播幂等忽略）
- 拒绝取证：`UsernameRejectRecordAdapter` → ReviewClient.insertUsernameRejectRecord（取证失败仅 error 留痕不影响拦截结论；不建 review_task、不携带用户名明文 PII）

## 八、MQ 消费（处罚联动）

- 队列：`QUEUE_AUTH_MODERATION_PUNISH`（拓扑由 PunishMqConfig 声明，与 review 侧参数对齐防 PRECONDITION_FAILED）
- 消费：`PunishResultConsumer` @RabbitListener（PunishConsumeConfig 单消费者串行 containerFactory + 显式 Jackson JSON 转换器，禁 Java 原生反序列化防毒丸）
- 重试：max-count=5 指数退避（base 30s → 30/60/120/240/480s），耗尽 nack 入 DLQ 由双向对账 sweeper 兑底

## 九、配置要点（application.yml）

- `litisle.captcha.*`：img-ttl=180 / email-ttl=300 / limit-ttl=60 / limit-max=5 / max-verify-fails=3
- `litisle.auth.refresh-token-expire=604800`（Refresh Token 7 天）
- `litisle.moderation.punish.retry.*`：max-count=5 / base-delay-ms=30000 / snapshot-ttl-seconds=300
- `sa-token.*`：JWT（Authorization + Bearer 前缀 / timeout 1d / active-timeout 7d / 多端并发不限）
- `litisle.security.crypto.*`：AES-256 key（LITISLE_CRYPTO_KEY 环境变量优先）+ bcrypt-cost=10
- `spring.mail.*`：QQ SMTP 465 SSL；`justauth.*`：OAuth2 三平台预留（WECHAT_OPEN/QQ/GITHUB）
- Nacos 双 DataId（litisle-auth.yaml + litisle-shared.yaml）/ Sentinel 规则外置 / Actuator(prometheus) 带 application 标签

## 十、安全闸口

- 匿名接口按 IP 限流（@RateLimiter + IpUtil），登录/注册防爆破；验证码校验连续失败计数封顶
- Token：Sa-Token JWT 网关校验；Refresh Token 存 Redis（JSON 载荷：familyId/deviceId/指纹）可吊销，三段判断轮换防重放（墓碑 + 原子认领），设备指纹绑定，令牌族全族吊销
- 处罚联动 fail-fast：checkSpeak/forceLogout/快照刷新禁止静默降级；快照缺失保守处理宁严勿松
- DFA 注册闸门：拦截结论与取证解耦（宁严勿松）

## 十一、开发导航（按需求找落点）

| 我要…… | 落点 |
|---|---|
| 改登录/注册逻辑 | `AuthServiceImpl`（注意 register A1→A2→A3 时序） |
| 改限流阈值 | `RateLimitConstant` |
| 改验证码 TTL/防刷参数 | `CaptchaProperties` + `CaptchaConstant` |
| 改 DFA 装载/热更 | `AuthDfaConfig` + `RedisWordLibraryLoader` + `WordlibChangeSubscriber` |
| 改处罚消费/重试策略 | `PunishResultConsumer` + `litisle.moderation.punish.retry.*` |
| 改 Redis 键 | `AuthCacheKey` |
| 改跨服务契约 | `litisle-api` 的 `AuthClient`（Provider：`AuthFeignProviderController`） |