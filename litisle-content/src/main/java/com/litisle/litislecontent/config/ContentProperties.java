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

        /**
         * 召回模式：db=数据库四路（P0 默认）；es=search 模块 ES 召回（P1，切换阈值见 next_update 批次十）
         * <p>本期只实现 db；配置为 es 时服务端会打印告警并按 db 执行，不留不可用的空分支</p>
         */
        private String recall = "db";

        /** 热度榜配置（阶段14 已切流启用；social 未落地期间 like/comment 恒 0，排序退化为浏览量+时间衰减） */
        private Hot hot = new Hot();

        /** 无限刷流配置（批次十 10.3/10.4：召回池、融合权重、快照、曝光去重全部外置，改参数不发版） */
        private Stream stream = new Stream();
    }

    /**
     * 无限刷流嵌套配置（批次十 10.3 文章流 / 10.4 书籍流）
     * <p>只放「需要按运营效果调整」的参数；固定口径（如召回模式 db/es、门控集合）留在 FeedConstant</p>
     */
    @Data
    public static class Stream {

        /** 召回池总上限（写入 Redis 快照的最大条目数，需求条款定为 300） */
        private int poolSize = 300;

        /** 每路召回条数上限（最热/最新/常看分区/常看分类各自取多少条） */
        private int recallLimitPerPath = 100;

        /** 快照 TTL（秒，默认 30 分钟）：过期后客户端带旧 snapshotId 会收到 reset=true */
        private long snapshotTtlSeconds = 1800L;

        /** 同一身份最多保留的活动快照数（超出删最旧，防止翻页风暴堆积快照键） */
        private int maxActiveSnapshots = 3;

        /** 探索位比例（0~1）：从低分段随机抽取一部分插入结果，避免永远只刷头部 */
        private double explorationRatio = 0.15;

        /** 已读降权扣分（已读不剔除只降权，保证长刷仍有内容可看） */
        private double readPenalty = 0.5;

        /** 已读判定窗口（天）：reading_history 在该窗口内的文章才算「已读」 */
        private int readWindowDays = 30;

        /** 新鲜度半衰期（小时）：发布 24 小时后新鲜度分衰减到 0.5 */
        private double freshHalfLifeHours = 24;

        /** 同作者最大连续出现次数（1 表示同作者不能连续出现两次） */
        private int maxConsecutiveSameAuthor = 1;

        /** 同分类最大连续出现次数（2 表示同分类最多连续两条） */
        private int maxConsecutiveSameCategory = 2;

        /** 当日曝光 Set TTL（秒，默认 2 天）：跨日残留不影响次日曝光去重口径 */
        private long exposureTtlSeconds = 172800L;

        /** 文章流四项融合权重（热度/新鲜度/分区匹配/分类匹配） */
        private ArticleWeights articleWeights = new ArticleWeights();

        /** 书籍流四项融合权重（收藏/阅读人数/新鲜度/完结偏好） */
        private BookWeights bookWeights = new BookWeights();

        /** 新书保底曝光窗口（天）：该窗口内创建的书籍即使分数低也保证进池前排 */
        private int newBookDays = 7;

        /** 新书保底曝光配额（每个书籍快照前排保证出现的新书条数） */
        private int newBookQuota = 6;

        /** 画像样本条数（reading_history 取最近多少条聚合分区/分类偏好） */
        private int profileSampleSize = 100;
    }

    /**
     * 文章流融合权重（四项加权；权重之和不必等于 1，按实际效果热调即可）
     */
    @Data
    public static class ArticleWeights {

        /** 热度权重：全局热度榜分数（复用 FeedHotRankSupport 的计算，不另建热度体系） */
        private double heat = 0.35;

        /** 新鲜度权重：发布越近分越高（半衰期见 Stream#freshHalfLifeHours） */
        private double fresh = 0.30;

        /** 分区匹配权重：文章分区命中用户常看分区得分 */
        private double zone = 0.10;

        /** 分类匹配权重：文章分类命中用户常看分类得分 */
        private double category = 0.20;
    }

    /**
     * 书籍流融合权重（收藏与阅读人数先做 log1p 归一化，再按权重加权）
     */
    @Data
    public static class BookWeights {

        /** 收藏数权重（favorite target_type=2 聚合） */
        private double collect = 0.4;

        /** 阅读人数权重（reading_history biz_type=1 去重用户数） */
        private double read = 0.3;

        /** 新鲜度权重（按 update_time 做半衰期衰减） */
        private double fresh = 0.2;

        /** 完结偏好权重（书籍状态命中用户偏好状态得分） */
        private double finish = 0.1;
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
