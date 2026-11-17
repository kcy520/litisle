package com.litisle.litisleai.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 相似文章推荐响应（板块八 1.1：L0/L1 分层路由的统一出参包装）
 *
 * <p>【口径】cards 为推荐卡片列表（与板块五卡片结构一致）；conditional 标识本次是否走
 * L1 条件版检索；notice 为降级提示（L1 条件提取失败/超时降级 L0 时标注「未识别自定义条件」，
 * 不报错——F8 边界铁律）。前端据此可展示条件命中状态与降级说明。</p>
 *
 * @author litisle
 */
@Data
public class XiaoyueSimilarResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 相似文章卡片列表（无命中/RAG 缺席/降级时为空列表，不报错） */
    private List<SimilarArticleCard> cards;

    /** 是否走 L1 条件版检索（false=L0 直通固定相似检索） */
    private boolean conditional;

    /** 降级/标注提示（L1 降级 L0 时为「未识别自定义条件」等说明；正常路径为 null） */
    private String notice;
}
