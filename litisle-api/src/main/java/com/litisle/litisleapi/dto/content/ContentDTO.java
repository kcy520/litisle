package com.litisle.litisleapi.dto.content;

import lombok.Data;

import java.io.Serializable;

/**
 * 内容 DTO（review 侧机审回查正文跨服务契约）
 *
 * 【使用场景】
 * review 侧消费 moderation.text.submit 后按 bizType+bizId 回查正文送审（ContentClient.getContent）；
 * 后续申诉 owner 校验、RAG 状态过滤（visibility=PUBLIC 且 status=2）复用本 DTO
 *
 * 【设计要点】
 * - status/visibility 用 Integer（code）而非枚举：枚举定义在 content 模块，
 *   litisle-api 不能反向依赖（会成环），由提供方解析
 * - 事件体不含正文，正文一律服务端回查，防事件体被篡改注入
 *
 * @author litisle
 */
@Data
public class ContentDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务类型（1-文章 2-评论，口径见 ModerationBizTypeEnum） */
    private Integer bizType;

    /** 业务主键（文章/评论ID） */
    private Long bizId;

    /** 标题（文章场景；评论可为空） */
    private String title;

    /** 正文（送审文本，标题随正文一并送审由调用方拼接） */
    private String body;

    /** 内容归属用户ID（申诉 owner 校验依据） */
    private Long ownerId;

    /** 发布态（内容模块口径，如 2-已发布；RAG 入库/复审回查须校验） */
    private Integer status;

    /** 可见性（内容模块口径，如 1-PUBLIC 2-PRIVATE；检索强制按 PUBLIC 过滤） */
    private Integer visibility;

    /** 内容指纹（可选，与事件 contentHash 比对防旧消息错配新内容） */
    private String contentHash;
}
