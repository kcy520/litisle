package com.litisle.litislereview.moderation.spi.dto;


import lombok.Data;

/**
 * 文本审核入参（SPI 门面）
 * <p>content 为单片待审文本：标题与正文拼接后一并送审（hash 含标题但送审漏标题时，
 * 违规语义藏标题可躲过语义层）；超长正文按段落分片/字符窗口硬切由消费端负责，
 * 本对象收到的已是切分后单片</p>
 */
@Data
public class TextModerationRequest {

    /** 待审文本（单片） */
    private String content;

}