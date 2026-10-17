package com.litisle.litislecontent.service;


import com.litisle.litisleapi.dto.content.ArticleCounterUpdateDTO;
import com.litisle.litisleapi.dto.content.ArticleImageRefDTO;
import com.litisle.litisleapi.dto.content.ContentDTO;

import java.util.List;

/**
 * content 内部契约 Service（ContentFeignProviderController 委托收口，对齐 file 模块
 * 「Controller 只做参数转换，业务逻辑收口 Service」范例）。
 *
 * @author litisle
 */
public interface ContentInternalService {

    /**
     * 机审回查内容正文（bizType=1-文章；bizType=2-评论模块未落地返回 COMMENT_NOT_FOUND）
     *
     * @param bizType 业务类型（1-文章 2-评论）
     * @param bizId   业务主键
     * @return 内容 DTO（body=入库 sanitize 后 contentHtml，与发布送审同源保 hash 一致）
     */
    ContentDTO getContent(Integer bizType, Long bizId);

    /**
     * 反查文章配图引用列表（复审反查用，position_order 升序=发布登记顺序）
     *
     * @param articleId 文章ID
     * @return 配图引用列表（无活跃配图返回空列表）
     */
    List<ArticleImageRefDTO> listArticleImages(Long articleId);

    /**
     * 计数回写（幂等绝对值 SET，仅非空字段更新）
     *
     * @param dto 计数回写 DTO
     */
    void updateCounters(ArticleCounterUpdateDTO dto);
}
