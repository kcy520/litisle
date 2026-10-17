package com.litisle.litislecontent.domain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 章节挂载入参（BookService#addChapter）
 * <p>(book_id, chapter_no) 唯一键防重：序号冲突由业务层拒绝；文章归属本人校验防挂他人文章；
 * 阅读进度指向被移除章节时按最新章容错（BusinessService 侧兜底）</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class ChapterAddDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 章节正文的文章ID（必须是当前用户本人的已发布文章） */
    @NotNull(message = "文章ID不能为空")
    private Long articleId;

    /** 章节序号（≥1，序号即排序键；与已有章节重复则拒绝） */
    @NotNull(message = "章节序号不能为空")
    @Min(value = 1, message = "章节序号必须从 1 开始")
    private Integer chapterNo;
}