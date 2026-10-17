package com.litisle.litislecontent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 标签 VO（前缀搜索联想/热门 TOP N 出参；封禁/已删标签不出现在聚合结果）
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class TagVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标签ID */
    private Long id;

    /** 标签名 */
    private String name;

    /** 引用文章数（热门排序依据） */
    private Long useCount;
}