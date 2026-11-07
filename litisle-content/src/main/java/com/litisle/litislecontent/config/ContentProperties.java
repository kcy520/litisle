package com.litisle.litislecontent.config;

import com.litisle.litislecontent.constant.ContentLimitConstant;
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

    /** 阅读历史裁剪配置（批次十一 11.4：90 天时间窗 + 单用户 1000 条 + 在读保留 10 条） */
    private History history = new History();

    /** 审核结果回写消费配置（板块三 3-5：消费重试/聚合暂存/last-hash 免审键 TTL） */
    private Moderation moderation = new Moderation();

    /** 互动统计配置（批次十一 11.16：统计表与源表每日对账） */
    private Stat stat = new Stat();

    /** 点赞域配置（批次十一 11.8：单用户点赞频控阈值可配置） */
    private Like like = new Like();

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

        /** 曝光/点击日聚合配置（批次十一 11.6：日表保留天数与清理任务参数） */
        private Exposure exposure = new Exposure();

        /** 负反馈名单配置（批次十一 11.11：屏蔽生效时间窗与名单缓存 TTL） */
        private Feedback feedback = new Feedback();
    }

    /**
     * 推荐流负反馈配置（批次十一 11.11）
     *
     * <p>屏蔽只在「建池 / 切片」时生效，不改写已有快照；名单缓存按用户单键失效，
     * 写入/撤销后下一次请求即可读到新名单。</p>
     */
    @Data
    public static class Feedback {

        /**
         * 屏蔽延续时间窗（天，默认 30）
         * <p>「不感兴趣 / 屏蔽作者 / 屏蔽分类」在最近 N 天内持续生效；
         * 超过窗口自动失效，避免永久屏蔽导致内容池越刷越窄。取 30 天与统计短窗口对齐。</p>
         */
        private int windowDays = 30;

        /** 名单缓存 TTL（秒，默认 300）：与画像缓存同量级，写入后按用户键主动失效 */
        private long cacheTtlSeconds = 300;
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

        /** 冷启动探索位比例（0~1）：无画像用户缺少个性化信号，上调探索位避免全局热门/最新固化 */
        private double coldStartExplorationRatio = 0.30;

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

        /** 计数平滑参数（批次十一 11.3：替换池内最大值归一化，保证同一内容跨候选池分数稳定） */
        private Smoothing smoothing = new Smoothing();

        /** 新书保底曝光窗口（天）：该窗口内创建的书籍即使分数低也保证进池前排 */
        private int newBookDays = 7;

        /** 新书保底曝光配额（每个书籍快照前排保证出现的新书条数） */
        private int newBookQuota = 6;

        /** 画像样本条数（reading_history 取最近多少条聚合分区/分类偏好） */
        private int profileSampleSize = 100;

        /** 画像缓存 TTL（秒，默认 15 分钟）：未命中才回源聚合，阅读行为不主动失效（成本说明见 FeedProfileSupport） */
        private long profileCacheTtlSeconds = 900L;
    }

    /**
     * 曝光/点击日聚合配置（批次十一 11.6）
     *
     * <p>选择「定时分批物理删除」而不是「表分区」：日表数据量小、单表即可承载，
     * 分区需要额外的分区维护 DDL 与分区键约束，收益不成比例；删除条件以 stat_date 开头，
     * 命中唯一键最左列，无需额外索引。</p>
     */
    @Data
    public static class Exposure {

        /** 保留天数（默认 30 天；早于「今天 - retainDays」的日聚合行由清理任务删除） */
        private int retainDays = 30;

        /** 清理任务开关（默认开；关闭后日表只增不减，仅保留手工清理能力） */
        private boolean cleanupEnabled = true;

        /** 单批删除上限（默认 1000 行，循环分批避免一次大删长时间锁表） */
        private int cleanupBatchSize = 1000;

        /** 清理周期（毫秒，默认 24 小时；fixedDelay 上一轮结束才计下一轮，不堆积） */
        private long cleanupIntervalMs = 86400000L;

        /** 启动延迟（毫秒，默认 5 分钟，避开启动高峰） */
        private long cleanupInitialDelayMs = 300000L;
    }

    /**
     * 文章流融合权重（批次十一 11.10：四项 → 七项）
     *
     * <p>权重之和不必等于 1，按实际效果热调即可。默认值拆分依据：保留原「热度总权重 0.35」，
     * 按信号可信度拆给四个统计量——点赞/收藏是显式正向行为、最难刷，各 0.12；
     * 评论需要付出内容成本但频率低，0.08；浏览最廉价、可被 PV 灌水，0.03。
     * 新鲜度 0.30、分区 0.10、分类 0.20 沿用批次十原值，避免改动既有行为。</p>
     */
    @Data
    public static class ArticleWeights {

        /** 旧热度总权重（保留配置键兼容旧 yml，批次十一 11.10 起不再读取；由下面四个统计量权重替代） */
        @Deprecated
        private double heat = 0.35;

        /** 收藏数权重（取 30/90 天窗口收藏数，窗口未计算时回退 article_stat 累计值） */
        private double collect = 0.12;

        /** 点赞数权重（article_stat.like_count 累计值，点赞事件已在 11.8 落地） */
        private double like = 0.12;

        /** 评论数权重（article_stat.comment_count 累计值；评论频率低于点赞，故低于点赞/收藏） */
        private double comment = 0.08;

        /** 浏览数权重（article_stat.view_count 累计值；PV 最易被刷，取最低权重） */
        private double view = 0.03;

        /** 新鲜度权重：发布越近分越高（半衰期见 Stream#freshHalfLifeHours） */
        private double fresh = 0.30;

        /** 分区匹配权重：文章分区命中用户常看分区得分 */
        private double zone = 0.10;

        /** 分类匹配权重：文章分类命中用户常看分类得分 */
        private double category = 0.20;
    }

    /**
     * 书籍流融合权重（收藏与阅读人数先做贝叶斯平滑，再按权重加权）
     */
    @Data
    public static class BookWeights {

        /** 收藏数权重（favorite target_type=2 窗口值优先，窗口未计算时回退累计值） */
        private double collect = 0.4;

        /** 阅读人数权重（reading_history biz_type=1 窗口去重用户数，未计算时回退累计值） */
        private double read = 0.3;

        /** 新鲜度权重（按 create_time 做半衰期衰减；批次十一 11.7 统一口径，不用 update_time） */
        private double fresh = 0.2;

        /** 完结偏好权重（书籍状态命中用户偏好状态得分） */
        private double finish = 0.1;
    }

    /**
     * 计数平滑参数（批次十一 11.3，11.10 统一为文章/书籍共用一组）
     *
     * <p>平滑公式（唯一实现，文章与书籍共用）：
     * smoothed = (x + m * prior) / (x + m)，x=计数，m=平滑强度（伪计数），prior=先验均值（0~1）。
     * 含义：没有互动时得分趋近 prior，互动越多越接近 1；m 越大越向先验收缩，
     * 避免「池内最大值=1」时新内容被放大到满分。</p>
     *
     * <p>prior 建议按全站历史均值折算到 0~1 后配置（而不是每池现算均值——那会重新引入池相关性）。</p>
     *
     * <p>默认 m=5、prior=0.1：沿用书籍原有参数，书籍分数不变；文章四个统计量也使用同一组参数，
     * 不再另设一套，避免出现两套公式/两套参数。</p>
     */
    @Data
    public static class Smoothing {

        /** 统一平滑强度 m（伪计数，默认 5；文章与书籍共用同一组参数） */
        private double strength = 5;

        /** 统一先验均值（0~1，默认 0.1；无互动时的中性分，文章与书籍共用） */
        private double prior = 0.1;

        /** 书籍收藏平滑强度 m（保留配置键兼容旧 yml，11.10 起不再读取；统一使用 strength） */
        @Deprecated
        private double collectStrength = 5;

        /** 书籍收藏先验均值（保留配置键，11.10 起不再读取；统一使用 prior） */
        @Deprecated
        private double collectPrior = 0.1;

        /** 书籍阅读人数平滑强度 m（保留配置键，11.10 起不再读取；统一使用 strength） */
        @Deprecated
        private double readStrength = 5;

        /** 书籍阅读人数先验均值（保留配置键，11.10 起不再读取；统一使用 prior） */
        @Deprecated
        private double readPrior = 0.1;

        /** 旧文章热度饱和中点（保留配置键兼容旧 yml，11.10 起不再读取；文章改为逐统计量统一平滑） */
        @Deprecated
        private double heatMidpoint = 1.0;
    }

    /**
     * 热度榜嵌套配置（阶段14 已切流：application.yml 置 enabled=true 启用；
     * 字段默认 false 仅作兜底——配置缺失时 /feed 安全降级时间倒序直查，零异常影响）
     */
    @Data
    public static class Hot {

        /** 热度榜总开关（yml 已置 true；false 则 /feed 退回时间倒序直查。social 落地后 like/comment 权重随计数回写自动生效） */
        private boolean enabled = false;

        /** 点赞权重（保留配置键兼容旧 yml，批次十一 11.10 起打分改用 ArticleWeights#like） */
        @Deprecated
        private double likeWeight = 3;

        /** 评论权重（保留配置键兼容旧 yml，11.10 起打分改用 ArticleWeights#comment） */
        @Deprecated
        private double commentWeight = 2;

        /** 浏览权重（保留配置键兼容旧 yml，11.10 起打分改用 ArticleWeights#view） */
        @Deprecated
        private double viewWeight = 0.2;

        /** 时间衰减指数（保留配置键兼容旧 yml，11.10 起时间衰减统一由 FeedScoreSupport#freshScore 半衰期承担） */
        @Deprecated
        private double decayExponent = 1.5;

        /** 小时偏移（保留配置键兼容旧 yml，11.10 起不再读取） */
        @Deprecated
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

    /**
     * 互动统计嵌套配置（批次十一 11.16）
     *
     * <p>对账任务只做「比对 + 日志 + 指标」，不自动修正；修正策略另行确定。
     * 调度周期与首次延迟由任务上的 @Scheduled 占位符直接读 yml，此处只管开关与阈值。</p>
     */
    @Data
    public static class Stat {

        /** 对账任务嵌套配置（统计表 vs 源表聚合） */
        private Reconcile reconcile = new Reconcile();

        /** 统计窗口嵌套配置（批次十一 11.3：收藏/阅读 30/90 天窗口预计算） */
        private Window window = new Window();

        /** 粗完读率统计配置（批次十一 11.13：定时全量重算，不在翻页期现算） */
        private ReadQuality readQuality = new ReadQuality();
    }

    /**
     * 书籍粗完读率统计配置（批次十一 11.13）
     *
     * <p>选择「按日全量重算」而不是增量：完读判定依赖「该书当前最大可见章节号」，
     * 任何新章节发布都会让已追平用户回退为未完成；历史行被裁剪也会让分母变化。
     * 增量无法感知这两类反向变化，全量重算按日一次即可（reading_history 有 90 天裁剪上限，扫描量可控）。</p>
     */
    @Data
    public static class ReadQuality {

        /** 任务开关（默认开；关闭后表内保留上次结果不再刷新） */
        private boolean enabled = true;

        /** 重算间隔（毫秒，默认 1 天）：fixedDelay 语义，上一轮结束才开始计时 */
        private long intervalMs = 86_400_000L;

        /** 首次延迟（毫秒，默认 10 分钟）：避开应用启动期 DB 压力峰值 */
        private long initialDelayMs = 600_000L;
    }

    /**
     * 统计窗口配置（批次十一 11.3）
     *
     * <p>窗口值由 ContentStatWindowJob 按日全量重算到 content_stat_window；
     * 推荐流翻页只读预计算值，禁止查询期现算聚合。</p>
     *
     * <p>约束：窗口天数不要超过阅读历史的 retain-days（11.4 默认 90 天），
     * 否则 reading_history 被裁剪后窗口阅读人数会偏小。</p>
     */
    @Data
    public static class Window {

        /** 窗口任务开关（默认开；关闭后读侧全部回退累计值兜底） */
        private boolean enabled = true;

        /** 短窗口天数（默认 30 天，收藏/阅读优先使用该窗口） */
        private int shortDays = 30;

        /** 长窗口天数（默认 90 天，短窗口行缺失时使用；也是阅读历史保留天数上限） */
        private int longDays = 90;
    }

    /**
     * 阅读历史裁剪配置（批次十一 11.4）
     *
     * <p>裁剪在「新插入一行历史」后 lazily 触发（不新增独立 job）：
     * 删除「超过 retain-days 天」或「超出 max-per-user 条」的历史行，
     * 但始终保留 keep-in-progress 条最近的在读书籍历史，避免把正在追更的读者清掉。</p>
     */
    @Data
    public static class History {

        /** 裁剪开关（默认开；关闭后 reading_history 只增不减） */
        private boolean trimEnabled = true;

        /** 时间窗保留天数（默认 90 天；早于该时间的非在读历史会被清理） */
        private int retainDays = 90;

        /** 单用户单类型条数上限（默认 1000，按 update_time 倒序保留最新；取常量默认值，可 yml 覆盖） */
        private int maxPerUser = ContentLimitConstant.HISTORY_MAX_PER_USER;

        /** 在读保留条数（默认 10 条最近的在读书籍历史，不受时间窗与条数上限影响） */
        private int keepInProgress = 10;

        /** 单批删除上限（默认 100 行，防异常数据一次大删） */
        private int trimBatchSize = 100;
    }

    /**
     * 点赞域配置（批次十一 11.8）
     *
     * <p>@RateLimiter 注解阈值只能是编译期常量，无法满足「阈值配置化」；
     * 点赞接口改用 LikeRateLimitSupport 运行时读取本节参数，复用同一 Redis 固定窗口脚本，
     * key 维度仍是单用户（rate_limit:like:user:{userId}），与注解的 USER 维度口径一致。</p>
     */
    @Data
    public static class Like {

        /** 频控窗口（秒，默认 60；与全站限流矩阵窗口一致） */
        private long rateLimitWindowSeconds = ContentLimitConstant.LIMIT_WINDOW_SECONDS;

        /** 单用户在窗口内点赞/取消点赞合计次数上限（默认 20，yml/Nacos 可调） */
        private int rateLimitMaxCount = ContentLimitConstant.LIKE_MAX_COUNT;
    }

    /**
     * 统计对账嵌套配置
     */
    @Data
    public static class Reconcile {

        /** 对账开关（默认开；关闭后定时任务直接返回，仅保留手工触发能力） */
        private boolean enabled = true;

        /** 差异行数告警阈值：单轮总差异行数超过该值按 error 级别输出，用于告警规则匹配 */
        private long diffRowsThreshold = 50L;
    }
}
