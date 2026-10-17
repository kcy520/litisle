# user-index · litisle-user 用户模块速查表

> 用途：写码/联调前查 user 模块坐标，**免去逐个 Read**。
> 关联：`Api-Architecture.md`（UserClient 契约）· `file-index.md`（头像上传/引用登记链路）。
> 纪律：手机号/邮箱为 AES 密文不可比对，查重/检索一律走 HMAC 摘要列（phone_hash / email_hash）。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 服务名 / 端口 | `litisle-user` / `8081`（application.yml） |
| 定位 | 独立微服务：用户档案 + RBAC 权限 + 管理端封禁/注销 + 敏感信息脱敏回显 |
| 库 | `sys_user` / `sys_user_profile` / `sys_user_role` / `sys_role` / `sys_permission` / `sys_role_permission` / `sys_oauth_binding` / `sys_user_ban_log` / `sys_user_cancel_log` |
| 关键依赖 | common-core / common-redis / common-security、litisle-api（契约）、MyBatis-Plus、Seata AT、Sentinel、Nacos、Knife4j |
| 不负责 | 登录认证（auth）、头像文件本体存储（file，经 FileClient 引用登记） |

## 二、包结构（`com.litisle.litisleuser`）

```text
config/           AuditMetaObjectHandler（填充器）/ SaTokenWebConfig
constant/         UserInfoConstant（默认封禁7天/系统操作人）/ UserLimitConstant（限流阈值）
controller/       UserProfileController（资料域）/ UserSearchController / UserRoleController / UserSensitiveController（敏感域）/ UserAdminController（管理域）
                  AccountCancelController / FollowController / VipController 整体注释保留（待二期）
controller/feign/ UserFeignProviderController（implements UserClient）
domain/dto/       AvatarUpdateDTO / UserProfileUpdateDTO / UserQueryDTO / UserStatusChangeDTO / BindEmailDTO / UpdateEmailDTO
domain/entity/    User / UserProfile / UserRole / Role / Permission / RolePermission / OAuthBinding / UserBanLog / UserCancelLog
domain/vo/        UserSelfVO / UserPublicVO / UserSimpleVO / UserRoleVO / UserUpdateProfileVO / UserAdminVO / UserAdminDetailVO / RoleVO
enums/            AvatarColor / PermStatusEnum / PermTypeEnum / RoleType / UserBanAction / UserBanReason / UserCancelSource
mapper/           九个 Mapper（UserProfile 走 XML：resources/mapper/UserProfileMapper.xml）
service/          十个 Service + impl（见 §六）
task/             UserUnbanTask（每日 03:00 自动解封）
utils/            RoleUtils
```

## 三、枚举（均 @EnumValue 入库）

- `AvatarColor`（+@JsonValue）：`NONE(0)` / `GOLD(1)` / `WHITE(2)`
- `PermStatusEnum`：`DISABLED(0)` / `ENABLED(1)`；`PermTypeEnum`：`MENU(1)` / `BUTTON_API(2)`
- `RoleType`：`SYSTEM(1)` / `BUSINESS(2)`
- `UserBanAction`：`BAN` / `UNBAN`；`UserCancelSource`：`SELF` / `ADMIN`
- `UserBanReason`：1-9 + 99(其他) 十类封禁原因

## 四、REST 接口（返回 `Result<T>`）

| 前缀 | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| /api/user/profile（@SaCheckLogin） | GET | `/me` | 登录 | 本人资料 |
| | GET | `/{userId}` | 登录 | 他人公开资料 |
| | PUT | — | 登录 | 更新资料 |
| | POST | `/avatar` | 登录 | 更新头像（fileId 两步流程，`@GlobalTransactional` 联动 file 引用登记） |
| | POST/PUT | `/email` | 登录+@RateLimiter(60s/5) | 绑定/改绑邮箱 |
| /api/user/roles | GET | — / `/me` / `/users/{userId}` | 登录 / 登录 / @SaCheckRole | 角色查询 |
| /api/user/search | GET | `/{uid}` | 登录 | 按 UID 查用户 |
| /api/sensitive（@SaCheckLogin） | GET | `/me/phone` · `/me/email` | 登录 | 本人敏感信息（@Sensitive 脱敏） |
| | GET | `/users/{userId}/phone` · `/email` | SENSITIVE_PHONE/EMAIL_READ | 他人敏感信息（管理用途） |
| /api/admin/users（@SaCheckRole(ADMIN)） | GET | `/page` · `/{userId}` | USER_READ | 分页 / 详情聚合 |
| | PATCH | `/{userId}/status` | USER_BAN | 启用/禁用/冻结（frozenUntil） |
| | POST/DELETE | `/{userId}/roles` | ROLE_ASSIGN | 角色 增/删 |
| | DELETE | `/{userId}` | USER_DELETE | 注销（软删除） |

## 五、Feign 提供端（`UserFeignProviderController implements UserClient`，网关不路由 /api/feign/**）

- ✅ `getUserById`：id 查用户（UserDTO）
- ✅ `createUser`：注册用户（用户名 + phone_hash/email_hash 唯一校验，默认分配 READER 角色）
- ✅ `findByAccount`：用户名 → 手机 → 邮箱 三路 HMAC 匹配，聚合角色+权限
- ✅ `findByEmail`：邮箱查询
- ✅ `getAuthUserInfo`：认证信息聚合（角色+权限；仅作者查扩展资料：笔名/创作方向/代表作）

## 六、Service 能力

- `UserService`：用户基础 CRUD / `getDtoById`
- `UserProfileService`：资料维护；`updateAvatar` 为 Seata AT 全局事务发起方（file 侧分支参与）
- `UserAdminService`：分页 / 详情 / 状态变更 / 角色分配 / 注销（联动 BanLog/CancelLog）
- RBAC 四件套：`RoleService` / `UserRoleService` / `RolePermissionService` / `PermissionService`
- `UserBanLogService` / `UserCancelLogService` / `OAuthBindingService`
- 敏感信息加解密在 Controller 层直接使用 `Aes256GcmCrypto` / `HmacSha256Crypto`（common-security）

## 七、数据模型

**sys_user**：`id`(AUTO) / `username`(UK) / `uid`(UK,17位随机) / `password`(BCrypt) / `phone`(AES密文) / `phone_hash`(HMAC,uk_phone_hash_del) / `email`(AES) / `email_hash`(uk_email_hash_del) / `avatar_file_id` / `avatar_color` / `bio` / `vip_expire_time` / `frozen_until` / `cancel_time` / `status` / `del_flag` / `version` / 时间戳

**sys_user_profile**：`user_id` / `pen_name`(≤32) / `creation_dir`(≤100) / `masterpiece`(≤500) / `version`(乐观锁)

**sys_user_role / sys_role / sys_permission / sys_role_permission**：RBAC 四表

**sys_user_ban_log**：userId / username / roleCode / action(BAN\|UNBAN) / reason / frozenUntil / operator*
**sys_user_cancel_log**：userId / username / roleCode / source(SELF\|ADMIN) / reason / operator*

判重键：phone_hash / email_hash 仅判活跃记录（del_flag=0），避免已注销用户阻塞同手机号/邮箱复注册。

## 八、定时任务（`UserUnbanTask`）

- 每日 03:00（cron 外置 `user.unban-task.cron`，占位符直读）：封号时解封时间吸附到 03:00 粒度，任务一次性扫描到期封禁 → 恢复 NORMAL + 记 UNBAN 日志。

## 九、配置要点（application.yml）

- Nacos 配置导入：`litisle-user.yaml` + `litisle-shared.yaml`（Sa-Token 集中于此）
- Redis：localhost:6380 db0（与 auth 共享会话）；MySQL utf8mb4 强制
- `litisle.security.crypto`：AES-256 key（`LITISLE_CRYPTO_KEY` 环境变量优先）+ `bcrypt-cost=10`
- Sentinel 数据源：dataId=`litisle-user-degrade-rules.json`（Nacos 持久化）
- Actuator：`health,prometheus`，指标加 `application` 标签
- MyBatis-Plus：`update-strategy=not_null` + `id-type=auto` + MybatisEnumTypeHandler

## 十、安全要点

- 手机号/邮箱 AES-256-GCM 加密存储；随机 IV 密文不可比对 → 查重/登录匹配走 HMAC-SHA256 摘要列
- 回显经 VO 层 `@Sensitive` 注解自动脱敏；Feign 内网明文传输，最终响应由 VO 层兜底
- UID 生成利用 `uk_uid` 唯一索引冲突重试兜底（`generateUniqueUid`）

## 十一、开发导航（按需求找落点）

| 我要…… | 落点 |
|---|---|
| 改头像更新链路 | `UserProfileServiceImpl.updateAvatar`（@GlobalTransactional + FileClient） |
| 改限流阈值 | `UserLimitConstant` |
| 改自动解封 | `UserUnbanTask` + `user.unban-task.cron` |
| 改 Feign 契约 | `litisle-api` 的 `UserClient`（Provider：`UserFeignProviderController`） |
| 改敏感信息脱敏 | `UserSensitiveController` + VO 层 `@Sensitive` |
| 加业务枚举 | `enums/`，按 `@EnumValue`(+@JsonValue) 口径 |
| 改 RBAC | `RoleService` / `UserRoleService` / `RolePermissionService` / `PermissionService` |