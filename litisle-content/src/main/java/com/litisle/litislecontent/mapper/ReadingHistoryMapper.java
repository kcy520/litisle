package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.litislecontent.domain.entity.ReadingHistory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
    @Select("<script>"
            + "select rh.* from reading_history rh "
            + "left join book b on rh.biz_type = 1 and b.id = rh.biz_id "
            + "left join article a on rh.biz_type = 2 and a.id = rh.biz_id "
            + "where rh.user_id = #{userId} "
            + "<if test='bizType != null'>and rh.biz_type = #{bizType} </if>"
            + "and ((rh.biz_type = 1 and b.del_flag = 0) or (rh.biz_type = 2 and a.del_flag = 0)) "
            + "order by rh.update_time desc, rh.id desc"
            + "</script>")
    Page<ReadingHistory> selectMyHistoryPage(Page<ReadingHistory> page,
                                             @Param("userId") Long userId,
                                             @Param("bizType") Integer bizType);

    /**
     * 删除单用户单类型下超出保留条数的最旧行（阅读历史增长治理懒删）
     *
     * <p>实现说明：
     * <ul>
     *   <li>子查询先按 update_time desc, id desc（与历史列表同款稳定排序）取出该用户该类型
     *       第 offset 条之后的行（即最新 keepCount 条之外的最旧行），再 join 回主表按主键删除</li>
     *   <li>不写「delete ... where 自引用子查询带 limit」：MySQL 不支持 delete 目标表直接
     *       引用带 limit 的子查询，join 派生表写法是标准解法</li>
     *   <li>rows 不足 offset 条时子查询为空集，删除 0 行，无副作用</li>
     *   <li>索引支撑：idx_user_time(user_id, biz_type, update_time) 覆盖子查询过滤与排序</li>
     * </ul>
     *
     * @param userId    读者ID
     * @param bizType   阅读对象类型：1-书籍 2-独立文章
     * @param offset    保留条数（保留最新 offset 条，删除其后所有行）
     * @param batchSize 单批删除上限（防御性封顶，防异常数据导致一次大删）
     * @return 实际删除行数
     */
    @Delete("<script>"
            + "delete rh from reading_history rh "
            + "join (select id from reading_history "
            + "where user_id = #{userId} and biz_type = #{bizType} "
            + "order by update_time desc, id desc "
            + "limit #{offset}, #{batchSize}) t on rh.id = t.id"
            + "</script>")
    int deleteOverflowOldest(@Param("userId") Long userId,
                             @Param("bizType") Integer bizType,
                             @Param("offset") int offset,
                             @Param("batchSize") int batchSize);
}