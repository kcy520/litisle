-- ============================================================================
-- search-schema.sql
-- 模块：litisle-search
-- 数据库：litisle_search
-- 说明：search 无业务数据表（ES 索引文档为投影，状态 SSOT 在业务方 DB），
--       search_history 为唯一自有表（用户搜索词记录，2026-11-03 新增，缺口 G8 闭环）
-- 位置：/docker-entrypoint-initdb.d/search-schema.sql（首启自动执行；
--       存量环境手动执行一次即可）
-- 关键口径：
--   1. UPSERT 同词仅前进：uk(user_id, keyword) 冲突时仅更新 updated_at（应用层 UPSERT）
--   2. 上限懒删：单用户超 history.max-per-user（默认 20）由应用层删除最旧记录，无 DB 约束
--   3. 隐私纪律：keyword 不写入任何 ES 索引文档/向量库（Search-Architecture §八-9）
-- ============================================================================

use litisle_search;

/* ================= 1. 用户搜索历史表（search 唯一自有表） ================= */
create table if not exists `search_history`
(
    `id`         bigint unsigned not null auto_increment primary key comment '主键ID',
    `user_id`    bigint unsigned not null comment '用户ID（sys_user.id；接口侧 user_id 硬收口，仅本人可见）',
    `keyword`    varchar(64)     not null comment '搜索关键词（长度 64 与查询词上限同源：SearchBizConstant.SEARCH_KEYWORD_MAX_LENGTH，批次四参数校验按 SEARCH_PARAM_INVALID 拒绝超长）',
    `updated_at` datetime        not null default current_timestamp on update current_timestamp comment '最近搜索时间（UPSERT 同词仅前进更新本列；列表按本列降序）',
    unique key `uk_user_keyword` (`user_id`, `keyword`),
    index `idx_user_updated` (`user_id`, `updated_at`)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = '用户搜索历史表（同词去重仅前进 + 上限懒删由应用层实现）';
