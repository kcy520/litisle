-- ============================================================================
-- 批次十 10.3 / 10.4 推荐流索引增量脚本
--
-- ⚠️ 需手工执行窗口：本脚本只落盘、不在代码里自动执行。
--    存量环境（数据卷已初始化）需在低峰窗口手工执行一次：
--      docker exec -i litisle-mysql mysql -uroot -p<MYSQL_ROOT_PASSWORD> < docker/init/mysql/zz-content-feed-index.sql
--    新环境首次初始化时，本脚本随 docker-entrypoint-initdb.d 在 content-schema.sql 之后自动执行一次。
--
--    索引构建会短暂持有表锁/产生 IO 抖动，务必避开写高峰；执行前确认已有备份。
--    重复执行会报 Duplicate key name，属预期（脚本非幂等，执行前先看 information_schema.statistics）。
-- ============================================================================

USE `litisle_content`;

-- 1) reading_history：(biz_type, biz_id) 组合索引
--    用途：书籍流 10.4 阅读人数聚合（biz_type=1 AND biz_id IN (...) GROUP BY biz_id），
--          也支撑按对象反查「谁读过」，避开只有 user_id 打头的现有索引。
--    影响表：reading_history（仅新增索引，不改数据）。
ALTER TABLE `reading_history`
    ADD INDEX `idx_biz` (`biz_type`, `biz_id`);

-- 2) article：推荐流分区召回索引
--    用途：10.3「常看分区」召回（zone IN (...) AND status=2 AND visibility=1 AND del_flag=0
--          ORDER BY create_time DESC），把过滤列与排序列一次覆盖。
--    影响表：article（仅新增索引，不改数据）。
ALTER TABLE `article`
    ADD INDEX `idx_feed_zone` (`zone`, `status`, `visibility`, `del_flag`, `create_time`);

-- 3) article：推荐流分类召回索引
--    用途：10.3「常看分类」召回（category IN (...) AND status=2 AND visibility=1 AND del_flag=0
--          ORDER BY create_time DESC）。
--    影响表：article（仅新增索引，不改数据）。
ALTER TABLE `article`
    ADD INDEX `idx_feed_category` (`category`, `status`, `visibility`, `del_flag`, `create_time`);

-- 4) book：书籍流召回索引
--    用途：10.4 书籍流（status IN (0,1) AND del_flag=0 [AND category IN (...)]
--          ORDER BY create_time DESC）；现有索引只有 author_id，无法支撑该过滤组合。
--    影响表：book（仅新增索引，不改数据）。
ALTER TABLE `book`
    ADD INDEX `idx_feed` (`status`, `del_flag`, `category`, `create_time`);
