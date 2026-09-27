package com.litisle.litislefile.constant;

/**
 * file 模块限流阈值常量
 * <p>
 * 【规范】限流阈值定义在业务模块内（而非公共库），与 UserLimitConstant 保持一致的组织方式。
 *
 * @author litisle
 */
public class FileLimitConstant {

    /**
     * 限流时间窗口（秒）
     */
    public static final long UPLOAD_WINDOW_SECONDS = 60L;

    /**
     * 单文件上传接口：同一用户 60 秒内最多 30 次（防刷存储/带宽）
     */
    public static final long UPLOAD_MAX_COUNT = 30L;

    /**
     * 批量上传接口：同一用户 60 秒内最多 5 次（单次最多 9 张，等效 45 张/分钟）
     */
    public static final long BATCH_UPLOAD_MAX_COUNT = 5L;

    /**
     * 流式下载接口限流时间窗口（秒）
     */
    public static final long DOWNLOAD_WINDOW_SECONDS = 60L;

    /**
     * 流式下载接口：同一用户 60 秒内最多 120 次
     * <p>阈值高于上传：需容纳 Range 分片请求（大文件按块续传会产生多次请求），
     * 同时限制服务端代理带宽被单用户滥用</p>
     */
    public static final long DOWNLOAD_MAX_COUNT = 120L;
}
