package com.litisle.litislereview.moderation.spi.dto;


import lombok.Data;

/**
 * 图片审核入参（SPI 门面）
 * <p>imageBytes 为图片字节流：消费端按 fileId 经 FileClient 内部契约端点取图后传入，
 * 严禁携带用户提供的 URL（SSRF 纪律：机审取图唯一入口是内部契约）；云 API 适配器
 * 优先传字节流而非内网 URL</p>
 */
@Data
public class ImageModerationRequest {

    /** 图片字节流 */
    private byte[] imageBytes;
}