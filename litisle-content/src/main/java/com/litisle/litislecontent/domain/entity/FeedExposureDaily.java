package com.litisle.litislecontent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 推荐流曝光/点击日聚合实体（批次十一 11.6 + 11.12）
 *
 * <p>一行 = 某天 + 某条流 + 某个内容 + 某类身份口径的曝光/点击人数。
 * 业务唯一性由唯一键 {@code uk_feed_exposure_daily(stat_date, stream, target_id, identity_type)} 保证，
 * 写入统一走 {@link com.litisle.litislecontent.mapper.FeedExposureDailyMapper} 的 upsert 语句。</p>
 *
 * <p>口径说明：</p>
 * <ul>
 *   <li>{@code exposure_count}：当日曝光该内容的身份数，同身份同日同条最多计 1 次（Redis 曝光 Set 去重）；</li>
 *   <li>{@code click_count}：当日点击该内容的身份数，同身份同日同条最多计 1 次（Redis 点击 Set 去重）；</li>
 *   <li>{@code identity_type}：login=登录用户（按 userId 区分身份），anon=匿名（按 X-Anon-Id 区分身份）。</li>
 * </ul>
 *
 * @author litisle
 */
@Data
@TableName("feed_exposure_daily")
public class FeedExposureDaily {

    /** 物理自增主键（业务唯一性不依赖它） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 统计日期（按天聚合；清理任务按该列删除过期行） */
    private LocalDate statDate;

    /** 流类型：article=文章流 / book=书籍流（取值来自 FeedConstant.STREAM_*） */
    private String stream;

    /** 内容 ID（文章 ID 或书籍 ID，按 stream 区分命名空间） */
    private Long targetId;

    /** 身份口径：login=登录用户 / anon=匿名（取值来自 FeedConstant.IDENTITY_TYPE_*） */
    private String identityType;

    /** 当日曝光人数（同身份同日同条最多 +1） */
    private Integer exposureCount;

    /** 当日点击人数（同身份同日同条最多 +1） */
    private Integer clickCount;

    /** 最后写入时间（由数据库 on update 自动维护） */
    private LocalDateTime updateTime;
}
