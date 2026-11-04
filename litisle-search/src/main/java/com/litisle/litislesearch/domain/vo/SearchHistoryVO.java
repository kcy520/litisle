package com.litisle.litislesearch.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 搜索历史列表项（批次六 6.2）。
 *
 * 【字段口径】只暴露展示所需字段，不回传 userId（防前端误用/user_id 外泄）。
 *
 * @author litisle
 */
@Data
public class SearchHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 搜索关键词（前端点击可回填搜索框） */
    private String keyword;

    /** 最近搜索时间（前端按此时间展示/排序） */
    private LocalDateTime updatedAt;
}
