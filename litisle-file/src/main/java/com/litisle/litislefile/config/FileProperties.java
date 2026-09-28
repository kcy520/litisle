package com.litisle.litislefile.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 配置绑定：@ConfigurationProperties(prefix = "litisle.file") + @Component，
 * 对应 application.yml 的 litisle.file 段：
 * - seaweedfs.endpoint：SDK 内部地址（上传/删除/预签名用），如 http://localhost:8333
 * - seaweedfs.publicUrlBase：公开直链拼接基址（给前端用）
 *   ⚠️ 与 endpoint 分离——dev 阶段两者相同（都指向 8333），
 *   接 CDN 后改为 CDN 域名（如 https://img.litisle.com），endpoint 保持内网不变
 * - seaweedfs.accessKey / secretKey / presignExpireMinutes（30 分钟）
 * - seaweedfs.collections（bizType→bucket 映射：avatar/cover/article-img→article/license）
 * - upload.maxImageSize（默认 10485760 = 10MB）
 * - upload.maxTotalSize（默认 209715200 = 200MB）/ maxFileCount（默认 200）：per-user 存储配额（软限）
 * - thumbnail.enabled / width(300) / height(300)
 * - limit.globalQpsWindowSeconds(1) / globalQpsMaxCount(200)：全站 QPS 上限（运营可调，外置到 yml/Nacos）
 * - reconcile-task：对账任务参数（补偿阈值 30min / 软删缓冲期 7 天，运营可调，外置到 yml/Nacos）
 *   ⚠️ cron 不走此 Bean——@Scheduled 注解要求编译期常量，无法注入 Bean 值，
 *   由占位符 ${litisle.file.reconcile-task.cron:...} 直接读取（见 FileReconcileTask.CRON_DAILY_2AM）
 */
@Data
@Component
@ConfigurationProperties(prefix = "litisle.file")
public class FileProperties {

    private Seaweedfs seaweedfs = new Seaweedfs();
    private Upload upload = new Upload();
    private Thumbnail thumbnail = new Thumbnail();
    private ReconcileTask reconcileTask = new ReconcileTask();
    private Executor executor = new Executor();
    private Limit limit = new Limit();

    /** SeaweedFS S3 连接配置（对应 yml: litisle.file.seaweedfs） */
    @Data
    public static class Seaweedfs {
        /** SDK 内部地址（上传/删除/预签名用），如 http://localhost:8333 */
        private String endpoint = "http://localhost:8333";
        /** 公开直链拼接基址（给前端用），接 CDN 后改为 CDN 域名 */
        private String publicUrlBase = "http://localhost:8333";
        /** S3 访问凭证（与 docker/init/seaweedfs/s3.json 保持一致） */
        private String accessKey;
        private String secretKey;
        /** 预签名 URL 有效期（分钟），默认 30 分钟 */
        private int presignExpireMinutes = 30;
        /** bizType→bucket 映射：avatar/cover/article-img→article/license */
        private Map<String, String> collections = new HashMap<>();
    }

    /** 上传限制配置（对应 yml: litisle.file.upload） */
    @Data
    public static class Upload {
        /** 图片大小上限（字节），默认 10MB */
        private long maxImageSize = 10485760L;
        /** per-user 总容量上限（字节），默认 200MB；超限抛 FILE_QUOTA_EXCEEDED */
        private long maxTotalSize = 209715200L;
        /** per-user 文件数上限，默认 200；超限抛 FILE_QUOTA_EXCEEDED */
        private long maxFileCount = 200L;
    }

    /** 缩略图配置（对应 yml: litisle.file.thumbnail） */
    @Data
    public static class Thumbnail {
        private boolean enabled = true;
        private int width = 300;
        private int height = 300;
    }

    /** 对账定时任务参数（对应 yml: litisle.file.reconcile-task） */
    @Data
    public static class ReconcileTask {
        /** Step1 补偿阈值（分钟）：status=0 占位记录超此时间进入对账扫描（正常上传秒级完成） */
        private int compensateThresholdMinutes = 30;
        /** Step2 软删缓冲期（天）：del_flag=1 超此时间才物理清理（防误删不可恢复） */
        private int physicalCleanDelayDays = 7;
    }

    /** 文件 IO 线程池参数（对应 yml: litisle.file.executor） */
    @Data
    public static class Executor {
        /** 核心线程数 */
        private int corePoolSize = 4;
        /** 最大线程数 */
        private int maxPoolSize = 8;
        /** 队列容量（满后触发拒绝策略 CallerRunsPolicy 回压到调用线程） */
        private int queueCapacity = 64;
        /** 空闲线程存活时间（秒） */
        private int keepAliveSeconds = 60;
        /** 异步请求超时（秒）：StreamingResponseBody 大文件下载需放宽，防默认 30s 截断 */
        private int asyncTimeoutSeconds = 600;
    }

    /** 限流阈值配置（对应 yml: litisle.file.limit），运营可调、Nacos 可覆盖 */
    @Data
    public static class Limit {
        /** 全站 QPS 时间窗口（秒），默认 1 秒 */
        private long globalQpsWindowSeconds = 1L;
        /** 文件服务全站 QPS 上限：所有 /api/file/** 合计每秒最多 200 次 */
        private long globalQpsMaxCount = 200L;
    }
}
