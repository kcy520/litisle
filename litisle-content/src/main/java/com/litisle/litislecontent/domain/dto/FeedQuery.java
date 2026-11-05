package com.litisle.litislecontent.domain.dto;

import com.litisle.litislecontent.enums.ZoneEnum;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 推荐流查询入参（批次十 10.2，文章流/书籍流共用）
 * <p>由 Controller 组装后传给 {@link com.litisle.litislecontent.service.FeedStreamService}，
 * 把分散的请求参数收口成一个对象，后续新增画像/种子字段时只改本类</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class FeedQuery {

    /** 登录用户ID（匿名时为 null；身份只作画像/去重/快照 key，不参与门控放行） */
    private Long userId;

    /** 匿名标识（服务端校验或兜底生成的 UUID v4；登录用户也为 null） */
    private String anonId;

    /** 下一页游标（快照内偏移编码；首次请求为 null） */
    private String cursor;

    /** 快照版本号（首次请求为 null；回传后用于校验快照是否失效） */
    private String snapshotId;

    /** 单页条数（null 时取配置 litisle.content.feed.page-size，收口后不超过上限） */
    private Integer pageSize;

    /** 文章流分区筛选（1-普通 / 2-作家；null 表示不筛选，书籍流恒为 null） */
    private ZoneEnum zone;
}
