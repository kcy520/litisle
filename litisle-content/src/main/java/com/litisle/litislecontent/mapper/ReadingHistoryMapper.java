package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.dto.IdCountDTO;
import com.litisle.litislecontent.domain.entity.ReadingHistory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * @Mapper + extends BaseMapper<ReadingHistory>（MyBatis-Plus）
 *
 * 关键查询：
 * - 断点续读：selectOne(user_id + biz_type + biz_id) —— (user_id, biz_type, biz_id) 唯一键 uk_user_biz，
 *   每人每类型每对象一条历史，update_time 即最后阅读时间（书籍续读固定 biz_type=1、biz_id=book_id）
 * - 历史落库 upsert：insert 冲突则 update(chapter_id, update_time) —— 业务层捕获 uk_user_biz 唯一键冲突转 update，
 *   或用 insert ... on duplicate key update 语义（MP 侧用 saveOrUpdate 思路组装）
 * - 阅读历史列表：selectMyHistoryPage 自定义 join 查询（SQL 层过滤软删对象 + 分页 + total，
 *   避免「先分页后内存过滤」导致 total 虚高/空页）；bizType=null 全部混合时间线走 idx_user_time_all，
 *   bizType=1/2 单类型 tab 走 idx_user_time；title/cover 等对象信息由业务层按 biz_type 分支联合
 *   BookMapper / ArticleMapper 批量取（in 查询防 N+1）
 * - 高频小写：历史上报有限流（ContentLimitConstant），DB 写压力可控，不引入缓存层
 */
@Mapper
public interface ReadingHistoryMapper extends BaseMapper<ReadingHistory> {

    /**
     * 分页查询当前用户阅读历史（SQL 层完成软删过滤、排序、分页与 total）
     *
     * 查询当前用户阅读历史分页列表，在 SQL 层直接完成「软删过滤 + 多态 JOIN + 动态类型过滤 + 稳定排序」，
     * 返回给前端的是干净的、可直接展示的数据。
     *
     * <p>查询口径：
     * <ul>
     *   <li>左连 book / article 并过滤 del_flag=0：对象已软删（或不存在）的历史行不出现在结果与 total 中；
     *       biz_type=1 只认 book 行，biz_type=2 只认 article 行（多态关联在 JOIN ON 中判别）</li>
     *   <li>bizType 条件用 if 动态拼接（不依赖 ? IS NULL 的驱动行为）：null=全部（书籍+文章混合时间线），
     *       1=书籍 tab，2=独立文章 tab</li>
     *   <li>order by update_time desc, id desc：update_time 倒序为主序，id 作稳定排序 tie-breaker，防同秒更新导致翻页漂移</li>
     *   <li>分页由 MyBatis-Plus 分页插件基于 page 参数自动追加 LIMIT 并生成 count 语句，total 只统计可见行</li>
     * </ul>
     *
     * @param page    分页参数（Service 层用 Page.of(pageNo, pageSize) 构造，不走 PageQuery 默认 update_time 排序，避免与 SQL 排序叠加）
     * @param userId  当前登录用户ID（历史只查本人；越权防护由 Controller/Service 以登录态注入保证）
     * @param bizType 阅读对象类型过滤：null-全部 1-书籍 2-独立文章（非法值由 Service 层白名单校验拦截）
     * @return 历史行分页结果（chapter_id 仅 biz_type=1 有值；title/cover 等对象信息由 Service 层批量装配）
     */
    // 多态关联/可见性过滤中的 1/2 统一拼接 ContentBizConstant.READING_BIZ_TYPE_* 编译期常量，
    // 与 ReadingTargetTypeEnum 的 code 保持同源，禁止再写裸数字
    @Select("<script>"
            + "select rh.* from reading_history rh "
            + "left join book b on rh.biz_type = " + ContentBizConstant.READING_BIZ_TYPE_BOOK + " and b.id = rh.biz_id "
            + "left join article a on rh.biz_type = " + ContentBizConstant.READING_BIZ_TYPE_ARTICLE + " and a.id = rh.biz_id "
            + "where rh.user_id = #{userId} "
            + "<if test='bizType != null'>and rh.biz_type = #{bizType} </if>"
            + "and ((rh.biz_type = " + ContentBizConstant.READING_BIZ_TYPE_BOOK + " and b.del_flag = 0) "
            + "or (rh.biz_type = " + ContentBizConstant.READING_BIZ_TYPE_ARTICLE + " and a.del_flag = 0)) "
            + "order by rh.update_time desc, rh.id desc"
            + "</script>")
    Page<ReadingHistory> selectMyHistoryPage(Page<ReadingHistory> page,
                                             @Param("userId") Long userId,
                                             @Param("bizType") Integer bizType);

    /**
     * 阅读历史裁剪：删除「过期或超额」且不属于「在读保留」的行（批次十一 11.4）
     *
     * <p>删除候选（同时满足）：</p>
     * <ul>
     *   <li>该用户该类型下的行；</li>
     *   <li>满足其一：update_time 早于 cutoff（时间窗过期），或不在最新 keepCount 条内（超出条数上限）；</li>
     *   <li>且不属于最近 keepInProgress 条「在读」书籍历史（在读豁免）。</li>
     * </ul>
     *
     * <p>在读判定（仅用现有字段，不新增埋点）：</p>
     * <ul>
     *   <li>取该书的最大「可见章节号」（book_chapter join article，仅未删+已发布+公开）；</li>
     *   <li>历史最后阅读章节号小于该最大值即视为未读完（在读）；</li>
     *   <li>chapter_id 为空 / 章节行已删 / 书籍无可见章节时无法判断，保守按在读保留。</li>
     * </ul>
     *
     * <p>实现说明：</p>
     * <ul>
     *   <li>三个派生表各自先按 update_time desc, id desc 取最新 N 条（与历史列表同款稳定排序），
     *       再 join 回主表；不写「delete ... where 自引用子查询带 limit」（MySQL 不支持该写法）；</li>
     *   <li>删除时按 update_time asc 从最旧开始，单批最多 batchSize 条，防异常数据一次大删；</li>
     *   <li>索引支撑：idx_user_time(user_id, biz_type, update_time) 覆盖过滤与排序；</li>
     *   <li>keepInProgress 传 0（文章类型）时 LIMIT 0 返回空集，文章不参与在读豁免。</li>
     * </ul>
     *
     * @param userId         读者ID
     * @param bizType        阅读对象类型：1-书籍 2-独立文章
     * @param cutoff         时间窗起点（早于该时刻的行进入删除候选）
     * @param keepCount      条数上限（按最后阅读时间倒序保留最新 keepCount 条）
     * @param keepInProgress 在读保留条数（仅书籍类型生效；文章传 0）
     * @param batchSize      单批删除上限
     * @return 实际删除行数
     */
    @Delete("<script>"
            + "delete rh from reading_history rh "
            + "join ("
            + "  select h.id from reading_history h "
            // 条数上限豁免集：最新 keepCount 条
            + "  left join (select id from reading_history "
            + "             where user_id = #{userId} and biz_type = #{bizType} "
            + "             order by update_time desc, id desc "
            + "             limit #{keepCount}) keep on keep.id = h.id "
            // 在读豁免集：最近 keepInProgress 条未读完的书籍历史
            + "  left join (select rh2.id from reading_history rh2 "
            + "             left join book_chapter bc on bc.id = rh2.chapter_id "
            + "             left join (select bc2.book_id as book_id, max(bc2.chapter_no) as max_no "
            + "                        from book_chapter bc2 "
            + "                        join article a2 on a2.id = bc2.article_id "
            + "                         and a2.del_flag = 0 and a2.status = 2 and a2.visibility = 1 "
            + "                        group by bc2.book_id) mx on mx.book_id = rh2.biz_id "
            + "             where rh2.user_id = #{userId} "
            + "               and rh2.biz_type = " + ContentBizConstant.READING_BIZ_TYPE_BOOK + " "
            + "               and (rh2.chapter_id is null or bc.id is null or mx.max_no is null "
            // 注：MyBatis 注解 SQL 会被当作 XML 解析，比较符必须转义，否则启动即报 SAXParseException
            + "                    or bc.chapter_no &lt; mx.max_no) "
            + "             order by rh2.update_time desc, rh2.id desc "
            + "             limit #{keepInProgress}) prot on prot.id = h.id "
            + "  where h.user_id = #{userId} and h.biz_type = #{bizType} "
            + "    and prot.id is null "
            + "    and (h.update_time &lt; #{cutoff} or keep.id is null) "
            + "  order by h.update_time asc, h.id asc "
            + "  limit #{batchSize}"
            + ") t on rh.id = t.id"
            + "</script>")
    int deleteOverflowOldest(@Param("userId") Long userId,
                             @Param("bizType") Integer bizType,
                             @Param("cutoff") LocalDateTime cutoff,
                             @Param("keepCount") int keepCount,
                             @Param("keepInProgress") int keepInProgress,
                             @Param("batchSize") int batchSize);

    /**
     * 批量统计书籍阅读人数（批次十 10.4 书籍流排序输入）
     *
     * <p>口径：biz_type=1（书籍）按 biz_id 分组，count(distinct user_id) 去重读者，
     * 同一用户重复阅读只计一人；调用方保证 bookIds 非空。</p>
     *
     * @param bookIds 书籍 ID 集合（非空）
     * @return 每本书的阅读人数（无阅读记录的书不在结果中，调用方按 0 处理）
     * @deprecated 已由统计表 book_stat.reader_count 替代，保留供回退与对账复用，禁止删除。
     */
    @Deprecated
    @Select("""
            <script>
            select biz_id as id, count(distinct user_id) as cnt
            from reading_history
            where biz_type = 1
              and biz_id in
              <foreach collection="bookIds" item="bookId" open="(" separator="," close=")">#{bookId}</foreach>
            group by biz_id
            </script>
            """)
    List<IdCountDTO> countBookReaders(@Param("bookIds") Collection<Long> bookIds);
}