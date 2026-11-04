package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * @Mapper + extends BaseMapper<Article>（MyBatis-Plus）
 *
 * 关键查询（优先用 LambdaQueryWrapper / LambdaUpdateWrapper 组装，必要时再补 @Select）：
 * - 详情页取文：selectOne(id + del_flag=0) —— 主键直达，状态/可见性过滤放业务层
 * - 作者文章列表：selectPage(author_id + del_flag=0 + status) —— 走 idx_author_id，
 *   按 create_time 倒序分页（我的文章/书籍章节挂载选文均走此口径）
 * - 分区浏览：selectPage(zone + status=PUBLISHED + visibility=PUBLIC + del_flag=0) —— 分区列表页
 * - 批量发布回滚：update(status→REJECTED, publish_batch_id=?) —— 同批次审核驳回时按批次号回滚，
 *   需带 version 乐观锁防与作者编辑并发冲突
 * - 计数刷新：update(view_cnt/like_cnt/comment_cnt) —— Redis 异步回刷冗余计数，
 *   用 setSql("view_cnt = view_cnt + #{delta}") 原子自增，禁先查后写（并发丢更新）
 * - 冗余计数只增不减由业务侧保证幂等；content_hash 用于免审直落判重（发布链路校验）
 */
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    /**
     * 浏览计数原子自增（阶段8①：Redis 待刷回增量合并回写）。
     *
     * 作用：view_cnt = view_cnt + delta 原子自增（SQL 层单语句，无先查后写并发丢更新）；
     * 仅刷回活跃文章（del_flag=0，软删文章行保留但计数无对外语义，不刷避免复活漂移）。
     *
     * 时间列口径（批次E P2 定案 D-1 2026-11-03）：
     * - update_time = update_time：显式赋原值抑制 ON UPDATE CURRENT_TIMESTAMP 自动刷新，
     *   浏览量刷回不再把老文章顶到「最新列表」顶部（列表按 update_time 倒序展示）；
     * - stat_update_time = CURRENT_TIMESTAMP：热度信号统计时间只由本语句（浏览）与
     *   updateCounters（点赞/评论回写）刷新，供热度榜增量灌榜按水位捞变更文章。
     *
     * @param articleId 文章ID
     * @param delta     待累加增量（>=1，由定时任务 GETDEL 聚合而来）
     * @return affected rows（0=文章不存在或已软删，调用方仅留痕不报错）
     */
    @Update("UPDATE article SET view_cnt = view_cnt + #{delta}, "
            + "update_time = update_time, "
            + "stat_update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{articleId} AND del_flag = 0")
    int incrViewCnt(@Param("articleId") Long articleId, @Param("delta") long delta);

    /**
     * 查询文章当前浏览量（只取 view_cnt 单列，避开详情大字段 content_html）。
     *
     * 作用：浏览计数刷回成功后，取落库后的最新绝对值填 `content.view-count-flushed`
     * 事件体（事件契约为覆盖语义，消费端直接 SET，不能传增量）。
     *
     * @param articleId 文章ID
     * @return 当前浏览量绝对值（文章不存在/已软删返回 null，调用方跳过事件发送）
     */
    @Select("SELECT view_cnt FROM article WHERE id = #{articleId} AND del_flag = 0")
    Long selectViewCnt(@Param("articleId") Long articleId);
}
