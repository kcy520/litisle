-- 一、litisle-user 模块（数据库：litisle_user）
-- 现状说明：现有 sys_role 仅初始化了 SUPER_ADMIN/ADMIN/USER 三个角色，与设计文档定义的 6 角色体系（READER/AUTHOR/LEGACY_AUTHOR/REVIEWER/ADMIN/SUPER_ADMIN）不完全匹配；sys_user 也缺 phone、bio、vip_expire_time 等字段。下面用 ALTER 补齐 + 新增扩展表，不破坏现有结构。
--
-- # ============================================================
-- # 模块：litisle-user
-- # 数据库：litisle_user
-- # 说明：在现有 user-schema.sql 基础上补充角色与扩展资料
-- # ============================================================


# ============================================================
# 模块：litisle-user
# 数据库：litisle_user
# 说明：用户 / 角色 / 用户资料扩展 / OAuth 绑定 / 短信验证码
# ============================================================

create database if not exists litisle_user
    character set utf8mb4
    collate utf8mb4_unicode_ci;

use litisle_user;

    /*seata 的 undolog表 */
CREATE TABLE IF NOT EXISTS `undo_log`
(
    `branch_id`     BIGINT       NOT NULL COMMENT 'branch transaction id',
    `xid`           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    `context`       VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
    `rollback_info` LONGBLOB     NOT NULL COMMENT 'rollback info',
    `log_status`    INT          NOT NULL COMMENT '0:normal status,1:defense status',
    `log_created`   DATETIME(6)  NOT NULL COMMENT 'create datetime',
    `log_modified`  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='AT transaction mode undo table';

/* ================= 1. 用户表 ================= */
create table if not exists `sys_user`
(
    `id`              bigint unsigned not null auto_increment primary key comment '用户ID',
    `username`        varchar(32)     not null comment '用户名',
    `uid`             varchar(16)     not null comment '对外UID，用户间搜索唯一标识',
    `password`        varchar(128)    not null comment '密码（BCrypt）',
    `email`           varchar(255)    null comment '邮箱（AES 加密存储，展示脱敏；长度对齐 Sql-Document §二 与存量库）',
    `phone`           varchar(255)     not null comment '手机号（AES 加密存储，展示脱敏）',
    -- 【2026-11-06 补列】依据：docs/Sql-Document.md 第二节 sys_user 定义（phone_hash/email_hash 段）
    -- 与 litisle-user User.java:63-71 字段映射、UserFeignProviderController#existByPhone 按 hash 查重用法。
    -- 原因：phone/email 为 AES 随机 IV 密文，同一号码每次加密结果不同，唯一约束必须建在确定性摘要列上。
    `phone_hash`      varchar(64)     not null comment '手机号 HMAC-SHA256 摘要（唯一约束与查重走本列；种子用 SHA2(phone,256) 占位）',
    `email_hash`      varchar(64)     null comment '邮箱 HMAC-SHA256 摘要（NULL 不参与唯一约束，允许多个无邮箱用户）',
    `avatar_file_id`  varchar(64)     null comment '头像文件ID（SeaweedFS fileId，URL 运行时反查拼接；默认头像走代码常量兜底，不落库）',
    `avatar_color`    tinyint         not null default 0 comment '头像标识：0-无 1-金 2-白', -- 【修改】
    `bio`             varchar(200)    null comment '个人简介',
    `vip_expire_time` datetime        null comment 'VIP 到期时间',
    `frozen_until`    datetime        null comment '封禁截止时间',
    `cancel_time`     datetime        null comment '注销时间',
    `status`          tinyint         not null default 1 comment '0-禁用 1-正常 2-注销',
    `del_flag`        tinyint         not null default 0 comment '删除标志',
    `version`         int             not null default 0 comment '乐观锁',
    `create_time`     datetime        not null default current_timestamp,
    `update_time`     datetime(3)     not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间（毫秒精度：供索引版本守卫识别同秒内二次编辑）',
    -- 【2026-11-06 调整】唯一约束改挂在确定性摘要列：AES 随机 IV 使 phone/email 列本身不可唯一比对
    unique key `uk_phone_hash_del` (`phone_hash`, `del_flag`),
    unique key `uk_email_hash_del` (`email_hash`, `del_flag`),
    unique key `uk_uid` (`uid`),
    index `idx_username` (`username`),
    index `idx_create_time` (`create_time`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '用户表';

# 2. 角色表
create table if not exists `sys_role`
(
    `id`          bigint unsigned not null auto_increment primary key comment '角色ID',
    `role_code`   varchar(32)     not null comment '角色编码',
    `role_name`   varchar(32)     not null comment '角色名称',
    `role_type`   tinyint         not null default 2 comment '角色类型：1-系统内置 2-业务角色',
    `create_time` datetime        not null default current_timestamp comment '创建时间',
    unique key `uk_role_code` (`role_code`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '角色表';

# 3. 用户-角色关联表
create table if not exists `sys_user_role`
(
    `id`      bigint unsigned not null auto_increment primary key comment '主键ID',
    `user_id` bigint unsigned not null comment '用户ID',
    `role_id` bigint unsigned not null comment '角色ID',
    unique key `uk_user_role` (`user_id`, `role_id`),
    index `idx_role_id` (`role_id`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '用户-角色关联表';

# 4. 角色初始化数据（已迁至 seed/user-seed.sql，保持 schema 文件为纯结构、可重复执行）

# 5. 权限表（菜单 + 按钮/API 统一管理，parent_id 自关联树形结构）
create table if not exists `sys_permission`
(
    `id`          bigint unsigned not null auto_increment primary key comment '权限ID',
    `parent_id`   bigint unsigned not null default 0 comment '父权限ID（0=根节点）',
    `perm_code`   varchar(64)     not null comment '权限编码（如 user:read / article:write）',
    `perm_name`   varchar(32)     not null comment '权限名称',
    `perm_type`   tinyint         not null comment '类型：1-菜单 2-按钮/API',
    `path`        varchar(128)    null comment '前端路由路径（菜单类型用）',
    `component`   varchar(128)    null comment '前端组件路径（菜单类型用）',
    `icon`        varchar(64)     null comment '图标',
    `sort`        int             not null default 0 comment '排序（同级内升序）',
    `status`      tinyint         not null default 1 comment '状态：0-禁用 1-正常',
    `create_time` datetime        not null default current_timestamp comment '创建时间',
    `update_time` datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
    unique key `uk_perm_code` (`perm_code`),
    index `idx_parent_id` (`parent_id`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '权限表（菜单与按钮/API 统一管理，parent_id 自关联形成树形结构）';

# 6. 角色-权限关联表
create table if not exists `sys_role_permission`
(
    `id`            bigint unsigned not null auto_increment primary key comment '主键ID',
    `role_id`       bigint unsigned not null comment '角色ID',
    `permission_id` bigint unsigned not null comment '权限ID',
    unique key `uk_role_permission` (`role_id`, `permission_id`),
    index `idx_permission_id` (`permission_id`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '角色-权限关联表';
create table if not exists `sys_user_profile`
(
    `id`           bigint unsigned not null auto_increment primary key,
    `user_id`      bigint unsigned not null unique comment '关联用户',
    `pen_name`     varchar(32)     null comment '【调整】笔名（作家专用）',
    `creation_dir` varchar(100)    null comment '创作方向',
    `masterpiece`  varchar(500)    null comment '代表作简介',
    `version`      int             not null default 0,
    `create_time`  datetime        not null default current_timestamp,
    `update_time`  datetime(3)     not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间（毫秒精度：供索引版本守卫识别同秒内二次编辑）',
    unique key `uk_pen_name` (`pen_name`), -- 【新增】唯一
    index `idx_user_id` (`user_id`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '用户资料扩展表（仅作家资料）';

# 8. 第三方 OAuth 绑定表（物理删除 + @AuditLog 审计 + app_id 预留）
create table if not exists `sys_oauth_binding`
(
    `id`          bigint unsigned not null auto_increment primary key comment '主键ID',
    `user_id`     bigint unsigned not null comment '用户ID',
    `provider`    varchar(16)     not null comment 'OAuth 提供方：WECHAT / QQ',
    `app_id`      varchar(32)     not null default 'default' comment '应用ID（预留多应用扩展，同一 provider 下区分不同 app）',
    `open_id`     varchar(64)     not null comment '第三方 openId',
    `union_id`    varchar(64)     null comment '第三方 unionId（可选）',
    `create_time` datetime        not null default current_timestamp comment '绑定时间',
    `update_time` datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
    unique key `uk_provider_app_open` (`provider`, `app_id`, `open_id`),
    index `idx_user_id` (`user_id`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '第三方账号绑定表（物理删除，解绑时直接 DELETE，由 @AuditLog 切面记录审计日志；唯一索引预留 app_id 支持多应用扩展）';

# 封号/解封审计日志表
create table if not exists `sys_user_ban_log`
(
    `id`            bigint unsigned not null auto_increment primary key comment '主键ID',
    `user_id`       bigint unsigned not null comment '被操作用户ID',
    `username`      varchar(32)     not null comment '被操作用户名（操作时快照）',
    `role_code`     varchar(255)    null comment '被操作用户角色快照（逗号分隔）',
    `action`        varchar(16)     not null comment '操作类型：BAN-封号 UNBAN-解封',
    `reason`        varchar(255)    null comment '封号原因（封号时必填；解封可为空）',
    `frozen_until`  datetime        null comment '解封时间（封号时记录；解封时为空）',
    `operator_id`   bigint unsigned not null comment '操作者ID（自动解封为0表示系统）',
    `operator_name` varchar(32)     null comment '操作者用户名（快照）',
    `create_time`   datetime        not null default current_timestamp comment '操作时间',
    index `idx_user_id` (`user_id`),
    index `idx_create_time` (`create_time`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '用户封号/解封审计日志表';

#注销账号日志表
create table if not exists `sys_user_cancel_log`
(
    `id`            bigint unsigned not null auto_increment primary key comment '主键ID',
    `user_id`       bigint unsigned not null comment '被注销用户ID',
    `username`      varchar(32)     not null comment '被注销用户名（操作时快照）',
    `role_code`     varchar(255)    null comment '被注销用户角色快照（逗号分隔）',
    `source`        varchar(16)     not null comment '注销来源：SELF-用户自助注销 ADMIN-管理员强制注销',
    `reason`        varchar(255)    null comment '注销原因（管理员强制注销时必填；自助注销可为空）',
    `operator_id`   bigint unsigned not null comment '操作者ID（自助注销为本人；管理员注销为操作管理员ID）',
    `operator_name` varchar(32)     null comment '操作者用户名（快照）',
    `create_time`   datetime        not null default current_timestamp comment '注销操作时间',
    index `idx_user_id` (`user_id`),
    index `idx_create_time` (`create_time`)
    ) engine = InnoDB default charset = utf8mb4 comment = '用户注销审计日志表';


-- **设计原因**：
--
-- - `sys_user_profile` 与 `sys_user` 拆分：主表保留高频鉴权字段，扩展资料单独表，避免列过多影响查询效率。
-- - `avatar_color` 用枚举而非字符串：0/1/2 三态对应无标识/金色/白色，与设计文档视觉区分一致，查询性能好。
-- - OAuth 绑定表对 `(provider, open_id)` 建唯一索引：保证一个第三方账号只能绑定一个文屿账号。
-- - `phone` 加密存储：符合设计文档"隐私脱敏"规范，明文查看需临时解密 + 审计。
--
-- **手机号联合唯一索引 `uk_phone_del(phone, del_flag)` 设计说明**：
--
-- - **问题**：若 phone 建单列唯一索引，用户注销后（`del_flag=1`）手机号仍占位，导致同一手机号无法重新注册。
-- - **方案**：联合唯一索引 `(phone, del_flag)`，活跃用户 `(phone, 0)` 与已注销用户 `(phone, 1)` 是不同的组合键，互不冲突。
-- - **NULL 语义说明**：phone 允许为空（`NULL`），MySQL 中 NULL 不参与唯一判断，因此多个 `(NULL, 0)` 不会冲突，这是期望行为——允许没有手机号的活跃用户共存。索引仅对非空 phone 生效。
-- - **冷却期**：通过 `cancel_time` 字段实现。注销时写入当前时间，重新注册时业务层判断 `cancel_time + 冷却期（如30天） < now()` 是否满足。
-- - **重复注销处理**：若同一手机号多次注销，业务层在重新注册前先清理旧的 `(phone, 1)` 记录再插入新行，保证唯一性。
-- - **注册校验流程**：
--   1. 查 `(phone, 0)` 存在 → 拒绝（手机号已被活跃用户使用）
--   2. 查 `(phone, 1)` 存在且 `cancel_time + 冷却期 > now()` → 拒绝（冷却期内）
--   3. 查 `(phone, 1)` 存在且冷却期已过 → 删除旧注销记录 → 允许注册
--   4. 无记录 → 直接允许注册
--
-- **删除状态统一说明（避免双写不一致）**：
--
-- - `sys_user` 的 `del_flag` 是用户删除状态的**唯一真相源**。
-- - `sys_user_profile` 不设 `del_flag`，查询时 JOIN 主表判断删除状态，避免双写导致的不一致。
-- - 注销时业务层在同一事务内：主表 `del_flag=1` + 扩展表保留（不删除，便于审计追溯）。
--
-- **OAuth 表设计说明**：
--
-- - **物理删除**：解绑操作直接 `DELETE FROM`，不留软删除痕迹，避免 openId 被"占位"导致无法重新绑定。
-- - **审计**：所有删除操作通过 `litisle_common` 的 `@AuditLog` AOP 切面自动记录审计日志（操作人、操作类型、请求参数、执行时间），满足安全审计需求。
-- - **app_id 预留**：唯一索引 `(provider, app_id, open_id)` 预留 `app_id` 字段，默认值 `'default'`。当前单应用无影响，未来接入多个微信/QQ 应用时，同一 provider 下不同 app 的 openId 不会冲突。

#初始化语句
-- 角色用户示例数据（每个角色各一名，密码统一为 bcrypt 加密的 123）
-- 哈希：$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2
-- 注意：请根据实际 sys_user 表结构调整 id 值，确保与现有数据不冲突
# ============================================================
# 初始化示例用户数据（已迁至 seed/user-seed.sql）
#
# 迁移原因：原 schema 内使用普通 INSERT，重复执行会因主键重复报错，
# 不满足「schema 可重复执行」要求；现统一由 seed/user-seed.sql 以固定 ID + INSERT IGNORE 写入。
# 1001~1006 账号（读者/作家/纪念作家/审核员/管理员/超管）与 2001~2003 演示用户在同一个种子文件内。
# ============================================================