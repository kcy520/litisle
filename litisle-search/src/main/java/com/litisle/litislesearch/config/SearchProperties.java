package com.litisle.litislesearch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置绑定：@ConfigurationProperties(prefix = "litisle.search") + @Component，
 * 对应 application.yml 的 litisle.search 段（架构文档 §六 配置表，参数外置 Nacos 可调）：
 * - es：ES 集群地址/凭证/连接与查询超时（3s/10s，ai 侧 RAG 依赖此超时可容忍）
 * - index：article_idx / book_idx / user_idx（外置，便于分环境/重建迁移）
 * - weight：multi_match 字段权重（title 4 / tags 3 / summary 2）
 *   + heat 公式权重（like 2 / comment 3 / view 0.1）与时间衰减半衰期（168h，批次A A5 定案）
 * - knn：KNN 参数（top-k 10 / num-candidates 100 / 相似度阈值默认不过滤）
 * - cache：热点查询缓存开关与 TTL（10m；兜底/降级结果不写缓存）
 * - suggest：搜索联想开关与默认条数（10）
 * - history：单用户搜索历史上限（20，超限懒删最旧）
 * - page：分页上限（50，超出抛 SEARCH_PAGE_EXCEEDED）
 * - rabbit：索引同步死信交换机命名（sync-queue 为预留 key，实际队列以 MqConstant 六队列为准）
 * - fallback：空结果兜底 Embedding 超时（1s，批次A A1 定案）+ ES 不可用降级开关（批次A A3 定案）
 * - heat：互动热度刷写周期/首批延迟/_bulk 分片（batch-size 1000 / concurrency 2）/ pending 键 TTL（2h，批次A A7 定案）
 */
@Data
@Component
@ConfigurationProperties(prefix = "litisle.search")
public class SearchProperties {

    /** ES 连接配置（对应 yml: litisle.search.es） */
    private Es es = new Es();

    /** 索引名（对应 yml: litisle.search.index） */
    private Index index = new Index();

    /** multi_match 权重 + heat 公式权重（对应 yml: litisle.search.weight） */
    private Weight weight = new Weight();

    /** KNN 参数（对应 yml: litisle.search.knn） */
    private Knn knn = new Knn();

    /** 热点查询缓存（对应 yml: litisle.search.cache） */
    private Cache cache = new Cache();

    /** 搜索联想（对应 yml: litisle.search.suggest） */
    private Suggest suggest = new Suggest();

    /** 搜索历史（对应 yml: litisle.search.history） */
    private History history = new History();

    /** 分页限制（对应 yml: litisle.search.page） */
    private Page page = new Page();

    /** MQ 拓扑命名（对应 yml: litisle.search.rabbit） */
    private Rabbit rabbit = new Rabbit();

    /** 降级链路（对应 yml: litisle.search.fallback） */
    private Fallback fallback = new Fallback();

    /** 互动热度刷写（对应 yml: litisle.search.heat，批次A A7 定案：三事件消费仅累加 Redis pending 键，IndexHeatFlushJob 定时聚合 _bulk；批次三 3.2 已落地） */
    private Heat heat = new Heat();

    /** 索引摄入（对应 yml: litisle.search.ingest，批次二：article_chunks 切片参数） */
    private Ingest ingest = new Ingest();

    /** Embedding 向量化（对应 yml: litisle.search.embedding，向量提供方批次七接入） */
    private Embedding embedding = new Embedding();

    /** 消费重试（对应 yml: litisle.search.retry，批次二：延迟重投退避参数） */
    private Retry retry = new Retry();

    /** Elasticsearch 连接配置（对应 yml: litisle.search.es） */
    @Data
    public static class Es {
        /** ES 集群地址列表（host:port，多节点逗号分隔，如 localhost:9200,localhost:9201） */
        private List<String> hosts = new ArrayList<>(List.of("localhost:9200"));
        /** ES 用户名（开发环境关闭安全认证留空跳过 Basic 认证；生产走环境变量） */
        private String username;
        /** ES 密码（同上） */
        private String password;
        /** 建连超时（默认 3s） */
        private Duration connectTimeout = Duration.ofSeconds(3);
        /** 查询 socket 超时（默认 10s） */
        private Duration socketTimeout = Duration.ofSeconds(10);
    }

    /** 索引名配置（对应 yml: litisle.search.index，外置便于分环境/重建迁移） */
    @Data
    public static class Index {
        /** 文章索引名 */
        private String article = "article_idx";
        /** 书籍索引名 */
        private String book = "book_idx";
        /** 用户索引名 */
        private String user = "user_idx";
        /** RAG 知识库切片索引名（article_chunks，search 摄入、ai 纯查询，架构文档 §四） */
        private String chunk = "article_chunks";
    }

    /** 权重配置（对应 yml: litisle.search.weight） */
    @Data
    public static class Weight {
        /** 标题字段权重（multi_match title^4） */
        private double title = 4;
        /** 标签字段权重（tags^3） */
        private double tags = 3;
        /** 摘要字段权重（summary^2） */
        private double summary = 2;
        /** heat 公式权重与时间衰减（对应 yml: litisle.search.weight.heat，批次A A5 定案） */
        private Heat heat = new Heat();

        /**
         * heat 公式权重配置：heat = (w_like×like_cnt + w_comment×comment_cnt + w_view×view_cnt) × 时间衰减因子，
         * 衰减按发布时间距今以半衰期折半（防旧爆款长期霸榜）
         */
        @Data
        public static class Heat {
            /** 点赞权重（默认 2，评论 > 点赞 > 浏览） */
            private double like = 2;
            /** 评论权重（默认 3） */
            private double comment = 3;
            /** 浏览权重（默认 0.1） */
            private double view = 0.1;
            /** 时间衰减半衰期（默认 168h = 7 天，热度每过一个半衰期折半） */
            private Duration timeDecayHalfLife = Duration.ofHours(168);
        }
    }

    /** KNN 参数配置（对应 yml: litisle.search.knn） */
    @Data
    public static class Knn {
        /** KNN 返回条数（默认 10） */
        private int topK = 10;
        /** 候选数量（默认 100，越大召回越准、开销越高） */
        private int numCandidates = 100;
        /** 相似度阈值（默认 0.0 不过滤；ai RAG 的 0.65 阈值属 ai 侧） */
        private double similarityThreshold = 0.0;
    }

    /** 热点查询缓存配置（对应 yml: litisle.search.cache） */
    @Data
    public static class Cache {
        /** 热点查询缓存总开关（兜底/降级结果恒不写缓存） */
        private boolean hotQueryEnabled = true;
        /** 缓存 TTL（默认 10 分钟） */
        private Duration ttl = Duration.ofMinutes(10);
    }

    /** 搜索联想配置（对应 yml: litisle.search.suggest） */
    @Data
    public static class Suggest {
        /** 联想总开关（关闭时 /search/suggest 直接返回空列表） */
        private boolean enabled = true;
        /** 默认返回条数（REST 未传 size 时使用） */
        private int size = 10;
    }

    /** 搜索历史配置（对应 yml: litisle.search.history） */
    @Data
    public static class History {
        /** 单用户搜索历史上限（超限懒删最旧记录） */
        private int maxPerUser = 20;
    }

    /** 分页限制配置（对应 yml: litisle.search.page） */
    @Data
    public static class Page {
        /** 单页条数上限（超出抛 SEARCH_PAGE_EXCEEDED） */
        private int maxPageSize = 50;

        /**
         * 深分页结果窗口上限（默认 10000，必须与 ES 索引级 index.max_result_window 一致）：
         * 检索时约束 pageNum × pageSize ≤ 本值，防止 from+size 超过窗口被 ES 拒绝（批次四 4.1）
         */
        private int maxResultWindow = 10000;
    }

    /** MQ 拓扑命名配置（对应 yml: litisle.search.rabbit） */
    @Data
    public static class Rabbit {
        /**
         * 预留 key（架构文档 §六 遗留项）：索引同步实际为 MqConstant 声明的六个队列，
         * 拓扑声明（SearchMqConfig）读取 MqConstant 常量，不读取此值
         */
        private String syncQueue = "search.index.sync.queue";
        /** 索引同步死信交换机名（与 SearchBizConstant.EXCHANGE_SEARCH_DLX 保持一致） */
        private String dlx = "litisle.search.dlx";
    }

    /** 降级链路配置（对应 yml: litisle.search.fallback） */
    @Data
    public static class Fallback {
        /** 空结果兜底：查询词实时 Embedding 超时上限（超时/失败二级兜底 HEAT 热门列表，批次A A1 定案） */
        private Duration embeddingTimeout = Duration.ofSeconds(1);
        /** ES 不可用降级开关（降级返回热门列表 + 前端提示，source=DEGRADED 不缓存，批次A A3 定案） */
        private boolean enabled = true;
    }

    /**
     * 互动热度刷写配置（对应 yml: litisle.search.heat，批次A A7 定案）：
     * liked/commented/view-count-flushed 三事件消费仅累加 Redis 热度待刷新键
     * （pending 键，Hash 结构，见 SearchBizConstant.HEAT_PENDING_KEY_PREFIX），
     * IndexHeatFlushJob 按刷写周期聚合 `_bulk` 批量 partial update heat（每篇每窗口至多 1 次 ES 写）
     */
    @Data
    public static class Heat {

        /** 定时 _bulk 刷写参数（对应 yml: litisle.search.heat.flush） */
        private Flush flush = new Flush();

        /**
         * _bulk 刷写嵌套配置（批次A A7 三处工程细节定版）：
         * bulk 必须分片执行——单批条数上限 + 并发度双约束，防超大请求被 ES 拒绝（http.max_content_length）；
         * bulk 内用 update + doc 只改 heat 相关字段，禁用 index 整篇替换（保住 vec_dense 不重写）
         */
        @Data
        public static class Flush {

            /** 刷写周期（毫秒，默认 900000 = 15min，可调 15~30min；fixedDelay 语义不堆积） */
            private long intervalMs = 900000L;

            /** 首次执行延迟（毫秒，默认 60000 = 1min，避开启动高峰） */
            private long initialDelayMs = 60000L;

            /** 单次 _bulk 批量条数上限（默认 1000，企业主流区间 500~2000；超出自动分批） */
            private int batchSize = 1000;

            /** bulk 并发度（默认 2，主流区间 2~4；失败批次重试并落 DLQ 由消费侧范式兜底） */
            private int concurrency = 2;

            /**
             * pending 键 TTL（默认 2h，≥ 2 个刷写周期）：防 Redis 异常期间 pending 键未及刷写被过期清空
             * 导致热度漂移；正常路径由 IndexHeatFlushJob 原子取走（Lua HGETALL+DEL，GETDEL 语义），TTL 仅兜底
             */
            private Duration pendingKeyTtl = Duration.ofHours(2);
        }
    }

    /**
     * 索引摄入配置（对应 yml: litisle.search.ingest，批次二）：
     * 发布文章正文切片入 article_chunks（RAG 知识库），参数控制入库门槛与切片粒度
     */
    @Data
    public static class Ingest {

        /** 正文最小字数门槛（默认 200：过短文章不入 RAG 知识库，架构文档 §四） */
        private int minContentLength = 200;

        /** 单切片目标字数（默认 500 字/段） */
        private int chunkSize = 500;

        /** 相邻切片重叠字数（默认 50 字，防断章取义） */
        private int chunkOverlap = 50;
    }

    /**
     * Embedding 向量化配置（对应 yml: litisle.search.embedding）：
     * 向量提供方（模型/接口）随批次七 VectorService 落地接入；
     * 未接入时 vec_dense / chunk_vector 不写入（ES mapping 允许字段缺省），不影响索引其余字段
     */
    @Data
    public static class Embedding {

        /** 向量化总开关（默认 false：模型未选型前关闭，开启需先接入提供方实现） */
        private boolean enabled = false;
    }

    /**
     * 消费重试配置（对应 yml: litisle.search.retry，批次二 2.4）：
     * 索引同步消费异常按指数退避延迟重投（baseDelayMs × 2^(已重试次数)），
     * 超过 maxCount 仍失败则 nack 入死信队列（QUEUE_SEARCH_INDEX_DLQ）
     */
    @Data
    public static class Retry {

        /** 最大重试次数（默认 5，含首发共 6 次投递） */
        private int maxCount = 5;

        /** 首次重试延迟（毫秒，默认 30000 = 30s；后续按 2 的幂次递增） */
        private long baseDelayMs = 30000L;
    }
}
