package com.litisle.litisleapi.dto.file;


import lombok.Data;

import java.io.Serializable;

/**
 * 文件内容 DTO（机审取图唯一入口，服务间字节流传输）
 *
 * 【使用场景】
 * review 侧机审取图：按 fileId 走 FileClient.getContent 取原始字节流（严禁按用户 URL
 * 抓取——SSRF 防护唯一入口，Review-Plan §4.2.2 取图纪律）；云 API 审核同样优先传
 * 字节流而非内网 URL
 *
 * 【设计要点】
 * - byte[] 经 Jackson 序列化为 Base64（≤10MB 图片内网传输可接受），不签发内网
 *   presign URL，杜绝「review 侧按 URL 请求」引入的 SSRF/内网探测面
 * - 提供方端点不过 safe_flag 闸口：申诉复核需重取隔离图（safe_flag=0）
 * - 仅服务间可用（网关未路由 /api/feign/**），不占公开面访问配额
 *
 * @author litisle
 */
@Data
public class FileContentDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SeaweedFS 文件ID */
    private String fileId;

    /** MIME 类型（机审/云API 内容类型标注用） */
    private String mime;

    /** 对象原始字节流（Jackson 自动 Base64 编解码，≤10MB） */
    private byte[] content;
}
