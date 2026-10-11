package com.litisle.litisleapi.dto.social;

import lombok.Data;

import java.io.Serializable;

/**
 * 私信 DTO（review 侧机审回查私信正文跨服务契约）
 *
 * 【使用场景】
 * review 侧消费 moderation.text.submit（bizType=5）后按 bizId 回查私信正文送审
 * （MessageClient.getContent）；先发后审链路据此回写状态
 *
 * @author litisle
 */
@Data
public class MessageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 私信主键（message.id） */
    private Long bizId;

    /** 发送者ID */
    private Long senderId;

    /** 接收者ID */
    private Long receiverId;

    /** 私信正文（送审文本） */
    private String content;

    /** 私信状态（message 模块口径，如正常态/撤回态） */
    private Integer status;
}
