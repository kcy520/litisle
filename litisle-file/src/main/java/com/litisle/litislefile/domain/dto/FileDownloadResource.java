package com.litisle.litislefile.domain.dto;

import java.io.InputStream;
/**
 * 服务端流式下载资源（FileService#download 返回，FileController 据此写 200/206/416 响应）
 * <p>持有 S3 对象流 + 响应元信息，供 Controller 组装响应头并流式回写；
 * <b>调用方负责关闭 {@code stream}</b>（Controller 在 finally 中关闭）。</p>
 *
 *
 * record 是 Java 16 正式引入的一种特殊类（JDK 14/15 为预览特性），用来简洁地表示一个不可变的数据载体。
 * record = 数据 + 自动生成的样板代码
 * 它专门为“这个类只是为了携带数据”而设计。
 */
public record FileDownloadResource(

        /**
         * 对象输入流（已按 Range 在 S3 侧定位；rangeInvalid=true 时为 null）
         */
        InputStream stream,

        /**
         * MIME 类型（Content-Type 用，可能为 null）
         */
        String contentType,

        /**
         * 下载文件名（Content-Disposition 用，由 path 尾段推导）
         */
        String fileName,

        /**
         * 一致性标识（取 sha256，供 If-Range/断点续传校验）
         */
        String etag,

        /**
         * 对象总大小（字节）
         */
        long totalSize,

        /**
         * 本次返回区间起始（含）
         */
        long start,

        /**
         * 本次返回区间结束（含）
         */
        long end,

        /**
         * true=206 部分内容；false=200 全量
         * 这次该回 206 吗
         */
        boolean partial,

        /**
         * true=416 区间非法（stream=null，仅 totalSize 有效） "客户端请求的 Range 区间是非法的 / 服务器无法满足的。" **比如start>end——客户端请求的 Range 区间是非法的 / 服务器无法满足的。"
         * 区间是否不可满足
         * 这次该回 416 吗 
         */
        boolean rangeInvalid

        /**
         * 两个都是 false = 正常 200 全量返回
         */
) {
}