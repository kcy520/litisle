-- =============================================================================
-- content 模块种子数据（原 zz-seed-feed-demo.sql「二、三、四」段 + 统计基线，2026-11-06 归位）
--
-- 适用库：litisle_content
-- 用途：推荐流/书籍流/详情/统计/对账验收所需的固定演示数据。
--
-- 幂等：业务表全部 INSERT IGNORE + 固定 ID（唯一键兜底）；统计表 INSERT IGNORE 以主键兜底，
--       重复执行不报错、不放大行数、不覆盖已有真实数据。
--
-- 统计自洽（重要）：本文件在同一份文件内同时写入原始业务表与对应统计结果，
--   article_stat / book_stat / content_stat_window / book_read_quality 的数值与上方业务数据一一对应，
--   新库初始化后无需再跑存量回填脚本即可展示正确的收藏数/阅读数/章节数。
--   口径与回填脚本一致：收藏数排除作者自收藏；章节数只统计「未删 + 已发布 + 公开」章节；
--   窗口行只覆盖「未删文章 / 未删书籍」，窗口天数 30/90。
-- =============================================================================

use `litisle_content`;

-- -----------------------------------------------------------------------------
-- 一、文章（10 篇覆盖全状态：已发布+公开（分区1/2）、私密、待审核、已下架、已删除、草稿）
-- -----------------------------------------------------------------------------
-- view_cnt/like_cnt/comment_cnt 写在源表，下方 article_stat 同步写入对应统计值。
INSERT IGNORE INTO `article`
    (`id`, `author_id`, `zone`, `title`, `summary`, `content_html`, `category`,
     `status`, `visibility`, `view_cnt`, `like_cnt`, `comment_cnt`, `del_flag`,
     `create_time`, `update_time`, `stat_update_time`)
VALUES
    (5001, 2002, 1, '长夜书店的第一盏灯', '都市夜归人的短篇故事',
     '<p>打烊前十分钟，最后一位客人推门进来。</p>', '小说', 2, 1, 120, 15, 3, 0,
     NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY),
    (5002, 2002, 1, '雨后的旧巷', '一篇关于记忆与潮湿空气的散文',
     '<p>雨停之后，青石板把整条巷子照得很亮。</p>', '散文', 2, 1, 80, 9, 2, 0,
     NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 20 DAY),
    (5003, 2002, 1, '给夜行的你', '写给深夜赶路的人',
     '<p>风从车窗缝里挤进来，像一句没说完的话。</p>', '现代诗', 2, 1, 30, 2, 0, 0,
     NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY),
    (5004, 2002, 2, '星际邮差（作家分区）', '科幻短篇：给三百年后的人送信',
     '<p>邮差的飞船在土星环外等了整整一个世纪。</p>', '随笔', 2, 1, 220, 30, 8, 0,
     NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY),
    (5005, 2002, 2, '长夜书店（作家分区）', '都市长篇的开篇章节',
     '<p>书店的灯从没有关过，哪怕整条街都睡着了。</p>', '小说', 2, 1, 500, 60, 20, 0,
     NOW() - INTERVAL 120 DAY, NOW() - INTERVAL 120 DAY, NOW() - INTERVAL 120 DAY),
    (5006, 2002, 1, '未寄出的信（私密）', '仅作者可见的草稿文章',
     '<p>这封信写了三年，最终没有寄出。</p>', '散文', 2, 2, 10, 0, 0, 0,
     NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 5 DAY),
    (5007, 2002, 1, '待审稿：雾中列车', '等待审核的文章',
     '<p>列车驶入雾里，站台的名字看不清了。</p>', '小说', 1, 1, 0, 0, 0, 0,
     NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY),
    (5008, 2002, 1, '已下架：旧日手记', '因内容调整已下架',
     '<p>旧日手记已由作者申请下架。</p>', '随笔', 4, 1, 40, 5, 1, 0,
     NOW() - INTERVAL 90 DAY, NOW() - INTERVAL 90 DAY, NOW() - INTERVAL 90 DAY),
    (5009, 2002, 1, '已删除：临时草稿', '软删除文章',
     '<p>这是一篇被软删除的文章。</p>', '小说', 2, 1, 15, 1, 0, 1,
     NOW() - INTERVAL 150 DAY, NOW() - INTERVAL 150 DAY, NOW() - INTERVAL 150 DAY),
    (5010, 2002, 1, '草稿：尚未命名', '未发布的草稿',
     '<p>草稿内容，尚未完成。</p>', '随笔', 0, 1, 0, 0, 0, 0,
     NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 6 DAY);

-- 标签池：固定 ID，use_count 与下方 article_tag 关联数保持一致
INSERT IGNORE INTO `tag` (`id`, `name`, `use_count`, `status`, `del_flag`, `create_time`)
VALUES
    (7001, '小说', 3, 0, 0, NOW() - INTERVAL 180 DAY),
    (7002, '散文', 2, 0, 0, NOW() - INTERVAL 180 DAY),
    (7003, '科幻', 2, 0, 0, NOW() - INTERVAL 180 DAY),
    (7004, '日常', 2, 0, 0, NOW() - INTERVAL 180 DAY),
    (7005, '情感', 2, 0, 0, NOW() - INTERVAL 180 DAY);

-- 文章-标签关联：覆盖不同分类与标签组合（重复执行由 uk_article_tag 兜底）
INSERT IGNORE INTO `article_tag` (`article_id`, `tag_id`) VALUES
    (5001, 7001), (5001, 7004),
    (5002, 7002),
    (5003, 7002), (5003, 7005),
    (5004, 7003),
    (5005, 7001), (5005, 7003),
    (5006, 7005),
    (5009, 7001),
    (5010, 7004);

-- -----------------------------------------------------------------------------
-- 二、书籍（5 本覆盖连载中 / 已完结 / 已下架 / 已删除，不同分类与创建时间）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `book`
    (`id`, `author_id`, `title`, `intro`, `category`, `status`, `del_flag`, `create_time`, `update_time`)
VALUES
    (6101, 2002, '长夜书店', '都市夜归人的群像长篇', '小说', 0, 0, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY),
    (6102, 2002, '星海归途', '已完结的科幻长篇', '科幻', 1, 0, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY),
    (6103, 2002, '旧巷手记', '已下架的散文合集', '散文', 2, 0, NOW() - INTERVAL 60 DAY, NOW() - INTERVAL 60 DAY),
    (6104, 2002, '雾中列车（已删除）', '已软删的悬疑小说', '悬疑', 0, 1, NOW() - INTERVAL 90 DAY, NOW() - INTERVAL 90 DAY),
    (6105, 2002, '第十三个站台', '连载中的悬疑新书', '悬疑', 0, 0, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY);

-- 每本书 3 章；其中 5006 为私密文章，用于验证章节数口径（只统计「未删 + 已发布 + 公开」章节，
-- 私密章节不计入，因此种子书 6101/6103/6105 应为 2 章、6102 为 3 章、6104（软删书）为 2 章）
-- 章节 ID 规则：bookId*100 + 序号，便于人工核对
INSERT IGNORE INTO `book_chapter` (`id`, `book_id`, `article_id`, `chapter_no`, `create_time`)
VALUES
    (610101, 6101, 5001, 1, NOW() - INTERVAL 2 DAY),
    (610102, 6101, 5002, 2, NOW() - INTERVAL 2 DAY),
    (610103, 6101, 5006, 3, NOW() - INTERVAL 1 DAY),
    (610201, 6102, 5004, 1, NOW() - INTERVAL 30 DAY),
    (610202, 6102, 5005, 2, NOW() - INTERVAL 30 DAY),
    (610203, 6102, 5003, 3, NOW() - INTERVAL 29 DAY),
    (610301, 6103, 5003, 1, NOW() - INTERVAL 60 DAY),
    (610302, 6103, 5002, 2, NOW() - INTERVAL 60 DAY),
    (610303, 6103, 5006, 3, NOW() - INTERVAL 59 DAY),
    (610401, 6104, 5005, 1, NOW() - INTERVAL 90 DAY),
    (610402, 6104, 5009, 2, NOW() - INTERVAL 90 DAY),
    (610403, 6104, 5001, 3, NOW() - INTERVAL 89 DAY),
    (610501, 6105, 5001, 1, NOW() - INTERVAL 1 DAY),
    (610502, 6105, 5004, 2, NOW() - INTERVAL 1 DAY),
    (610503, 6105, 5006, 3, NOW() - INTERVAL 1 DAY);

-- -----------------------------------------------------------------------------
-- 三、收藏与阅读历史（驱动 collect_count / read_count / reader_count）
-- -----------------------------------------------------------------------------
-- 收藏：2003 收藏 2 本书 + 2 篇文章；2001 收藏 1 本书 + 1 篇文章
-- （重复执行由 uk_user_target 兜底；作者 2002 无自收藏，故统计值 = favorite 聚合值）
INSERT IGNORE INTO `favorite` (`user_id`, `target_type`, `target_id`, `create_time`) VALUES
    (2003, 2, 6101, NOW() - INTERVAL 2 DAY),
    (2003, 2, 6102, NOW() - INTERVAL 20 DAY),
    (2003, 1, 5001, NOW() - INTERVAL 1 DAY),
    (2003, 1, 5004, NOW() - INTERVAL 2 DAY),
    (2001, 2, 6101, NOW() - INTERVAL 1 DAY),
    (2001, 1, 5002, NOW() - INTERVAL 10 DAY);

-- 阅读历史：书籍（biz_type=1，带最后阅读章节）与独立文章（biz_type=2）
-- 2003 读 2 本书 + 2 篇文章；2001 读 1 本书 → reader_count / read_count 可验证
-- （重复执行由 uk_user_biz 兜底）
INSERT IGNORE INTO `reading_history` (`id`, `user_id`, `biz_type`, `biz_id`, `chapter_id`) VALUES
    (630101, 2003, 1, 6101, 610103),
    (630102, 2003, 1, 6102, 610202),
    (630201, 2003, 2, 5001, NULL),
    (630202, 2003, 2, 5004, NULL),
    (630301, 2001, 1, 6101, 610102);

-- -----------------------------------------------------------------------------
-- 四、统计基线（与上方业务数据自洽；原由 zz-content-stat*.sql 回填产生）
-- -----------------------------------------------------------------------------
-- 文章统计：只覆盖未删除文章（5009 软删不写）；收藏数排除作者自收藏；
-- 点赞/评论/浏览量取上方 article 源表列值。
INSERT IGNORE INTO `article_stat`
    (`article_id`, `collect_count`, `like_count`, `comment_count`, `view_count`, `stat_update_time`)
VALUES
    (5001, 1, 15, 3, 120, NOW()),
    (5002, 1, 9, 2, 80, NOW()),
    (5003, 0, 2, 0, 30, NOW()),
    (5004, 1, 30, 8, 220, NOW()),
    (5005, 0, 60, 20, 500, NOW()),
    (5006, 0, 0, 0, 10, NOW()),
    (5007, 0, 0, 0, 0, NOW()),
    (5008, 0, 5, 1, 40, NOW()),
    (5010, 0, 0, 0, 0, NOW());

-- 书籍统计：收藏数排除作者自收藏；reader_count=阅读历史去重人数；
-- chapter_count 只统计「未删 + 已发布 + 公开」章节（6101/6103/6105=2、6102=3、6104=2）。
INSERT IGNORE INTO `book_stat`
    (`book_id`, `collect_count`, `reader_count`, `chapter_count`, `like_count`, `view_count`, `stat_update_time`)
VALUES
    (6101, 2, 2, 2, 0, 0, NOW()),
    (6102, 1, 1, 3, 0, 0, NOW()),
    (6103, 0, 0, 2, 0, 0, NOW()),
    (6104, 0, 0, 2, 0, 0, NOW()),
    (6105, 0, 0, 2, 0, 0, NOW());

-- 统计窗口：30/90 天各一组；只覆盖「未删文章 / 未删书籍」（5009、6104 不写）；
-- 种子收藏与阅读历史的时间都落在 30 天内，故 30 天与 90 天数值一致。
INSERT IGNORE INTO `content_stat_window`
    (`target_type`, `target_id`, `window_days`, `collect_count`, `read_count`, `stat_update_time`)
VALUES
    (1, 5001, 30, 1, 1, NOW()),
    (1, 5002, 30, 1, 0, NOW()),
    (1, 5003, 30, 0, 0, NOW()),
    (1, 5004, 30, 1, 1, NOW()),
    (1, 5005, 30, 0, 0, NOW()),
    (1, 5006, 30, 0, 0, NOW()),
    (1, 5007, 30, 0, 0, NOW()),
    (1, 5008, 30, 0, 0, NOW()),
    (1, 5010, 30, 0, 0, NOW()),
    (2, 6101, 30, 2, 2, NOW()),
    (2, 6102, 30, 1, 1, NOW()),
    (2, 6103, 30, 0, 0, NOW()),
    (2, 6105, 30, 0, 0, NOW()),
    (1, 5001, 90, 1, 1, NOW()),
    (1, 5002, 90, 1, 0, NOW()),
    (1, 5003, 90, 0, 0, NOW()),
    (1, 5004, 90, 1, 1, NOW()),
    (1, 5005, 90, 0, 0, NOW()),
    (1, 5006, 90, 0, 0, NOW()),
    (1, 5007, 90, 0, 0, NOW()),
    (1, 5008, 90, 0, 0, NOW()),
    (1, 5010, 90, 0, 0, NOW()),
    (2, 6101, 90, 2, 2, NOW()),
    (2, 6102, 90, 1, 1, NOW()),
    (2, 6103, 90, 0, 0, NOW()),
    (2, 6105, 90, 0, 0, NOW());

-- 粗完读率：6101 的 2001 最后阅读到 610102（可见最大章为 2）→ 完成；2003 的最后章节指向私密章节 610103，
-- 该章节不可见、不进入分母，故 6101 = 1/1。6102 的 2003 只读到第 2 章（最大 3，未完成）→ 0/1；
-- 其余书无可见章节阅读历史 → 0/0。与 BookReadQualityJob 对种子数据的计算结果一致。
INSERT IGNORE INTO `book_read_quality`
    (`book_id`, `finish_reader_count`, `eligible_reader_count`, `stat_update_time`)
VALUES
    (6101, 1, 1, NOW()),
    (6102, 0, 1, NOW()),
    (6103, 0, 0, NOW()),
    (6104, 0, 0, NOW()),
    (6105, 0, 0, NOW());

-- -----------------------------------------------------------------------------
-- 五、执行后核对（只读，不影响数据）
-- -----------------------------------------------------------------------------
select 'article' as table_name, count(*) as total_rows from article
union all select 'book', count(*) from book
union all select 'book_chapter', count(*) from book_chapter
union all select 'favorite', count(*) from favorite
union all select 'reading_history', count(*) from reading_history
union all select 'tag', count(*) from tag
union all select 'article_tag', count(*) from article_tag;

-- 统计表行数（应与上方业务数据匹配，而不是全 0）
select 'article_stat' as table_name, count(*) as total_rows from article_stat
union all select 'book_stat', count(*) from book_stat
union all select 'content_stat_window', count(*) from content_stat_window
union all select 'book_read_quality', count(*) from book_read_quality;
