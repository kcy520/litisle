package com.litisle.litislecontent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 草稿保存结果 VO（DraftService#save）
 * <p>前端拿到 draftVersion 作为下次 baseVersion 乐观锁基准，形成保存闭环</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class DraftSaveVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 草稿ID（新草稿首次保存时生成，后续自动保存复用） */
    private Long draftId;

    /** 服务端最新草稿版本号（递增） */
    private Integer draftVersion;
}