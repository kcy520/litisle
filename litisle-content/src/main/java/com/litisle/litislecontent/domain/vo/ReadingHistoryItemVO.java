package com.litisle.litislecontent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 阅读历史列表项 VO（我的阅读历史分页出参，混合书籍与独立文章两类行）
 * <p>title/coverFileId 按 biz_type 分支取自 book 或 article；
 * lastChapterId/lastChapterNo/lastChapterTitle 仅 biz_type=1 有值；
 * 章节行已删 → 序号/标题均 null，章节在但文章已删 → 序号保留、标题 null，
 * 两种情况都不阻断书籍行展示（书名+最后阅读时间仍返回）</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class ReadingHistoryItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 阅读对象类型：1-书籍 2-独立文章（编码取自 ReadingTargetTypeEnum，对外仍用 Integer 保持前端契约不变） */
    private Integer bizType;

    /** 展示用类型描述：书籍/独立文章（取自 ReadingTargetTypeEnum 的 desc；bizType 仍保留编码供前端做逻辑判断） */
    private String bizTypeDesc;

    /** 阅读对象ID（biz_type=1→book.id，biz_type=2→article.id） */
    private Long bizId;

    /** 对象标题（biz_type=1→书名，biz_type=2→文章标题） */
    private String title;

    /** 封面文件ID（SeaweedFS；按 biz_type 取自 book/article，未设置时为 null） */
    private String coverFileId;

    /** 最后阅读章节ID（仅 biz_type=1 有值） */
    private Long lastChapterId;

    /** 最后阅读章节序号（仅 biz_type=1 有值；章节行已删为 null） */
    private Integer lastChapterNo;

    /** 最后阅读章节标题（仅 biz_type=1 有值；章节行已删或章节文章已删为 null） */
    private String lastChapterTitle;

    /** 最后阅读时间（reading_history.update_time） */
    private LocalDateTime lastReadTime;
}
