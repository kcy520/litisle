package com.litisle.litislecontent.domain.vo;

import com.litisle.litislecontent.enums.BookStatusEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 书籍流条目 VO（批次十 10.1 新增，书籍流不参与 zone，按 category 组织）
 * <p>字段口径对齐 {@link BookCardVO}，减少前端两处卡片适配；不带章节列表（不做章节流）</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class BookFeedItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 书籍ID（点击落书籍详情页） */
    private Long id;

    /** 作者用户ID（作者昵称由 user 服务聚合，不冗余本表） */
    private Long authorId;

    /** 书名 */
    private String title;

    /** 简介 */
    private String intro;

    /** 封面文件ID（前端经 file 服务换直链） */
    private String coverFileId;

    /** 分类（书籍表为字符串分类名，非文章分类枚举） */
    private String category;

    /** 书籍状态：连载中 / 已完结（下架书籍永不进流，JSON 序列化输出中文描述） */
    private BookStatusEnum status;

    /**
     * 章节数（book 表无该列，10.4 接入章节表聚合后填充）
     * <p>骨架阶段为 null，前端按空值隐藏该行即可</p>
     */
    private Integer chapterCount;

    /**
     * 收藏数（10.4 按 favorite(target_type=2) 聚合填充）
     * <p>骨架阶段为 null，避免返回 0 被前端误当真实数据</p>
     */
    private Long collectCount;

    /**
     * 阅读人数（10.4 按 reading_history(biz_type=1) 聚合填充）
     * <p>骨架阶段为 null，避免返回 0 被前端误当真实数据</p>
     */
    private Long readerCount;

    /** 更新时间（书籍流新鲜度排序依据；书籍无发布时间列） */
    private LocalDateTime updateTime;
}
