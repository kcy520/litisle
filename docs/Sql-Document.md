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

## 一、litisle-user 模块（数据库：litisle_user）

```sql
--
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
    `uid`             varchar(16)     not null comment '对外UID（用户间搜索唯一标识，随机生成，永久不变；注册时 UidGenerator 生成，唯一键冲突则重生成）',
    `password`        varchar(128)    not null comment '密码（BCrypt）',
    `email`           varchar(255)    null comment '邮箱（AES 加密存储，展示脱敏，安全通道解密）',
    `phone`           varchar(255)     not null comment '手机号（AES 加密存储，展示脱敏，安全通道解密）',
    `phone_hash`      varchar(64)     not null comment '手机号 HMAC-SHA256 摘要（密钥优先独立 hashKey，未配置时由 CryptoAutoConfiguration 从 AES 主密钥确定性派生子密钥）；唯一约束与查重走本列，phone 密文列只管存储（AES 随机 IV 密文不重复，唯一索引必须建在确定性 hash 上）',
    `email_hash`      varchar(64)     null comment '邮箱 HMAC-SHA256 摘要（独立密钥）；同 phone_hash',
    `avatar_file_id`  varchar(64)     null comment '头像文件ID（SeaweedFS fileId，URL 运行时通过 FileService 批量反查拼接；默认头像走代码常量兜底，不落库）',
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
--- - **手机号联合唯一索引 `uk_phone_hash_del(phone_hash, del_flag)` 设计说明**：
--
-- - **问题**：若 phone 建单列唯一索引，用户注销后（`del_flag=1`）手机号仍占位，导致同一手机号无法重新注册。
-- - **方案**：联合唯一索引建在 `(phone_hash, del_flag)` 上，活跃用户与已注销用户是不同的组合键，互不冲突。
-- - **为什么唯一索引建在 hash 列而非密文列**：AES 加密采用随机 IV，同一手机号每次加密密文都不同，
--   对密文建唯一索引永远撞不上 → 查重/防重复注册失效；HMAC-SHA256 是确定性摘要（同输入同输出），
--   查重等值匹配走 hash 列，密文列保留加密收益，二者职责分离（密态存储标准做法）。
-- - **非空语义**：phone 为 not null（注册必填），phone_hash 随注册/换绑由 CryptoService 同步写入；email 可为空，email_hash 为 NULL 时不参与唯一判断（MySQL 中 NULL 不冲突），允许多个无邮箱用户共存。
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
# 初始化示例用户数据（无邮箱；本地开发种子数据）
# 密码统一为 bcrypt 加密的 123
# 哈希：$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2
#
# 手机号加密列种子策略（本地开发简化）：
# - 正式数据一律走应用层 CryptoService：phone 存 AES-GCM 密文、phone_hash 存 HMAC-SHA256
#   （随机 IV 密文每次不同，无法在 SQL 中静态预生成真实值）
# - 种子简化：phone 暂存明文，phone_hash 用 MySQL 端 SHA2(phone,256) 占位（64 位 hex，长度合规）
# - 已知局限：种子 hash 与应用 HMAC 不一致 → 种子用户不可走手机号登录/查重路径；
#   测手机号登录请走注册流程造数，种子账号用用户名+密码登录
# - 本地密钥见 yml security.crypto.key（仅本地开发；生产走 LITISLE_CRYPTO_KEY 环境变量注入）
# - uid 为 not null：应用注册时由 UidGenerator 随机生成；种子数据用固定值便于测试与互搜
# ============================================================

-- 1. READER 读者
INSERT INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`,`avatar_color`)
VALUES (1001, 'reader_test', 'U1001READER01', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', 'd1FyZ+b0E1E/eFmhtQWhkRZ7YDGMQODobmsRB4aPte7quJGt2lV6', '92ee487f83ae8784872d86f8c0d579b781d7956a60ebf95305197edc0ba3cb35', '热爱阅读的普通用户', 1, 0, NOW(),0);

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1001, `id` FROM `sys_role` WHERE `role_code` = 'READER';

INSERT INTO `sys_user_profile` (`user_id`)
VALUES (1001);

-- 2. AUTHOR 作家
INSERT INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`, `avatar_color`)
VALUES (1002, 'author_test', 'U1002AUTHOR01', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', 'rtIAtr//RH0fX0HKS+2l22spZsei7h4E4PxohDvooz+0h4PhsWWb', 'f41c3968090234792656a3fd53ad3a6436286cbd531fd58692cc1fab75c38780', '专注科幻小说创作', 1, 0, NOW(),1);

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1002, `id` FROM `sys_role` WHERE `role_code` = 'AUTHOR';

INSERT INTO `sys_user_profile` (`user_id`, `pen_name`, `creation_dir`, `masterpiece`)
VALUES (1002, '红袖添香', '科幻 / 奇幻', '《星海归途》系列');

-- 3. LEGACY_AUTHOR 纪念作家
INSERT INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`, `avatar_color`)
VALUES (1003, 'legacy_test', 'U1003LEGACY01', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '4cPacxVojqKfv+0dP5qMjmnkvPJM63euWFyoa0qUr6pYUNFl9EYU', '61bcee3ebe69365829ad5662522be825230d23939fe5189e2a1161792f9acc80', '已故文学巨匠，作品永久珍藏', 1, 0, NOW(),2);

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1003, `id` FROM `sys_role` WHERE `role_code` = 'LEGACY_AUTHOR';

INSERT INTO `sys_user_profile` (`user_id`, `pen_name`, `creation_dir`, `masterpiece`)
VALUES (1003, '老舍', '现实主义文学', '《骆驼祥子》《茶馆》');

-- 4. REVIEWER 审核员
INSERT INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1004, 'reviewer_test', 'U1004REVIEW01', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', 'MmhwRQz5Y2phTeQWof43m/B8woUk3AZkXRB2CRBts9bnwYJ3ekOx', '0e40f2a460b37e4c2563b649607abbd8d8289c5b5aea5dc36e99a96623c5a602', '内容安全审核专员', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1004, `id` FROM `sys_role` WHERE `role_code` = 'REVIEWER';

INSERT INTO `sys_user_profile` (`user_id`)
VALUES (1004);

-- 5. ADMIN 管理员
INSERT INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1005, 'admin_test', 'U1005ADMIN001', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', 'hLqQug7IZ2ArhGrKNBBX+DuAONUUqG7gwoov2ugcWSJAUMsofOcJ', 'cd21bdf19ed271a099031dd89d2ac78f23b60c4900b18b8365bbb92a142e66e7', '负责平台日常运营管理', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1005, `id` FROM `sys_role` WHERE `role_code` = 'ADMIN';

INSERT INTO `sys_user_profile` (`user_id`)
VALUES (1005);

-- 6. SUPER_ADMIN 超级管理员
INSERT INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1006, 'super_admin_test', 'U1006SADMIN01', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', 'xKHNh2in4lCXBiZtGakTnADPBJswk8k3f351UfkkeDylQWbV/1oJ', 'd161009f4c0a655d7aedd00ab559955ee2d0bb6b343efc4e637a3aad2d76b711', '系统最高权限拥有者', 1, 0, NOW());

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

**手机号联合唯一索引 `uk_phone_hash_del(phone_hash, del_flag)` 设计说明**：

- **问题**：若 phone 建单列唯一索引，用户注销后（`del_flag=1`）手机号仍占位，导致同一手机号无法重新注册。
- **方案**：联合唯一索引建在 `(phone_hash, del_flag)` 上，活跃用户与已注销用户是不同的组合键，互不冲突。
- **为什么唯一索引建在 hash 列而非密文列**：AES 加密采用随机 IV，同一手机号每次加密密文都不同，对密文建唯一索引永远撞不上 → 查重/防重复注册失效；HMAC-SHA256 是确定性摘要（同输入同输出），查重等值匹配走 hash 列，密文列保留加密收益。
- **非空语义**：phone 为 not null（注册必填）；email 可为空，email_hash 为 NULL 时不参与唯一判断。
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
- **审计**：所有删除操作通过 `litisle_common` 的 `@AuditLog` AOP 切面自动记录审计日志（操作人、操作类型、请求参数、执行时间），满足安全审计需求。
- **app_id 预留**：唯一索引 `(provider, app_id, open_id)` 预留 `app_id` 字段，默认值 `'default'`。当前单应用无影响，未来接入多个微信/QQ 应用时，同一 provider 下不同 app 的 openId 不会冲突。

```

## 二、litisle-content 模块（数据库：litisle_content）

（草稿上的图片也能暂存在数据库中）

发布时可以选择文章是否公开，可以在之后几章同时发布，或者先暂时存着（后续可以多选发布-即修改公开状态）
作者写好了一批内容（几篇文章、几个章节），暂时不对外公开，等攒够了/准备好了，一次性把它们一起发布出去。

```sql
# ============================================================
# 模块：litisle-content
# 数据库：litisle_content
# 说明：文章 / 草稿 / 书籍 / 章节 / 图片 / 阅读进度
# ============================================================

create database if not exists litisle_content
character set utf8mb4
collate utf8mb4_unicode_ci;

use litisle_content;

# 1. 文章主表
create table if not exists `article`
(
`id`            bigint unsigned not null auto_increment primary key comment '文章ID',
`author_id`     bigint unsigned not null comment '作者用户ID',
`zone`          tinyint         not null default 1 comment '分区：1-普通分区 2-作家分区 3-推荐流（运营/算法推荐内容）',
`title`         varchar(100)    not null comment '标题',
`summary`       varchar(500)    null comment '摘要',
`content_html`  longtext        not null comment '正文HTML',
`cover_file_id`  varchar(64)    null comment '封面文件ID（SeaweedFS）',
`category`      varchar(20)     not null default '散文' comment '分类：小说/散文/现代诗/随笔等',
`status`        tinyint         not null default 0 comment '状态：0-草稿 1-待审核 2-已发布 3-驳回 4-下架',
`visibility`    tinyint         not null default 1 comment '可见性：1-公开 2-仅自己可见 3-仅关注者可见 4-密码访问（密码存 access_password）',
`access_password` varchar(128)  null comment '访问密码（visibility=4 时必填，BCrypt 加密存储，与用户密码同级别保护）',
`publish_batch_id` bigint unsigned null comment '批量发布批次ID（同批次发布的多篇文章/章节共享，便于按批次展示与批量操作）',
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
index `idx_publish_batch` (`publish_batch_id`),
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
index `idx_draft_id` (`draft_id`),
index `idx_file_id` (`file_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '文章图片表（idx_file_id 支撑图片复审再聚合：图片人审/图片机审结果落库/申诉改判后按 file_id 反查引用该图的所有文章，再定位其 ARTICLE_IMG_RE_REVIEW 复审任务重算聚合结论，防挂起任务永久卡待审，见 Review-Plan §4.2.2④）';



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
`status`       tinyint         not null default 0 comment '状态：0-连载中 1-已完结 2-下架（默认连载中）',
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

## 三、litisle-social 模块（数据库：litisle_social）

```sql
# ============================================================
# 模块：litisle-social
# 数据库：litisle_social
# 说明：关注 / 点赞 / 评论 / 收藏 / 佳句 / 私信
# ============================================================

create database if not exists litisle_social
character set utf8mb4
collate utf8mb4_unicode_ci;
       
use litisle_social;

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
index `idx_article_status` (`article_id`, `status`, `create_time`),
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
`status`         tinyint         not null default 1 comment '状态：0-撤回（仅用户主动撤回）1-正常 2-风控拦截（平台审核强制不可见：机审 REJECT/SUSPICIOUS 经 moderation.message.result 事件 CAS 1→2，人审终审 PASS 恢复 2→1，default-deny 宁严勿松）',
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

message.status 三态按行为主体分离：0-撤回仅用户主动使用，2-风控拦截为平台审核专用
（机审 REJECT/SUSPICIOUS 拦截、人审 PASS 恢复，经 moderation.message.result 事件 CAS 流转，
重放幂等）——平台行为不占用撤回值，避免一列混载两种行为语义。

## 四、litisle-review 模块（数据库：litisle_review）

```sql
# ============================================================
# 模块：litisle-review
# 数据库：litisle_review
# 说明：审核任务 / 审核留痕 / 举报 / 申诉 / 违规分 / 敏感词库
# ============================================================

create database if not exists litisle_review
character set utf8mb4
collate utf8mb4_unicode_ci;

use litisle_review;

    # 1. 审核任务表（状态机核心，不可物理删除；append-only 留痕另存 moderation_record）
create table if not exists `review_task`
(
`id`           bigint unsigned not null auto_increment primary key comment '任务ID',
`biz_type`     tinyint         not null comment '业务类型：1-文章 2-评论 3-用户名 4-作家申请 5-私信 6-文件/图片（biz_type=6 时 biz_id = file_meta.id）',
`biz_id`       bigint unsigned not null comment '业务ID',
`scene`        varchar(32)     not null comment '审核场景：ARTICLE_FEATURED/ARTICLE_COMMUNITY/ARTICLE_RE_REVIEW/COMMENT/COMMENT_RE_REVIEW/USERNAME/MESSAGE/MESSAGE_RE_REVIEW/IMAGE/ARTICLE_IMG_RE_REVIEW（*_RE_REVIEW 为举报/复审触发场景，与发布场景分列防 uk_biz_scene 撞键重置已终态任务，见 Review-Plan §4.5.3；varchar(32) 留余量：最长场景名 ARTICLE_IMG_RE_REVIEW 恰 20 字符，20 无余量易溢出）',
`layer1_hit`   tinyint         not null default 0 comment 'DFA 命中：0-未命中 1-HIGH 2-MID',
`hit_words`    text            null comment 'DFA 命中词快照（逗号分隔，审核时固化，不随词库热更新变化，供人工复核回溯；应用层截断保护后落 text：超长 varchar 在 strict mode 报错会中断审核写入，截断兜底+text 大容量，证据宁存不丢）',
`layer2_risk`  tinyint         not null default 0 comment '小审风险：0-未送审 1-低 2-中 3-高（辅助风险分级：由 LLM 适配器写入供人审队列排序，不承载判定语义，判定以 mod_verdict 为准）',
`status`       tinyint         not null default 0 comment '状态：0-待审 1-自动通过 2-人工待审 3-通过 4-驳回',
`mod_verdict`  tinyint         not null default 0 comment '机审三分态冗余：0-未审 1-PASS 2-REJECT 3-SUSPICIOUS（人审队列列表直读，免 JOIN moderation_record）',
`retry_count`  int             not null default 0 comment 'MQ 重试次数，耗尽转人审（DLX 消费者累加写回，与消息头 retryCount 对账）',
`content_hash` char(64)        null comment '内容指纹SHA256（幂等入口 + 审核缓存复用入口；文本=标题+正文，图片=文件sha256，文章级图片复审=全部配图 sha256 聚合指纹）',
`assignee`     bigint unsigned null comment '认领审核员ID',
`claim_time`   datetime        null comment '认领时间（claim 写入；sweeper 按此判定认领超时回池，勿用 update_time——详情查看/重置等写入会刷新 update_time 致超时判定失准）',
`decided_by`   bigint unsigned null comment '决策者ID',
`opinion`      varchar(500)    null comment '审核意见/修改建议',
`decide_time`  datetime        null comment '终审落库时间（decide 写入；申诉窗口基准与申诉超时判定的权威时间源，见 Review-Plan §4.7）',
`create_time`  datetime        not null default current_timestamp comment '创建时间',
`update_time`  datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
unique key `uk_biz_scene` (`biz_type`, `biz_id`, `scene`),
index `idx_status` (`status`, `create_time`),
index `idx_assignee` (`assignee`)
) engine = InnoDB
default charset = utf8mb4 comment = '审核任务表';

    # 1.1 审核记录表（append-only 留痕：机审+人审+申诉改判全程追加，与 review_task 状态机分离）
create table if not exists `moderation_record`
(
`id`                bigint unsigned not null auto_increment primary key comment '记录ID',
`mod_type`          tinyint         not null comment '审核方式：1-关键词DFA 2-LLM语义 3-本地图片模型 4-云API 5-人工复审 6-申诉改判',
`biz_type`          tinyint         not null comment '业务类型（同 review_task.biz_type）：1-文章 2-评论 3-用户名 4-作家申请 5-私信 6-文件/图片',
`biz_id`            bigint unsigned not null comment '业务ID（biz_type=6 时为 file_meta.id；USERNAME 注册被拒时用户尚不存在，约定 biz_id=0，取证靠 content_hash+reason 自包含）',
`review_task_id`    bigint unsigned null comment '关联审核任务ID（同步闸门可无任务直审，如 USERNAME）',
`scene`             varchar(32)     null comment '审核场景（同 review_task.scene；同 biz_type+biz_id 可并存文本/图片复审多场景，场景列使单行凭证自包含免 JOIN；USERNAME 直审可空；varchar(32) 与 review_task.scene 对齐）',
`content_hash`      char(64)        not null comment '内容指纹SHA256（文本=标题+正文；图片=文件sha256；文章级图片复审=全部配图 sha256 排序拼接后再 sha256 的聚合指纹）——审核缓存查重键，与文件判重键同纪律',
`provider`          varchar(32)     null comment '审核提供方（图片：onnx/aliyun；文本：llm；人工/DFA 为空）——缓存兜底按此过滤，防 onnx→aliyun 切换复用旧结论',
`wordlib_ver`       bigint          null comment '词库版本快照（文本记录写入=sensitive_word_version.version；图片/人工为空）——缓存兜底按此比对，防词库热更后窗口内旧结论继续放行',
`model_version`     varchar(32)     null comment '判定口径版本快照（图片=ONNX 模型版本/云 API 接口版本；文本=LLM few-shot prompt 版本；DFA/人工为空）——缓存兜底按此比对，防同 provider 下模型/prompt 升级后复用旧结论（§2.11）',
`verdict`           tinyint         not null comment '判定：1-PASS 2-REJECT 3-SUSPICIOUS',
`category`          varchar(32)     null comment '违规类目（机审统一映射枚举）：politics/porn/abuse/ad/none',
`hit_words`         text            null comment 'DFA 命中词快照（冗余自 review_task，凭证自包含便于导出审计；与 review_task 同纪律：应用层截断保护后落 text，留痕写入宁存不丢）',
`reason`            varchar(500)    null comment '判定理由（作者申诉与管理员复审依据，LLM 输出透传）',
`provider_trace_id` varchar(128)    null comment '审核凭证ID（云厂商 traceId / LLM 请求ID；本地组件与人工为空）',
`trace_id`          varchar(64)     null comment '网关→MQ→消费端链路追踪ID（排障一串到底；与厂商凭证分列：前者排内部链路，后者对外出凭证）',
`operator_id`       bigint unsigned not null default 0 comment '操作人（0-系统机审）',
`operator_name`     varchar(32)     not null default 'system' comment '操作人名称（审计冗余）',
`cost_ms`           int             null comment '审核耗时（观测 Timer 旁路佐证）',
`create_time`       datetime        not null default current_timestamp comment '记录时间',
index `idx_hash` (`content_hash`, `scene`, `id`),
index `idx_biz` (`biz_type`, `biz_id`),
index `idx_task` (`review_task_id`),
index `idx_create` (`create_time`)
) engine = InnoDB
default charset = utf8mb4 comment = '审核记录表（append-only 不可改不可删：《网络安全法》留痕硬凭证留存≥6个月，到期归档任务迁移历史存储，改判/申诉均追加新行）';
​ 


# 1.1 审核记录归档表（6-4 §4.4.1：热表到期迁移只读历史存储——同库独立表承接，列口径与 moderation_record 逐列对齐；
# 唯一写入入口 = ModerationRecordArchiveSweeper 原子迁档事务：迁档+删热行同事务，原子性天然兑底无半途态）
# ⚠️ DBA 权限纪律：应用账号对本表仅授 INSERT/SELECT（无 UPDATE/DELETE），防误改误删历史凭证
create table if not exists `moderation_record_archive`
(
`id`                bigint unsigned not null primary key comment '记录ID（原 moderation_record.id 原样保留，审计链路不断；禁 auto_increment，防重发新 id 破坏按 id 幂等迁档）',
`mod_type`          tinyint         not null comment '审核方式：1-关键词DFA 2-LLM语义 3-本地图片模型 4-云API 5-人工复审 6-申诉改判（同 moderation_record.mod_type）',
`biz_type`          tinyint         not null comment '业务类型：1-文章 2-评论 3-用户名 4-作家申请 5-私信 6-文件/图片（同 moderation_record.biz_type）',
`biz_id`            bigint unsigned not null comment '业务ID（同 moderation_record.biz_id）',
`review_task_id`    bigint unsigned null comment '关联审核任务ID（同 moderation_record.review_task_id）',
`scene`             varchar(32)     null comment '审核场景（同 moderation_record.scene）',
`content_hash`      char(64)        not null comment '内容指纹SHA256（同 moderation_record.content_hash）',
`provider`          varchar(32)     null comment '审核提供方（同 moderation_record.provider）',
`wordlib_ver`       bigint          null comment '词库版本快照（同 moderation_record.wordlib_ver）',
`model_version`     varchar(32)     null comment '判定口径版本快照（同 moderation_record.model_version）',
`verdict`           tinyint         not null comment '判定：1-PASS 2-REJECT 3-SUSPICIOUS（同 moderation_record.verdict）',
`category`          varchar(32)     null comment '违规类目（同 moderation_record.category）',
`hit_words`         text            null comment 'DFA 命中词快照（同 moderation_record.hit_words）',
`reason`            varchar(500)    null comment '判定理由（同 moderation_record.reason）',
`provider_trace_id` varchar(128)    null comment '审核凭证ID（同 moderation_record.provider_trace_id）',
`trace_id`          varchar(64)     null comment '链路追踪ID（同 moderation_record.trace_id）',
`operator_id`       bigint unsigned not null default 0 comment '操作人（同 moderation_record.operator_id）',
`operator_name`     varchar(32)     not null default 'system' comment '操作人名称（同 moderation_record.operator_name）',
`cost_ms`           int             null comment '审核耗时（同 moderation_record.cost_ms）',
`create_time`       datetime        not null default current_timestamp comment '记录时间（=原热表记录时间，合规留存期以此为准）',
index `idx_biz` (`biz_type`, `biz_id`),
index `idx_create` (`create_time`)
) engine = InnoDB
default charset = utf8mb4 comment = '审核记录归档表（只读历史存储：仅 INSERT/SELECT；《网络安全法》≥6个月合规留存收口于此，热表删除不影响审计凭证可查）';


# 2. 举报表
create table if not exists `report`
(
`id`               bigint unsigned not null auto_increment primary key comment '举报ID',
`reporter_id`      bigint unsigned not null comment '举报人ID',
`target_type`      tinyint         not null comment '目标类型：1-文章 2-评论 3-用户 4-私信 5-图片/文件（target_type=5 时 target_id = file_meta.id，与其余 target 对齐；与 review_task.biz_type 映射见 Review-Plan §4.5.2 SSOT 表：私信 5↔4、图片 6↔5 错位，禁按 biz_type==target_type 恒等实现，否则反查举报零行卡死）',
`target_id`        bigint unsigned not null comment '目标ID',
`reason`           varchar(200)    not null comment '举报理由',
`status`           tinyint         not null default 0 comment '状态：0-待处理 1-初审中 2-转人工 3-已裁决 4-已撤回（举报人本人撤回；active_key 生成列按 status<3 判定，4 亦置空允许再举报）',
`review_task_id`   bigint unsigned null comment '关联审核任务ID（转人工建 review_task 时回填；人审 decide 按此定位并回写 report.status=3-已裁决/result，闭环举报，见 Review-Plan §4.5.3）',
`handler`          bigint unsigned null comment '处理人ID',
`result`           varchar(200)    null comment '裁决结果',
`abuse_flag`       tinyint         not null default 0 comment '诬告标记：0-否 1-是（裁决时判定「明确无依据/明显虚假」才置 1，区别于「证据不足未采纳」；累计达阈值触发反向处罚+降信誉，见 Review-Plan §4.5.3）',
`create_time`      datetime        not null default current_timestamp comment '举报时间',
`update_time`      datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
`active_key`       varchar(64)     generated always as (if(`status` < 3, concat(`reporter_id`, '-', `target_type`, '-', `target_id`), null)) stored comment '活跃举报去重键（status<3 非空，唯一索引兜底并发重复举报；status≥3 置空允许再次举报，MySQL 唯一索引忽略 NULL）',
unique key `uk_active` (`active_key`),
index `idx_target` (`target_type`, `target_id`),
index `idx_review_task_id` (`review_task_id`),
index `idx_status` (`status`),
index `idx_reporter_id` (`reporter_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '举报表（防刷：uk_active 生成列唯一索引兜底并发重复，应用层保留友好提示——同一 reporter_id+target_type+target_id 存在 status<3 的举报即拒绝）';
​
# 2.1 举报证据关联表
create table if not exists `report_evidence`
(
`report_id`   bigint unsigned not null comment '举报ID',
`file_id`     varchar(64)     not null comment '证据文件ID（引用 file_meta.file_id 业务主键，注意与 review_task.biz_id 引用的 file_meta.id 是两套引用）',
`create_time` datetime        not null default current_timestamp comment '关联时间',
primary key (`report_id`, `file_id`),
index `idx_file_id` (`file_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '举报证据关联表（JOIN file_meta 校验证据归属与 safe_flag；管理端展示走 REVIEWER/ADMIN 鉴权预签名直读，不受公开闸口占位影响——审核员是看图裁决的人，详见 Review-Architecture-Development-Plan.md §4.4.5）';
​
# 3. 申诉表
create table if not exists `appeal`
(
`id`            bigint unsigned not null auto_increment primary key comment '申诉ID',
`report_id`     bigint unsigned null comment '关联举报ID',
`punish_id`     bigint unsigned null comment '关联处罚记录ID（punish_log.id）',
`review_task_id` bigint unsigned null comment '关联审核任务ID（机审驳回申诉：无 report/punish 可关联，靠此定位被驳回内容并回写业务状态；report_id/punish_id/review_task_id 三者至少其一非空，应用层校验）',
`appellant_id`  bigint unsigned not null comment '申诉人ID',
`content`       varchar(500)    not null comment '申诉内容',
`status`        tinyint         not null default 0 comment '状态：0-待裁决 1-维持 2-撤销',
`judge_by`      bigint unsigned null comment '裁决审核员ID',
`judge_opinion` varchar(500)    null comment '裁决意见',
`decide_time`   datetime        null comment '裁决落库时间（judge 写入；② 内容级轮次新鲜度判定权威时间源——防同一驳回经 report/punish/review_task 多路径交叉或维持后换路径重复申诉，见 Review-Plan §4.2.3）',
`biz_type`      tinyint         not null comment '申诉目标内容业务类型（提交时由关联列解析落库：report_id→report.target_type 经 §4.5.2 映射、punish_id/review_task_id→其 biz_type；三者交叉须内容身份一致，冲突拒收）',
`biz_id`        bigint unsigned not null comment '申诉目标内容业务ID（同 biz_type 语义）',
`active_key`    varchar(64)     generated always as (if(`status` = 0, concat(`biz_type`, '-', `biz_id`), null)) stored comment '待裁决内容级去重键（status=0 非空，唯一索引兜底同内容经 report/punish/review_task 多路径并发重复申诉；裁决后置空，不阻断新一轮驳回后的新申诉）',
`create_time`   datetime        not null default current_timestamp comment '申诉时间',
`update_time`   datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
unique key `uk_content_active` (`active_key`),
index `idx_appellant_id` (`appellant_id`),
index `idx_content` (`biz_type`, `biz_id`),
index `idx_review_task_id` (`review_task_id`),
index `idx_report_id` (`report_id`),
index `idx_punish_id` (`punish_id`),
index `idx_status` (`status`)
) engine = InnoDB
default charset = utf8mb4 comment = '申诉表';
​
# 4. 违规分累积表
create table if not exists `violation_score`
(
`user_id`            bigint unsigned not null primary key comment '用户ID',
`score`              int             not null default 0 comment '违规分累计',
`last_punish_action` tinyint         not null default 0 comment '上次处罚动作：0-无 1-禁言 2-封禁（只记影响登录态的处罚；punish_log.action 的 3-内容下架/4-警告 仅落流水不改本表，枚举不对齐系有意设计）',
`frozen_until`       datetime        null comment '禁言截止时间',
`update_time`        datetime        not null default current_timestamp on update current_timestamp comment '更新时间'
) engine = InnoDB
default charset = utf8mb4 comment = '违规分累积表（窄状态表：只存当前态，历史流水见 punish_log；最近处罚按 punish_log.user_id 倒序取一条，避免双写不一致）';
​
# 4.1 处罚流水表（append-only，与状态表分离）
create table if not exists `punish_log`
(
`id`             bigint unsigned not null auto_increment primary key comment '处罚ID',
`user_id`        bigint unsigned not null comment '被处罚用户ID',
`action`         tinyint         not null comment '处罚动作：1-禁言 2-封禁 3-内容下架（含机审首发驳回场景，此时内容从未发布）4-警告',
`score`          int             not null default 0 comment '本次处罚记入的违规分值快照（分值规则外置，落行快照使「按 status=1 行重放」确定可算；机审 action=3 通常记 0，不参与登录态阈值判定，见 Review-Plan §4.2.3）',
`reason`         varchar(200)    not null comment '处罚原因',
`biz_type`       tinyint         not null comment '关联违规业务类型（同 review_task.biz_type）',
`biz_id`         bigint unsigned not null comment '关联违规业务ID',
`review_task_id` bigint unsigned null comment '关联审核任务ID（回溯审核链路）',
`expire_time`    datetime        null comment '处罚截止时间（永久处罚为 null）',
`operator_id`    bigint unsigned not null default 0 comment '操作人ID（0-系统）',
`operator_name`  varchar(32)     not null comment '操作人名称（审计冗余）',
`status`         tinyint         not null default 1 comment '状态：1-生效 2-已撤销（申诉成立）',
`revoke_time`    datetime        null comment '撤销时间',
`create_time`    datetime        not null default current_timestamp comment '处罚时间',
index `idx_user_id` (`user_id`),
index `idx_review_task_id` (`review_task_id`),
index `idx_biz` (`biz_type`, `biz_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '处罚流水表（历史行不可删除；申诉裁决=2-撤销 时 status 就地置 2 + violation_score 重算——与 moderation_record 的真 append-only（改判追加新行）区分，本表允许状态列就地更新、行不删）';
​
# 5. 敏感词库版本表（DFA 热更/缓存失效的版本单一来源：scene 级单调递增）
create table if not exists `sensitive_word_version`
(
`scene`       varchar(20)     not null comment '场景：ARTICLE/COMMENT/USERNAME/MESSAGE',
`version`     bigint          not null default 0 comment '该 scene 词库当前版本：任何变更（单条/批量增删改、启停、黑白切换）在同一事务内 +1；Redis Pub 携带此值，消费端 newVersion > curVersion 才重建（防乱序/重复 Pub 旧覆盖新）；缓存 key/留痕快照均取此单一来源，避免行级取版本不一致',
`updated_by`  bigint unsigned not null comment '最后变更人ID（审计）',
`update_time` datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
primary key (`scene`)
) engine = InnoDB
default charset = utf8mb4 comment = '词库版本表（DFA 热更与缓存失效的版本单一来源）';

# 5.1 敏感词库表（DFA 热更新数据源）
create table if not exists `sensitive_word_library`
(
`id`           bigint unsigned not null auto_increment primary key comment '词ID',
`word`         varchar(64)     not null comment '敏感词',
`scene`        varchar(20)     not null comment '场景：ARTICLE/COMMENT/USERNAME/MESSAGE',
`level`        tinyint         not null default 2 comment '级别：1-HIGH 2-MID',
`is_blacklist` tinyint         not null default 1 comment '是否黑名单：0-白名单 1-黑名单',
`status`       tinyint         not null default 1 comment '状态：0-禁用 1-启用',
`updated_by`   bigint unsigned not null comment '修改人ID（审计）',
`version`       bigint          not null default 0 comment '该词最后变更时的 scene 版本快照（只读参考，非版本源；版本源见 sensitive_word_version——行级取版本会因单条变更致同 scene 各行不一致）',
`create_time`  datetime        not null default current_timestamp comment '创建时间',
`update_time`  datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_scene_status` (`scene`, `status`),
unique key `uk_scene_word` (`scene`, `word`)
) engine = InnoDB
default charset = utf8mb4 comment = '敏感词库表（物理删除：唯一键与逻辑删除互斥，配置类小表可全量重建，删除即物理删、updated_by 留审计痕）';
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


# ============================================================
# 模块：litisle-user
# 数据库：litisle_user
# 说明：用户 / 角色 / 用户资料扩展 / OAuth 绑定 / 短信验证码
# ============================================================


-- ============================================================================
-- file-schema.sql
-- 模块：litisle-file
-- 数据库：litisle_file
-- 说明：SeaweedFS 文件元数据 + 引用登记（方案C 秒传共享），含 Seata AT 模式 undo_log
-- 位置：/docker-entrypoint-initdb.d/file-schema.sql（MySQL 首次启动按字母序自动执行）
-- 参考：user-schema.sql（风格保持一致）
-- 设计来源：docs/Sql-Document.md 第八节「litisle-file 模块」
-- ============================================================================

create database if not exists litisle_file
character set utf8mb4
collate utf8mb4_unicode_ci;

use litisle_file;

/* seata 的 undolog表（AT 模式硬性要求：file_ref 登记/解绑需与 user/content 等业务表
同一全局事务（跨服务 AT 分支），每个参与全局事务的库都要有 undo_log） */
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

/* ================= 1. 文件元数据表 ================= */
create table if not exists `file_meta`
(
`id`          bigint unsigned not null auto_increment primary key comment '主键ID',
`file_id`     varchar(64)     not null unique comment 'SeaweedFS 文件ID（业务主键）',
`owner_id`    bigint unsigned not null comment '上传者ID',
`biz_type`    varchar(20)     not null comment '业务类型：AVATAR/COVER/ARTICLE_IMG/LICENSE',
`bucket`      varchar(32)     not null comment 'SeaweedFS collection',
`path`        varchar(255)    not null comment 'Filer 路径',
`mime`        varchar(64)     not null comment 'MIME 类型',
`size`        bigint unsigned not null comment '文件大小（字节）',
`width`       int             null comment '图片宽度',
`height`      int             null comment '图片高度',
`align`       tinyint         null comment '对齐：1-左 2-左偏中 3-中 4-中偏右 5-右（图片专用）',
`access`      tinyint         not null default 0 comment '访问级别：0-私有 1-公开',
`status`      tinyint         not null default 0 comment '上传状态：0-上传中（锁内占位写库）1-上传完成（锁外 put 成功后更新）；put 失败保留 0，由对账任务清理（见 seaweedfs笔记.md §3.4）',
`safe_flag`   tinyint         not null default 2 comment '【预留｜已定接入，暂未开发】违规审核（三态 default-deny）：0-违规（禁止访问）1-正常 2-待检测（默认；放行以检测完成为前提，而非未检出违规，避免异步检测窗口内未检图被放行）；启用链路：status=1 后异步消息 → 病毒/违规检测 → 写本列；getUrl/batchGetUrls 返回前校验（访问闸口，见 seaweedfs笔记.md §3.6/§3.6.3）',
`sha256`      varchar(64)     not null comment 'SHA256 摘要（联合判重键：sha256+size+biz_type，PRIVATE 追加 owner_id 隔离）',
`del_flag`    tinyint         not null default 0 comment '删除标志：仅当 file_ref 无活跃引用才可置 1（方案C，防秒传共享悬空他人引用）；置 1 后不再参与秒传判重',
`create_time` datetime        not null default current_timestamp comment '上传时间',
`update_time` datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
`delete_time` datetime        null comment '软删除时间（del_flag 置 1 时写入，物理清理按此判超 7 天）',
index `idx_owner_biz` (`owner_id`, `biz_type`),
index `idx_dedup` (`sha256`, `size`, `biz_type`, `del_flag`)
) engine = InnoDB
default charset = utf8mb4 comment = '文件元数据表';

/* ================= 2. 文件引用登记表（方案C：秒传使 file_meta 成为多方共享资产，删除=解绑引用，
物理清理前置校验本表，杜绝悬空引用；详见 seaweedfs笔记.md §3.4） ================= */
/* ================= 2. 文件引用登记表（方案C：秒传使 file_meta 成为多方共享资产，删除=解绑引用，
物理清理前置校验本表，杜绝悬空引用；详见 seaweedfs笔记.md §3.4） ================= */
create table if not exists `file_ref`
(
`id`          bigint unsigned not null auto_increment primary key comment '主键ID',
`file_id`     varchar(64)     not null comment '引用的文件ID（file_meta.file_id）',
`biz_type`    varchar(20)     not null comment '引用方业务类型：AVATAR/COVER/ARTICLE_IMG/LICENSE',
`biz_id`      bigint unsigned not null comment '引用方业务主键：如 article_image.id / 换头像场景为 sys_user.id',
`owner_id`    bigint unsigned not null comment '引用归属用户ID（审计与授权依据）',
`create_time` datetime        not null default current_timestamp comment '引用建立时间',
unique key `uk_ref` (`file_id`, `biz_type`, `biz_id`, `owner_id`),
index `idx_biz` (`biz_type`, `biz_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '文件引用登记表：业务方绑定/解绑引用时与业务表同事务登记';


-- **设计原因**：
--
-- - `file_id` 业务主键 + 唯一索引：业务层只持有 fileId，与 SeaweedFS 解耦。
-- - `idx_dedup` 联合判重索引：上传前按 (sha256+size+biz_type) 查重，命中直接复用 fileId 实现秒传；
--   判重限定 biz_type → 命中记录 bucket 必等于本次请求 bucket，URL 永不跨桶坏链（seaweedfs笔记.md §3.4.5）；
--   PRIVATE/LICENSE 去重追加 owner_id（最左前缀匹配 + 回表过滤，后期量大再扩 owner_id 进索引）。
-- - `access` 区分公开/私有：公开文件走 CDN 直链，私有文件走预签名短时效 URL。
-- - `safe_flag` 预留列：违规信息审核（已定接入、暂未开发）。建表一次到位避免将来改表，三态化
--   default-deny（default 2）：公开资源放行以"检测完成"为前提，而非"未检出违规"；启用仅需
--   MQ 异步消费写本列，主链路零改动（设计见 seaweedfs笔记.md §3.6）。
-- - 闸口按曝光面分层放行：高曝光（ARTICLE_IMG/COVER，公开可见）仅 safe_flag=1 放行、2 展示占位图；
--   低曝光（AVATAR / LICENSE，私有仅本人/管理员可见）2 放行、检出违规置 0 并强制刷新。
-- - `align` 放此表为冗余可选：实际对齐信息以 article_image 为准，此处冗余便于文件维度查询。
-- - `file_ref` 引用登记表（方案C）：秒传使一条 file_meta 成为多方共享资产（B 秒传命中 A 的
--   记录并把 fileId 写进业务表），而原有"owner 删除 → 7 天后物理清理"会悬空 B 的所有引用
--   ——7 天缓冲只防"误删恢复"，不防"共享悬空"。规则：
--   ① 业务方绑定/解绑引用时同步写/删 file_ref（与业务表同事务，跨服务场景走 Seata AT）；
--   ② delete 接口仅解绑当前调用方引用；仅当 file_ref 无活跃引用才置 del_flag=1；
--   ③ 对账任务物理清理前置校验 file_ref 无记录（仍有引用则跳过并告警），杜绝悬空引用；
--   ④ 引用可审计：随时可查"谁在引用该文件"，漏登记可定位。



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
`uid`             varchar(16)     not null comment '对外UID（用户间搜索唯一标识，随机生成，永久不变；注册时 UidGenerator 生成，唯一键冲突则重生成）',
`password`        varchar(128)    not null comment '密码（BCrypt）',
`email`           varchar(255)    null comment '邮箱（AES 加密存储，展示脱敏，安全通道解密）',
`phone`           varchar(255)     not null comment '手机号（AES 加密存储，展示脱敏，安全通道解密）',
`phone_hash`      varchar(64)     not null comment '手机号 HMAC-SHA256 摘要（密钥优先独立 hashKey，未配置时由 CryptoAutoConfiguration 从 AES 主密钥确定性派生子密钥）；唯一约束与查重走本列，phone 密文列只管存储（AES 随机 IV 密文不重复，唯一索引必须建在确定性 hash 上）',
`email_hash`      varchar(64)     null comment '邮箱 HMAC-SHA256 摘要（独立密钥）；同 phone_hash',
`avatar_file_id`  varchar(64)     null comment '头像文件ID（SeaweedFS fileId，URL 运行时通过 FileService 批量反查拼接；默认头像走代码常量兜底，不落库）',
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
-- - **手机号联合唯一索引 `uk_phone_hash_del(phone_hash, del_flag)` 设计说明**：
--
-- - **问题**：若 phone 建单列唯一索引，用户注销后（`del_flag=1`）手机号仍占位，导致同一手机号无法重新注册。
-- - **方案**：联合唯一索引建在 `(phone_hash, del_flag)` 上，活跃用户与已注销用户是不同的组合键，互不冲突。
-- - **为什么唯一索引建在 hash 列而非密文列**：AES 加密采用随机 IV，同一手机号每次加密密文都不同，
--   对密文建唯一索引永远撞不上 → 查重/防重复注册失效；HMAC-SHA256 是确定性摘要（同输入同输出），
--   查重等值匹配走 hash 列，密文列保留加密收益，二者职责分离（密态存储标准做法）。
-- - **非空语义**：phone 为 not null（注册必填），phone_hash 随注册/换绑由 CryptoService 同步写入；email 可为空，email_hash 为 NULL 时不参与唯一判断（MySQL 中 NULL 不冲突），允许多个无邮箱用户共存。
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
# 初始化示例用户数据（无邮箱；本地开发种子数据）
# 密码统一为 bcrypt 加密的 123
# 哈希：$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2
#
# 手机号加密列种子策略（本地开发简化）：
# - 正式数据一律走应用层 CryptoService：phone 存 AES-GCM 密文、phone_hash 存 HMAC-SHA256
#   （随机 IV 密文每次不同，无法在 SQL 中静态预生成真实值）
# - 种子简化：phone 暂存明文，phone_hash 用 MySQL 端 SHA2(phone,256) 占位（64 位 hex，长度合规）
# - 已知局限：种子 hash 与应用 HMAC 不一致 → 种子用户不可走手机号登录/查重路径；
#   测手机号登录请走注册流程造数，种子账号用用户名+密码登录
# - 本地密钥见 yml security.crypto.key（仅本地开发；生产走 LITISLE_CRYPTO_KEY 环境变量注入）
# - uid 为 not null：应用注册时由 UidGenerator 随机生成；种子数据用固定值便于测试与互搜
# ============================================================

-- 1. READER 读者
INSERT INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`,`avatar_color`)
VALUES (1001, 'reader_test', 'U1001READER01', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', 'd1FyZ+b0E1E/eFmhtQWhkRZ7YDGMQODobmsRB4aPte7quJGt2lV6', '92ee487f83ae8784872d86f8c0d579b781d7956a60ebf95305197edc0ba3cb35', '热爱阅读的普通用户', 1, 0, NOW(),0);

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1001, `id` FROM `sys_role` WHERE `role_code` = 'READER';

INSERT INTO `sys_user_profile` (`user_id`)
VALUES (1001);

-- 2. AUTHOR 作家
INSERT INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`, `avatar_color`)
VALUES (1002, 'author_test', 'U1002AUTHOR01', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', 'rtIAtr//RH0fX0HKS+2l22spZsei7h4E4PxohDvooz+0h4PhsWWb', 'f41c3968090234792656a3fd53ad3a6436286cbd531fd58692cc1fab75c38780', '专注科幻小说创作', 1, 0, NOW(),1);

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1002, `id` FROM `sys_role` WHERE `role_code` = 'AUTHOR';

INSERT INTO `sys_user_profile` (`user_id`, `pen_name`, `creation_dir`, `masterpiece`)
VALUES (1002, '红袖添香', '科幻 / 奇幻', '《星海归途》系列');

-- 3. LEGACY_AUTHOR 纪念作家
INSERT INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`, `avatar_color`)
VALUES (1003, 'legacy_test', 'U1003LEGACY01', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '4cPacxVojqKfv+0dP5qMjmnkvPJM63euWFyoa0qUr6pYUNFl9EYU', '61bcee3ebe69365829ad5662522be825230d23939fe5189e2a1161792f9acc80', '已故文学巨匠，作品永久珍藏', 1, 0, NOW(),2);

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1003, `id` FROM `sys_role` WHERE `role_code` = 'LEGACY_AUTHOR';

INSERT INTO `sys_user_profile` (`user_id`, `pen_name`, `creation_dir`, `masterpiece`)
VALUES (1003, '老舍', '现实主义文学', '《骆驼祥子》《茶馆》');

-- 4. REVIEWER 审核员
INSERT INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1004, 'reviewer_test', 'U1004REVIEW01', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', 'MmhwRQz5Y2phTeQWof43m/B8woUk3AZkXRB2CRBts9bnwYJ3ekOx', '0e40f2a460b37e4c2563b649607abbd8d8289c5b5aea5dc36e99a96623c5a602', '内容安全审核专员', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1004, `id` FROM `sys_role` WHERE `role_code` = 'REVIEWER';

INSERT INTO `sys_user_profile` (`user_id`)
VALUES (1004);

-- 5. ADMIN 管理员
INSERT INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1005, 'admin_test', 'U1005ADMIN001', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', 'hLqQug7IZ2ArhGrKNBBX+DuAONUUqG7gwoov2ugcWSJAUMsofOcJ', 'cd21bdf19ed271a099031dd89d2ac78f23b60c4900b18b8365bbb92a142e66e7', '负责平台日常运营管理', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1005, `id` FROM `sys_role` WHERE `role_code` = 'ADMIN';

INSERT INTO `sys_user_profile` (`user_id`)
VALUES (1005);

-- 6. SUPER_ADMIN 超级管理员
INSERT INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1006, 'super_admin_test', 'U1006SADMIN01', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', 'xKHNh2in4lCXBiZtGakTnADPBJswk8k3f351UfkkeDylQWbV/1oJ', 'd161009f4c0a655d7aedd00ab559955ee2d0bb6b343efc4e637a3aad2d76b711', '系统最高权限拥有者', 1, 0, NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1006, `id` FROM `sys_role` WHERE `role_code` = 'SUPER_ADMIN';

INSERT INTO `sys_user_profile` (`user_id`)
VALUES (1006);

/*
**设计原因**：

- `sys_user_profile` 与 `sys_user` 拆分：主表保留高频鉴权字段，扩展资料单独表，避免列过多影响查询效率。
- `avatar_color` 用枚举而非字符串：0/1/2 三态对应无标识/金色/白色，与设计文档视觉区分一致，查询性能好。
- OAuth 绑定表对 `(provider, open_id)` 建唯一索引：保证一个第三方账号只能绑定一个文屿账号。
- `phone` 加密存储：符合设计文档"隐私脱敏"规范，明文查看需临时解密 + 审计。

**手机号联合唯一索引 `uk_phone_hash_del(phone_hash, del_flag)` 设计说明**：

- **问题**：若 phone 建单列唯一索引，用户注销后（`del_flag=1`）手机号仍占位，导致同一手机号无法重新注册。
- **方案**：联合唯一索引建在 `(phone_hash, del_flag)` 上，活跃用户与已注销用户是不同的组合键，互不冲突。
- **为什么唯一索引建在 hash 列而非密文列**：AES 加密采用随机 IV，同一手机号每次加密密文都不同，对密文建唯一索引永远撞不上 → 查重/防重复注册失效；HMAC-SHA256 是确定性摘要（同输入同输出），查重等值匹配走 hash 列，密文列保留加密收益。
- **非空语义**：phone 为 not null（注册必填）；email 可为空，email_hash 为 NULL 时不参与唯一判断。
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
- **审计**：所有删除操作通过 `litisle_common` 的 `@AuditLog` AOP 切面自动记录审计日志（操作人、操作类型、请求参数、执行时间），满足安全审计需求。
- **app_id 预留**：唯一索引 `(provider, app_id, open_id)` 预留 `app_id` 字段，默认值 `'default'`。当前单应用无影响，未来接入多个微信/QQ 应用时，同一 provider 下不同 app 的 openId 不会冲突。
  */

设计原因：

review_task 不加 del_flag：审核记录不可删除（设计文档审计规范），所有变更走 status 流转。

review_task 唯一键 uk_biz_scene(biz_type, biz_id, scene) + 幂等 upsert 建任务：一个内容主体
（同一 biz_type+biz_id+scene）只保留一行任务——否则编辑重发/举报复审/超时补偿重发会堆出
同键多行，消费端按此键定位歧义复发。幂等语义：命中已有行则复用同行（重置 status=0、
更新 content_hash、retry_count 归零），旧任务未终态时先追加一条 moderation_record 标记
superseded 再重置（并清空 assignee/claim_time/decided_by/opinion/decide_time，防旧认领人残留；同时清空机审证据列
mod_verdict=0/layer1_hit=0/hit_words=null/layer2_risk=0——重置仅因内容变更或终态触发，旧证据属
旧内容，不清则人审队列按 mod_verdict/layer2_risk 展示与排序时张冠李戴）；历史链路靠
moderation_record（append-only）保留，不需多行任务——
与「review_task 是状态机、moderation_record 是流水」职责分离口径一致。
重置例外（多举报不打断在审）：仅 content_hash 变化（编辑重发）或已有任务终态（status∈{1,3,4}）才重置，
重置目标态按场景分列——发布场景置 0、*_RE_REVIEW 复审场景置 2（直进人审）；命中行 content_hash 未变
且非终态（status∈{0,2}）时只复用不重置（保留 status/assignee，仅回填 report.review_task_id），
防同内容第二条举报把已认领在审任务打回 0、清空 assignee。多举报同任务裁决：decide 回写
`update report set status=3 where review_task_id=? and status<3` 一次闭环全部关联举报。
（该唯一键覆盖 idx_biz 前缀，原 idx_biz 普通索引已移除避免冗余。）

scene 场景枚举扩展与存量迁移：新增 ARTICLE_RE_REVIEW/COMMENT_RE_REVIEW/MESSAGE_RE_REVIEW 三个
举报复审场景（与发布场景分列，防举报转人工 upsert 撞已终态发布任务被重置）。scene 为 not null
无默认值：若存量表已有数据，ALTER 须先加可空列 → 按 biz_type 回填默认场景 → 再改 not null，
禁直接加 not null 无默认值列（存量迁移会失败）。

统一建任务规则：凡走异步机审的内容（文本/图片/私信）一律建 review_task——否则 SUSPICIOUS
进不了人审队列、申诉也无 appeal.review_task_id 可挂；图片 biz_type=6（biz_id=file_meta.id、
scene=IMAGE、content_hash=文件 sha256）。唯一例外 USERNAME 同步拒绝不建任务，仅落
moderation_record（biz_id=0），避免每次注册失败堆一行废任务。

moderation_record 与 review_task 职责分离：review_task 是任务状态机（assignee/status 可变更），
moderation_record 是审核流水（append-only，每次机审/人审/申诉改判追加一行，不 UPDATE）。
同一任务多次审核形成多行流水，链路可回溯；idx_hash 支撑审核缓存查重兜底（Redis miss 后查表，
数据丢失不重复烧模型费）；命中词/凭证/理由/场景（scene）四列自包含，单行即可对外出具审核凭证无需 JOIN
（设计见 Review-Architecture-Development-Plan.md §4.4.1）。trace_id 与 provider_trace_id 分列：
前者是网关→MQ→消费端内部链路追踪 ID（排障），后者是云厂商/LLM 对外合规凭证（出证）；
hit_words 用 text 与 review_task 同纪律，留痕写入宁存不下不可丢；
idx_hash 三列 (content_hash, scene, id)，scene 前置防跨场景误复用，id 自增即时间序取最近一次结论免
filesort；provider/wordlib_ver/model_version 列支撑兜底按厂商/词库/模型版本比对（防 onnx→aliyun、
词库热更、同 provider 模型/prompt 升级后复用旧结论）。

appeal 表补 review_task_id 列：机审驳回（REJECT）场景既无 report 也无 punish，
申诉记录靠该列定位被驳回内容并回写业务状态（见 Review-Architecture-Development-Plan.md §4.2.3）。
防重复申诉走应用层校验：同一 review_task_id/report_id/punish_id 存在任意状态申诉即拒绝再次发起
（含已裁决维持，防维持后反复申诉；review_task_id 可空不便建唯一索引，查询走既有
idx_review_task_id；@Idempotent 只防请求级重复）。

sensitive_word_library 同时支持黑/白名单与多场景：对应设计文档"评论加载白名单 / 用户名不加载白名单"的差异化策略，按 scene 过滤加载。IMAGE/ARTICLE_IMG_RE_REVIEW 等图片场景不走 DFA：
WordLibraryLoader 仅加载文本场景（ARTICLE/COMMENT/USERNAME/MESSAGE），图片任务按 scene
查词库为空属预期，跳过不报错。

DFA 闸门跨服务部署：DFA 门面与 WordLibraryLoader 下沉共享模块（litisle-api），发布链路在
各业务服务本地执行免网络；词库 SSOT 仍在 sensitive_word_library 表，变更经 Redis Pub +
version 乐观比对广播各实例重建（见 Review-Architecture-Development-Plan.md §4.1/§4.3）；
新实例冷启动走 Redis 全量快照优先（词库热更时顺带重写快照 key），miss 时直连本库只读
词表兑底——业务方「不跨库直查」约定的唯一特例（配置类小表：无 join、无写竞争、只读）。

violation_score 用 user_id 作主键：一用户一行，分数累加用 SQL 原子自增（set score = score + Δ）
与状态列条件更新，不引入 version 乐观锁——本表为窄状态表，重算依据在 punish_log 流水（每行 Δ
见 punish_log.score 快照），乐观锁属多余复杂度；Redis 计数器仅作人审队列/预警的快速视图
（如违规分即将触顶提醒）。重放口径两作用域分列：① 展示/预警 score=全部 status=1 生效行累计
（含机审 action=3）；② 自动禁言/封禁阈值判定=只重放 action∈{1,2}（影响登录态）的 status=1
行，机审 action=3 不计入——避免 Redis 与 DB 双写漂移导致误禁，且机审误判在申诉裁决前不触发
真实封禁（与「append-only 流水即重算依据」「机审不驱动自动处罚」口径一致）。

author_apply 独立于 sys_user：申请记录需保留历史，多次申请形成流水。

# ============================================================
# 初始化种子数据（review-seed.sql，本地开发/联调用）
# 位置：/docker-entrypoint-initdb.d/review-seed.sql（MySQL 首次启动按字母序自动执行）
# 顺序：review-schema.sql（建表）→ review-seed.sql（本段）→ seed-words-article.sql（词库本体）
# 依赖：样本引用 user-schema.sql 的测试用户 1001~1006（无外键约束，执行顺序不敏感）
# 注意：/docker-entrypoint-initdb.d 仅在数据卷首次初始化时执行；存量环境请手动执行一次
# ============================================================

use litisle_review;

-- 1. 词库版本基线（四文本场景，版本 0 = 空词库；词库本体由 seed-words-article.sql 灌入并 bump 版本）
-- insert ignore：seed-words-article.sql 稍后 upsert ARTICLE 时自然 +1，不冲突
insert ignore into `sensitive_word_version` (`scene`, `version`, `updated_by`) values
('ARTICLE',  0, 0),
('COMMENT',  0, 0),
('USERNAME', 0, 0),
('MESSAGE',  0, 0);

-- 2. 作家申请示例（READER 1001 提交申请，待审——供审核员队列联调）
INSERT INTO `author_apply` (`id`, `user_id`, `pen_name`, `creation_dir`, `masterpiece`, `status`, `create_time`)
VALUES (1, 1001, '追风少年', '都市 / 青春', '《晚风与海》短篇集', 0, NOW());

-- 3. 举报示例（两条状态：待处理 + 转人工）
-- 3.1 READER 1001 举报用户 1003（target_type=3-用户，target_id 即用户ID）；active_key 生成列自动填 '1001-3-1003'
INSERT INTO `report` (`id`, `reporter_id`, `target_type`, `target_id`, `reason`, `status`, `create_time`)
VALUES (1, 1001, 3, 1003, '该用户多次发布引战言论', 0, NOW());
-- 3.2 AUTHOR 1002 举报评论 1 转人工（status=2，回填 review_task_id=1 关联任务 4）
-- 供联调人审 decide 回写闭环：update report set status=3 where review_task_id=1 and status<3
INSERT INTO `report` (`id`, `reporter_id`, `target_type`, `target_id`, `reason`, `status`, `review_task_id`, `create_time`)
VALUES (2, 1002, 2, 1, '评论内容涉嫌赌博引流', 2, 1, NOW());

-- 4. 审核任务示例（评论 SUSPICIOUS 转人审——供人审队列/认领/裁决链路联调）
-- biz_id=1 为占位评论ID（联调时以内容服务实际评论为准）；content_hash 为占位指纹（64位hex）
INSERT INTO `review_task` (`id`, `biz_type`, `biz_id`, `scene`, `layer1_hit`, `hit_words`, `layer2_risk`,
                           `status`, `mod_verdict`, `content_hash`, `create_time`)
VALUES (1, 2, 1, 'COMMENT', 2, '赌博', 2,
        2, 3, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', NOW());

-- 5. 机审留痕示例（与任务 1 对应的 DFA 审核记录，append-only）
-- mod_type=1-关键词DFA；verdict=3-SUSPICIOUS；operator 0-系统机审；wordlib_ver=0 对应空词库基线
INSERT INTO `moderation_record` (`id`, `mod_type`, `biz_type`, `biz_id`, `review_task_id`, `scene`,
                                 `content_hash`, `wordlib_ver`, `verdict`, `category`, `hit_words`, `reason`,
                                 `operator_id`, `operator_name`, `cost_ms`, `create_time`)
VALUES (1, 1, 2, 1, 1, 'COMMENT',
        '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 0, 3, 'abuse', '赌博', 'DFA命中赌博类敏感词',
        0, 'system', 3, NOW());

-- 6. 处罚流水示例（ADMIN 1005 对用户 1003 落一次警告，生效中）
-- biz_type 走 ModerationBizTypeEnum（同 review_task.biz_type）：2=评论，挂任务 1——勿用 TargetTypeEnum 的 3=用户（两套枚举错位）
-- action=4-警告：仅落流水不改 violation_score.last_punish_action（枚举不对齐系有意设计）
INSERT INTO `punish_log` (`id`, `user_id`, `action`, `score`, `reason`, `biz_type`, `biz_id`, `review_task_id`,
                          `operator_id`, `operator_name`, `status`, `create_time`)
VALUES (1, 1003, 4, 1, '评论涉嫌违规，首次违规警告', 2, 1, 1,
        1005, 'admin_test', 1, NOW());

-- 7. 违规分累积示例（用户 1003 累计 1 分；警告不影响登录态，last_punish_action 保持 0-无）
INSERT INTO `violation_score` (`user_id`, `score`, `last_punish_action`)
VALUES (1003, 1, 0);

-- 8. 申诉示例（已裁决-维持：挂任务 1 与处罚 1，供申诉列表/详情链路联调）
-- status=1-维持时 active_key 生成列为 NULL，不占用内容级去重键；裁决人 ADMIN 1005（原任务尚未裁决无回避冲突）
-- biz_type 走 ModerationBizTypeEnum：2=评论，与 punish_log.biz_type 同口径
INSERT INTO `appeal` (`id`, `report_id`, `punish_id`, `review_task_id`, `appellant_id`, `content`, `status`,
                      `judge_by`, `judge_opinion`, `decide_time`, `biz_type`, `biz_id`, `create_time`)
VALUES (1, NULL, 1, 1, 1003, '评论内容被误判，未涉及赌博仅为讨论剧情', 1,
        1005, '证据不足，维持原判', NOW(), 2, 1, NOW());

-- 9. 词库测试词（每文本场景 1 条，防 DFA 闸门装载失败拒启：词库表为空时 TableWordLibraryLoader
--    报「词库装载失败且无可用本地快照」并禁止放行；正式词库由管理端维护/seed-words-article.sql 灌入）
--    level=2-MID：命中走 LLM 复审而非同步拒绝，避免联调时误拦用户名注册/私信发送；
--    同词跨场景复用（uk_scene_word 以 scene+word 为键，同词各场景独立生效）
INSERT INTO `sensitive_word_library` (`word`, `scene`, `level`, `is_blacklist`, `status`, `updated_by`, `version`)
VALUES
('测试敏感词_文章',   'ARTICLE',  2, 1, 1, 0, 1),
('测试敏感词_评论',   'COMMENT',  2, 1, 1, 0, 1),
('测试敏感词_用户名', 'USERNAME', 2, 1, 1, 0, 1),
('测试敏感词_私信',   'MESSAGE',  2, 1, 1, 0, 1);

-- 测试词落库后各场景版本 bump 到 1（基线 insert ignore 已建 version=0 行，此处 upsert +1；
-- 热更/快照语义要求版本 >0：消费端 newVersion > curVersion 才重建；
-- ARTICLE 随后由 seed-words-article.sql 灌入正式词库继续单调 +1，不冲突）
INSERT INTO `sensitive_word_version` (`scene`, `version`, `updated_by`)
VALUES
('ARTICLE',  1, 0),
('COMMENT',  1, 0),
('USERNAME', 1, 0),
('MESSAGE',  1, 0)
ON DUPLICATE KEY UPDATE `version` = `version` + 1, `updated_by` = VALUES(`updated_by`);

-- 种子联调链路说明：
-- 举报(2条/2态) → review_task(人审队列/认领/裁决) → moderation_record(机审留痕) →
-- appeal(已维持) → punish_log(警告) → violation_score(1分)，全链开箱可测；
-- report_evidence 不种数据：依赖 file 模块真实文件，占位无意义。

## 五、litisle-ai 模块（数据库：litisle_ai）

redis+mysql结合处理token相关。定时任务清理之前的token记录数据

```sql
# ============================================================
# 模块：litisle-ai
# 数据库：litisle_ai
# 说明：小月会话 / 消息 / 已保存会话 / Token 用量
# ============================================================

create database if not exists litisle_ai
character set utf8mb4
collate utf8mb4_unicode_ci;

use litisle_ai;

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
`prompt_tokens`     int      null comment '本条消息输入 token 消耗（逐条记录，与 ai_token_usage 日汇总对账，防统计口径漂移）',
`completion_tokens` int      null comment '本条消息输出 token 消耗（同上）',
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

## 六、litisle-payment 模块（数据库：litisle_payment）

```sql
# ============================================================
# 模块：litisle-payment
# 数据库：litisle_payment
# 说明：VIP 订单 / 小硬币账户 / 流水 / 赞赏 / 提现 / Seata
# ============================================================

create database if not exists litisle_payment
character set utf8mb4
collate utf8mb4_unicode_ci;

use litisle_payment;

# 1. VIP 订单表
create table if not exists `vip_order`
(
`id`           bigint unsigned not null auto_increment primary key comment '订单ID',
`order_no`     varchar(32)     not null comment '订单号（唯一，支付回调幂等/第三方对账用，与 shop_order.order_no 对齐）',
`user_id`      bigint unsigned not null comment '用户ID',
`plan`         tinyint         not null comment '套餐：1-月度 2-季度 3-年度 4-三年',
`gift_coin`    int             not null default 0 comment '赠送小硬币数',
`amount`       decimal(10,2)   not null comment '支付金额（元）',
`expire_date`  datetime        not null comment 'VIP 到期时间',
`status`       tinyint         not null default 0 comment '状态：0-待支付 1-已支付 2-已取消',
`del_flag`     tinyint         not null default 0 comment '删除标志',
`create_time`  datetime        not null default current_timestamp comment '创建时间',
`update_time`  datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
unique key `uk_order_no` (`order_no`),
index `idx_user_id` (`user_id`),
index `idx_status` (`status`)
) engine = InnoDB
default charset = utf8mb4 comment = 'VIP 订单表';

# 2. 小硬币账户表（BIGINT 微币防精度丢失）
create table if not exists `coin_account`
(
`user_id`        bigint unsigned not null primary key comment '用户ID',
`balance_micro`  bigint          not null default 0 comment '微币余额（1小硬币=100微币，BIGINT 防精度）',
`version`        int             not null default 0 comment '乐观锁版本号',
`update_time`    datetime        not null default current_timestamp on update current_timestamp comment '更新时间'
) engine = InnoDB
default charset = utf8mb4 comment = '小硬币账户表';

# 3. 流水表（append-only 不可改）
create table if not exists `coin_transaction`
(
`id`          bigint unsigned not null auto_increment primary key comment '流水ID',
`user_id`     bigint unsigned not null comment '用户ID',
`tx_type`     tinyint         not null comment '类型：1-充值 2-赞赏支出 3-赞赏收入 4-提现 5-VIP赠送 6-商城消费 7-商城退款 8-提现驳回退币（穷举所有资金变动场景，任何一种变动无类型即资金链路漏洞）',
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

## 七、litisle-notification 模块（数据库：litisle_notification）

```sql
# ============================================================
# 模块：litisle-notification
# 数据库：litisle_notification
# 说明：通知 / 通知设置
# ============================================================

create database if not exists litisle_notification
character set utf8mb4
collate utf8mb4_unicode_ci;

use litisle_notification;

# 1. 通知表
create table if not exists `notification`
(
`id`         bigint unsigned not null auto_increment primary key comment '通知ID',
`user_id`    bigint unsigned not null comment '接收人ID',
`type`       tinyint         not null comment '类型：1-社交 2-审核 3-举报申诉 4-私信 5-系统',
`biz_type`   varchar(20)     null comment '业务类型：LIKE/COMMENT/FOLLOW/REVIEW/REPORT等',
`biz_id`     bigint unsigned null comment '业务ID',
`dedup_key`  varchar(64)     null comment '幂等去重键（生产者拼：举报人通知=`R-{reportId}-{reviewTaskId}`、作者通知=`A-{reviewTaskId}-{contentHash}-{verdict}`（含 contentHash：同一 review_task 被编辑重发复用、再次 REJECT 时 contentHash 已变可正常通知，仅吞同事件重投），使申诉改判 PASS 与首次 REJECT 不互相误伤；社交类可空不限重），唯一索引忽略 NULL 兜底 MQ at-least-once 重投重复通知',
`title`      varchar(100)    not null comment '通知标题',
`content`    varchar(500)    not null comment '通知内容',
`is_read`    tinyint         not null default 0 comment '已读：0-未读 1-已读',
`create_time` datetime       not null default current_timestamp comment '创建时间',
unique key `uk_dedup` (`dedup_key`),
index `idx_user_read` (`user_id`, `is_read`),
index `idx_create_time` (`create_time`)
) engine = InnoDB
default charset = utf8mb4 comment = '通知表（审核/举报类通知按 dedup_key 唯一索引幂等，防 MQ 重投重复通知）';
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

## 八、litisle-file 模块（数据库：litisle_file）

```sql
# ============================================================
# 模块：litisle-file
# 数据库：litisle_file
# 说明：SeaweedFS 文件元数据
# ============================================================

create database if not exists litisle_file
character set utf8mb4
collate utf8mb4_unicode_ci;

use litisle_file;

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
`status`     tinyint         not null default 0 comment '上传状态：0-上传中（锁内占位写库）1-上传完成（锁外 put 成功后更新）；put 失败保留 0，由对账任务清理（见 seaweedfs笔记.md §3.4）',
`safe_flag`  tinyint         not null default 2 comment '违规审核（四态 default-deny）：0-违规（禁止访问）1-正常 2-待检测（默认；放行以检测完成为前提，而非未检出违规，避免异步检测窗口内未检图被放行）3-存疑人审（机审 SUSPICIOUS / 重试耗尽转人审，闸口不放行）；写本列链路：status=1 后异步消息 → 机审三分态 → PASS写1/REJECT写0/SUSPICIOUS写3；getUrl/batchGetUrls 返回前校验（访问闸口，见 seaweedfs笔记.md §3.6/§3.6.3）',
`sha256`     varchar(64)     not null comment 'SHA256 摘要（联合判重键：sha256+size+biz_type，PRIVATE 追加 owner_id 隔离）',
`del_flag`   tinyint         not null default 0 comment '删除标志：仅当 file_ref 无活跃引用才可置 1（方案C，防秒传共享悬空他人引用）；置 1 后不再参与秒传判重',
`create_time` datetime       not null default current_timestamp comment '上传时间',
`update_time` datetime       not null default current_timestamp on update current_timestamp comment '更新时间',
`delete_time` datetime       null comment '软删除时间（del_flag 置 1 时写入，物理清理按此判超 7 天；违规隔离图例外：quarantine/ 前缀由 moderation 专属清理任务按 quarantine.retention-days=10 处理，本清理任务跳过，防 7 天早于申诉窗口物理删除）',
index `idx_owner_biz` (`owner_id`, `biz_type`),
index `idx_dedup` (`sha256`, `size`, `biz_type`, `del_flag`)
) engine = InnoDB
default charset = utf8mb4 comment = '文件元数据表';

# 2. 文件引用登记表（方案C：秒传使 file_meta 成为多方共享资产，删除=解绑引用，
#    物理清理前置校验本表，杜绝悬空引用；详见 seaweedfs笔记.md §3.4）
create table if not exists `file_ref`
(
    `id`          bigint unsigned not null auto_increment primary key comment '主键ID',
    `file_id`     varchar(64)     not null comment '引用的文件ID（file_meta.file_id）',
    `biz_type`    varchar(20)     not null comment '引用方业务类型：AVATAR/COVER/ARTICLE_IMG/LICENSE',
    `biz_id`      bigint unsigned not null comment '引用方业务主键：如 article_image.id / 换头像场景为 sys_user.id',
    `owner_id`    bigint unsigned not null comment '引用归属用户ID（审计与授权依据）',
    `create_time` datetime        not null default current_timestamp comment '引用建立时间',
    unique key `uk_ref` (`file_id`, `biz_type`, `biz_id`, `owner_id`),
    index `idx_biz` (`biz_type`, `biz_id`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '文件引用登记表：业务方绑定/解绑引用时与业务表同事务登记';

```

设计原因：

file_id 业务主键 + 唯一索引：业务层只持有 fileId，与 SeaweedFS 解耦。

idx_dedup 联合判重索引：上传前按 (sha256+size+biz_type) 查重，命中直接复用 fileId 实现秒传；
判重限定 biz_type → 命中记录 bucket 必等于本次请求 bucket，URL 永不跨桶坏链（seaweedfs笔记.md §3.4.5）；
PRIVATE/LICENSE 去重追加 owner_id（最左前缀匹配 + 回表过滤，后期量大再扩 owner_id 进索引）。

access 区分公开/私有：公开文件走 CDN 直链，私有文件走预签名短时效 URL。

safe_flag 审核闸口列：违规信息审核四态化（0-违规 1-正常 2-待检测 3-存疑人审）。建表一次到位
避免将来改表，default-deny（default 2）：公开资源放行以"检测完成"为前提，而非"未检出违规"；
机审三分态写本列，SUSPICIOUS 与重试耗尽均落 3 转人审，绝不自动放行
（设计见 Review-Architecture-Development-Plan.md §4.2.2/§4.2.4、seaweedfs笔记.md §3.6）。

闸口按曝光面分层放行：高曝光（AVATAR/COVER/ARTICLE_IMG，公开可见）仅 safe_flag=1 放行、
2/3 展示占位图；LICENSE 等私有面按 owner 鉴权放行，不受闸口影响。AVATAR 由原"低曝光 2 放行"
调整为高曝光仅 1 放行：头像公开展示且上传即审，审核完成前不放行。
REJECT 图对称恢复：申诉改判 PASS 时 safe_flag 回 1 + 对象从 quarantine/ 移回原前缀（del_flag
全程保持 0，REJECT 不再置 1，不破坏 file_ref 不变式），并前置校验 file_ref 仍有活跃引用；
同时失效该图审核缓存与拒传黑名单（见 Review-Plan §4.2.3）；隔离保留期取申诉窗口 + 缓冲，
防申诉未裁决对象先被清理。

align 放此表为冗余可选：实际对齐信息以 article_image 为准，此处冗余便于文件维度查询。

file_ref 引用登记表（方案C）：秒传使一条 file_meta 成为多方共享资产（B 秒传命中 A 的
记录并把 fileId 写进业务表），而原有"owner 删除 → 7 天后物理清理"会悬空 B 的所有引用
——7 天缓冲只防"误删恢复"，不防"共享悬空"。规则：
① 业务方绑定/解绑引用时同步写/删 file_ref（与业务表同事务）；
② delete 接口仅解绑当前调用方引用；仅当 file_ref 无活跃引用才置 del_flag=1；
③ 对账任务物理清理前置校验 file_ref 无记录（仍有引用则跳过并告警），杜绝悬空引用；
④ 引用可审计：随时可查"谁在引用该文件"，漏登记可定位。

## 九、litisle-search 模块

该模块不建 MySQL 表，数据全部落 Elasticsearch：

- `article_idx`（标题^4 / 标签^3 / 摘要^2 / 正文 / dense_vector 向量字段）
- `book_idx`（书名 / 简介 / 章节标题）
- `user_idx`（笔名 / 简介 / 作品标签聚合）

索引由 MQ 事件（content.published / social.liked）异步增量同步，无需关系表。

## 十、litisle-shop 模块（数据库：litisle_shop）

```sql
-- =============================================
-- litisle_shop 库：积分商城 + 订单 + 运单
-- =============================================
create database if not exists litisle_shop
default character set utf8mb4
default collate utf8mb4_unicode_ci;

use litisle_shop;

-- 1. 商品表
create table if not exists shop_product
(
id              bigint unsigned not null auto_increment comment '商品ID',
name            varchar(100)    not null comment '商品名称',
description     varchar(500)    default '' comment '商品描述',
price_coins     bigint          not null comment '价格（小硬币，单位：微币 BIGINT）',
total_stock     int             not null comment '总库存',
stock           int             not null comment '剩余库存',
image_file_id   varchar(64)     default '' comment '商品图片文件ID（file_meta.file_id），URL 运行时经 FileService 反查拼接，永不落库（过 safe_flag 违规图闸口，文件迁移不坏链）',
sale_start_time datetime        not null comment '开售时间',
sale_end_time   datetime        not null comment '截止时间',
status          tinyint         not null default 0 comment '状态：0-下架 1-上架',
version         int             not null default 0 comment '乐观锁（库存扣减并发）',
del_flag        tinyint         not null default 0 comment '逻辑删除：0-正常 1-删除',
create_time     datetime        not null default current_timestamp,
update_time     datetime        not null default current_timestamp on update current_timestamp,
primary key (id),
index idx_status (status, sale_start_time, sale_end_time)
) engine = InnoDB default charset = utf8mb4 comment = '积分商城商品表';
​
-- 2. 订单表
create table if not exists shop_order
(
id              bigint unsigned not null auto_increment comment '订单ID',
order_no        varchar(32)     not null comment '订单号（唯一）',
user_id         bigint unsigned not null comment '用户ID',
product_id      bigint unsigned not null comment '商品ID',
quantity        int             not null default 1 comment '数量',
total_price     bigint          not null comment '总价（微币）',
status          tinyint         not null default 0 comment '状态：0-待支付 1-已支付 2-已发货 3-已送达 4-已完成 5-已取消 6-已退款',
paid_time       datetime        default null comment '支付时间',
cancel_reason   varchar(200)    default '' comment '取消原因',
cancel_time     datetime        default null comment '取消时间',
del_flag        tinyint         not null default 0 comment '逻辑删除',
create_time     datetime        not null default current_timestamp,
update_time     datetime        not null default current_timestamp on update current_timestamp,
primary key (id),
unique key uk_order_no (order_no),
index idx_user_id (user_id),
index idx_status (status, create_time)
) engine = InnoDB default charset = utf8mb4 comment = '积分商城订单表';
​
-- 3. 运单表
create table if not exists shipping
(
id               bigint unsigned not null auto_increment comment '运单ID',
order_id         bigint unsigned not null comment '订单ID',
express_company  varchar(50)    default '' comment '快递公司',
express_no       varchar(50)    default '' comment '快递单号',
receiver_name    varchar(50)    not null comment '收件人姓名（展示脱敏：张*三）',
receiver_phone   varchar(255)   not null comment '收件人电话（AES 加密存储，展示脱敏 138****1234，发货打款场景走安全通道解密；敏感数据保护标准与 user 模块手机号一致）',
receiver_address varchar(300)   not null comment '收件人地址（展示脱敏，完整地址仅发货场景解密）',
status           tinyint        not null default 0 comment '状态：0-待发货 1-已发货 2-已签收',
shipped_time     datetime       default null comment '发货时间',
signed_time      datetime       default null comment '签收时间',
create_time      datetime       not null default current_timestamp,
update_time      datetime       not null default current_timestamp on update current_timestamp,
primary key (id),
unique key uk_order_id (order_id),
index idx_express (express_company, express_no)
) engine = InnoDB default charset = utf8mb4 comment = '运单表';
```
```
