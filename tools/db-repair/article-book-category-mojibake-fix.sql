-- =============================================================================
-- 存量分类双重编码（mojibake）一次性修复脚本
-- 文件：tools/db-repair/article-book-category-mojibake-fix.sql
--
-- 【2026-11-09 实测修正】
--   1) 判定口径修正：实测发现双重编码第一跳客户端字符集是 cp1252（Windows-1252）而非纯
--      latin1——0x80~0x9F 区段字节被映射为 U+2018/U+201C/U+2022 等标点（hex 含 E280xx
--      三字节序列）。latin1 解码对这些字符无法映射（替换成 ?），导致按 latin1 口径统计
--      恒为 0 行、修复段漏检全部脏行。
--   2) 执行方式修正：MySQL 8.0 已移除 cp1252 字符集（CONVERT USING cp1252 报
--      Unknown character set），且 PowerShell 管道传 SQL 会按 GBK 重编码中文导致条件失真。
--      故本脚本整体改为「已知 mojibake HEX 白名单」精确匹配（见下），执行方式改为
--      docker cp 拷入容器后由容器内 mysql 执行（字节保真，见「八、执行方式」）。
--   3) 白名单覆盖当前库全部 8 种脏值形态（article 31 行 + book 5 行）。若未来出现新的
--      mojibake 形态（1.4 枚举外值检查可发现），需在库外按 cp1252→utf8mb4 人工解码，
--      确认中文字面值后把新 HEX 追加进白名单与第三部分映射，不得凭猜测直接 UPDATE。
--
-- 一、用途
--   修复存量库中 article.category / book.category 的双重编码值：
--   早期 cp1252/latin1 客户端把 UTF-8 中文写进 utf8mb4 列，库里留下的是
--   「å°è¯´」这类 Latin-1 区间字符（hex 形如 C3A5C2B0…），
--   按 cp1252 -> utf8mb4 解码后才能还原为正常中文（如「小说」）。
--   本脚本只改这两列的数据值，不做任何结构性 DDL、不删表、不 truncate。
--
-- 二、适用范围（只处理脚本能判定的异常行）
--   1) article.category、book.category 两列；
--   2) 判定口径：HEX(category) 命中已知双重编码 HEX 白名单（全文各处判断必须保持一致）；
--      白名单与还原值的对应关系见第三部分映射表，均为已实测验证的 cp1252 双重编码形态；
--   3) 不在白名单内的脏值不会被自动改，会留在第四部分 4.3「枚举外值」核对里，
--      需人工解码（cp1252->utf8mb4）后扩白名单再跑。
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
--      （2026-11-09 实测：article 31 行 + book 5 行），执行前先用第一部分确认行数；
--      若实际行数达到万级，先停在这里，改为按主键分批（见文件末备注）。
--
-- 五、幂等性
--   1) 备份表用 CREATE TABLE IF NOT EXISTS，重复执行不会重复建表、不会覆盖首次快照；
--   2) 修复 UPDATE 的 WHERE 与第一部分统计口径完全一致（HEX 白名单），修复后条件不再成立，
--      重复执行匹配 0 行，不会把已还原的中文再转一次；
--   3) 第四部分校验可反复执行，只读。
--
-- 六、回滚
--   备份表保留修复前原值，按主键回灌即可（见第五部分，默认注释不执行）：
--   article_category_backup_20261107 / book_category_backup_20261107。
--   确认修复无误并观察一段时间后，再人工决定是否清理备份表（本脚本不删表）。
--
-- 七、操作顺序（务必遵守）
--   1) 第一部分：只读统计，先看清影响面；
--   2) 第二部分：备份待修改行；
--   3) 第三部分：执行修复（按 HEX 映射写入还原后的中文字面值，同时保住 update_time）；
--   4) 第四部分：修复后复核（剩余异常应为 0、枚举映射正常）；
--   5) 需要回滚时才执行第五部分。
--
-- 八、执行方式（Windows 11 PowerShell + docker 容器；禁止用 PowerShell 管道直传 SQL——
--   中文会被按系统 ANSI 码页重编码，导致 WHERE 条件失真。必须 docker cp 拷入容器再执行）
--   docker cp tools/db-repair/article-book-category-mojibake-fix.sql litisle-mysql:/tmp/fix.sql
--   docker exec litisle-mysql sh -c "mysql -uroot -pverysecret --default-character-set=utf8mb4 < /tmp/fix.sql"
--
-- 九、备注
--   1) 备份表名后缀按脚本编写日期 2026-11-07；若改期执行，请同步替换两张备份表名；
--   2) 若受影响行数达到万级，把第三部分的 UPDATE 改为按 id 区间分批
--      （每次 1000 行，条件不变），避免长事务与行锁堆积；
--   3) 本脚本不修改 feed_feedback.type=3 的 target_key；
--      修复后用户已提交的错误分类屏蔽记录需另按业务口径修正；
--   4) 第三部分 UPDATE 显式 SET update_time = update_time，抵消列上
--      ON UPDATE CURRENT_TIMESTAMP 的自动刷新，避免修复动作污染内容更新时间。
-- =============================================================================

SET NAMES utf8mb4;
USE litisle_content;

-- =============================================================================
-- 第一部分：只读统计（不改任何数据）
-- 判定口径说明（全文三处判断必须保持一致）：
--   HEX(category) IN (已知双重编码 HEX 白名单)。白名单来源：2026-11-09 实测
--   SELECT DISTINCT HEX(category) 全量枚举，共 6 种 HEX（article/book 两侧通用）：
--     小说   C3A5C2B0C28FC3A8C2AFC2B4
--     散文   C3A6E280A2C2A3C3A6E28093E280A1
--     随笔   C3A9C5A1C28FC3A7C2ACE2809D
--     现代诗 C3A7C5BDC2B0C3A4C2BBC2A3C3A8C2AFE28094
--     悬疑   C3A6E2809AC2ACC3A7E28093E28098（仅 book 侧）
--     科幻   C3A7C2A7E28098C3A5C2B9C2BB（仅 book 侧）
-- =============================================================================

-- 1.1 影响面总览（article / book 各多少行）
SELECT 'article' AS table_name,
       COUNT(*) AS mojibake_rows
  FROM article
 WHERE category IS NOT NULL
   AND category <> ''
   AND HEX(category) IN ('C3A5C2B0C28FC3A8C2AFC2B4', 'C3A6E280A2C2A3C3A6E28093E280A1',
                         'C3A9C5A1C28FC3A7C2ACE2809D', 'C3A7C5BDC2B0C3A4C2BBC2A3C3A8C2AFE28094',
                         'C3A6E2809AC2ACC3A7E28093E28098', 'C3A7C2A7E28098C3A5C2B9C2BB')
UNION ALL
SELECT 'book' AS table_name,
       COUNT(*) AS mojibake_rows
  FROM book
 WHERE category IS NOT NULL
   AND category <> ''
   AND HEX(category) IN ('C3A5C2B0C28FC3A8C2AFC2B4', 'C3A6E280A2C2A3C3A6E28093E280A1',
                         'C3A9C5A1C28FC3A7C2ACE2809D', 'C3A7C5BDC2B0C3A4C2BBC2A3C3A8C2AFE28094',
                         'C3A6E2809AC2ACC3A7E28093E28098', 'C3A7C2A7E28098C3A5C2B9C2BB');

-- 1.2 当前值分布（看异常值集中在哪些写法）
SELECT 'article' AS table_name,
       category,
       HEX(category) AS category_hex,
       COUNT(*) AS cnt
  FROM article
 WHERE category IS NOT NULL
   AND category <> ''
   AND HEX(category) IN ('C3A5C2B0C28FC3A8C2AFC2B4', 'C3A6E280A2C2A3C3A6E28093E280A1',
                         'C3A9C5A1C28FC3A7C2ACE2809D', 'C3A7C5BDC2B0C3A4C2BBC2A3C3A8C2AFE28094',
                         'C3A6E2809AC2ACC3A7E28093E28098', 'C3A7C2A7E28098C3A5C2B9C2BB')
 GROUP BY category, HEX(category)
UNION ALL
SELECT 'book' AS table_name,
       category,
       HEX(category) AS category_hex,
       COUNT(*) AS cnt
  FROM book
 WHERE category IS NOT NULL
   AND category <> ''
   AND HEX(category) IN ('C3A5C2B0C28FC3A8C2AFC2B4', 'C3A6E280A2C2A3C3A6E28093E280A1',
                         'C3A9C5A1C28FC3A7C2ACE2809D', 'C3A7C5BDC2B0C3A4C2BBC2A3C3A8C2AFE28094',
                         'C3A6E2809AC2ACC3A7E28093E28098', 'C3A7C2A7E28098C3A5C2B9C2BB')
 GROUP BY category, HEX(category);

-- 1.3 影响行清单（逐行记录修复前的 id 与 hex，供回滚比对；还原目标值见第三部分映射表）
SELECT 'article' AS table_name,
       id,
       HEX(category) AS category_hex_before
  FROM article
 WHERE category IS NOT NULL
   AND category <> ''
   AND HEX(category) IN ('C3A5C2B0C28FC3A8C2AFC2B4', 'C3A6E280A2C2A3C3A6E28093E280A1',
                         'C3A9C5A1C28FC3A7C2ACE2809D', 'C3A7C5BDC2B0C3A4C2BBC2A3C3A8C2AFE28094',
                         'C3A6E2809AC2ACC3A7E28093E28098', 'C3A7C2A7E28098C3A5C2B9C2BB')
 ORDER BY id
 LIMIT 50;

SELECT 'book' AS table_name,
       id,
       HEX(category) AS category_hex_before
  FROM book
 WHERE category IS NOT NULL
   AND category <> ''
   AND HEX(category) IN ('C3A5C2B0C28FC3A8C2AFC2B4', 'C3A6E280A2C2A3C3A6E28093E280A1',
                         'C3A9C5A1C28FC3A7C2ACE2809D', 'C3A7C5BDC2B0C3A4C2BBC2A3C3A8C2AFE28094',
                         'C3A6E2809AC2ACC3A7E28093E28098', 'C3A7C2A7E28098C3A5C2B9C2BB')
 ORDER BY id
 LIMIT 50;

-- 1.4 文章分类枚举外的值（修复前先记录，便于判断是否还有白名单之外的新脏值）
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
   AND HEX(category) IN ('C3A5C2B0C28FC3A8C2AFC2B4', 'C3A6E280A2C2A3C3A6E28093E280A1',
                         'C3A9C5A1C28FC3A7C2ACE2809D', 'C3A7C5BDC2B0C3A4C2BBC2A3C3A8C2AFE28094',
                         'C3A6E2809AC2ACC3A7E28093E28098', 'C3A7C2A7E28098C3A5C2B9C2BB');

CREATE TABLE IF NOT EXISTS book_category_backup_20261107 AS
SELECT id,
       category AS category_before,
       HEX(category) AS category_hex_before,
       NOW() AS backup_time
  FROM book
 WHERE category IS NOT NULL
   AND category <> ''
   AND HEX(category) IN ('C3A5C2B0C28FC3A8C2AFC2B4', 'C3A6E280A2C2A3C3A6E28093E280A1',
                         'C3A9C5A1C28FC3A7C2ACE2809D', 'C3A7C5BDC2B0C3A4C2BBC2A3C3A8C2AFE28094',
                         'C3A6E2809AC2ACC3A7E28093E28098', 'C3A7C2A7E28098C3A5C2B9C2BB');

-- 备份完成后先核对行数，应与第一部分 1.1 完全一致，再继续第三部分
SELECT 'article_backup' AS table_name, COUNT(*) AS backup_rows
  FROM article_category_backup_20261107
UNION ALL
SELECT 'book_backup' AS table_name, COUNT(*) AS backup_rows
  FROM book_category_backup_20261107;

-- =============================================================================
-- 第三部分：修复（只改命中行）
-- MySQL 8 无 cp1252 字符集，无法在 SQL 内按字符集解码，故按 HEX 白名单逐条
-- 写入人工核对过的中文字面值（映射关系见第一部分注释）；显式保住 update_time。
-- 条件与第一部分完全一致，未命中的正常中文不会被改写。
-- =============================================================================

-- 3.1 article 侧（4 种 HEX → 4 个枚举值）
UPDATE article
   SET category = '随笔', update_time = update_time
 WHERE HEX(category) = 'C3A9C5A1C28FC3A7C2ACE2809D';

UPDATE article
   SET category = '小说', update_time = update_time
 WHERE HEX(category) = 'C3A5C2B0C28FC3A8C2AFC2B4';

UPDATE article
   SET category = '散文', update_time = update_time
 WHERE HEX(category) = 'C3A6E280A2C2A3C3A6E28093E280A1';

UPDATE article
   SET category = '现代诗', update_time = update_time
 WHERE HEX(category) = 'C3A7C5BDC2B0C3A4C2BBC2A3C3A8C2AFE28094';

-- 3.2 book 侧（4 种 HEX → 4 个分类值）
UPDATE book
   SET category = '小说', update_time = update_time
 WHERE HEX(category) = 'C3A5C2B0C28FC3A8C2AFC2B4';

UPDATE book
   SET category = '悬疑', update_time = update_time
 WHERE HEX(category) = 'C3A6E2809AC2ACC3A7E28093E28098';

UPDATE book
   SET category = '科幻', update_time = update_time
 WHERE HEX(category) = 'C3A7C2A7E28098C3A5C2B9C2BB';

UPDATE book
   SET category = '散文', update_time = update_time
 WHERE HEX(category) = 'C3A6E280A2C2A3C3A6E28093E280A1';

-- =============================================================================
-- 第四部分：修复后校验（只读）
-- =============================================================================

-- 4.1 剩余异常应为 0（严格按第一部分口径复查）
SELECT 'article' AS table_name, COUNT(*) AS remaining_mojibake
  FROM article
 WHERE category IS NOT NULL
   AND category <> ''
   AND HEX(category) IN ('C3A5C2B0C28FC3A8C2AFC2B4', 'C3A6E280A2C2A3C3A6E28093E280A1',
                         'C3A9C5A1C28FC3A7C2ACE2809D', 'C3A7C5BDC2B0C3A4C2BBC2A3C3A8C2AFE28094',
                         'C3A6E2809AC2ACC3A7E28093E28098', 'C3A7C2A7E28098C3A5C2B9C2BB')
UNION ALL
SELECT 'book' AS table_name, COUNT(*) AS remaining_mojibake
  FROM book
 WHERE category IS NOT NULL
   AND category <> ''
   AND HEX(category) IN ('C3A5C2B0C28FC3A8C2AFC2B4', 'C3A6E280A2C2A3C3A6E28093E280A1',
                         'C3A9C5A1C28FC3A7C2ACE2809D', 'C3A7C5BDC2B0C3A4C2BBC2A3C3A8C2AFE28094',
                         'C3A6E2809AC2ACC3A7E28093E28098', 'C3A7C2A7E28098C3A5C2B9C2BB');

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
