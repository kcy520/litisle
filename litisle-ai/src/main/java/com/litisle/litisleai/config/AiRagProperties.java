package com.litisle.litisleai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/*
 * 通俗说明：
 * 这是"知识库问答"功能的所有可调参数。做什么：把文章切成小段，用本地 Ollama 模型
 * 把这些小段转成向量存进 Elasticsearch；用户提问时按相似度找回最相关的几段交给大模型作答。
 * 这里管的是：Ollama 地址和模型、ES 地址和索引名、每段切多长/重叠多少、
 * 一次取回几段、相似度门槛多高、匿名用户每天能问几次、相似文章推荐取几篇。
 * enabled=false 时整套知识库功能不启用，没装 Ollama/ES 的本地环境照样能启动服务。
 */
/**
 * RAG 知识库配置（板块四 4.1/4.2：摄入链路与检索问答，参数全部外置 yml/Nacos）
 *
 * <p>【配置】前缀 litisle.ai.rag；enabled=false（本地默认）时 RAG 链路整体缺席：
 * MQ 消费者不装配、检索工具返回占位降级说明（Q54：降级为无引用回答）。</p>
 *
 * <p>【选型（P3/P9 定档）】Embedding 用本地 Ollama + bge-m3（1024 维，零外发零成本）；
 * ES 与 search 共用实例但 AI 自建自管索引（索引名隔离，默认 article_chunks_ai），
 * 与 search 的 article_idx 互不干涉。</p>
 *
 * @author litisle
 */
@Data
@ConfigurationProperties(prefix = "litisle.ai.rag")
public class AiRagProperties {

    /**
     * RAG 链路总开关：false 时 MQ 消费者与检索能力均不装配（本地无 Ollama/ES 也可启动）
     */
    private boolean enabled = false;

    // ==================== Embedding（P3 定档） ====================

    /** Ollama 服务地址（本地部署，零外发） */
    private String ollamaBaseUrl = "http://localhost:11434";

    /** Embedding 模型标识（bge-m3，1024 维） */
    private String ollamaModel = "bge-m3";

    /** 单次 embedding 调用超时 */
    private Duration embedTimeout = Duration.ofSeconds(30);

    /** 向量维度（索引 mapping 与维度校验依据） */
    private int vectorDimension = 1024;

    // ==================== ES 索引（P9 定档：AI 自建自管，索引名隔离） ====================

    /** ES 集群地址（host:port，逗号分隔，与 search 共用实例） */
    private String esHosts = "localhost:9200";

    /** ES 用户名（开发环境留空跳过认证；生产经环境变量注入） */
    private String esUsername = "";

    /** ES 密码（环境变量注入，禁提交明文） */
    private String esPassword = "";

    /** AI 自管切片索引名（与 search 的 article_chunks 索引名隔离） */
    private String esIndex = "article_chunks_ai";

    /** ES 建连超时 */
    private Duration esConnectTimeout = Duration.ofSeconds(3);

    /** ES socket 超时 */
    private Duration esSocketTimeout = Duration.ofSeconds(10);

    // ==================== 切片（4.1 定档口径） ====================

    /** 切片长度（字符，500 字/段） */
    private int chunkSize = 500;

    /** 切片重叠（字符，重叠 50 字保上下文连续） */
    private int chunkOverlap = 50;

    /** 单篇切片数上限（防超长正文撑爆索引与 embedding 成本） */
    private int maxChunksPerArticle = 100;

    // ==================== 检索（4.2 定档口径） ====================

    /** TopK 召回条数 */
    private int topK = 5;

    /** 相似度下限（低于该分数的召回片段丢弃，防低质引用） */
    private double minScore = 0.6;

    // ==================== 匿名问答配额（P5 定档） ====================

    /**
     * 匿名问答日配额（次/日/IP哈希；P5 定档：数值先外置配置，Redis 日计数键 ai:quota:anon:{ip哈希}）
     */
    private int anonQaDailyLimit = 10;

    // ==================== 相似文章推荐（板块五 5.1/5.2：F8 纯检索形态） ====================

    /** 相似推荐配置组（topK/minScore/理由生成开关，随 rag.enabled 总开关生效） */
    private Similar similar = new Similar();

    /**
     * 相似文章推荐配置（F8：只做相似检索不评价作者，纯检索形态零生成成本）
     */
    @Data
    public static class Similar {

        /** 相似推荐篇数上限（切片召回后按篇去重，返回的文章数上限） */
        private int topK = 5;

        /** 条件版检索 topK 上限（板块八 1.2 提取侧校验与 search 侧截断对齐口径，防止客户端超量拉取） */
        private int conditionalTopKMax = 50;

        /** 相似度下限（低于该分数的召回切片丢弃，防低质推荐） */
        private double minScore = 0.6;

        /** 推荐理由生成开关（5.2 可选增强，默认关闭——避免每次推荐都产生生成成本） */
        private boolean reasonEnabled = false;

        /** 生成推荐理由的候选篇数上限（TopN，超过 topK 时取两者较小值） */
        private int reasonTopN = 3;

        /** 一句话推荐理由字数上限（service 侧校验，超出判失败回喂重试） */
        private int reasonMaxChars = 60;

        /** 发布前查重风险阈值（板块十二 4.2：相似度 ≥ 该值提示与库内已有文章的重复风险，外置可调） */
        private double duplicateScoreThreshold = 0.85;
    }
}
