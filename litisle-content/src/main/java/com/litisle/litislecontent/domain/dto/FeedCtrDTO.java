package com.litisle.litislecontent.domain.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CTR 查询结果 DTO（批次十一 11.12）
 *
 * <p>同一份结果支持两种查询视角：按内容（{@code targetId} 有值）与按天（{@code statDate} 有值）。
 * 是否含匿名由查询入参 {@code identityType} 决定：传 login 是登录口径，传 null 是全站口径。</p>
 *
 * <p>分母保护：曝光为 0 时 {@code ctr} 返回 null（表示「没有分母」），而不是 0（表示「有曝光但无人点击」）。</p>
 *
 * @author litisle
 */
@Data
public class FeedCtrDTO {

    /** 统计日期（按天查询视角有值；按内容查询视角为 null） */
    private LocalDate statDate;

    /** 内容 ID（按内容查询视角有值；按天查询视角为 null） */
    private Long targetId;

    /** 曝光人数合计（分母） */
    private Long exposureCount;

    /** 点击人数合计（分子） */
    private Long clickCount;

    /** 点击率 = clickCount / exposureCount；曝光为 0 时为 null */
    private BigDecimal ctr;
}
