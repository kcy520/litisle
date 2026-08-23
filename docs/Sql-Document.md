SQL
我先分析一下你现有 user-schema.sql 的风格特征，再据此风格给出各模块建表语句。

修改！
我这个项目只有一个数据库，不需要分库！并且需要修改数据库的库名！不是ms-demo！！！）

现有 SQL 风格归纳
维度	风格
库名	Litisle_{模块} 下划线分隔
字符集	utf8mb4 + utf8mb4_unicode_ci
表名	反引号包裹，业务表 sys_xxx / 关联表 sys_xxx_yyy
主键	id bigint unsigned not null auto_increment primary key comment 'xxx'
通用字段	status / del_flag / version / create_time / update_time（每字段均带 comment）
索引	index idx_xxx (field) 单列索引为主
引擎	engine = InnoDB default charset = utf8mb4 comment = 'xxx'
关联表	联合主键 + 双向索引，不加通用字段
注释	用 # 写分节注释
角色编码	全大写下划线 SUPER_ADMIN
关于 4.2 中间件落地矩阵：已按你要求仅保留 SeaweedFS，其余（Sentinel Dashboard / Zipkin / Prometheus / SkyWalking）从矩阵移除，后续不再展开。

## 一、litisle-user 模块（数据库：litisleuser）

```sql
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


use litisleuser;
# ============================================================
# 模块：litisle-user
# 数据库：litisleuser
# 说明：用户 / 角色 / 用户资料扩展 / OAuth 绑定 / 短信验证码
# ============================================================

create database if not exists litisleuser
    character set utf8mb4
    collate utf8mb4_unicode_ci;

use litisleuser;

/* ================= 1. 用户表 ================= */
create table if not exists `sys_user`
(
    `id`              bigint unsigned not null auto_increment primary key comment '用户ID',
    `username`        varchar(32)     not null comment '用户名',
    `password`        varchar(128)    not null comment '密码（BCrypt）',
    `email`           varchar(64)     null comment '邮箱',
    `phone`           varchar(20)     not null comment '手机号',
    `avatar`          varchar(255)    not null default 'default-avatar-url' comment '头像URL',
    `avatar_color`    tinyint         not null default 0 comment '头像标识：0-无 1-金 2-白', -- 【修改】
    `bio`             varchar(200)    null comment '个人简介',
    `vip_expire_time` datetime        null comment 'VIP 到期时间',
    `frozen_until`    datetime        null comment '封禁截止时间',
    `cancel_time`     datetime        null comment '注销时间',
    `status`          tinyint         not null default 1 comment '0-禁用 1-正常 2-注销',
    `del_flag`        tinyint         not null default 0 comment '删除标志',
    `version`         int             not null default 0 comment '乐观锁',
    `create_time`     datetime        not null default current_timestamp,
    `update_time`     datetime        not null default current_timestamp on update current_timestamp,
    unique key `uk_phone_del` (`phone`, `del_flag`),
    unique key `uk_email_del` (`email`, `del_flag`),
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

# 4. 角色初始化数据
insert ignore into `sys_role` (`role_code`, `role_name`, `role_type`) values
                                                                          ('SUPER_ADMIN',   '超级管理员', 1),
                                                                          ('ADMIN',         '管理员',     1),
                                                                          ('READER',        '读者',       2),
                                                                          ('AUTHOR',        '作家',       2),
                                                                          ('LEGACY_AUTHOR', '纪念作家',   2),
                                                                          ('REVIEWER',      '审核员',     2);

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
    `update_time`  datetime        not null default current_timestamp on update current_timestamp,
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
INSERT INTO `sys_user` (`id`, `username`, `password`, `phone`, `bio`, `status`, `del_flag`, `create_time`,`avatar_color`)
VALUES (1001, 'reader_test', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010001', '热爱阅读的普通用户', 1, 0, NOW(),0);

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1001, `id` FROM `sys_role` WHERE `role_code` = 'READER';

INSERT INTO `sys_user_profile` (`user_id`)
VALUES (1001);

-- 2. AUTHOR 作家
INSERT INTO `sys_user` (`id`, `username`, `password`, `phone`, `bio`, `status`, `del_flag`, `create_time`, `avatar_color`)
VALUES (1002, 'author_test', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010002', '专注科幻小说创作', 1, 0, NOW(),1);

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1002, `id` FROM `sys_role` WHERE `role_code` = 'AUTHOR';

INSERT INTO `sys_user_profile` (`user_id`, `pen_name`, `creation_dir`, `masterpiece`)
VALUES (1002, '红袖添香', '科幻 / 奇幻', '《星海归途》系列');

-- 3. LEGACY_AUTHOR 纪念作家
INSERT INTO `sys_user` (`id`, `username`, `password`, `phone`, `bio`, `status`, `del_flag`, `create_time`, `avatar_color`)
VALUES (1003, 'legacy_test', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010003', '已故文学巨匠，作品永久珍藏', 1, 0, NOW(),2);

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1003, `id` FROM `sys_role` WHERE `role_code` = 'LEGACY_AUTHOR';

INSERT INTO `sys_user_profile` (`user_id`, `pen_name`, `creation_dir`, `masterpiece`)
VALUES (1003, '老舍', '现实主义文学', '《骆驼祥子》《茶馆》');

-- 4. REVIEWER 审核员
INSERT INTO `sys_user` (`id`, `username`, `password`, `phone`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1004, 'reviewer_test', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010004', '内容安全审核专员', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1004, `id` FROM `sys_role` WHERE `role_code` = 'REVIEWER';

INSERT INTO `sys_user_profile` (`user_id`)
VALUES (1004);

-- 5. ADMIN 管理员
INSERT INTO `sys_user` (`id`, `username`, `password`, `phone`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1005, 'admin_test', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010005', '负责平台日常运营管理', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1005, `id` FROM `sys_role` WHERE `role_code` = 'ADMIN';

INSERT INTO `sys_user_profile` (`user_id`)
VALUES (1005);

-- 6. SUPER_ADMIN 超级管理员
INSERT INTO `sys_user` (`id`, `username`, `password`, `phone`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1006, 'super_admin_test', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010006', '系统最高权限拥有者', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1006, `id` FROM `sys_role` WHERE `role_code` = 'SUPER_ADMIN';

INSERT INTO `sys_user_profile` (`user_id`)
VALUES (1006);
```
```sql

**设计原因**：

- `sys_user_profile` 与 `sys_user` 拆分：主表保留高频鉴权字段，扩展资料单独表，避免列过多影响查询效率。
- `avatar_color` 用枚举而非字符串：0/1/2 三态对应无标识/金色/白色，与设计文档视觉区分一致，查询性能好。
- OAuth 绑定表对 `(provider, open_id)` 建唯一索引：保证一个第三方账号只能绑定一个文屿账号。
- `phone` 加密存储：符合设计文档"隐私脱敏"规范，明文查看需临时解密 + 审计。

**手机号联合唯一索引 `uk_phone_del(phone, del_flag)` 设计说明**：

- **问题**：若 phone 建单列唯一索引，用户注销后（`del_flag=1`）手机号仍占位，导致同一手机号无法重新注册。
- **方案**：联合唯一索引 `(phone, del_flag)`，活跃用户 `(phone, 0)` 与已注销用户 `(phone, 1)` 是不同的组合键，互不冲突。
- **NULL 语义说明**：phone 允许为空（`NULL`），MySQL 中 NULL 不参与唯一判断，因此多个 `(NULL, 0)` 不会冲突，这是期望行为——允许没有手机号的活跃用户共存。索引仅对非空 phone 生效。
- **冷却期**：通过 `cancel_time` 字段实现。注销时写入当前时间，重新注册时业务层判断 `cancel_time + 冷却期（如30天） < now()` 是否满足。
- **重复注销处理**：若同一手机号多次注销，业务层在重新注册前先清理旧的 `(phone, 1)` 记录再插入新行，保证唯一性。
- **注册校验流程**：
  1. 查 `(phone, 0)` 存在 → 拒绝（手机号已被活跃用户使用）
  2. 查 `(phone, 1)` 存在且 `cancel_time + 冷却期 > now()` → 拒绝（冷却期内）
  3. 查 `(phone, 1)` 存在且冷却期已过 → 删除旧注销记录 → 允许注册
  4. 无记录 → 直接允许注册

**删除状态统一说明（避免双写不一致）**：

- `sys_user` 的 `del_flag` 是用户删除状态的**唯一真相源**。
- `sys_user_profile` 不设 `del_flag`，查询时 JOIN 主表判断删除状态，避免双写导致的不一致。
- 注销时业务层在同一事务内：主表 `del_flag=1` + 扩展表保留（不删除，便于审计追溯）。

**OAuth 表设计说明**：

- **物理删除**：解绑操作直接 `DELETE FROM`，不留软删除痕迹，避免 openId 被"占位"导致无法重新绑定。
- **审计**：所有删除操作通过 `litisle-common` 的 `@AuditLog` AOP 切面自动记录审计日志（操作人、操作类型、请求参数、执行时间），满足安全审计需求。
- **app_id 预留**：唯一索引 `(provider, app_id, open_id)` 预留 `app_id` 字段，默认值 `'default'`。当前单应用无影响，未来接入多个微信/QQ 应用时，同一 provider 下不同 app 的 openId 不会冲突。

```

## 二、litisle-content 模块（数据库：litislecontent）

（草稿上的图片也能暂存在数据库中）

发布时可以选择文章是否公开，可以在之后几章同时发布，或者先暂时存着（后续可以多选发布-即修改公开状态）
作者写好了一批内容（几篇文章、几个章节），暂时不对外公开，等攒够了/准备好了，一次性把它们一起发布出去。

```sql
# ============================================================
# 模块：litisle-content
# 数据库：litislecontent
# 说明：文章 / 草稿 / 书籍 / 章节 / 图片 / 阅读进度
# ============================================================

create database if not exists litislecontent
character set utf8mb4
collate utf8mb4_unicode_ci;

use litislecontent;

# 1. 文章主表
create table if not exists `article`
(
`id`            bigint unsigned not null auto_increment primary key comment '文章ID',
`author_id`     bigint unsigned not null comment '作者用户ID',
`zone`          tinyint         not null default 1 comment '分区：1-普通分区 2-作家分区',
`title`         varchar(100)    not null comment '标题',
`summary`       varchar(500)    null comment '摘要',
`content_html`  longtext        not null comment '正文HTML',
`cover_file_id`  varchar(64)    null comment '封面文件ID（SeaweedFS）',
`category`      varchar(20)     not null default '散文' comment '分类：小说/散文/现代诗/随笔等',
`status`        tinyint         not null default 0 comment '状态：0-草稿 1-待审核 2-已发布 3-驳回 4-下架',
`visibility`    tinyint         not null default 1 comment '可见性：1-公开 2-仅自己可见',
`view_cnt`      bigint unsigned not null default 0 comment '浏览量',
`like_cnt`      bigint unsigned not null default 0 comment '点赞数',
`comment_cnt`   bigint unsigned not null default 0 comment '评论数',
`del_flag`      tinyint         not null default 0 comment '删除标志：0-未删除 1-已删除',
`version`       int             not null default 0 comment '乐观锁版本号',
`create_time`   datetime        not null default current_timestamp comment '创建时间',
`update_time`   datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_author_id` (`author_id`),
index `idx_zone_status` (`zone`, `status`),
index `idx_visibility` (`visibility`),
index `idx_create_time` (`create_time`)
) engine = InnoDB
default charset = utf8mb4 comment = '文章表';
# 2. 文章图片表（记录插入位置与对齐方式）
create table if not exists `article_image`
(
`id`              bigint unsigned not null auto_increment primary key comment '主键ID',
`article_id`      bigint unsigned not null comment '文章ID（正式文章时有值）',
`draft_id`        bigint unsigned null comment '草稿ID（与 article_id 二选一，发布后清空）',
`file_id`         varchar(64)     not null comment 'SeaweedFS 文件ID',
`position_order`  int             not null comment '图片在正文中的顺序号',
`align`           tinyint         not null default 2 comment '对齐：1-左 2-左偏中 3-中 4-中偏右 5-右',
`width`           int             null comment '宽度（px）',
`height`          int             null comment '高度（px）',
`create_time`     datetime        not null default current_timestamp comment '创建时间',
index `idx_article_id` (`article_id`),
index `idx_draft_id` (`draft_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '文章图片表';



# 3. 草稿自动保存表（多次编辑历史）
create table if not exists `article_draft`
(
`id`              bigint unsigned not null auto_increment primary key comment '草稿ID',
`article_id`      bigint unsigned null comment '关联文章ID（新草稿为 null，发布后回填）',
`author_id`       bigint unsigned not null comment '作者用户ID',
`title`           varchar(100)    not null comment '标题',
`summary`         varchar(500)    null comment '摘要',
`content_html`    longtext        not null comment '正文HTML',
`cover_file_id`   varchar(64)     null comment '封面文件ID（SeaweedFS）',
`category`        varchar(20)     not null default '散文' comment '分类',
`visibility`      tinyint         not null default 1 comment '可见性（与 article 一致，发布时继承）',
`draft_version`   int             not null default 1 comment '草稿版本号（递增）',
`create_time`     datetime        not null default current_timestamp comment '创建时间',
`update_time`     datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_article_id` (`article_id`),
index `idx_author_id` (`author_id`),
index `idx_create_time` (`create_time`)
) engine = InnoDB
default charset = utf8mb4 comment = '草稿自动保存表（多次编辑历史，发布后清空或标记）';

# 4. 书籍/合集表
create table if not exists `book`
(
`id`           bigint unsigned not null auto_increment primary key comment '书籍ID',
`author_id`    bigint unsigned not null comment '作者ID',
`title`        varchar(100)    not null comment '书名',
`intro`        varchar(500)    null comment '简介',
`cover_file_id` varchar(64)    null comment '封面文件ID',
`category`     varchar(20)     not null default '小说' comment '分类',
`status`       tinyint         not null default 1 comment '状态：0-连载中 1-已完结 2-下架',
`del_flag`     tinyint         not null default 0 comment '删除标志',
`version`      int             not null default 0 comment '乐观锁版本号',
`create_time`  datetime        not null default current_timestamp comment '创建时间',
`update_time`  datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_author_id` (`author_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '书籍/合集表';

# 5. 章节表（文章作为章节挂到书籍）
create table if not exists `book_chapter`
(
`id`         bigint unsigned not null auto_increment primary key comment '章节ID',
`book_id`    bigint unsigned not null comment '书籍ID',
`article_id` bigint unsigned not null comment '文章ID',
`chapter_no` int             not null comment '章节序号',
`create_time` datetime       not null default current_timestamp comment '创建时间',
unique key `uk_book_chapter_no` (`book_id`, `chapter_no`),
index `idx_book_id` (`book_id`),
index `idx_article_id` (`article_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '书籍章节表';

# 6. 阅读进度表
create table if not exists `reading_progress`
(
`id`         bigint unsigned not null auto_increment primary key comment '主键ID',
`user_id`    bigint unsigned not null comment '读者ID',
`book_id`    bigint unsigned not null comment '书籍ID',
`chapter_id` bigint unsigned not null comment '最后阅读章节ID',
`update_time` datetime       not null default current_timestamp on update current_timestamp comment '更新时间',
unique key `uk_user_book` (`user_id`, `book_id`),
index `idx_book_id` (`book_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '阅读进度表';
```

设计原因：

article 计数字段冗余（view_cnt/like_cnt）：避免每次列表查询都 join 聚合，配合 Redis 异步刷新。

article_image 独立表：设计文档要求图片在任意位置插入 + 5 种对齐，落表后支持"图片管理"与重新排版。

article_draft 与 article 分离：草稿是高频写、低频读，独立表避免污染主表查询缓存。article_draft 记录每次编辑的快照，draft_version 递增；发布后 article_id 回填，关联到正式文章。

publish_batch_id：批量发布时同一批次文章共享一个 batch_id，便于前端"按批次展示"和"批量操作"。

access_password：visibility=4（密码访问）时使用，BCrypt 加密存储，与用户密码同级别安全保护。

book_chapter 用 (book_id, chapter_no) 唯一索引：保证章节序号不重复，序号即排序键。

content_html 用 longtext：支持大篇幅切片前的完整正文。

## 三、litisle-social 模块（数据库：litislesocial）

```sql
# ============================================================
# 模块：litisle-social
# 数据库：litislesocial
# 说明：关注 / 点赞 / 评论 / 收藏 / 佳句 / 私信
# ============================================================

create database if not exists litislesocial
character set utf8mb4
collate utf8mb4_unicode_ci;
​
use litislesocial;
​
# 1. 关注表
create table if not exists `follow`
(
`follower_id` bigint unsigned not null comment '关注者ID',
`followee_id` bigint unsigned not null comment '被关注者ID',
`create_time` datetime        not null default current_timestamp comment '关注时间',
primary key (`follower_id`, `followee_id`),
index `idx_followee_id` (`followee_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '关注关系表';
​
# 2. 点赞表
create table if not exists `like_record`
(
`user_id`     bigint unsigned not null comment '点赞用户ID',
`target_type` tinyint         not null comment '目标类型：1-文章 2-评论 3-佳句',
`target_id`   bigint unsigned not null comment '目标ID',
`create_time` datetime        not null default current_timestamp comment '点赞时间',
primary key (`user_id`, `target_type`, `target_id`),
index `idx_target` (`target_type`, `target_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '点赞记录表';
​
# 3. 评论表（三级折叠：root_id 支持整棵子树拉取）
create table if not exists `comment`
(
`id`         bigint unsigned not null auto_increment primary key comment '评论ID',
`article_id` bigint unsigned not null comment '文章ID',
`user_id`    bigint unsigned not null comment '评论人ID',
`parent_id`  bigint unsigned null comment '父评论ID（null=一级评论）',
`root_id`    bigint unsigned null comment '根评论ID（null=自身为根）',
`content`    varchar(2000)   not null comment '评论内容（普通500/VIP2000）',
`floor`      int             not null default 1 comment '楼层号',
`status`     tinyint         not null default 0 comment '状态：0-待审核 1-正常 2-驳回',
`like_cnt`   bigint unsigned not null default 0 comment '点赞数',
`del_flag`   tinyint         not null default 0 comment '删除标志',
`version`    int             not null default 0 comment '乐观锁版本号',
`create_time` datetime       not null default current_timestamp comment '创建时间',
`update_time` datetime       not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_article_id` (`article_id`),
index `idx_root_id` (`root_id`),
index `idx_user_id` (`user_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '评论表';
​
# 4. 收藏夹表
create table if not exists `favorite_folder`
(
`id`         bigint unsigned not null auto_increment primary key comment '收藏夹ID',
`user_id`    bigint unsigned not null comment '用户ID',
`name`       varchar(32)     not null comment '收藏夹名称',
`del_flag`   tinyint         not null default 0 comment '删除标志',
`create_time` datetime       not null default current_timestamp comment '创建时间',
`update_time` datetime       not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_user_id` (`user_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '收藏夹表';
​
# 5. 收藏项表
create table if not exists `favorite_item`
(
`id`         bigint unsigned not null auto_increment primary key comment '收藏项ID',
`folder_id`  bigint unsigned not null comment '收藏夹ID',
`user_id`    bigint unsigned not null comment '用户ID（冗余便于校验权限）',
`target_type` tinyint        not null comment '目标类型：1-文章 2-书籍',
`target_id`  bigint unsigned not null comment '目标ID',
`create_time` datetime       not null default current_timestamp comment '收藏时间',
unique key `uk_user_target` (`user_id`, `target_type`, `target_id`),
index `idx_folder_id` (`folder_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '收藏项表';
​
# 6. 佳句收藏表（带出处 + 备注）
create table if not exists `quote_favorite`
(
`id`            bigint unsigned not null auto_increment primary key comment '佳句ID',
`user_id`       bigint unsigned not null comment '收藏用户ID',
`article_id`    bigint unsigned not null comment '出处文章ID',
`chapter_id`    bigint unsigned null comment '出处章节ID',
`selected_text` varchar(500)    not null comment '选中的文本',
`source_title`  varchar(100)    not null comment '出处文章标题',
`source_author` varchar(32)     not null comment '出处作者笔名/昵称',
`note`          varchar(200)    null comment '用户备注（≤200字）',
`del_flag`      tinyint         not null default 0 comment '删除标志',
`create_time`   datetime        not null default current_timestamp comment '收藏时间',
`update_time`   datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_user_id` (`user_id`),
index `idx_article_id` (`article_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '佳句佳段收藏表';
​
# 7. 对话表（承载陌生人3条限制 + 确认机制）
create table if not exists `conversation`
(
`id`            bigint unsigned not null auto_increment primary key comment '对话ID',
`user_a`        bigint unsigned not null comment '发起方ID',
`user_b`        bigint unsigned not null comment '接收方ID',
`status`        tinyint         not null default 0 comment '状态：0-陌生人待确认 1-已确认 2-已忽略',
`a_quota_used`  tinyint         not null default 0 comment 'A 已用陌生人配额（0-3）',
`create_time`   datetime        not null default current_timestamp comment '创建时间',
`update_time`   datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
unique key `uk_a_b` (`user_a`, `user_b`),
index `idx_user_b` (`user_b`)
) engine = InnoDB
default charset = utf8mb4 comment = '私信对话表';
​
# 8. 消息表
create table if not exists `message`
(
`id`             bigint unsigned not null auto_increment primary key comment '消息ID',
`conversation_id` bigint unsigned not null comment '对话ID',
`sender_id`      bigint unsigned not null comment '发送者ID',
`content`        varchar(1000)   not null comment '消息内容（≤1000字）',
`status`         tinyint         not null default 1 comment '状态：0-撤回 1-正常 2-风控拦截',
`create_time`    datetime        not null default current_timestamp comment '发送时间',
index `idx_conversation_id` (`conversation_id`),
index `idx_sender_id` (`sender_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '私信消息表';
​
# 9. 私信黑名单表
create table if not exists `message_blacklist`
(
`user_id`    bigint unsigned not null comment '拉黑者ID',
`blocked_id` bigint unsigned not null comment '被拉黑者ID',
`create_time` datetime       not null default current_timestamp comment '拉黑时间',
primary key (`user_id`, `blocked_id`),
index `idx_blocked_id` (`blocked_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '私信黑名单表';
​
# 10. 私信设置表
create table if not exists `message_setting`
(
`user_id`          bigint unsigned not null primary key comment '用户ID',
`accept_stranger`  tinyint         not null default 1 comment '接受陌生人私信：0-关 1-开',
`receive_scope`    tinyint         not null default 0 comment '可发私信范围：0-所有人 1-仅关注我 2-仅互关',
`update_time`      datetime        not null default current_timestamp on update current_timestamp comment '更新时间'
) engine = InnoDB
default charset = utf8mb4 comment = '私信设置表';
```

设计原因：

follow / like_record / message_blacklist 用联合主键 + 无通用字段：纯关系表，跟随 sys_user_role 风格，写入快、查询走覆盖索引。

comment 同时存 parent_id 和 root_id：parent_id 用于直接回复关系，root_id 用于一次性拉取整棵评论子树实现三级折叠，避免递归查询。

conversation.a_quota_used：把"陌生人 3 条限制"落到 DB 层兜底（Redis 为主），防 Redis 宕机后用户绕过限制。

quote_favorite 冗余 source_title / source_author：佳句需展示出处，冗余避免文章被删后出处丢失。

favorite_item 加 user_id 冗余 + 唯一索引：防止同一用户重复收藏同一目标，权限校验也无需 join folder。

## 四、litisle-review 模块（数据库：litislereview）

```sql
# ============================================================
# 模块：litisle-review
# 数据库：litislereview
# 说明：审核任务 / 举报 / 申诉 / 违规分 / 敏感词库
# ============================================================

create database if not exists litislereview
character set utf8mb4
collate utf8mb4_unicode_ci;
​
use litislereview;
​
# 1. 审核任务表（状态机核心，append-only 不可改不可删）
create table if not exists `review_task`
(
`id`           bigint unsigned not null auto_increment primary key comment '任务ID',
`biz_type`     tinyint         not null comment '业务类型：1-文章 2-评论 3-用户名 4-作家申请',
`biz_id`       bigint unsigned not null comment '业务ID',
`scene`        varchar(20)     not null comment '审核场景：ARTICLE_FEATURED/ARTICLE_COMMUNITY/COMMENT/USERNAME/MESSAGE',
`layer1_hit`   tinyint         not null default 0 comment 'DFA 命中：0-未命中 1-HIGH 2-MID',
`layer2_risk`  tinyint         not null default 0 comment '小审风险：0-未送审 1-低 2-中 3-高',
`status`       tinyint         not null default 0 comment '状态：0-待审 1-自动通过 2-人工待审 3-通过 4-驳回',
`assignee`     bigint unsigned null comment '认领审核员ID',
`decided_by`   bigint unsigned null comment '决策者ID',
`opinion`      varchar(500)    null comment '审核意见/修改建议',
`create_time`  datetime        not null default current_timestamp comment '创建时间',
`update_time`  datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_biz` (`biz_type`, `biz_id`),
index `idx_status` (`status`),
index `idx_assignee` (`assignee`)
) engine = InnoDB
default charset = utf8mb4 comment = '审核任务表';
​
# 2. 举报表
create table if not exists `report`
(
`id`               bigint unsigned not null auto_increment primary key comment '举报ID',
`reporter_id`      bigint unsigned not null comment '举报人ID',
`target_type`      tinyint         not null comment '目标类型：1-文章 2-评论 3-用户',
`target_id`        bigint unsigned not null comment '目标ID',
`reason`           varchar(200)    not null comment '举报理由',
`evidence_file_ids` varchar(500)   null comment '证据文件ID列表（逗号分隔）',
`status`           tinyint         not null default 0 comment '状态：0-待处理 1-初审中 2-转人工 3-已裁决',
`handler`          bigint unsigned null comment '处理人ID',
`result`           varchar(200)    null comment '裁决结果',
`create_time`      datetime        not null default current_timestamp comment '举报时间',
`update_time`      datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_target` (`target_type`, `target_id`),
index `idx_status` (`status`)
) engine = InnoDB
default charset = utf8mb4 comment = '举报表';
​
# 3. 申诉表
create table if not exists `appeal`
(
`id`            bigint unsigned not null auto_increment primary key comment '申诉ID',
`report_id`     bigint unsigned null comment '关联举报ID',
`punish_id`     bigint unsigned null comment '关联处罚记录ID',
`appellant_id`  bigint unsigned not null comment '申诉人ID',
`content`       varchar(500)    not null comment '申诉内容',
`status`        tinyint         not null default 0 comment '状态：0-待裁决 1-维持 2-撤销',
`judge_by`      bigint unsigned null comment '裁决审核员ID',
`judge_opinion` varchar(500)    null comment '裁决意见',
`create_time`   datetime        not null default current_timestamp comment '申诉时间',
`update_time`   datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_appellant_id` (`appellant_id`),
index `idx_status` (`status`)
) engine = InnoDB
default charset = utf8mb4 comment = '申诉表';
​
# 4. 违规分累积表
create table if not exists `violation_score`
(
`user_id`            bigint unsigned not null primary key comment '用户ID',
`score`              int             not null default 0 comment '违规分累计',
`last_punish_action` tinyint         not null default 0 comment '上次处罚动作：0-无 1-禁言 2-封禁',
`frozen_until`       datetime        null comment '禁言截止时间',
`update_time`        datetime        not null default current_timestamp on update current_timestamp comment '更新时间'
) engine = InnoDB
default charset = utf8mb4 comment = '违规分累积表';
​
# 5. 敏感词库表（DFA 热更新数据源）
create table if not exists `sensitive_word_library`
(
`id`           bigint unsigned not null auto_increment primary key comment '词ID',
`word`         varchar(64)     not null comment '敏感词',
`scene`        varchar(20)     not null comment '场景：ARTICLE/COMMENT/USERNAME/MESSAGE',
`level`        tinyint         not null default 2 comment '级别：1-HIGH 2-MID',
`is_blacklist` tinyint         not null default 1 comment '是否黑名单：0-白名单 1-黑名单',
`status`       tinyint         not null default 1 comment '状态：0-禁用 1-启用',
`del_flag`     tinyint         not null default 0 comment '删除标志',
`updated_by`   bigint unsigned not null comment '修改人ID（审计）',
`create_time`  datetime        not null default current_timestamp comment '创建时间',
`update_time`  datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_scene_status` (`scene`, `status`),
index `idx_word` (`word`)
) engine = InnoDB
default charset = utf8mb4 comment = '敏感词库表';
​
# 6. 作家申请表
create table if not exists `author_apply`
(
`id`            bigint unsigned not null auto_increment primary key comment '申请ID',
`user_id`       bigint unsigned not null comment '申请人ID',
`pen_name`      varchar(32)     not null comment '笔名',
`creation_dir`  varchar(100)    not null comment '创作方向',
`masterpiece`   varchar(500)    null comment '代表作简介',
`status`        tinyint         not null default 0 comment '状态：0-待审 1-通过 2-驳回',
`reviewer_id`   bigint unsigned null comment '审核员ID',
`reject_reason` varchar(200)    null comment '驳回原因',
`create_time`   datetime        not null default current_timestamp comment '申请时间',
`update_time`   datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_user_id` (`user_id`),
index `idx_status` (`status`)
) engine = InnoDB
default charset = utf8mb4 comment = '作家申请表';
```

设计原因：

review_task 不加 del_flag：审核记录不可删除（设计文档审计规范），所有变更走 status 流转。

sensitive_word_library 同时支持黑/白名单与多场景：对应设计文档"评论加载白名单 / 用户名不加载白名单"的差异化策略，按 scene 过滤加载。

violation_score 用 user_id 作主键：一用户一行，分数累加用乐观锁 + Redis 计数器双写。

author_apply 独立于 sys_user：申请记录需保留历史，多次申请形成流水。

## 五、litisle-ai 模块（数据库：litisleai）

redis+mysql结合处理token相关。定时任务清理之前的token记录数据

```sql
# ============================================================
# 模块：litisle-ai
# 数据库：litisleai
# 说明：小月会话 / 消息 / 已保存会话 / Token 用量
# ============================================================
​
create database if not exists litisleai
character set utf8mb4
collate utf8mb4_unicode_ci;
​
use litisleai;
​
# 1. AI 会话表（暂存区 LRU 淘汰）
create table if not exists `ai_session`
(
`id`         bigint unsigned not null auto_increment primary key comment '会话ID',
`user_id`    bigint unsigned not null comment '用户ID',
`agent_type` varchar(16)     not null comment 'Agent 类型：XIAOYUE / XIAOSHEN',
`title`      varchar(100)    null comment '会话标题（首条消息摘要）',
`mode`       varchar(20)     null comment '小月模式：POLISH/STRUCTURE/SUMMARY/DEEP',
`is_lru`     tinyint         not null default 1 comment '是否暂存区：1-是 0-已保存区',
`last_active_time` datetime  not null default current_timestamp comment '最后活跃时间（LRU 依据）',
`del_flag`   tinyint         not null default 0 comment '删除标志',
`create_time` datetime       not null default current_timestamp comment '创建时间',
`update_time` datetime       not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_user_lru` (`user_id`, `is_lru`, `last_active_time`)
) engine = InnoDB
default charset = utf8mb4 comment = 'AI 会话表';
​
# 2. AI 消息表
create table if not exists `ai_message`
(
`id`         bigint unsigned not null auto_increment primary key comment '消息ID',
`session_id` bigint unsigned not null comment '会话ID',
`role`       varchar(16)     not null comment '角色：USER / ASSISTANT / SYSTEM',
`content`    text            not null comment '消息内容',
`token_cnt`  int             not null default 0 comment '本条消息 token 数',
#应该记录token吗
`create_time` datetime       not null default current_timestamp comment '创建时间',
index `idx_session_id` (`session_id`)
) engine = InnoDB
default charset = utf8mb4 comment = 'AI 消息表';
​
# 3. Token 用量统计表（VIP 不限 / 普通计数）
create table if not exists `ai_token_usage`
(
`id`         bigint unsigned not null auto_increment primary key comment '主键ID',
`user_id`    bigint unsigned not null comment '用户ID',
`usage_date` date            not null comment '统计日期',
`token_total` int            not null default 0 comment '当日 token 总量',
`call_cnt`   int             not null default 0 comment '当日调用次数',
`update_time` datetime       not null default current_timestamp on update current_timestamp comment '更新时间',
unique key `uk_user_date` (`user_id`, `usage_date`),
index `idx_usage_date` (`usage_date`)
) engine = InnoDB
default charset = utf8mb4 comment = 'AI Token 用量表';
```

设计原因：

ai_session 用 is_lru + last_active_time 联合索引：直接支持 LRU 淘汰查询（order by last_active_time limit 1）。

ai_message 不加 del_flag：消息跟随会话级联清理，避免冗余字段。

ai_token_usage 按 (user_id, usage_date) 唯一键：每日一行，支持 upsert 累加，便于限额校验。

## 六、litisle-payment 模块（数据库：litislepayment）

```sql
# ============================================================
# 模块：litisle-payment
# 数据库：litislepayment
# 说明：VIP 订单 / 小硬币账户 / 流水 / 赞赏 / 提现 / Seata
# ============================================================
​
create database if not exists litislepayment
character set utf8mb4
collate utf8mb4_unicode_ci;
​
use litislepayment;
​
# 1. VIP 订单表
create table if not exists `vip_order`
(
`id`           bigint unsigned not null auto_increment primary key comment '订单ID',
`user_id`      bigint unsigned not null comment '用户ID',
`plan`         tinyint         not null comment '套餐：1-月度 2-季度 3-年度 4-三年',
`gift_coin`    int             not null default 0 comment '赠送小硬币数',
`amount`       decimal(10,2)   not null comment '支付金额（元）',
`expire_date`  datetime        not null comment 'VIP 到期时间',
`status`       tinyint         not null default 0 comment '状态：0-待支付 1-已支付 2-已取消',
`del_flag`     tinyint         not null default 0 comment '删除标志',
`create_time`  datetime        not null default current_timestamp comment '创建时间',
`update_time`  datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_user_id` (`user_id`),
index `idx_status` (`status`)
) engine = InnoDB
default charset = utf8mb4 comment = 'VIP 订单表';
​
# 2. 小硬币账户表（BIGINT 微币防精度丢失）
create table if not exists `coin_account`
(
`user_id`        bigint unsigned not null primary key comment '用户ID',
`balance_micro`  bigint          not null default 0 comment '微币余额（1小硬币=100微币，BIGINT 防精度）',
`version`        int             not null default 0 comment '乐观锁版本号',
`update_time`    datetime        not null default current_timestamp on update current_timestamp comment '更新时间'
) engine = InnoDB
default charset = utf8mb4 comment = '小硬币账户表';
​
# 3. 流水表（append-only 不可改）
create table if not exists `coin_transaction`
(
`id`          bigint unsigned not null auto_increment primary key comment '流水ID',
`user_id`     bigint unsigned not null comment '用户ID',
`tx_type`     tinyint         not null comment '类型：1-充值 2-赞赏支出 3-赞赏收入 4-提现 5-VIP赠送',
`amount_micro` bigint         not null comment '变动金额（微币，正负）',
`balance_after` bigint        not null comment '变动后余额（对账用）',
`related_id`  bigint unsigned null comment '关联业务ID（订单/赞赏记录/提现单）',
`create_time` datetime        not null default current_timestamp comment '发生时间',
index `idx_user_id` (`user_id`),
index `idx_related` (`tx_type`, `related_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '小硬币流水表';
​
# 4. 赞赏记录表
create table if not exists `reward_record`
(
`id`                bigint unsigned not null auto_increment primary key comment '赞赏ID',
`from_user_id`      bigint unsigned not null comment '赞赏者ID',
`to_user_id`        bigint unsigned not null comment '被赞赏者ID',
`target_type`       tinyint         not null comment '目标类型：1-文章 2-佳句',
`target_id`         bigint unsigned not null comment '目标ID',
`amount_micro`      bigint          not null comment '赞赏金额（微币）',
`platform_fee_micro` bigint         not null comment '平台抽成（微币，10%）',
`create_time`       datetime        not null default current_timestamp comment '赞赏时间',
index `idx_to_user` (`to_user_id`),
index `idx_target` (`target_type`, `target_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '赞赏记录表';
​
# 5. 提现申请表
create table if not exists `withdraw_request`
(
`id`            bigint unsigned not null auto_increment primary key comment '提现单ID',
`user_id`       bigint unsigned not null comment '申请人ID',
`amount_micro`  bigint          not null comment '提现金额（微币，≥100000 即 1000 小硬币）',
`status`        tinyint         not null default 0 comment '状态：0-待审 1-通过 2-驳回 3-已打款',
`audited_by`    bigint unsigned null comment '审核人ID',
`reject_reason` varchar(200)    null comment '驳回原因',
`create_time`   datetime        not null default current_timestamp comment '申请时间',
`update_time`   datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_user_id` (`user_id`),
index `idx_status` (`status`)
) engine = InnoDB
default charset = utf8mb4 comment = '提现申请表';
​
# 6. Seata AT 模式 undo_log 表（必须，Seata 规范要求）
create table if not exists `undo_log`
(
`branch_id`     bigint unsigned not null comment '分支事务ID',
`xid`           varchar(128)    not null comment '全局事务ID',
`context`       varchar(128)    not null comment '上下文',
`rollback_info` longblob        not null comment '回滚信息',
`log_status`    int             not null comment '日志状态',
`log_created`   datetime        not null default current_timestamp comment '创建时间',
`log_modified`  datetime        not null default current_timestamp on update current_timestamp comment '修改时间',
unique key `ux_undo_log` (`xid`, `branch_id`)
) engine = InnoDB
default charset = utf8mb4 comment = 'Seata AT 模式 undo_log 表';
```

设计原因：

coin_account.balance_micro 用 BIGINT：设计文档明确"内部微币 BIGINT 防精度"，展示层 BigDecimal/100 转小硬币。

coin_transaction 加 balance_after：每笔流水记录变动后余额，便于对账与异常排查，符合金融系统规范。

reward_record 拆 amount_micro 与 platform_fee_micro：赞赏 10% 抽成透明记录，作者收入 = amount - fee。

undo_log 表：Seata AT 模式硬性要求，每个参与全局事务的库都要有。

## 七、litisle-notification 模块（数据库：litislenotification）

```sql
# ============================================================
# 模块：litisle-notification
# 数据库：litislenotification
# 说明：通知 / 通知设置
# ============================================================
​
create database if not exists litislenotification
character set utf8mb4
collate utf8mb4_unicode_ci;
​
use litislenotification;
​
# 1. 通知表
create table if not exists `notification`
(
`id`         bigint unsigned not null auto_increment primary key comment '通知ID',
`user_id`    bigint unsigned not null comment '接收人ID',
`type`       tinyint         not null comment '类型：1-社交 2-审核 3-举报申诉 4-私信 5-系统',
`biz_type`   varchar(20)     null comment '业务类型：LIKE/COMMENT/FOLLOW/REVIEW/REPORT等',
`biz_id`     bigint unsigned null comment '业务ID',
`title`      varchar(100)    not null comment '通知标题',
`content`    varchar(500)    not null comment '通知内容',
`is_read`    tinyint         not null default 0 comment '已读：0-未读 1-已读',
`create_time` datetime       not null default current_timestamp comment '创建时间',
index `idx_user_read` (`user_id`, `is_read`),
index `idx_create_time` (`create_time`)
) engine = InnoDB
default charset = utf8mb4 comment = '通知表';
​
# 2. 通知设置表
create table if not exists `notification_setting`
(
`user_id`        bigint unsigned not null primary key comment '用户ID',
`social_notify`  tinyint         not null default 1 comment '社交通知：0-关 1-开',
`review_notify`  tinyint         not null default 1 comment '审核通知：0-关 1-开',
`pm_notify`      tinyint         not null default 1 comment '私信通知：0-关 1-开',
`system_notify`  tinyint         not null default 1 comment '系统通知：0-关 1-开',
`update_time`    datetime        not null default current_timestamp on update current_timestamp comment '更新时间'
) engine = InnoDB
default charset = utf8mb4 comment = '通知设置表';
```

设计原因：

notification 用 (user_id, is_read) 联合索引：高频查询"未读列表"，索引覆盖直接命中。

notification_setting 用 user_id 作主键：一用户一行，按需 upsert。

## 八、litisle-file 模块（数据库：litislefile）

```sql
# ============================================================
# 模块：litisle-file
# 数据库：litislefile
# 说明：SeaweedFS 文件元数据
# ============================================================
​
create database if not exists litislefile
character set utf8mb4
collate utf8mb4_unicode_ci;
​
use litislefile;
​
# 1. 文件元数据表
create table if not exists `file_meta`
(
`id`         bigint unsigned not null auto_increment primary key comment '主键ID',
`file_id`    varchar(64)     not null unique comment 'SeaweedFS 文件ID（业务主键）',
`owner_id`   bigint unsigned not null comment '上传者ID',
`biz_type`   varchar(20)     not null comment '业务类型：AVATAR/COVER/ARTICLE_IMG/LICENSE',
`bucket`     varchar(32)     not null comment 'SeaweedFS collection',
`path`       varchar(255)    not null comment 'Filer 路径',
`mime`       varchar(64)     not null comment 'MIME 类型',
`size`       bigint unsigned not null comment '文件大小（字节）',
`width`      int             null comment '图片宽度',
`height`     int             null comment '图片高度',
`align`      tinyint         null comment '对齐：1-左 2-左偏中 3-中 4-中偏右 5-右（图片专用）',
`access`     tinyint         not null default 0 comment '访问级别：0-私有 1-公开',
`sha256`     varchar(64)     not null comment 'SHA256 摘要（去重）',
`del_flag`   tinyint         not null default 0 comment '删除标志',
`create_time` datetime       not null default current_timestamp comment '上传时间',
`update_time` datetime       not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_owner_biz` (`owner_id`, `biz_type`),
index `idx_sha256` (`sha256`)
) engine = InnoDB
default charset = utf8mb4 comment = '文件元数据表';
```

设计原因：

file_id 业务主键 + 唯一索引：业务层只持有 fileId，与 SeaweedFS 解耦。

sha256 索引：上传前先查 sha256，命中直接复用 fileId，实现秒传与去重。

access 区分公开/私有：公开文件走 CDN 直链，私有文件走预签名短时效 URL。

align 放此表为冗余可选：实际对齐信息以 article_image 为准，此处冗余便于文件维度查询。

## 九、litisle-search 模块

该模块不建 MySQL 表，数据全部落 Elasticsearch：

- `article_idx`（标题^4 / 标签^3 / 摘要^2 / 正文 / dense_vector 向量字段）
- `book_idx`（书名 / 简介 / 章节标题）
- `user_idx`（笔名 / 简介 / 作品标签聚合）

索引由 MQ 事件（content.published / social.liked）异步增量同步，无需关系表。

## 十、litisle-shop 模块（数据库：litisleshop）

```sql
-- =============================================
-- litisle_shop 库：积分商城 + 订单 + 运单
-- =============================================
create database if not exists litisle_shop
default character set utf8mb4
default collate utf8mb4_unicode_ci;
​
use litisle_shop;
​
-- 1. 商品表
create table if not exists shop_product
(
id              bigint          not null auto_increment comment '商品ID',
name            varchar(100)    not null comment '商品名称',
description     varchar(500)    default '' comment '商品描述',
price_coins     bigint          not null comment '价格（小硬币，单位：微币 BIGINT）',
total_stock     int             not null comment '总库存',
stock           int             not null comment '剩余库存',
image_url       varchar(500)    default '' comment '商品图片（SeaweedFS 直链）',
sale_start_time datetime        not null comment '开售时间',
sale_end_time   datetime        not null comment '截止时间',
status          tinyint         not null default 0 comment '状态：0-下架 1-上架',
version         int             not null default 0 comment '乐观锁（库存扣减并发）',
deleted         tinyint         not null default 0 comment '逻辑删除：0-正常 1-删除',
create_time     datetime        not null default current_timestamp,
update_time     datetime        not null default current_timestamp on update current_timestamp,
primary key (id),
key idx_status (status, sale_start_time, sale_end_time)
) engine=innodb default charset=utf8mb4 comment='积分商城商品表';
​
-- 2. 订单表
create table if not exists shop_order
(
id              bigint          not null auto_increment comment '订单ID',
order_no        varchar(32)     not null comment '订单号（唯一）',
user_id         bigint          not null comment '用户ID',
product_id      bigint          not null comment '商品ID',
quantity        int             not null default 1 comment '数量',
total_price     bigint          not null comment '总价（微币）',
status          tinyint         not null default 0 comment '状态：0-待支付 1-已支付 2-已发货 3-已送达 4-已完成 5-已取消 6-已退款',
paid_time       datetime        default null comment '支付时间',
cancel_reason   varchar(200)    default '' comment '取消原因',
cancel_time     datetime        default null comment '取消时间',
deleted         tinyint         not null default 0 comment '逻辑删除',
create_time     datetime        not null default current_timestamp,
update_time     datetime        not null default current_timestamp on update current_timestamp,
primary key (id),
unique key uk_order_no (order_no),
key idx_user_id (user_id),
key idx_status (status, create_time)
) engine=innodb default charset=utf8mb4 comment='积分商城订单表';
​
-- 3. 运单表
create table if not exists shipping
(
id               bigint         not null auto_increment comment '运单ID',
order_id         bigint         not null comment '订单ID',
express_company  varchar(50)    default '' comment '快递公司',
express_no       varchar(50)    default '' comment '快递单号',
receiver_name    varchar(50)    not null comment '收件人姓名',
receiver_phone   varchar(20)    not null comment '收件人电话',
receiver_address varchar(300)   not null comment '收件人地址',
status           tinyint        not null default 0 comment '状态：0-待发货 1-已发货 2-已签收',
shipped_time     datetime       default null comment '发货时间',
signed_time      datetime       default null comment '签收时间',
create_time      datetime       not null default current_timestamp,
update_time      datetime       not null default current_timestamp on update current_timestamp,
primary key (id),
unique key uk_order_id (order_id),
key idx_express (express_company, express_no)
) engine=innodb default charset=utf8mb4 comment='运单表';
```
```
