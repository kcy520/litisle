--
-- 一、litisle-user 模块（数据库：litisleuser）
-- 现状说明：现有 sys_role 仅初始化了 SUPER_ADMIN/ADMIN/USER 三个角色，与设计文档定义的 6 角色体系（READER/AUTHOR/LEGACY_AUTHOR/REVIEWER/ADMIN/SUPER_ADMIN）不完全匹配；sys_user 也缺 phone、bio、vip_expire_time 等字段。下面用 ALTER 补齐 + 新增扩展表，不破坏现有结构。
--
-- # ============================================================
-- # 模块：litisle-user
-- # 数据库：litisleuser
-- # 说明：在现有 user-schema.sql 基础上补充角色与扩展资料
-- # ============================================================

CREATE DATABASE IF NOT EXISTS litisleuser
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE litisleuser;

# 1. sys_user 表字段补齐
alter table `sys_user`
    add column `phone`          varchar(20)  null comment '手机号（加密存储）' after `email`,
add column `bio`            varchar(200) null comment '个人简介' after `avatar`,
add column `vip_expire_time` datetime    null comment 'VIP 到期时间' after `bio`,
add column `frozen_until`   datetime     null comment '封禁截止时间（null=未封禁）' after `vip_expire_time`,
add column `cancel_time`    datetime     null comment '注销时间（用于手机号冷却期计算，null=未注销过）' after `frozen_until`,
add unique key `uk_phone_del` (`phone`, `del_flag`);

# 2. 补充角色数据（前台业务角色）
insert into `sys_role` (`role_code`, `role_name`, `role_type`)
values
('READER',        '读者',     2),
('AUTHOR',        '作家',     2),
('LEGACY_AUTHOR', '纪念作家', 2),
('REVIEWER',      '审核员',   2);

                  -- 补充系统内置角色（role_type = 1）
insert into `sys_role` (`role_code`, `role_name`, `role_type`) values
                                                                   ('SUPER_ADMIN', '超级管理员', 1),
                                                                   ('ADMIN',       '管理员',     1);

# 3. 用户资料扩展表（删除状态以主表 sys_user.del_flag 为准）
create table `sys_user_profile`
(
    `id`            bigint unsigned not null auto_increment primary key comment '主键ID',
    `user_id`       bigint unsigned not null unique comment '用户ID（关联 sys_user.id）',
    `pen_name`      varchar(32)     null comment '笔名（作家专用）',
    `creation_dir`  varchar(100)    null comment '创作方向（作家申请时填写）',
    `masterpiece`   varchar(500)    null comment '代表作简介',
    `avatar_color`  tinyint         not null default 0 comment '头像标识：0-无 1-金色（作家）2-白色（纪念作家）',
    `version`       int             not null default 0 comment '乐观锁版本号',
    `create_time`   datetime        not null default current_timestamp comment '创建时间',
    `update_time`   datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
    index `idx_user_id` (`user_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '用户资料扩展表（删除状态以主表 sys_user.del_flag 为唯一真相源，本表不设 del_flag）';

# 4. 第三方 OAuth 绑定表（物理删除 + @AuditLog 审计 + app_id 预留）
create table `sys_oauth_binding`
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
-- - **审计**：所有删除操作通过 `litisle-common` 的 `@AuditLog` AOP 切面自动记录审计日志（操作人、操作类型、请求参数、执行时间），满足安全审计需求。
-- - **app_id 预留**：唯一索引 `(provider, app_id, open_id)` 预留 `app_id` 字段，默认值 `'default'`。当前单应用无影响，未来接入多个微信/QQ 应用时，同一 provider 下不同 app 的 openId 不会冲突。

#初始化语句
-- 角色用户示例数据（每个角色各一名，密码统一为 bcrypt 加密的 123）
-- 哈希：$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2
-- 注意：请根据实际 sys_user 表结构调整 id 值，确保与现有数据不冲突
# ============================================================
# 初始化示例用户数据（手机号明文，暂不加密，无邮箱）
# 密码统一为 bcrypt 加密的 123
# 哈希：$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2
# ============================================================

-- 1. READER 读者
INSERT INTO `sys_user` (`id`, `username`, `password`, `phone`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1001, 'reader_test', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010001', '热爱阅读的普通用户', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1001, `id` FROM `sys_role` WHERE `role_code` = 'READER';

INSERT INTO `sys_user_profile` (`user_id`, `avatar_color`)
VALUES (1001, 0);

-- 2. AUTHOR 作家
INSERT INTO `sys_user` (`id`, `username`, `password`, `phone`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1002, 'author_test', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010002', '专注科幻小说创作', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1002, `id` FROM `sys_role` WHERE `role_code` = 'AUTHOR';

INSERT INTO `sys_user_profile` (`user_id`, `pen_name`, `creation_dir`, `masterpiece`, `avatar_color`)
VALUES (1002, '红袖添香', '科幻 / 奇幻', '《星海归途》系列', 1);

-- 3. LEGACY_AUTHOR 纪念作家
INSERT INTO `sys_user` (`id`, `username`, `password`, `phone`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1003, 'legacy_test', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010003', '已故文学巨匠，作品永久珍藏', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1003, `id` FROM `sys_role` WHERE `role_code` = 'LEGACY_AUTHOR';

INSERT INTO `sys_user_profile` (`user_id`, `pen_name`, `creation_dir`, `masterpiece`, `avatar_color`)
VALUES (1003, '老舍', '现实主义文学', '《骆驼祥子》《茶馆》', 2);

-- 4. REVIEWER 审核员
INSERT INTO `sys_user` (`id`, `username`, `password`, `phone`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1004, 'reviewer_test', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010004', '内容安全审核专员', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1004, `id` FROM `sys_role` WHERE `role_code` = 'REVIEWER';

INSERT INTO `sys_user_profile` (`user_id`, `avatar_color`)
VALUES (1004, 0);

-- 5. ADMIN 管理员
INSERT INTO `sys_user` (`id`, `username`, `password`, `phone`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1005, 'admin_test', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010005', '负责平台日常运营管理', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1005, `id` FROM `sys_role` WHERE `role_code` = 'ADMIN';

INSERT INTO `sys_user_profile` (`user_id`, `avatar_color`)
VALUES (1005, 0);

-- 6. SUPER_ADMIN 超级管理员
INSERT INTO `sys_user` (`id`, `username`, `password`, `phone`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1006, 'super_admin_test', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010006', '系统最高权限拥有者', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1006, `id` FROM `sys_role` WHERE `role_code` = 'SUPER_ADMIN';

INSERT INTO `sys_user_profile` (`user_id`, `avatar_color`)
VALUES (1006, 0);