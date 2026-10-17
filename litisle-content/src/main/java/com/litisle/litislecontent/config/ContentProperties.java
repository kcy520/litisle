package com.litisle.litislecontent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * content 模块配置绑定（对齐 file 模块 FileProperties 模式：@Component + @ConfigurationProperties）：
 *
 * 【绑定前缀】litisle.content（application.yml 本地默认值兜底，环境特定值经 Nacos litisle-content.yaml 覆盖）
 *
 * @author litisle
 */
@Data
@Component
@ConfigurationProperties(prefix = "litisle.content")
public class ContentProperties {

    /** 发布域配置（§6.3 批量发布口径） */
    private Publish publish = new Publish();

    /** 推荐流域配置（[7.1] 缓存三防参数外置 yml） */
    private Feed feed = new Feed();

    /** 审核结果回写消费配置（板块三 3-5：消费重试/聚合暂存/last-hash 免审键 TTL） */
    private Moderation moderation = new Moderation();

    /**
     * 审核结果回写消费嵌套配置
     */
    @Data
    public static class Moderation {

        /** 消费端指数退避重试参数（3-8.1 同口径：耗尽 nack 入 DLQ 兑底） */
        private Retry retry = new Retry();

        /** last-hash 免审键 TTL（小时，4-3.1：与 review 结果缓存同窗，防免审口径漂移） */
        private long lastHashTtlHours = 72;

        /** TTL 抖动比例（±10%，防同批键同刻过期雪崩） */
        private double ttlJitterRatio = 0.1;
    }

    /**
     * 消费端重试嵌套配置
     */
    @Data
    public static class Retry {

        /** 消费端延迟重试上限：耗尽后 nack 入 DLQ，绝不自动放行 */
        private int maxCount = 5;

        /** 指数退避基数（毫秒）：第 n 次重试延迟 = baseDelayMs × 2^(n-1) */
        private long baseDelayMs = 30000;
    }

    /**
     * 发布域嵌套配置
     */
    @Data
    public static class Publish {

        /** 批量发布单批上限（超限抛 ARTICLE_BATCH_EXCEEDED——防批量刷审，§6.3） */
        private int batchMax = 20;
    }

    /**
     * 推荐流域嵌套配置
     */
    @Data
    public static class Feed {

        /** 推荐流缓存 TTL 基准（秒）：实际写入 ±10% 抖动防雪崩 */
        private long cacheTtlSeconds = 300;

        /** 分页大小上限（入参超过按本值收口，防恶意大分页拖垮缓存与 DB） */
        private int pageSize = 20;
    }
}
