package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.litislecontent.domain.dto.IdCountDTO;
import com.litisle.litislecontent.domain.entity.Favorite;
import com.litisle.litislecontent.domain.vo.FavoriteItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * favorite 收藏表 Mapper（阶段16：一期 content 临时承载单层收藏）
 * <p>收藏状态等值查询直接复用 BaseMapper#selectCount（uk_user_target 唯一键）；
 * 我的收藏分页用自定义多态 join SQL，SQL 层完成过滤、排序与 total</p>
 */
@Mapper
public interface FavoriteMapper extends BaseMapper<Favorite> {

    /**
     * 我的收藏分页（多态 join：文章/书籍按 target_type 分支取标题/封面/摘要/作者）。
     *
     * 口径：
     * ① 左连 article / book，ON 条件按 target_type 判别，互不干扰；
     * ② 列表只过滤目标已软删（del_flag=1）的行——目标转私密/下架仍保留展示（用户自己收藏过），
     *    点击详情时由详情可见性兜底（阶段16 C⑤ 口径）；
     * ③ order by create_time desc, id desc——create_time 倒序 + id 作稳定排序 tie-breaker，防翻页漂移；
     * ④ targetType 条件用 &lt;if&gt; 动态拼接（null=全部，不依赖 ? IS NULL 的驱动行为）；
     * ⑤ SQL 层完成分页与 total，分页插件拦截 Page 参数。
     *
     * @param page       MyBatis-Plus 分页参数（Page.of 构造，调用方控制页码/条数）
     * @param userId     收藏用户ID
     * @param targetType 目标类型过滤（null=全部混合时间线；1-文章 2-书籍）
     * @return 收藏列表项分页（含目标冗余信息）
     */
    @Select("""
            <script>
            select
                f.target_type                                        as targetType,
                f.target_id                                          as targetId,
                case f.target_type when 1 then a.title  when 2 then b.title  end as title,
                case f.target_type when 1 then a.cover_file_id when 2 then b.cover_file_id end as coverFileId,
                case f.target_type when 1 then a.summary when 2 then b.intro end as summary,
                case f.target_type when 1 then a.author_id when 2 then b.author_id end as authorId,
                f.create_time                                        as favoriteTime
            from favorite f
            left join article a on f.target_type = 1 and a.id = f.target_id
            left join book b    on f.target_type = 2 and b.id = f.target_id
            where f.user_id = #{userId}
              and (
                    (f.target_type = 1 and a.del_flag = 0)
                 or (f.target_type = 2 and b.del_flag = 0)
                  )
              <if test="targetType != null">
              and f.target_type = #{targetType}
              </if>
            order by f.create_time desc, f.id desc
            </script>
            """)
    Page<FavoriteItemVO> selectMyFavorites(Page<FavoriteItemVO> page,
                                           @Param("userId") Long userId,
                                           @Param("targetType") Integer targetType);

    /**
     * 批量统计书籍收藏数（批次十 10.4 书籍流排序输入）
     *
     * <p>口径：target_type=2（书籍）按 target_id 分组计数，一次查询覆盖整页候选，
     * 避免逐本查询造成 N+1；调用方保证 bookIds 非空。</p>
     *
     * @param bookIds 书籍 ID 集合（非空）
     * @return 每本书的收藏数（无收藏的书不在结果中，调用方按 0 处理）
     */
    @Select("""
            <script>
            select target_id as id, count(*) as cnt
            from favorite
            where target_type = 2
              and target_id in
              <foreach collection="bookIds" item="bookId" open="(" separator="," close=")">#{bookId}</foreach>
            group by target_id
            </script>
            """)
    List<IdCountDTO> countBookCollects(@Param("bookIds") Collection<Long> bookIds);
}
