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

    /**
     * 缩略图接口限流时间窗口（秒）
     */
    public static final long THUMBNAIL_WINDOW_SECONDS = 60L;

    /**
     * 缩略图接口：同一用户 60 秒内最多 120 次
     * <p>缩略图命中缓存后为纯拼接、成本低，故阈值与下载一致；但未命中缓存会触发
     * ImageIO 缩放（CPU 密集）+ 对象存储读写，需限流防 CPU 打满</p>
     */
    public static final long THUMBNAIL_MAX_COUNT = 120L;

    /**
     * 预签名接口限流时间窗口（秒）
     */
    public static final long PRESIGN_WINDOW_SECONDS = 60L;

    /**
     * 预签名接口：同一用户 60 秒内最多 60 次
     * <p>预签名 URL 是可传播凭证，且可按 fileId 枚举刷新，需限速防批量刷凭证</p>
     */
    public static final long PRESIGN_MAX_COUNT = 60L;

    /**
     * 删除接口限流时间窗口（秒）
     */
    public static final long DELETE_WINDOW_SECONDS = 60L;

    /**
     * 删除接口：同一用户 60 秒内最多 30 次
     * <p>删除为写操作（解绑引用/软删），与上传同档，防被批量刷</p>
     */
    public static final long DELETE_MAX_COUNT = 30L;

    /**
     * 全站 QPS 上限时间窗口（秒）
     */
    public static final long GLOBAL_QPS_WINDOW_SECONDS = 1L;

    /**
     * 文件服务全站 QPS 上限：所有 /api/file/** 合计每秒最多 200 次
     * <p>与各接口的 per-user @RateLimiter 并存：per-user 防单用户刷，本项防全站洪峰打爆
     * Tomcat 线程池 / SeaweedFS 后端（系统级限流，见 FileGlobalQpsInterceptor）</p>
     */
    public static final long GLOBAL_QPS_MAX_COUNT = 200L;
}
