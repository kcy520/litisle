-- =============================================================================
-- user 模块种子数据（原 user-schema.sql「角色/示例用户」段 + zz-seed-feed-demo.sql「一、用户」段，
-- 2026-11-06 统一归位）
--
-- 适用库：litisle_user
-- 用途：
--   1) 6 个角色（超管/管理员/读者/作家/纪念作家/审核员）；
--   2) 6 个角色示范账号（1001~1006）；
--   3) 3 个推荐流/搜索验收用演示用户（2001/2003 读者、2002 作家）。
--
-- 幂等：全部 INSERT IGNORE + 固定 ID，重复执行不报错、不放大行数、不覆盖已有真实数据。
-- 说明：phone_hash 为 NOT NULL 且带唯一键 uk_phone_hash_del，种子必须同步写入，
--       否则 INSERT IGNORE 会把「缺 NOT NULL 列」降级为告警并以空串落库，
--       多行空串还会撞唯一键被静默跳过（账号直接缺失）。
--       种子口径沿用 docs/Sql-Document.md §二：phone 存密文/明文占位，phone_hash 用 SHA2(phone,256) 占位
--       （64 位 hex，长度合规）；email_hash 留 NULL（MySQL 唯一索引不约束 NULL）。
-- =============================================================================

use `litisle_user`;

-- -----------------------------------------------------------------------------
-- 一、角色（固定 role_code，重复执行由 uk_role_code 兜底）
-- -----------------------------------------------------------------------------
insert ignore into `sys_role` (`role_code`, `role_name`, `role_type`) values
    ('SUPER_ADMIN',   '超级管理员', 1),
    ('ADMIN',         '管理员',     1),
    ('READER',        '读者',       2),
    ('AUTHOR',        '作家',       2),
    ('LEGACY_AUTHOR', '纪念作家',   2),
    ('REVIEWER',      '审核员',     2);

-- -----------------------------------------------------------------------------
-- 二、角色示范账号 1001~1006（每个角色各一名，密码统一为 bcrypt 加密的 123）
-- -----------------------------------------------------------------------------
-- 哈希：$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2

-- 1. READER 读者
INSERT IGNORE INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`, `avatar_color`)
VALUES (1001, 'reader_test', '275312400001001', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010001', SHA2('13800010001', 256), '热爱阅读的普通用户', 1, 0, NOW(), 0);
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1001, `id` FROM `sys_role` WHERE `role_code` = 'READER';
INSERT IGNORE INTO `sys_user_profile` (`user_id`) VALUES (1001);

-- 2. AUTHOR 作家
INSERT IGNORE INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`, `avatar_color`)
VALUES (1002, 'author_test', '275312400002002', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010002', SHA2('13800010002', 256), '专注科幻小说创作', 1, 0, NOW(), 1);
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1002, `id` FROM `sys_role` WHERE `role_code` = 'AUTHOR';
INSERT IGNORE INTO `sys_user_profile` (`user_id`, `pen_name`, `creation_dir`, `masterpiece`)
VALUES (1002, '红袖添香', '科幻 / 奇幻', '《星海归途》系列');

-- 3. LEGACY_AUTHOR 纪念作家
INSERT IGNORE INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`, `avatar_color`)
VALUES (1003, 'legacy_test', '275312400003003', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010003', SHA2('13800010003', 256), '已故文学巨匠，作品永久珍藏', 1, 0, NOW(), 2);
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1003, `id` FROM `sys_role` WHERE `role_code` = 'LEGACY_AUTHOR';
INSERT IGNORE INTO `sys_user_profile` (`user_id`, `pen_name`, `creation_dir`, `masterpiece`)
VALUES (1003, '老舍', '现实主义文学', '《骆驼祥子》《茶馆》');

-- 4. REVIEWER 审核员
INSERT IGNORE INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1004, 'reviewer_test', '275312400004004', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010004', SHA2('13800010004', 256), '内容安全审核专员', 1, 0, NOW());
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1004, `id` FROM `sys_role` WHERE `role_code` = 'REVIEWER';
INSERT IGNORE INTO `sys_user_profile` (`user_id`) VALUES (1004);

-- 5. ADMIN 管理员
INSERT IGNORE INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1005, 'admin_test', '275312400005005', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010005', SHA2('13800010005', 256), '负责平台日常运营管理', 1, 0, NOW());
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1005, `id` FROM `sys_role` WHERE `role_code` = 'ADMIN';
INSERT IGNORE INTO `sys_user_profile` (`user_id`) VALUES (1005);

-- 6. SUPER_ADMIN 超级管理员
INSERT IGNORE INTO `sys_user` (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`)
VALUES (1006, 'super_admin_test', '275312400006006', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2', '13800010006', SHA2('13800010006', 256), '系统最高权限拥有者', 1, 0, NOW());
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 1006, `id` FROM `sys_role` WHERE `role_code` = 'SUPER_ADMIN';
INSERT IGNORE INTO `sys_user_profile` (`user_id`) VALUES (1006);

-- -----------------------------------------------------------------------------
-- 三、推荐流/搜索验收演示用户 2001~2003（原 zz-seed-feed-demo.sql）
-- -----------------------------------------------------------------------------
-- 2001/2003 读者、2002 作家；phone 为密文，phone_hash 用 SHA2(phone,256) 占位（见文件头说明）。
-- 哈希：$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2
INSERT IGNORE INTO `sys_user`
    (`id`, `username`, `uid`, `password`, `phone`, `phone_hash`, `bio`, `status`, `del_flag`, `create_time`, `avatar_color`)
VALUES
    (2001, 'seed_reader_a', '275312400007007', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2',
     'ih16FUPbehySt4yoMNrf4xRKv7i5AHrLytafWLZnCsdAZh7eZByw', SHA2('ih16FUPbehySt4yoMNrf4xRKv7i5AHrLytafWLZnCsdAZh7eZByw', 256),
     '喜欢科幻与悬疑的读者', 1, 0, NOW() - INTERVAL 30 DAY, 0),
    (2002, 'seed_author', '275312400008008', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2',
     'MJR/Qr6Ss9Y6Y+/5jUclMJEIaHcoNd2epIENR2yxpRnSmfsMx/rh', SHA2('MJR/Qr6Ss9Y6Y+/5jUclMJEIaHcoNd2epIENR2yxpRnSmfsMx/rh', 256),
     '专注都市与科幻题材创作', 1, 0, NOW() - INTERVAL 60 DAY, 1),
    (2003, 'seed_reader_b', '275312400009009', '$2a$10$gdvA0F6AZe8WxbFNJygUuuZ0ZWjx4ofm9.Fr1rKgKUrU/AQj.OnQ2',
     'u7WGD/urTgBpmK3erk23YR9Tt95hJdTFafvN1Jw9BqSMXRI9tZnm', SHA2('u7WGD/urTgBpmK3erk23YR9Tt95hJdTFafvN1Jw9BqSMXRI9tZnm', 256),
     '偏爱散文与随笔的读者', 1, 0, NOW() - INTERVAL 10 DAY, 2);

-- 作家资料：笔名非空（user_idx 门控要求），并补充创作方向与代表作
INSERT IGNORE INTO `sys_user_profile` (`user_id`, `pen_name`, `creation_dir`, `masterpiece`)
VALUES (2002, '林间有风', '都市 / 科幻', '《长夜书店》系列');

-- 读者资料：仅建关联行（笔名为空，不触发 user_idx 收录）
INSERT IGNORE INTO `sys_user_profile` (`user_id`) VALUES (2001);
INSERT IGNORE INTO `sys_user_profile` (`user_id`) VALUES (2003);

-- 角色绑定：2002 作家、2001/2003 读者（重复执行由 uk_user_role 兜底）
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 2001, `id` FROM `sys_role` WHERE `role_code` = 'READER';
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 2002, `id` FROM `sys_role` WHERE `role_code` = 'AUTHOR';
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT 2003, `id` FROM `sys_role` WHERE `role_code` = 'READER';
