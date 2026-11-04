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

        /** 热度榜配置（阶段14 已切流启用；social 未落地期间 like/comment 恒 0，排序退化为浏览量+时间衰减） */
        private Hot hot = new Hot();
    }

    /**
     * 热度榜嵌套配置（阶段14 已切流：application.yml 置 enabled=true 启用；
     * 字段默认 false 仅作兜底——配置缺失时 /feed 安全降级时间倒序直查，零异常影响）
     */
    @Data
    public static class Hot {

        /** 热度榜总开关（yml 已置 true；false 则 /feed 退回时间倒序直查。social 落地后 like/comment 权重随计数回写自动生效） */
        private boolean enabled = false;

        /** 点赞权重（热度分分子：互动信号最高） */
        private double likeWeight = 3;

        /** 评论权重（热度分分子：次高） */
        private double commentWeight = 2;

        /** 浏览权重（热度分分子：防刷 PV，远低于互动信号） */
        private double viewWeight = 0.2;

        /** 时间衰减指数（Hacker News 同款 1.5：越大旧文沉底越快） */
        private double decayExponent = 1.5;

        /** 小时偏移（分母基数，防新文章分母趋 0 分数爆炸） */
        private double hourOffset = 2;

        /** 榜容量上限（灌榜内存裁剪保留 top N，防 ZSet 无界增长） */
        private int topN = 200;

        /**
         * 全量重灌间隔（毫秒，默认 3600000=1h）：增量轮只升不降（不重算存量成员分数），
         * 时间衰减靠低频全量轮覆盖校正收敛；灌榜任务每轮判「距上次全量 ≥ 本值」决定走全量或增量
         */
        private long fullRefillIntervalMs = 3600000L;

        /** 灌榜执行开关（默认开；多实例部署时分布式锁保证单点执行，也可只开一台再关其余。
         *  开关关闭的实例：定时任务不灌，P7 异步重建触发同样被拦截——统一「谁有资格灌榜」口径） */
        private boolean refillEnabled = true;
    }
}
