-- =============================================================================
-- 存量分类双重编码（mojibake）一次性修复脚本
-- 文件：tools/db-repair/article-book-category-mojibake-fix.sql
--
-- 一、用途
--   修复存量库中 article.category / book.category 的双重编码值：
--   早期 latin1 客户端把 UTF-8 中文写进 utf8mb4 列，库里留下的是
--   「å¤§æ–‡」这类 Latin-1 区间字符（hex 形如 C3A5C2B0…），
--   按 latin1 -> utf8mb4 解码后才能还原为正常中文（如「散文」）。
--   本脚本只改这两列的数据值，不做任何结构性 DDL、不删表、不 truncate。
--
-- 二、适用范围（只处理脚本能判定的异常行）
--   1) article.category、book.category 两列；
--   2) 判定口径：原值不含中文（无 CJK 字符），但把原值按 latin1 还原字节、
--      再按 utf8mb4 解释后能得到中文。正常中文、纯英文、正常 Latin-1 文本都不会命中；
--   3) 同一行同时含中文与 mojibake 的「混合脏值」不会被自动改（条件互斥），
--      会留在第四部分的「剩余异常」核对里，需人工处理。
--
-- 三、不参与自动初始化（重要）
--   本文件位于 tools/db-repair/，不在 docker/init/mysql/ 及其子目录，
--   MySQL 容器 entrypoint 不会扫描到它。全新库由 01-schema.sql / 03-seed.sql
--   入口的 SET NAMES utf8mb4 保证写入正确，不需要跑本脚本。
--
-- 四、执行前提
--   1) 能连接目标 MySQL，账号对 litisle_content 具备 SELECT/CREATE/UPDATE 权限；
--   2) 客户端字符集必须是 utf8mb4（本脚本已显式 SET NAMES utf8mb4）；
--   3) 建议低峰窗口执行。本脚本是单次修复、非分批重算，受影响行数预期很小
--      （现象记录里仅种子文章 5001~5005 及对应书籍），执行前先用第一部分确认行数；
--      若实际行数达到万级，先停在这里，改为按主键分批（见文件末备注）。
--
-- 五、幂等性
--   1) 备份表用 CREATE TABLE IF NOT EXISTS，重复执行不会重复建表、不会覆盖首次快照；
--   2) 修复 UPDATE 的 WHERE 与第一部分统计口径完全一致，修复后条件不再成立，
--      重复执行匹配 0 行，不会把已还原的中文再转一次；
--   3) 第四部分校验可反复执行，只读。
--
-- 六、回滚
--   备份表保留修复前原值，按主键回灌即可（见第五部分，默认注释不执行）：
--   article_category_backup_20261107 / book_category_backup_20261107。
--   确认修复无误并观察一段时间后，再人工决定是否清理备份表（本脚本不删表）。
--
-- 七、操作顺序（务必遵守）
--   1) 第一部分：只读统计，先看清影响面与解码样例；
--   2) 第二部分：备份待修改行；
--   3) 第三部分：执行修复；
--   4) 第四部分：修复后复核（剩余异常应为 0、枚举映射正常）；
--   5) 需要回滚时才执行第五部分。
--
-- 八、执行方式（Windows 11 PowerShell，容器内执行；容器名按实际编排替换）
--   Get-Content .\tools\db-repair\article-book-category-mojibake-fix.sql -Raw |
--     docker exec -i litisle-mysql mysql -uroot -pverysecret --default-character-set=utf8mb4
--
-- 九、备注
--   1) 备份表名后缀按脚本编写日期 2026-11-07；若改期执行，请同步替换两张备份表名；
--   2) 若受影响行数达到万级，把第三部分的 UPDATE 改为按 id 区间分批
--      （每次 1000 行，条件不变），避免长事务与行锁堆积；
--   3) 本脚本不修改 feed_feedback.type=3 的 target_key；
--      修复后用户已提交的错误分类屏蔽记录需另按业务口径修正。
-- =============================================================================

SET NAMES utf8mb4;
USE litisle_content;

-- =============================================================================
-- 第一部分：只读统计（不改任何数据）
-- 判定口径说明（全文三处判断必须保持一致）：
--   ① 原值非空且不含中文：NOT (category REGEXP '[一-龥]')
--   ② 按 latin1 还原字节、再按 utf8mb4 解释后能读出中文：
--      CONVERT(CONVERT(category USING latin1) USING utf8mb4) REGEXP '[一-龥]'
--   正常中文在 ① 就被排除；纯英文在 ② 被排除；正常 Latin-1 文本（如 café）
--   在 ② 被排除（解码结果没有中文）。
-- =============================================================================

-- 1.1 影响面总览（article / book 各多少行）
SELECT 'article' AS table_name,
       COUNT(*) AS mojibake_rows
  FROM article
 WHERE category IS NOT NULL
   AND category <> ''
   AND NOT (category REGEXP '[一-龥]')
   AND CONVERT(CONVERT(category USING latin1) USING utf8mb4) REGEXP '[一-龥]'
UNION ALL
SELECT 'book' AS table_name,
       COUNT(*) AS mojibake_rows
  FROM book
 WHERE category IS NOT NULL
   AND category <> ''
   AND NOT (category REGEXP '[一-龥]')
   AND CONVERT(CONVERT(category USING latin1) USING utf8mb4) REGEXP '[一-龥]';

-- 1.2 当前值分布（看异常值集中在哪些写法）
SELECT 'article' AS table_name,
       category,
       HEX(category) AS category_hex,
       COUNT(*) AS cnt
  FROM article
 WHERE category IS NOT NULL
   AND category <> ''
   AND NOT (category REGEXP '[一-龥]')
   AND CONVERT(CONVERT(category USING latin1) USING utf8mb4) REGEXP '[一-龥]'
 GROUP BY category, HEX(category)
UNION ALL
SELECT 'book' AS table_name,
       category,
       HEX(category) AS category_hex,
       COUNT(*) AS cnt
  FROM book
 WHERE category IS NOT NULL
   AND category <> ''
   AND NOT (category REGEXP '[一-龥]')
   AND CONVERT(CONVERT(category USING latin1) USING utf8mb4) REGEXP '[一-龥]'
 GROUP BY category, HEX(category);

-- 1.3 解码样例（逐行对照「修复后会变成什么」）
SELECT 'article' AS table_name,
       id,
       category AS category_before,
       HEX(category) AS category_hex_before,
       CONVERT(CONVERT(category USING latin1) USING utf8mb4) AS category_after_expected
  FROM article
 WHERE category IS NOT NULL
   AND category <> ''
   AND NOT (category REGEXP '[一-龥]')
   AND CONVERT(CONVERT(category USING latin1) USING utf8mb4) REGEXP '[一-龥]'
 ORDER BY id
 LIMIT 50;

SELECT 'book' AS table_name,
       id,
       category AS category_before,
       HEX(category) AS category_hex_before,
       CONVERT(CONVERT(category USING latin1) USING utf8mb4) AS category_after_expected
  FROM book
 WHERE category IS NOT NULL
   AND category <> ''
   AND NOT (category REGEXP '[一-龥]')
   AND CONVERT(CONVERT(category USING latin1) USING utf8mb4) REGEXP '[一-龥]'
 ORDER BY id
 LIMIT 50;

-- 1.4 文章分类枚举外的值（修复前先记录，便于判断是否还有别的脏值）
--     ArticleCategoryEnum 合法值：小说 / 散文 / 现代诗 / 随笔
SELECT id, category
  FROM article
 WHERE category IS NOT NULL
   AND category <> ''
   AND category NOT IN ('小说', '散文', '现代诗', '随笔')
 ORDER BY id
 LIMIT 100;

-- =============================================================================
-- 第二部分：备份待修改行
-- 只备份第一部分命中的行；备份表保留原值 + 原 hex + 备份时间，供回滚与比对。
-- IF NOT EXISTS 保证重复执行不覆盖首次快照。
-- =============================================================================

CREATE TABLE IF NOT EXISTS article_category_backup_20261107 AS
SELECT id,
       category AS category_before,
       HEX(category) AS category_hex_before,
       NOW() AS backup_time
  FROM article
 WHERE category IS NOT NULL
   AND category <> ''
   AND NOT (category REGEXP '[一-龥]')
   AND CONVERT(CONVERT(category USING latin1) USING utf8mb4) REGEXP '[一-龥]';

CREATE TABLE IF NOT EXISTS book_category_backup_20261107 AS
SELECT id,
       category AS category_before,
       HEX(category) AS category_hex_before,
       NOW() AS backup_time
  FROM book
 WHERE category IS NOT NULL
   AND category <> ''
   AND NOT (category REGEXP '[一-龥]')
   AND CONVERT(CONVERT(category USING latin1) USING utf8mb4) REGEXP '[一-龥]';

-- 备份完成后先核对行数，应与第一部分 1.1 完全一致，再继续第三部分
SELECT 'article_backup' AS table_name, COUNT(*) AS backup_rows
  FROM article_category_backup_20261107
UNION ALL
SELECT 'book_backup' AS table_name, COUNT(*) AS backup_rows
  FROM book_category_backup_20261107;

-- =============================================================================
-- 第三部分：修复（只改命中行）
-- 原理：把 mojibake 字符串按 latin1 重新取回原始 UTF-8 字节，再按 utf8mb4 解释。
-- 条件与第一部分完全一致，未命中的正常中文不会被改写。
-- =============================================================================

UPDATE article
   SET category = CONVERT(CONVERT(category USING latin1) USING utf8mb4)
 WHERE category IS NOT NULL
   AND category <> ''
   AND NOT (category REGEXP '[一-龥]')
   AND CONVERT(CONVERT(category USING latin1) USING utf8mb4) REGEXP '[一-龥]';

UPDATE book
   SET category = CONVERT(CONVERT(category USING latin1) USING utf8mb4)
 WHERE category IS NOT NULL
   AND category <> ''
   AND NOT (category REGEXP '[一-龥]')
   AND CONVERT(CONVERT(category USING latin1) USING utf8mb4) REGEXP '[一-龥]';

-- =============================================================================
-- 第四部分：修复后校验（只读）
-- =============================================================================

-- 4.1 剩余异常应为 0（严格按第一部分口径复查）
SELECT 'article' AS table_name, COUNT(*) AS remaining_mojibake
  FROM article
 WHERE category IS NOT NULL
   AND category <> ''
   AND NOT (category REGEXP '[一-龥]')
   AND CONVERT(CONVERT(category USING latin1) USING utf8mb4) REGEXP '[一-龥]'
UNION ALL
SELECT 'book' AS table_name, COUNT(*) AS remaining_mojibake
  FROM book
 WHERE category IS NOT NULL
   AND category <> ''
   AND NOT (category REGEXP '[一-龥]')
   AND CONVERT(CONVERT(category USING latin1) USING utf8mb4) REGEXP '[一-龥]';

-- 4.2 逐行比对「修复前 / 修复后」：after_hex 应为正常中文的 UTF-8 字节
SELECT b.id,
       b.category_before,
       b.category_hex_before,
       a.category AS category_after,
       HEX(a.category) AS category_hex_after
  FROM article_category_backup_20261107 b
  JOIN article a ON a.id = b.id
 ORDER BY b.id;

SELECT b.id,
       b.category_before,
       b.category_hex_before,
       bk.category AS category_after,
       HEX(bk.category) AS category_hex_after
  FROM book_category_backup_20261107 b
  JOIN book bk ON bk.id = b.id
 ORDER BY b.id;

-- 4.3 文章枚举映射校验：期望只剩 0 行（若仍有枚举外值，逐行人工判断）
--     ArticleCategoryEnum 合法值：小说 / 散文 / 现代诗 / 随笔
SELECT id, category
  FROM article
 WHERE category IS NOT NULL
   AND category <> ''
   AND category NOT IN ('小说', '散文', '现代诗', '随笔')
 ORDER BY id
 LIMIT 100;

-- 4.4 书籍分类分布（修复后应全是可读中文或正常英文，不再出现 å¤§æ–‡ 形态）
SELECT category, COUNT(*) AS cnt
  FROM book
 WHERE category IS NOT NULL
   AND category <> ''
 GROUP BY category
 ORDER BY cnt DESC
 LIMIT 100;

-- =============================================================================
-- 第五部分：回滚（默认注释，不自动执行）
-- 仅在确认修复结果错误时使用；按主键把备份的原值回灌。
-- =============================================================================
--
-- UPDATE article a
--   JOIN article_category_backup_20261107 b ON b.id = a.id
--    SET a.category = b.category_before;
--
-- UPDATE book bk
--   JOIN book_category_backup_20261107 b ON b.id = bk.id
--    SET bk.category = b.category_before;
--
-- 回滚后请重跑第四部分 4.1，确认异常行数恢复到修复前的数量（即备份行数）。
-- =============================================================================
