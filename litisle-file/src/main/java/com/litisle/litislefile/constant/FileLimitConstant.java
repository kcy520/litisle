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
}
