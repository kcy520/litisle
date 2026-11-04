package com.litisle.litislecontent.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 章节批量重排入参（BookService#reorderChapters，批次5 2026-11-02）
 * <p>前端拖拽排序后「全量提交新序」：清单必须覆盖本书全部章节且 newChapterNo 恰为 1..N
 * （连续无重复无空洞），完整性由 Service 整单校验（DTO 层只做单条格式校验）；
 * uk(book_id, chapter_no) 冲突由 Service 两阶段偏移落库规避</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class BookChapterReorderDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 重排清单（全量：本书全部章节的新序映射，缺章/多章均拒绝） */
    @NotEmpty(message = "重排清单不能为空")
    @Valid
    private List<ReorderItem> items;

    /**
     * 单条重排项：章节ID → 新序号
     */
    @Data
    @Accessors(chain = true)
    public static class ReorderItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 章节（挂载行）ID：必须归属本书（跨书章节ID拒绝） */
        @NotNull(message = "章节ID不能为空")
        private Long chapterId;

        /** 新章节序号（≥1；全量清单内必须恰好构成 1..N 连续无重复，Service 校验） */
        @NotNull(message = "新章节序号不能为空")
        @Min(value = 1, message = "新章节序号必须从 1 开始")
        private Integer newChapterNo;
    }
}
