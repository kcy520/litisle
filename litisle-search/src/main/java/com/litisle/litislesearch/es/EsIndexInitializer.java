package com.litisle.litislesearch.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.json.JsonData;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchBizConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ES 索引 mapping 初始化器（批次一 1.1，架构文档 §四 索引设计）：
 * 服务启动时幂等创建四个索引（已存在则跳过，绝不覆盖既有 mapping）：
 * - article_idx：文章索引（ik 分词 + 可见性闸门字段 + completion 联想子字段 + vec_dense 向量）
 * - book_idx：书籍索引（书名/作者/简介 ik + 分类 keyword + authorId keyword；批次五 B4 同步事件落地）
 * - user_idx：用户索引（笔名/简介 ik；批次五 B5 同步事件落地）
 * - article_chunks：RAG 知识库切片索引（search 摄入、ai 纯查询，架构文档 §四）
 *
 * 容错约定：单个索引创建失败仅告警不阻断启动（ES 不可用属设计的降级场景，
 * 批次八降级链路兜底；索引缺失时消费端写入会失败入 DLQ，可人工重投）
 *
 * 向量元信息：article_idx 与 article_chunks 的 mappings._meta 写入 Embedding 模型标识与维度
 * （批次C C1 定版：两索引统一同一 Embedding 单模型；模型变更 → 全量重建索引，架构文档 §八-8）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsIndexInitializer implements ApplicationRunner {

    /** ES 客户端（ElasticsearchConfig 装配的唯一 ES 入口） */
    private final ElasticsearchClient elasticsearchClient;

    /** search 配置绑定 Bean（索引名取 litisle.search.index.*，外置便于分环境/重建迁移） */
    private final SearchProperties properties;

    /**
     * 启动后执行索引初始化：逐索引幂等创建，单个失败告警不阻断启动
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        // 变量：待初始化的 索引名 → mapping 清单（顺序无依赖，逐个创建）
        Map<String, TypeMapping> indexMappings = Map.of(
                properties.getIndex().getArticle(), articleMapping(),
                properties.getIndex().getBook(), bookMapping(),
                properties.getIndex().getUser(), userMapping(),
                properties.getIndex().getChunk(), chunkMapping());

        // 遍历：已存在则跳过（幂等，防覆盖线上 mapping），否则创建
        indexMappings.forEach(this::createIndexIfAbsent);
        // 批次五 B4 遗留收口：给「已存在的 book_idx」幂等补 authorId 字段映射
        // （新增字段可热更新 mapping，无需重建索引；但存量文档不回填，需重同步或全量重建后才能取到 authorId）
        upgradeBookMappingIfNeeded();
    }

    /**
     * 幂等升级 book_idx mapping：补齐 authorId 字段（批次五 B4，批次四遗留）。
     *
     * 作用：book_idx 首次创建时就带 authorId（见 bookMapping）；本方法只处理「索引已存在但
     * mapping 还是旧版（无 authorId）」的存量环境，启动时补一次 putMapping。
     *
     * 【影响说明】
     * - ES 允许对已存在索引新增字段，添加同定义字段也是幂等操作，可重复执行；
     * - 补 mapping 只让「新写入/更新」的文档带 authorId，存量文档该字段仍缺失，
     *   需对存量书籍执行事件重放或全量重建索引才能回填；
     * - 未回填前检索结果 BookDocVO.authorId 为 null（不影响检索命中与排序）。
     *
     * 失败仅告警不阻断启动（与索引创建容错口径一致）。
     */
    private void upgradeBookMappingIfNeeded() {
        String indexName = properties.getIndex().getBook();
        try {
            // 索引不存在则跳过（下次创建会带 authorId）
            boolean exists = elasticsearchClient.indices()
                    .exists(e -> e.index(indexName)).value();
            if (!exists) {
                return;
            }
            // 幂等补字段映射（已存在同名字段时 ES 不报错）
            elasticsearchClient.indices().putMapping(p -> p
                    .index(indexName)
                    .properties(Map.of(SearchBizConstant.FIELD_AUTHOR_ID, keywordProperty())));
            log.info("[ES索引初始化] book_idx 已幂等补齐 authorId 字段映射：{}", indexName);
        } catch (Exception e) {
            // 容错：升级失败仅告警（旧 mapping 仍可检索，authorId 待人工重建时回填）
            log.error("[ES索引初始化] book_idx 补 authorId 映射失败（服务继续启动）：{}", indexName, e);
        }
    }

    /**
     * 幂等创建单个索引：存在即跳过；失败仅告警不抛出（不阻断服务启动）
     *
     * @param indexName 索引名（litisle.search.index.* 配置）
     * @param mapping   索引 mapping 定义
     */
    private void createIndexIfAbsent(String indexName, TypeMapping mapping) {
        try {
            // 分支：索引已存在则跳过（exists 幂等检查，避免覆盖既有 mapping/数据）
            boolean exists = elasticsearchClient.indices()
                    .exists(e -> e.index(indexName)).value();
            if (exists) {
                log.info("[ES索引初始化] 索引已存在，跳过创建：{}", indexName);
                return;
            }
            // 动作：创建索引（仅 mapping，不设分片副本——沿用集群默认，需要时 Nacos/运维侧调整）
            elasticsearchClient.indices().create(c -> c.index(indexName).mappings(mapping));
            log.info("[ES索引初始化] 索引创建成功：{}", indexName);
        } catch (Exception e) {
            // 容错：ES 不可用/创建失败仅告警，不阻断启动（批次八降级链路兜底，索引可后续补建）
            log.error("[ES索引初始化] 索引创建失败（服务继续启动，请检查 ES 后重启或人工补建）：{}", indexName, e);
        }
    }

    /**
     * article_idx mapping（架构文档 §四 核心索引）：
     * id 主键 / title+tags+summary+content 检索字段（ik 分词，title/authorName 含 completion 联想子字段）/
     * visibility 可见性闸门 / authorId+authorName 作者字段（批次A A4）/ zone+category 筛选字段 /
     * 互动计数三列 / heat 热度 / vec_dense 1024 维向量（KNN）/ updateTime 排序字段
     *
     * @return 文章索引 mapping 定义
     */
    private TypeMapping articleMapping() {
        return TypeMapping.of(m -> m
                // _meta：向量模型版本登记（批次C C1，模型变更 → 全量重建索引）
                .meta(vectorModelMeta())
                // 注：字段数超过 Map.of 10 对上限，用 Map.ofEntries
                .properties(Map.ofEntries(
                        // 文档主键：articleId（幂等 upsert 依据）
                        Map.entry(SearchBizConstant.FIELD_ID, keywordProperty()),
                        // 标题：ik 分文 + ^4 权重（权重在查询 DSL 侧配置）+ completion 联想子字段
                        Map.entry(SearchBizConstant.FIELD_TITLE, ikTextWithCompletion()),
                        // 标签：keyword 数组，^3 权重，兼作标签重合度计算
                        Map.entry(SearchBizConstant.FIELD_TAGS, keywordProperty()),
                        // 摘要：ik 分词 + ^2 权重
                        Map.entry(SearchBizConstant.FIELD_SUMMARY, ikTextProperty()),
                        // 正文：ik 分词，全文检索 + 高亮
                        Map.entry(SearchBizConstant.FIELD_CONTENT, ikTextProperty()),
                        // 可见性：写入侧闸门 + 查询侧 filter 双保险（仅 PUBLIC 入索引）
                        Map.entry(SearchBizConstant.FIELD_VISIBILITY, keywordProperty()),
                        // 作者 ID：keyword，作者维度聚合/筛选（批次A A4）
                        Map.entry(SearchBizConstant.FIELD_AUTHOR_ID, keywordProperty()),
                        // 作者名：ik 分词 + completion 联想子字段（批次A A4）
                        Map.entry(SearchBizConstant.FIELD_AUTHOR_NAME, ikTextWithCompletion()),
                        // 分区与分类：keyword 筛选字段（批次二 2.1 补，批次四 tag/category/zone 筛选的数据来源）
                        Map.entry(SearchBizConstant.FIELD_ZONE, keywordProperty()),
                        Map.entry(SearchBizConstant.FIELD_CATEGORY, keywordProperty()),
                        // 互动计数三列：heat 计算基数（like/comment 随互动事件增量，view 随 flush 覆盖）
                        Map.entry(SearchBizConstant.FIELD_LIKE_CNT, longProperty()),
                        Map.entry(SearchBizConstant.FIELD_COMMENT_CNT, longProperty()),
                        Map.entry(SearchBizConstant.FIELD_VIEW_CNT, longProperty()),
                        // 互动热度：double，function_score 加权因子 + HEAT 排序
                        Map.entry(SearchBizConstant.FIELD_HEAT, doubleProperty()),
                        // 文章向量：1024 维 cosine indexed，KNN 相似推荐（查询期零 Embedding 开销）
                        Map.entry(SearchBizConstant.FIELD_VEC_DENSE, denseVectorProperty()),
                        // 更新时间：NEWEST 排序
                        Map.entry(SearchBizConstant.FIELD_UPDATE_TIME, dateProperty()))));
    }

    /**
     * book_idx mapping（架构文档 §四）：书名/作者/简介（text + ik）+ 分类 keyword + authorId keyword；
     * 无向量字段（书籍一期不做相似推荐）；批次五 B4 已补 authorId（BookDocVO.authorId 回填依据）
     *
     * @return 书籍索引 mapping 定义
     */
    private TypeMapping bookMapping() {
        return TypeMapping.of(m -> m
                .properties(Map.of(
                        // 文档主键：bookId
                        SearchBizConstant.FIELD_ID, keywordProperty(),
                        // 书名：ik 分词
                        SearchBizConstant.FIELD_TITLE, ikTextProperty(),
                        // 作者 ID：keyword，作者维度筛选 + BookDocVO.authorId 回填（批次五 B4 新增）
                        SearchBizConstant.FIELD_AUTHOR_ID, keywordProperty(),
                        // 作者名：ik 分词
                        SearchBizConstant.FIELD_AUTHOR_NAME, ikTextProperty(),
                        // 简介：ik 分词
                        SearchBizConstant.FIELD_SUMMARY, ikTextProperty(),
                        // 分类：keyword 筛选维度
                        SearchBizConstant.FIELD_CATEGORY, keywordProperty(),
                        // 更新时间：NEWEST 排序
                        SearchBizConstant.FIELD_UPDATE_TIME, dateProperty())));
    }

    /**
     * user_idx mapping（架构文档 §四）：笔名（text + ik，模糊匹配）+ 简介；
     * 索引同步事件（user.registered / user.profile-updated）已于批次五 B5 落地，写入见 UserIndexServiceImpl
     *
     * @return 用户索引 mapping 定义
     */
    private TypeMapping userMapping() {
        return TypeMapping.of(m -> m
                .properties(Map.of(
                        // 文档主键：userId
                        SearchBizConstant.FIELD_ID, keywordProperty(),
                        // 笔名：ik 分词，模糊匹配检索
                        SearchBizConstant.FIELD_PEN_NAME, ikTextProperty(),
                        // 简介：ik 分词
                        SearchBizConstant.FIELD_BIO, ikTextProperty(),
                        // 更新时间：NEWEST 排序
                        SearchBizConstant.FIELD_UPDATE_TIME, dateProperty())));
    }

    /**
     * article_chunks mapping（RAG 知识库索引，架构文档 §四）：
     * chunk_id 主键 / article_id+author_id 归属 / 标题+作者冗余 / chunk_text 切片正文 /
     * chunk_vector 1024 维向量 / zone 分区 / visibility 可见性 / publish_time+ingest_time 时间
     *
     * @return 切片索引 mapping 定义
     */
    private TypeMapping chunkMapping() {
        return TypeMapping.of(m -> m
                // _meta：与 article_idx 同一 Embedding 单模型版本登记（批次C C1）
                .meta(vectorModelMeta())
                // 注：字段数超过 Map.of 10 对上限，用 Map.ofEntries
                .properties(Map.ofEntries(
                        // 切片主键：articleId_seq（按 articleId 幂等覆盖删旧写新）
                        Map.entry(SearchBizConstant.FIELD_CHUNK_ID, keywordProperty()),
                        // 所属文章 ID：按 articleId 幂等删除旧块的查询键
                        Map.entry(SearchBizConstant.FIELD_ARTICLE_ID, longProperty()),
                        // 作者 ID：RAG 检索可选过滤
                        Map.entry(SearchBizConstant.FIELD_AUTHOR_ID, keywordProperty()),
                        // 文章标题：冗余存储，引用卡片展示
                        Map.entry(SearchBizConstant.FIELD_TITLE, ikTextProperty()),
                        // 作者名：冗余存储，引用卡片展示
                        Map.entry(SearchBizConstant.FIELD_AUTHOR_NAME, ikTextProperty()),
                        // 切片正文：ik 分词，小月 RAG 召回文本
                        Map.entry(SearchBizConstant.FIELD_CHUNK_TEXT, ikTextProperty()),
                        // 切片向量：与 vec_dense 同一 Embedding 单模型（批次C C1）
                        Map.entry(SearchBizConstant.FIELD_CHUNK_VECTOR, denseVectorProperty()),
                        // 分区：RAG 检索可选过滤维度
                        Map.entry(SearchBizConstant.FIELD_ZONE, keywordProperty()),
                        // 可见性：仅 PUBLIC 文章切片入库（隐私红线，批次C C2）
                        Map.entry(SearchBizConstant.FIELD_VISIBILITY, keywordProperty()),
                        // 发布时间/摄入时间：date
                        Map.entry(SearchBizConstant.FIELD_PUBLISH_TIME, dateProperty()),
                        Map.entry(SearchBizConstant.FIELD_INGEST_TIME, dateProperty()))));
    }

    /**
     * 构建向量索引的 _meta 元信息：Embedding 模型标识 + 维度（批次C C1 防漂移）
     *
     * @return _meta 键值对（写入 mappings.meta）
     */
    private Map<String, JsonData> vectorModelMeta() {
        return Map.of(
                SearchBizConstant.META_EMBEDDING_MODEL, JsonData.of(SearchBizConstant.EMBEDDING_MODEL_PLACEHOLDER),
                SearchBizConstant.META_EMBEDDING_DIMS, JsonData.of(SearchBizConstant.EMBEDDING_DIMS));
    }

    /**
     * 构建 ik 中文分词 text 属性：写入 ik_max_word（细粒度提召回）+ 查询 ik_smart（粗粒度防误召回）
     *
     * @return text 字段属性
     */
    private Property ikTextProperty() {
        return Property.of(p -> p.text(t -> t
                .analyzer(SearchBizConstant.ANALYZER_IK_MAX_WORD)
                .searchAnalyzer(SearchBizConstant.ANALYZER_IK_SMART)));
    }

    /**
     * 构建 ik 中文分词 text 属性 + completion 联想子字段（title.completion / authorName.completion，
     * 仅搜索联想 suggester 用，不参与打分）
     *
     * @return 带 completion 子字段的 text 字段属性
     */
    private Property ikTextWithCompletion() {
        return Property.of(p -> p.text(t -> t
                .analyzer(SearchBizConstant.ANALYZER_IK_MAX_WORD)
                .searchAnalyzer(SearchBizConstant.ANALYZER_IK_SMART)
                // multi-field 子字段：completion 类型，搜索联想 suggester 数据源
                .fields(SearchBizConstant.FIELD_COMPLETION_SUB,
                        Property.of(cp -> cp.completion(c -> c)))));
    }

    /**
     * 构建 keyword 属性（精确值过滤/聚合/排序，不分词）
     *
     * @return keyword 字段属性
     */
    private Property keywordProperty() {
        return Property.of(p -> p.keyword(k -> k));
    }

    /**
     * 构建 long 属性（互动计数/article_id）
     *
     * @return long 字段属性
     */
    private Property longProperty() {
        return Property.of(p -> p.long_(l -> l));
    }

    /**
     * 构建 double 属性（heat 热度）
     *
     * @return double 字段属性
     */
    private Property doubleProperty() {
        return Property.of(p -> p.double_(d -> d));
    }

    /**
     * 构建 dense_vector 属性：1024 维 + cosine 相似度 + indexed（KNN 检索必需）
     *
     * @return dense_vector 字段属性
     */
    private Property denseVectorProperty() {
        return Property.of(p -> p.denseVector(d -> d
                .dims(SearchBizConstant.EMBEDDING_DIMS)
                .index(true)
                // 相似度：cosine（余弦相似，KNN 检索与相似推荐统一口径）
                .similarity(DenseVectorSimilarity.Cosine)));
    }

    /**
     * 构建 date 属性（默认格式：epoch_millis 与 ISO 日期均兼容，兼容 Jackson 序列化 LocalDateTime）
     *
     * @return date 字段属性
     */
    private Property dateProperty() {
        return Property.of(p -> p.date(d -> d));
    }
}
