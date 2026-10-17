package com.litisle.litislecontent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 批量操作单篇回执 VO（批量发布/批量改可见性通用）
 * <p>逐篇独立事务：某篇失败不影响已成功篇目，前端按回执逐条提示</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class ArticleBatchResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文章ID */
    private Long articleId;

    /** 本篇是否成功 */
    private Boolean success;

    /** 失败错误码（成功时为 null；如 NO_PERMISSION/CAS 冲突） */
    private String errorCode;
}