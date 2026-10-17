# litisle-user 用户模块架构文档

> 版本：v1.1（2026-10-17 同步 DFA 同步审核链路：改名/笔名/简介类字段接入审核闸门）
>
> v1.0（2026-10-15 按当前实现对齐）
>
> 关联文档：[user-index.md](../index/user-index.md)（模块速查表）· [File-Architecture.md](File-Architecture.md)（头像引用登记链路）· Api-Architecture.md（UserClient 契约）
>
> 定位：**独立微服务**（端口 8081），承载用户档案 + RBAC 权限 + 管理端治理 + 敏感信息脱敏回显

------

## 一、模块定位与边界

| 项目     | 说明 |
| -------- | --- |
| 定位     | **独立微服务**（端口 8081），`sys_user` 及 RBAC 的唯一真相源（SSOT） |
| 依赖方   | auth（注册/登录经 UserClient 创建与查询用户）、各业务服务（经 UserClient 取用户/角色/权限）；user 反向依赖 file（头像 FileClient 引用登记） |
| 职责边界 | 只管**用户本体 + 资料 + 角色权限 + 状态治理**；登录认证/会话在 auth，头像文件本体在 file |
| 不负责   | 登录认证、验证码下发（auth）、文件存储（file） |

### 1.1 服务协作

```
auth  ──Feign(UserClient)──> user：注册 createUser / 登录 findByAccount / 认证信息 getAuthUserInfo
user  ──Feign(FileClient)──> file：头像引用登记 bindRef（Seata AT 全局事务，updateAvatar 发起方）
review ──词库──> user：DFA 词库经 Redis 快照装载 + review:wordlib:change Pub/Sub 热更广播（词库 SSOT 在 litisle_review.sensitive_word_library）
gateway ──路由──> user：/api/user/** /api/admin/users /api/sensitive
```

### 1.2 代码结构

```
litisle-user
├── config/           # AuditMetaObjectHandler（字段自动填充）/ SaTokenWebConfig
├── constant/         # UserInfoConstant（默认封禁7天/系统操作人）/ UserLimitConstant（限流阈值）
├── controller/       # 资料域 UserProfile / 搜索 UserSearch / 角色 UserRole / 敏感域 UserSensitive / 管理域 UserAdmin
│                     #   AccountCancel / Follow / Vip 整体注释保留（二期预留）
│   └── feign/        # UserFeignProviderController（implements UserClient）
├── dfa/              # DFA 宿主装配（UserDfaConfig fail-fast 初始化 / RedisWordLibraryLoader 快照装载 /
│                     #   WordlibChangeSubscriber 热更订阅 / UsernameRejectRecordAdapter 拒绝取证落 moderation_record）
├── domain/           # entity（9 张 sys_*）/ vo（8 个）/ dto（6 个）
├── enums/            # AvatarColor / PermStatusEnum / PermTypeEnum / RoleType / UserBanAction / UserBanReason / UserCancelSource
├── mapper/           # 9 个 Mapper（UserProfile 走 XML：resources/mapper/UserProfileMapper.xml）
├── service/          # 10 个 Service + impl（见 §3.2）
├── task/             # UserUnbanTask（每日 03:00 自动解封）
└── utils/            # RoleUtils
```

------

## 二、功能设计

### 2.1 功能清单

| 功能 | 说明 | 核心机制 |
| --- | --- | --- |
| 用户资料 | 本人/他人资料查询与更新 | UserProfile 乐观锁（@Version）；pen_name ≤32 / creation_dir ≤100 / masterpiece ≤500；携带字段同步 DFA 审核：username/penName 走 USERNAME 场景（UsernameModerationGate SSOT，HIGH 拒绝+取证），bio/creationDir/masterpiece 走 BIO 场景（BioModerationGate，HIGH 拒绝不取证）；空串=清空语义短路免审 |
| 头像更新 | 两步流程：先传 file 后绑 fileId | updateAvatar `@GlobalTransactional`（Seata AT）：校验文件存在 + 本人所有 → 本地写库 + FileClient.bindRef 同全局事务 |
| 邮箱绑定/改绑 | 绑定与换绑 | HMAC 摘要列查重（EMAIL_ALREADY_BOUND/EMAIL_EXISTS/EMAIL_NOT_BOUND）；@RateLimiter(60s/5) 防撞库 |
| 用户搜索 | 按 uid 查用户 | UserSearchController，@SaCheckLogin |
| 敏感信息回显 | 手机号/邮箱脱敏查询 | 本人 `@Sensitive` 脱敏；查他人需 SENSITIVE_PHONE/EMAIL_READ 权限 |
| 管理端治理 | 分页/详情/状态变更/角色分配/注销 | @SaCheckRole(ADMIN) + @SaCheckPermission 细粒度；解封时间吸附每日 03:00 |
| 封禁/解封闭环 | changeStatus 记 BanLog | UserUnbanTask 每日 03:00 扫描到期封禁恢复 NORMAL + 记 UNBAN |
| 注销 | 用户软删除（自助/管理员） | 记 CancelLog（source=SELF/ADMIN）；del_flag=1 |
| 注册（Feign） | auth 注册链路调 user 落库 | username/phone_hash/email_hash 活跃记录查重 → 默认分配 READER 角色 |
| UID 生成 | 17 位随机对外 UID | uk_uid 唯一索引冲突重试兜底（generateUniqueUid） |

### 2.2 枚举定义

```
AvatarColor       // NONE(0)/GOLD(1)/WHITE(2)，@EnumValue+@JsonValue
PermStatusEnum    // DISABLED(0)/ENABLED(1)；PermTypeEnum：MENU(1)/BUTTON_API(2)
RoleType          // SYSTEM(1)/BUSINESS(2)
UserBanAction     // BAN/UNBAN；UserCancelSource：SELF/ADMIN
UserBanReason     // 1-9 十类封禁原因（违法违规/政治敏感/广告/刷屏/辱骂/欺诈/侵权/恶意注册/违规引流/其他99）
```

### 2.3 核心流程

**注册（auth 发起，user 落库）**：

```
1. username 同步 DFA 审核（auth 侧 UsernameModerationGate SSOT：注册/改名/笔名三入口共用）→ 唯一校验 → phone_hash / email_hash 活跃记录查重（del_flag=0，防注销用户阻塞同手机号/邮箱复注册）
2. AES 加密 phone/email + HMAC 摘要 → save sys_user → 分配 READER 角色
3. 返回 AuthUserDTO（含角色）
```

**头像更新（Seata AT 全局事务）**：

```
1. 校验 fileId 存在（FILE_NOT_FOUND）+ owner 为本人（NO_PERMISSION）→ 本地更新 sys_user.avatar_file_id
2. FileClient.bindRef 引用登记同全局事务（file 侧分支事务参与）
3. 登记失败 REMOTE_SERVICE_ERROR，全局回滚
```

**封禁/解封闭环**：

```
后台 changeStatus（frozenUntil 吸附 03:00 粒度）→ UserUnbanTask 每日 03:00 一次性扫描到期
→ 恢复 NORMAL + BanLog(UNBAN)
```

------

## 三、接口设计

### 3.1 REST 接口（返回 Result<T>）

| 前缀 | 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- | --- |
| /api/user/profile（类级 @SaCheckLogin） | GET | `/me` | 登录 | 本人资料（UserSelfVO） |
| | GET | `/{userId}` | 登录 | 他人公开资料（UserPublicVO） |
| | PUT | — | 登录 | 更新资料（UserProfileUpdateDTO + @Valid） |
| | POST | `/avatar` | 登录 | 更新头像（fileId 两步流程，@GlobalTransactional） |
| | POST/PUT | `/email` | 登录 + @RateLimiter(60s/5) | 绑定/改绑邮箱 |
| /api/user/roles | GET | — / `/me` | 登录 | 角色列表 / 我的角色 |
| | GET | `/users/{userId}` | @SaCheckRole | 查他人角色 |
| /api/user/search | GET | `/{uid}` | 登录 | 按 UID 查用户 |
| /api/sensitive（类级 @SaCheckLogin） | GET | `/me/phone` · `/me/email` | 登录 | 本人敏感信息（@Sensitive 脱敏） |
| | GET | `/users/{userId}/phone` · `/email` | SENSITIVE_PHONE/EMAIL_READ | 他人敏感信息（管理用途） |
| /api/admin/users（类级 @SaCheckRole(ADMIN)） | GET | `/page` · `/{userId}` | USER_READ | 分页 / 详情聚合（角色+VIP+扩展资料） |
| | PATCH | `/{userId}/status` | USER_BAN | 启用/禁用/冻结（frozenUntil） |
| | POST/DELETE | `/{userId}/roles` | ROLE_ASSIGN | 角色 增/删 |
| | DELETE | `/{userId}` | USER_DELETE | 注销（软删除） |

> 二期预留（整体注释保留）：AccountCancelController（注销申请/确认）、FollowController（关注/粉丝）、VipController（VIP 查询/激活）。

### 3.2 内部 Service（Feign 契约，Provider 端实现）

契约在 litisle-api（`UserClient` + FallbackFactory），user 侧由 `UserFeignProviderController` 实现（网关不路由，不加 @SaCheckLogin）：

```
UserDTO getUserById(Long userId);             // id 查用户（展示读）
AuthUserDTO createUser(RegisterUserDTO dto);  // 注册落库（查重 + 默认 READER 角色）
AuthUserDTO findByAccount(String account);    // username → phone_hash → email_hash 三路匹配
AuthUserDTO findByEmail(String email);        // 邮箱查询
AuthUserDTO getAuthUserInfo(Long userId);     // 角色+权限聚合；仅作者查扩展资料（笔名/创作方向/代表作）
```

**AuthUserDTO 聚合口径**：基础信息 + roles + permissions（批量 IN 查询防 N+1）；password（BCrypt）仅内网传递用于登录校验；phone/email 解密明文内网传输，最终响应由 VO 层 `@Sensitive` 脱敏兜底。

------

## 四、数据模型

### 4.1 sys_user（核心表）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint AUTO | 主键 |
| username | varchar UK | 登录名 |
| uid | varchar UK | 对外 17 位随机 UID（uk_uid 冲突重试） |
| password | varchar | BCrypt 哈希（auth 校验用，禁外传） |
| phone / email | varchar | AES-256-GCM 密文（随机 IV，不可比对） |
| phone_hash / email_hash | varchar | HMAC-SHA256 确定性摘要，查重/登录匹配入口（uk_phone_hash_del / uk_email_hash_del 含 del_flag 复合语义） |
| avatar_file_id | varchar | 头像文件（file 模块 fileId） |
| avatar_color | tinyint | 头像框（AvatarColor） |
| bio / vip_expire_time | — | 简介 / VIP 到期 |
| frozen_until | datetime | 冻结截止（封禁吸附 03:00） |
| cancel_time | datetime | 注销时间 |
| status | tinyint | UserStatus（common-core） |
| del_flag | tinyint | 软删标志 |
| version | int | 乐观锁 |

### 4.2 sys_user_profile（作者扩展资料）

`user_id`（关联 sys_user.id）/ `pen_name`（≤32，唯一性校验 PEN_NAME_ALREADY_EXISTS）/ `creation_dir`（≤100）/ `masterpiece`（≤500）/ `version`（乐观锁）；独立 XML Mapper 维护查询。

### 4.3 RBAC 四表

| 表 | 说明 |
| --- | --- |
| sys_role | 角色（role_code，RoleType 区分系统内置/业务） |
| sys_permission | 权限点（perm_code，PermTypeEnum：菜单/按钮API，PermStatusEnum 启停） |
| sys_user_role | 用户-角色关联 |
| sys_role_permission | 角色-权限关联 |

### 4.4 治理日志表

| 表 | 字段要点 |
| --- | --- |
| sys_user_ban_log | userId / username / roleCode / action(BAN\|UNBAN) / reason(UserBanReason) / frozen_until / operator* |
| sys_user_cancel_log | userId / username / roleCode / source(SELF\|ADMIN) / reason / operator* |
| sys_oauth_binding | 第三方 OAuth 绑定（预留） |

------

## 五、配置说明

配置体系与 file 模块一致：`application.yml`（主配置）+ `application-local.yaml` / `application-dev.yaml`（环境特定值）+ Nacos `litisle-user.yaml` / `litisle-shared.yaml`。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | 8081 | 服务端口 |
| `spring.config.import` | litisle-user.yaml + litisle-shared.yaml | Nacos 配置中心导入（Sa-Token 集中于 shared） |
| `litisle.nacos.*` | localhost:8848 | 注册/配置中心连接（环境特定值在 local/dev.yaml） |
| `litisle.db.*` | localhost:3306 | MySQL（utf8mb4 强制，connectionCollation） |
| `litisle.redis.*` | localhost:6380 db0 | 与 auth 共享实例（会话互通） |
| `litisle.security.crypto.key` | LITISLE_CRYPTO_KEY 环境变量优先 | AES-256 密钥（Base64 44 字符），本地回退默认值 |
| `litisle.security.crypto.bcrypt-cost` | 10 | BCrypt 强度因子（每 +1 计算量翻倍） |
| `user.unban-task.cron` | 0 0 3 * * ? | 自动解封任务（@Scheduled 占位符直读） |
| `spring.servlet.multipart.*` | — | multipart 上限（本服务无直传接口，预留） |
| Sentinel datasource | dataId=litisle-user-degrade-rules.json | 熔断规则 Nacos 持久化（DEFAULT_GROUP） |
| `management.endpoints...` | health,prometheus | Actuator 指标（application 标签区分服务） |
| `mybatis-plus...` | not_null / auto | 更新策略非空刷 + 主键自增 + MybatisEnumTypeHandler |
| `knife4j.*` | — | API 文档（扫描 controller 包） |

------

## 六、异常与错误码

错误码定义于 common-core `ErrorCode`，user 模块实际抛出：

| 错误码常量 | 触发场景 |
| --- | --- |
| USERNAME_ALREADY_EXISTS | 注册/资料更新：用户名已占用 |
| PHONE_EXISTS / DATA_EXISTS(邮箱已被注册) | 注册：phone_hash/email_hash 活跃查重命中 |
| EMAIL_ALREADY_BOUND / EMAIL_EXISTS / EMAIL_NOT_BOUND | 邮箱绑定/改绑三态 |
| PEN_NAME_ALREADY_EXISTS | 笔名占用 |
| SENSITIVE_WORD_DETECTED(70001) | 改名/笔名/简介类字段同步 DFA HIGH 命中拦截（身份字段 NLP/LLM 不接入，仅本地词库快筛） |
| USER_NOT_FOUND | 用户查无（Feign fail-fast） |
| USER_NOT_EXIST_OR_CANCELLED | 用户不存在或已注销（del_flag=1） |
| FILE_NOT_FOUND | 头像 fileId 在 file 侧不存在或已软删 |
| NO_PERMISSION | 使用他人头像文件等越权 |
| REMOTE_SERVICE_ERROR | FileClient.bindRef 头像引用登记失败（触发全局回滚） |
| FORBIDDEN / PARAM_ERROR | 管理端非法操作（如非 ADMIN 操作 ADMIN）与参数校验 |
| TOO_MANY_REQUESTS | @RateLimiter / Sentinel 触发 |

------

## 七、安全与可靠性

1. **敏感数据双轨加密**：手机号/邮箱 AES-256-GCM 加密存储（随机 IV 密文不可比对）→ 查重/登录匹配一律走 HMAC-SHA256 确定性摘要列（phone_hash/email_hash）；回显经 VO 层 `@Sensitive` 脱敏，Feign 内网明文传输由 VO 兑底
2. **权限模型**：网关 JWT → Sa-Token 会话（Redis 共享）；接口层 @SaCheckLogin / @SaCheckRole / @SaCheckPermission 三级校验；管理端 ADMIN + 细粒度权限点
3. **限流与熔断**：@RateLimiter(USER)（common-redis，Redis 故障降级本地兑底）+ Sentinel 熔断规则外置 Nacos 热更新；改绑邮箱 60s/5 防撞库
4. **分布式一致性**：updateAvatar 采用 Seata AT 全局事务（本地写库 + FileClient.bindRef 同事务，任一失败全局回滚）；引入后数据源自动代理，undo_log 需已建
5. **乐观锁**：sys_user / sys_user_profile 均 @Version，防资料并发覆盖
6. **软删语义**：del_flag 贯穿全部查询；phone_hash/email_hash 唯一索引含 del_flag 复合语义，注销后同手机号/邮箱可复注册
7. **日志留痕**：封禁/解封、注销全量落 ban_log / cancel_log（含操作人），可审计
8. **自动解封兜底**：封禁时间吸附 03:00 粒度 + 定时任务一次性扫描，避免逐用户到期触发
9. **N+1 防护**：角色/权限聚合采用批量 IN 查询（buildAuthUserDTO 三步 Map 映射）
10. **可观测性**：Actuator + Prometheus 指标端点（application 标签），日志含 traceId
11. **身份/简介字段同步审核**：DFA 门面（litisle-api SSOT）+ user 宿主装配（dfa/ 包）；启动 fail-fast（任一文本场景词库装载失败拒启，含 BIO）；词库热更经 Redis Pub/Sub 广播重建（version 乐观比对防乱序）；UsernameModerationGate 拒绝时经 UsernameRejectRecordAdapter 落 moderation_record 取证（biz_id=0，content_hash+reason 自包含）；BioModerationGate 空串清空语义短路、HIGH 拦截不取证