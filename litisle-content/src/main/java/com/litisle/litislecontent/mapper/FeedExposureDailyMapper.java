package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.dto.FeedCtrDTO;
import com.litisle.litislecontent.domain.entity.FeedExposureDaily;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 推荐流曝光/点击日聚合 Mapper（批次十一 11.6 + 11.12）
 *
 * <p>职责：</p>
 * <ol>
 *   <li>曝光批量 upsert（一页 20 条只发一条 SQL，避免每页 20 次往返）；</li>
 *   <li>点击单行 upsert（同身份同日同条已由 Redis 点击 Set 去重，DB 只负责 +1）；</li>
 *   <li>过期行分批清理（保留天数由 {@code litisle.content.feed.exposure.retain-days} 控制）；</li>
 *   <li>CTR 只读查询（登录口径 / 全站口径共用同一方法，由 {@code identityType} 入参区分）。</li>
 * </ol>
 *
 * <p>为什么 CTR 不在线聚合：本表本身就是「按天」聚合结果，报告直接按日期区间查询即可；
 * 再加一张结果表只会多一次同步与一份重复数据，故不建汇总结果表（批次十一 11.12 选择）。</p>
 *
 * @author litisle
 */
@Mapper
public interface FeedExposureDailyMapper extends BaseMapper<FeedExposureDaily> {

    /**
     * 曝光批量累加（一条 SQL 多行 values + on duplicate key update）。
     *
     * <p>幂等/去重说明：同一身份同一天同一条只曝光一次由 Redis 当日曝光 Set 保证，
     * 本方法只会在「确认是新曝光」时被调用，因此重复键直接 +1 即为正确口径；
     * 不另建去重键，避免双份去重状态不一致。</p>
     *
     * @param statDate     统计日期（按天）
     * @param stream       流类型（article/book）
     * @param identityType 身份口径（login/anon）
     * @param targetIds    本页实际返回的内容 ID（调用方保证非空）
     * @return 受影响行数（MySQL 对 duplicate update 每行计 2，调用方不依赖该值）
     */
    @Insert("""
            <script>
            insert into feed_exposure_daily
              (stat_date, stream, target_id, identity_type, exposure_count, click_count)
            values
            <foreach collection="targetIds" item="targetId" separator=",">
              (#{statDate}, #{stream}, #{targetId}, #{identityType}, 1, 0)
            </foreach>
            on duplicate key update exposure_count = exposure_count + 1
            </script>
            """)
    int batchIncreaseExposure(@Param("statDate") LocalDate statDate,
                              @Param("stream") String stream,
                              @Param("identityType") String identityType,
                              @Param("targetIds") Collection<Long> targetIds);

    /**
     * 点击累加（单行 upsert）。
     *
     * <p>行不存在时按「曝光 0、点击 1」插入：正常链路点击前必有曝光行，
     * 出现 exposure_count=0 的点击行说明曝光写入曾失败，CTR 查询会因分母为 0 返回 null，
     * 不会产生除零错误。</p>
     *
     * @param statDate     统计日期
     * @param stream       流类型
     * @param targetId     内容 ID
     * @param identityType 身份口径
     * @return 受影响行数
     */
    @Insert("insert into feed_exposure_daily "
            + "  (stat_date, stream, target_id, identity_type, exposure_count, click_count) "
            + "values (#{statDate}, #{stream}, #{targetId}, #{identityType}, 0, 1) "
            + "on duplicate key update click_count = click_count + 1")
    int increaseClick(@Param("statDate") LocalDate statDate,
                      @Param("stream") String stream,
                      @Param("targetId") Long targetId,
                      @Param("identityType") String identityType);

    /**
     * 分批删除过期日聚合行（保留天数之外的数据）。
     *
     * <p>删除条件以 {@code stat_date} 开头，命中唯一键最左列，无需额外索引；
     * 调用方循环分批执行，避免一次大删长时间锁表。</p>
     *
     * @param cutoffDate 保留起点（早于该日期的行删除）
     * @param batchSize  单批最多删除行数
     * @return 本批实际删除行数
     */
    @Delete("delete from feed_exposure_daily where stat_date < #{cutoffDate} limit #{batchSize}")
    int deleteBefore(@Param("cutoffDate") LocalDate cutoffDate,
                     @Param("batchSize") int batchSize);

    /**
     * 按内容查询 CTR（批次十一 11.12）。
     *
     * <p>两种口径共用本方法：</p>
     * <ul>
     *   <li>登录 CTR：{@code identityType = "login"}，仅登录身份的 click/exposure；</li>
     *   <li>全站 CTR：{@code identityType = null}，含匿名身份。</li>
     * </ul>
     *
     * <p>分母保护：曝光合计为 0 时 ctr 返回 null（没有分母），而不是 0（有曝光但无人点击）。</p>
     *
     * @param stream       流类型（article/book）
     * @param startDate    统计起始日（含）
     * @param endDate      统计结束日（含）
     * @param identityType 身份口径（login / anon / null=全部）
     * @return 每个内容的曝光、点击与 CTR
     */
    @Select("""
            <script>
            select target_id,
                   sum(exposure_count) as exposure_count,
                   sum(click_count) as click_count,
                   case when sum(exposure_count) = 0 then null
                        else round(sum(click_count) / sum(exposure_count), 4) end as ctr
            from feed_exposure_daily
            where stream = #{stream}
              and stat_date &gt;= #{startDate}
              and stat_date &lt;= #{endDate}
            <if test="identityType != null and identityType != ''">
              and identity_type = #{identityType}
            </if>
            group by target_id
            </script>
            """)
    List<FeedCtrDTO> selectCtrByTarget(@Param("stream") String stream,
                                       @Param("startDate") LocalDate startDate,
                                       @Param("endDate") LocalDate endDate,
                                       @Param("identityType") String identityType);

    /**
     * 按天查询 CTR 趋势（批次十一 11.12，供报表按天看趋势）。
     *
     * <p>口径入参同 {@link #selectCtrByTarget}：login=登录口径，null=全站口径（含匿名）。
     * 曝光为 0 的日期 ctr 返回 null。</p>
     *
     * @param stream       流类型（article/book）
     * @param startDate    统计起始日（含）
     * @param endDate      统计结束日（含）
     * @param identityType 身份口径（login / anon / null=全部）
     * @return 每天的曝光、点击与 CTR
     */
    @Select("""
            <script>
            select stat_date,
                   sum(exposure_count) as exposure_count,
                   sum(click_count) as click_count,
                   case when sum(exposure_count) = 0 then null
                        else round(sum(click_count) / sum(exposure_count), 4) end as ctr
            from feed_exposure_daily
            where stream = #{stream}
              and stat_date &gt;= #{startDate}
              and stat_date &lt;= #{endDate}
            <if test="identityType != null and identityType != ''">
              and identity_type = #{identityType}
            </if>
            group by stat_date
            order by stat_date
            </script>
            """)
    List<FeedCtrDTO> selectDailyCtr(@Param("stream") String stream,
                                    @Param("startDate") LocalDate startDate,
                                    @Param("endDate") LocalDate endDate,
                                    @Param("identityType") String identityType);
}
