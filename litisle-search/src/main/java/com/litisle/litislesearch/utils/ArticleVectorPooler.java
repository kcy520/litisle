package com.litisle.litislesearch.utils;

import com.litisle.litislesearch.constant.SearchEmbeddingConstant;

import java.util.List;

/**
 * 文章向量切片池化纯函数聚合器（search 板块一 1.1）。
 *
 * 【职责边界】本类只做向量聚合数学（切片聚合 + 加权池化 + L2 归一化），
 * 不依赖 Spring/ES/Embedding 提供方；标题/摘要向量的获取、切片向量的来源（事件链路或重建回查）、
 * 无切片兜底决策由 {@code ArticleVectorComposer}（写入与重建共用编排单点）承担。
 * 【池化口径】vec_dense = (w_t·v标题 + w_s·v摘要 + w_c·chunk聚合向量) / Σw，L2 归一化后输出；
 * 权重 w_t=2 / w_s=2（摘要向量缺失则 w_s 并入 w_t）/ w_c=1；chunk 聚合按配置策略 mean 或 max-pool。
 * 【失败语义】任一输入为 null 视为该分量缺席（标题缺席权重归零、由切片独扛，防 null 向量）；
 * 全部分量缺失或合成向量模长为 0 时返回 null，调用方跳过向量字段写入（字段缺省不影响其余检索字段）。
 *
 * @author litisle
 */
public final class ArticleVectorPooler {

    /** 标题向量权重（w_t，清单定案口径） */
    public static final double WEIGHT_TITLE = 2.0D;

    /** 摘要向量权重（w_s，清单定案口径；摘要缺席时并入标题权重） */
    public static final double WEIGHT_SUMMARY = 2.0D;

    /** 切片聚合向量权重（w_c，清单定案口径） */
    public static final double WEIGHT_CHUNK = 1.0D;

    /** 工具类禁止实例化 */
    private ArticleVectorPooler() {
    }

    /**
     * 切片向量聚合：按配置策略把全部切片向量合成一个聚合向量。
     *
     * <p>【通俗说明】做什么：一篇文章被切成了很多小段，每段都有一个向量；
     * 本方法把这一堆段向量合并成一个向量，代表整篇文章的切片内容。
     * 怎么做：1）先把列表里是空的（null）切片去掉（某段向量化失败就是 null）；
     * 2）去掉后一个都没有，返回 null，让调用方走"没有切片"的处理；
     * 3）按配置的合并方式逐个位置计算——mean（平均）：每个位置把所有切片的数值加起来，
     * 再除以切片个数；max-pool（最大值）：每个位置取所有切片里最大的那个数；
     * 4）某条切片的长度和其他的不一样，就跳过它不参与计算（正常情况下不会发生，纯防御）。
     * 算出来的聚合向量交给 pool 方法做加权合成。</p>
     *
     * @param chunkVectors 切片向量列表（元素可为 null，null 元素跳过）
     * @param strategy     池化策略（mean / max-pool；非法值按 mean 兜底）
     * @return 聚合向量；列表为空或无有效切片时返回 null
     */
    public static List<Float> chunkAggregate(List<List<Float>> chunkVectors, String strategy) {
        /*
         * 业务逻辑步骤：
         * 1. 过滤有效切片向量（null 元素跳过），无有效输入返回 null；
         * 2. 以首个有效向量维度为基准，按策略逐维聚合（mean=累加平均 / max-pool=逐维取最大）；
         * 3. 维度与基准不符的向量跳过（防御分支：正常路径同一单模型维度恒一致）。
         */
        // 变量：过滤后的有效切片向量（null 元素剔除）
        List<List<Float>> valid = validVectors(chunkVectors);
        // 无有效切片：返回 null（调用方走无切片兜底）
        if (valid.isEmpty()) {
            return null;
        }
        // 变量：向量维度基准（取首个有效切片；正常路径全部切片同维度）
        int dims = valid.get(0).size();
        // 判定池化策略：仅精确匹配 max-pool 才走最大值聚合，其余（含非法配置）一律 mean 兜底
        boolean maxPool = SearchEmbeddingConstant.POOL_STRATEGY_MAX_POOL.equals(strategy);
        // 变量：聚合结果容器（逐维累加均值或逐维最大值）
        double[] aggregate = new double[dims];
        // 变量：参与聚合的有效切片数（维度不符的跳过，不计入 mean 分母）
        int count = 0;
        // 步骤2：逐切片逐维聚合
        for (List<Float> vector : valid) {
            // 维度防御：与基准不符的切片跳过，防数组越界（正常路径不会命中）
            if (vector.size() != dims) {
                continue;
            }
            // 逐维处理：mean 累加、max-pool 取最大
            for (int i = 0; i < dims; i++) {
                if (maxPool) {
                    // max-pool：首片直接填充，其后逐维取更大值
                    aggregate[i] = count == 0 ? vector.get(i) : Math.max(aggregate[i], vector.get(i));
                } else {
                    // mean：逐维累加，循环外除以有效片数
                    aggregate[i] += vector.get(i);
                }
            }
            count++;
        }
        // 全部切片维度不符（异常防御）：返回 null 交调用方兜底
        if (count == 0) {
            return null;
        }
        // mean 收尾：累加值除以有效切片数（max-pool 无需平均）
        if (!maxPool) {
            for (int i = 0; i < dims; i++) {
                aggregate[i] /= count;
            }
        }
        return toFloatList(aggregate);
    }
/// ## 1. 一篇一篇还是批量？
//         逐篇处理 。触发来源是 MQ 事件——content 每发布/更新 一篇文章 就发一条消息，search 的消费者一次只处理这一篇。所谓“批量”只发生在 单篇文章内部 ：这一篇切出来的几十个切片，组装好后用 一次 ES`_bulk` 请求 批量写入（`bulkIndex` 方法），减少网络往返。多篇之间不合并、不攒批。
       
// ## 2. 是为了 agent 查相似文章吗？
// 对，但准确说是两层用途
//         - 切片向量 → 池化 →`vec_dense` （相似推荐用） ：这篇文件里的`rebuild` 返回本批切片向量，交给 ArticleVectorComposer 池化，最终写进`article_idx` 索引的`vec_dense` 字段。AI 模块的 agent 调`SearchClient.similar / similarByCondition` 查相似文章时，KNN 搜的就是这个字段—— 这是你说的那个用途 。
//         - 切片本身 →`article_chunks` 索引（RAG 问答用） ：切片文档（含每片的向量）单独存一份，供 AI 模块问答时按片段召回——“文章太长不适合整篇喂给模型，按片段检索更精准”。

/// ## 3. 是如何向量化的？
// 三个粒度，全部调外部 embedding 服务（bge-m3，文本 → 1024 维浮点数组）：
// 标题文字        → 标题向量（权重 2）
// 摘要文字        → 摘要向量（权重 2）
// 每个切片文字    → 每片一个向量（先把几十片聚合成 1 个，权重 1）
//                           ↓
//         池化：vec_dense = (2×标题 + 2×摘要 + 1×切片聚合) ÷ 5，再归一化
// 见 ArticleVectorPooler.pool 。缺某项就权重归零（如摘要缺失，其权重并入标题），全部缺失就跳过向量字段写入。

/// ## 4. 正文是全都要切片吗？
// 不是全文必切，有三个规则 ：
// 1. 长度门槛 ：正文纯文本不足`min-content-length` 直接不入库（过短没切的意义）；
// 2. 滑窗切片 ：达到门槛后按 500 字一片、相邻片重叠 50 字（`chunk-size` /`chunk-overlap` ，重叠是为了句子被切在边界上时，下一片还能接上上文，防断章取义），尽量在段落边界断句；
// 3. 单篇上限 ：最多 100 片，防止超长文章打爆索引。

    /**
     * 加权池化合成文章向量：vec_dense = (w_t·v标题 + w_s·v摘要 + w_c·chunk聚合) / Σw，L2 归一化。
     *
     * <p>【通俗说明】做什么：把「标题向量 + 摘要向量 + 切片聚合向量」三个输入，
     * 合成文章最终写入索引的那个向量（vec_dense）。
     * 怎么做：1）按固定权重加权——标题占 2、摘要占 2、切片占 1；
     * 2）有输入缺席就调权重：缺摘要，摘要的 2 加到标题上（标题变 4）；
     * 缺标题或缺切片，对应权重直接当 0（由剩下的分量撑起整个向量）；
     * 3）三个都缺，返回 null（没有东西可合成）；4）每个位置按
     * 「标题值×2 + 摘要值×2 + 切片值×1，再除以总权重」算出合成值；
     * 5）最后做 L2 归一化——把整段向量除以自己的长度，让长度变成 1，
     * 这样后续算相似度只比较向量的方向、不受长短影响；6）合成出来全是 0（没有方向）也返回 null，
     * 调用方就跳过向量写入，不影响文章其他字段的正常索引。</p>
     *
     * @param titleVector   标题向量（可空=标题缺席，权重归零）
     * @param summaryVector 摘要向量（可空=摘要缺席，w_s 并入 w_t）
     * @param chunkAggregateVector 切片聚合向量（可空=切片缺席，w_c 归零）
     * @return L2 归一化后的文章向量；全部分量缺失或模长为 0 时返回 null
     */
    public static List<Float> pool(List<Float> titleVector, List<Float> summaryVector,
                                   List<Float> chunkAggregateVector) {
        /*
         * 业务逻辑步骤：
         * 1. 计算各分量权重：摘要缺席 w_s 并入 w_t；标题缺席 w_t 归零；切片缺席 w_c 归零；
         * 2. 以首个非空向量维度为基准，逐维加权累加（维度不符分量跳过）；
         * 3. 按总权重归一，再做 L2 归一化输出；全缺/零模长返回 null。
         */
        // 变量：标题有效权重（标题向量缺失时归零，防 null 向量写入）
        double weightTitle = titleVector == null ? 0.0D : WEIGHT_TITLE;
        // 变量：摘要有效权重（摘要向量缺失时其权重并入标题，清单定案口径）
        double weightSummary = summaryVector == null ? 0.0D : WEIGHT_SUMMARY;
        if (summaryVector == null) {
            weightTitle += WEIGHT_SUMMARY;
        }
        // 变量：切片聚合有效权重（切片聚合缺失时归零）
        double weightChunk = chunkAggregateVector == null ? 0.0D : WEIGHT_CHUNK;
        // 总权重为 0：全部分量缺失，返回 null（调用方跳过向量写入）
        double totalWeight = weightTitle + weightSummary + weightChunk;
        if (totalWeight <= 0.0D) {
            return null;
        }
        // 变量：维度基准（首个非空输入向量）
        List<Float> baseline = titleVector != null ? titleVector
                : summaryVector != null ? summaryVector : chunkAggregateVector;
        int dims = baseline.size();
        // 变量：加权累加容器
        double[] weighted = new double[dims];
        // 步骤2：逐分量加权累加（维度与基准不符的分量跳过，防御分支）
        accumulate(weighted, titleVector, weightTitle, dims);
        accumulate(weighted, summaryVector, weightSummary, dims);
        accumulate(weighted, chunkAggregateVector, weightChunk, dims);
        // 变量：加权合成向量（先按总权重归一）
        double[] merged = new double[dims];
        for (int i = 0; i < dims; i++) {
            merged[i] = weighted[i] / totalWeight;
        }
        // 步骤3：L2 归一化（模长 0 防御：全零向量无方向语义，返回 null 跳过写入）
        double norm = 0.0D;
        for (double v : merged) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm <= 0.0D) {
            return null;
        }
        for (int i = 0; i < dims; i++) {
            merged[i] /= norm;
        }
        return toFloatList(merged);
    }

    /**
     * 过滤向量列表中的 null 元素。
     *
     * <p>【通俗说明】做什么：把切片向量列表里"没有向量"的位置（null）挑出去，只留下真正有值的。
     * 怎么做：1）传进来是空列表或 null，直接返回一个空列表；
     * 2）否则逐个检查，是 null 的剔除（某段切片向量化失败时那个位置就是 null），
     * 剩下的按原顺序组成新列表返回。</p>
     *
     * @param vectors 原始向量列表（可空）
     * @return 非 null 向量列表（无有效元素时为空列表）
     */
    private static List<List<Float>> validVectors(List<List<Float>> vectors) {
        // 空入参直接返回空列表
        if (vectors == null || vectors.isEmpty()) {
            return List.of();
        }
        // 过滤 null 元素（Embedding 单片失败时该位为 null）
        return vectors.stream().filter(java.util.Objects::nonNull).toList();
    }

    /**
     * 把单个向量按权重累加进累加容器（维度不符跳过，防御分支）。
     *
     * <p>【通俗说明】做什么：把一个输入向量乘上它的权重，然后一位一位地加到累计容器里，
     * 是 pool 方法合成向量时的一个步骤。
     * 怎么做：1）向量是 null，或者权重是 0（说明这个分量缺席），什么都不加直接返回；
     * 2）向量长度和基准维度对不上也直接返回（正常不会发生，纯防御，防止越界）；
     * 3）正常情况：容器第 i 位加上「向量第 i 位 × 权重」，逐位处理完为止。</p>
     *
     * @param target  累加容器（长度=dims）
     * @param vector  输入向量（可空）
     * @param weight  有效权重（向量可空时调用方已传 0）
     * @param dims    维度基准
     */
    private static void accumulate(double[] target, List<Float> vector, double weight, int dims) {
        // 空向量或零权重：无贡献，直接返回
        if (vector == null || weight <= 0.0D) {
            return;
        }
        // 维度防御：与基准不符的向量跳过（正常路径同模型恒一致，不会命中）
        if (vector.size() != dims) {
            return;
        }
        // 逐维加权累加
        for (int i = 0; i < dims; i++) {
            target[i] += vector.get(i) * weight;
        }
    }

    /**
     * double 数组转 ES 写入用的 Float 列表。
     *
     * <p>【通俗说明】做什么：把中间计算用的 double 数组，变成写进 Elasticsearch 要用的 Float 列表。
     * 怎么做：逐个数字把 double 转成 float，按顺序放进列表返回——
     * ES 的向量字段（dense_vector）要求传 Float 列表，而中间计算用 double 是为了少丢精度。</p>
     *
     * @param values 聚合结果
     * @return Float 列表
     */
    private static List<Float> toFloatList(double[] values) {
        // 逐位装箱（ES dense_vector 接受 Float 列表）
        List<Float> result = new java.util.ArrayList<>(values.length);
        for (double v : values) {
            result.add((float) v);
        }
        return result;
    }
}
