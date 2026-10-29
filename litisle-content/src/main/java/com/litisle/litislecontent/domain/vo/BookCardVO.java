package com.litisle.litislecontent.domain.vo;

import com.litisle.litislecontent.enums.BookStatusEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 书籍卡片 VO（公开书籍列表 / 我的书籍 列表项，P2 书籍发现入口 2026-10-28）
 * <p>列表卡片字段：无章节明细（走详情/章节分页接口）；
 * book 无热度计数冗余列，热度排序待计数落地后扩展（见 temp.md P3 书籍收藏数条目）</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class BookCardVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 书籍ID */
    private Long id;

    /** 作者ID */
    private Long authorId;

    /** 书名 */
    private String title;

    /** 简介（varchar(500)，列表卡片直接展示） */
    private String intro;

    /** 封面文件ID（SeaweedFS，可空=无封面占位） */
    private String coverFileId;

    /** 分类（自由字符串，默认"小说"） */
    private String category;

    /** 状态：连载中/已完结/下架 */
    private BookStatusEnum status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
