-- =============================================================================
-- 存量互动统计回填脚本
-- 文件：tools/db-repair/content-stat-backfill.sql
--
-- 一、用途
--   把 litisle_content 的 article_stat / book_stat 与源表（favorite、article、
--   reading_history、book_chapter）重新对齐，补回被删除的 zz-content-stat.sql 所承担
--   的「存量统计重算」能力。只重算数值，不做任何结构性 DDL。
--
-- 二、适用场景（满足任一条再跑）
--   1) 老环境从 zz- 脚本时代升级，统计表数值落后于真实业务数据；
--   2) 对账任务（ContentStatReconcileJob）已报出差异，需要人工修复统计表；
--   3) 误操作/手工改数导致卡片收藏数、点赞数、浏览量、章节数与实际不符。
--
-- 三、不参与自动初始化（重要）
--   本文件位于 tools/db-repair/，不在 docker/init/mysql/ 及其任何子目录，
--   MySQL 容器 entrypoint 不会扫描到它。全新库的统计基线由
--   docker/init/mysql/seed/content-seed.sql 直接写入，不需要跑本脚本。
--
-- 四、执行前提
--   1) 能连接目标 MySQL，且账号对 litisle_content 具备 SELECT/INSERT/UPDATE 权限；
--   2) 建议低峰期执行：重算期间业务仍在做 +1/-1，可能出现短暂偏差，跑完后需再核对一次；
--   3) 不要对生产库加锁或停写，本脚本按主键分批，单批 1000 行，可随时中断。
--
-- 五、幂等性
--   全部使用 INSERT ... ON DUPLICATE KEY UPDATE，按固定主键覆盖为「源表聚合值」；
--   - 文章四项计数（收藏/点赞/评论/浏览）以源表为唯一真值，重复执行结果收敛；
--   - 书籍收藏数、章节数以源表为真值；
--   - 书籍阅读人数只取 GREATEST(现值, 源值)：reading_history 会按 90 天/单用户 1000 条裁剪，
--     源聚合天然偏小，不能把历史累计人数改小；
--   - 书籍点赞/浏览量无源表列（目前无采集链路），重算时保留现值，不做覆盖。
--   任意次重复执行不会产生重复行、不会把数值改乱。
--
-- 六、操作顺序（务必遵守）
--   1) 先跑「第一部分 执行前核对」，记录 6 项 diff_rows；
--   2) 再跑「第二部分 分批重算」，batch_size 默认 1000；
--   3) 最后跑「第三部分 执行后复核」，diff_rows 应全部为 0
--      （book_reader_under 因历史裁剪可能仍为旧值差，属预期）。
--
-- 七、执行方式（Windows 11 PowerShell，容器内执行）
--   Get-Content .\tools\db-repair\content-stat-backfill.sql -Raw |
--     docker exec -i litisle-mysql mysql -uroot -pverysecret --default-character-set=utf8mb4
--
-- 八、失败可观测性
--   每个批次结束会输出一行 progress（如 `article batch from 5001 done`）；
--   若中途报错，最后一行 progress 即为已完成的批次边界；修复原因后整体重跑即可。
-- =============================================================================

use litisle_content;

-- =============================================================================
-- 第一部分：执行前核对（只读）
-- 与 ContentStatReconcileJob 的口径保持一致：
--   article_collect = 源表差异 + 统计表残留（两者相加）
--   book_collect    = 源表差异 + 统计表残留（两者相加）
--   article_view/like/comment = 统计表缺失或与 article 镜像列不等
--   book_reader_under = 只看「源表聚合 > 统计表」的少计方向
-- =============================================================================

select 'article_collect' as type,
       (select count(*)
          from (select s.target_id,
                       s.cnt as src,
                       coalesce(a.collect_count, 0) as stat
                  from (select f.target_id, count(*) as cnt
                          from favorite f
                          join article a2 on a2.id = f.target_id
                           and a2.author_id <> f.user_id
                         where f.target_type = 1
                         group by f.target_id) s
                  left join article_stat a on a.article_id = s.target_id
                 where coalesce(a.collect_count, 0) <> s.cnt) t)
     + (select count(*)
          from article_stat a
          left join (select f.target_id, count(*) as cnt
                       from favorite f
                       join article a2 on a2.id = f.target_id
                        and a2.author_id <> f.user_id
                      where f.target_type = 1
                      group by f.target_id) s
            on s.target_id = a.article_id
         where s.target_id is null
           and a.collect_count <> 0) as diff_rows
union all
select 'book_collect' as type,
       (select count(*)
          from (select s.target_id,
                       s.cnt as src,
                       coalesce(b.collect_count, 0) as stat
                  from (select f.target_id, count(*) as cnt
                          from favorite f
                          join book b2 on b2.id = f.target_id
                           and b2.author_id <> f.user_id
                         where f.target_type = 2
                         group by f.target_id) s
                  left join book_stat b on b.book_id = s.target_id
                 where coalesce(b.collect_count, 0) <> s.cnt) t)
     + (select count(*)
          from book_stat b
          left join (select f.target_id, count(*) as cnt
                       from favorite f
                       join book b2 on b2.id = f.target_id
                        and b2.author_id <> f.user_id
                      where f.target_type = 2
                      group by f.target_id) s
            on s.target_id = b.book_id
         where s.target_id is null
           and b.collect_count <> 0) as diff_rows
union all
select 'article_view' as type,
       count(*) as diff_rows
  from article a
  left join article_stat s on s.article_id = a.id
 where a.del_flag = 0
   and coalesce(s.view_count, 0) <> a.view_cnt
union all
select 'article_like' as type,
       count(*) as diff_rows
  from article a
  left join article_stat s on s.article_id = a.id
 where a.del_flag = 0
   and coalesce(s.like_count, 0) <> a.like_cnt
union all
select 'article_comment' as type,
       count(*) as diff_rows
  from article a
  left join article_stat s on s.article_id = a.id
 where a.del_flag = 0
   and coalesce(s.comment_count, 0) <> a.comment_cnt
union all
select 'book_reader_under' as type,
       count(*) as diff_rows
  from (select rh.biz_id as book_id, count(distinct rh.user_id) as src
          from reading_history rh
         where rh.biz_type = 1
         group by rh.biz_id) r
  left join book_stat b on b.book_id = r.book_id
 where r.src > coalesce(b.reader_count, 0);

-- =============================================================================
-- 第二部分：分批重算（幂等，可中断）
-- 用临时存储过程驱动分批；结束时自动 drop，不留过程对象。
-- =============================================================================

drop procedure if exists sp_repair_content_stat;

delimiter //

create procedure sp_repair_content_stat(in p_batch_size int)
begin
    -- 当前批次起始 ID（含）
    declare v_cur bigint default 0;
    -- 该表最大 ID（含），用于循环终止
    declare v_max bigint default 0;
    -- 实际批次大小（入参非法时回落 1000）
    declare v_batch int default 0;

    -- 入参保护：空值/非正数统一按 1000 处理
    set v_batch = case when p_batch_size is null or p_batch_size <= 0 then 1000 else p_batch_size end;

    -- ---------- 2.1 文章统计 ----------
    -- 只重算未软删文章；已软删文章的历史统计行保持原样（不删除任何行）
    select coalesce(min(id), 0), coalesce(max(id), 0) into v_cur, v_max
      from article
     where del_flag = 0;
    while v_cur <= v_max do
        insert into article_stat (article_id, collect_count, like_count, comment_count, view_count)
        select a.id,
               -- 收藏数：排除作者自收藏（与 favorite 写入口径一致）
               (select count(*)
                  from favorite f
                 where f.target_type = 1
                   and f.target_id = a.id
                   and f.user_id <> a.author_id),
               -- 点赞数：article.like_cnt 为镜像列，统计表跟随它
               a.like_cnt,
               -- 评论数：social 回写 article.comment_cnt
               a.comment_cnt,
               -- 浏览量：ViewCountFlushJob 落库后的绝对值
               a.view_cnt
          from article a
         where a.del_flag = 0
           and a.id >= v_cur
           and a.id < v_cur + v_batch
        on duplicate key update
            collect_count = values(collect_count),
            like_count = values(like_count),
            comment_count = values(comment_count),
            view_count = values(view_count);
        -- 进度输出：中断时以最后一行为准
        select concat('article batch from ', v_cur, ' done') as progress;
        set v_cur = v_cur + v_batch;
    end while;

    -- ---------- 2.2 书籍统计 ----------
    -- 只重算未软删书籍
    select coalesce(min(id), 0), coalesce(max(id), 0) into v_cur, v_max
      from book
     where del_flag = 0;
    while v_cur <= v_max do
        insert into book_stat (book_id, collect_count, reader_count, chapter_count, like_count, view_count)
        select b.id,
               -- 收藏数：排除作者自收藏
               (select count(*)
                  from favorite f
                 where f.target_type = 2
                   and f.target_id = b.id
                   and f.user_id <> b.author_id),
               -- 阅读人数：reading_history 去重用户（源聚合可能被裁剪，更新时取 GREATEST）
               (select count(distinct rh.user_id)
                  from reading_history rh
                 where rh.biz_type = 1
                   and rh.biz_id = b.id),
               -- 章节数：只统计「未删 + 已发布(status=2) + 公开(visibility=1)」章节
               (select count(*)
                  from book_chapter bc
                  join article a on a.id = bc.article_id
                 where bc.book_id = b.id
                   and a.status = 2
                   and a.del_flag = 0
                   and a.visibility = 1),
               -- 书籍点赞数：无源表列，新行先写 0，已存在时由 update 段保留现值
               0,
               -- 书籍浏览量：无采集链路，同上保留现值
               0
          from book b
         where b.del_flag = 0
           and b.id >= v_cur
           and b.id < v_cur + v_batch
        on duplicate key update
            collect_count = values(collect_count),
            -- 只增不减：避免把 reading_history 裁剪前的历史人数改小
            reader_count = greatest(reader_count, values(reader_count)),
            chapter_count = values(chapter_count),
            -- 点赞/浏览无源表真值，保留库里现值
            like_count = book_stat.like_count,
            view_count = book_stat.view_count;
        select concat('book batch from ', v_cur, ' done') as progress;
        set v_cur = v_cur + v_batch;
    end while;
end //

delimiter ;

call sp_repair_content_stat(1000);

drop procedure if exists sp_repair_content_stat;

-- =============================================================================
-- 第三部分：执行后复核（口径与第一部分完全一致）
-- 期望：article_collect / book_collect / article_view / article_like /
--       article_comment 全部为 0；
--       book_reader_under 若仍非 0，说明历史裁剪导致 source 小于库内累计值，
--       属预期（库内值不应被改小）。
-- =============================================================================

select 'article_collect' as type,
       (select count(*)
          from (select s.target_id,
                       s.cnt as src,
                       coalesce(a.collect_count, 0) as stat
                  from (select f.target_id, count(*) as cnt
                          from favorite f
                          join article a2 on a2.id = f.target_id
                           and a2.author_id <> f.user_id
                         where f.target_type = 1
                         group by f.target_id) s
                  left join article_stat a on a.article_id = s.target_id
                 where coalesce(a.collect_count, 0) <> s.cnt) t)
     + (select count(*)
          from article_stat a
          left join (select f.target_id, count(*) as cnt
                       from favorite f
                       join article a2 on a2.id = f.target_id
                        and a2.author_id <> f.user_id
                      where f.target_type = 1
                      group by f.target_id) s
            on s.target_id = a.article_id
         where s.target_id is null
           and a.collect_count <> 0) as diff_rows
union all
select 'book_collect' as type,
       (select count(*)
          from (select s.target_id,
                       s.cnt as src,
                       coalesce(b.collect_count, 0) as stat
                  from (select f.target_id, count(*) as cnt
                          from favorite f
                          join book b2 on b2.id = f.target_id
                           and b2.author_id <> f.user_id
                         where f.target_type = 2
                         group by f.target_id) s
                  left join book_stat b on b.book_id = s.target_id
                 where coalesce(b.collect_count, 0) <> s.cnt) t)
     + (select count(*)
          from book_stat b
          left join (select f.target_id, count(*) as cnt
                       from favorite f
                       join book b2 on b2.id = f.target_id
                        and b2.author_id <> f.user_id
                      where f.target_type = 2
                      group by f.target_id) s
            on s.target_id = b.book_id
         where s.target_id is null
           and b.collect_count <> 0) as diff_rows
union all
select 'article_view' as type,
       count(*) as diff_rows
  from article a
  left join article_stat s on s.article_id = a.id
 where a.del_flag = 0
   and coalesce(s.view_count, 0) <> a.view_cnt
union all
select 'article_like' as type,
       count(*) as diff_rows
  from article a
  left join article_stat s on s.article_id = a.id
 where a.del_flag = 0
   and coalesce(s.like_count, 0) <> a.like_cnt
union all
select 'article_comment' as type,
       count(*) as diff_rows
  from article a
  left join article_stat s on s.article_id = a.id
 where a.del_flag = 0
   and coalesce(s.comment_count, 0) <> a.comment_cnt
union all
select 'book_reader_under' as type,
       count(*) as diff_rows
  from (select rh.biz_id as book_id, count(distinct rh.user_id) as src
          from reading_history rh
         where rh.biz_type = 1
         group by rh.biz_id) r
  left join book_stat b on b.book_id = r.book_id
 where r.src > coalesce(b.reader_count, 0);
