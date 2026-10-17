package com.litisle.litislecontent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 阅读进度 VO（续读接口出参；无进度时 Service 返回 null，前端从第一章开始）
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class ReadingProgressVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 书籍ID */
    private Long bookId;

    /** 最后阅读章节ID */
    private Long chapterId;

    /** 最后阅读时间 */
    private LocalDateTime updateTime;
}