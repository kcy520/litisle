package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
     * @param articleId 文章ID
     * @param delta     待累加增量（>=1，由定时任务 GETDEL 聚合而来）
     * @return affected rows（0=文章不存在或已软删，调用方仅留痕不报错）
     */
    @Update("UPDATE article SET view_cnt = view_cnt + #{delta} "
            + "WHERE id = #{articleId} AND del_flag = 0")
    int incrViewCnt(@Param("articleId") Long articleId, @Param("delta") long delta);
}
