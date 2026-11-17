package com.litisle.litislesearch.utils;

import com.litisle.litisleapi.util.ArticleVectorTextUtils;
import com.litisle.litislesearch.constant.SearchEmbeddingConstant;

import java.util.List;

/**
 * 检索文本处理工具（批次二）：文章正文 HTML 转纯文本 + RAG 知识库切片。
 *
 * 设计说明：
 * - content 的正文以 HTML 存储（编辑器出品，含 &lt;p&gt; 等标签与 {img:文件ID} 占位符），
 *   直接入 ES 全文检索字段会污染分词结果，故索引前统一转纯文本；
 * - 切片按字数切分且相邻重叠，防止关键句被切断造成的断章取义（架构文档 §四）；
 * - search 板块一 1.1 起本类纯文本转换与向量输入拼接规则下沉 litisle-api
 *   {@link ArticleVectorTextUtils}（search/ai 两服务共享单点，防跨服务口径漂移），
 *   本类方法保留原签名委托之，既有调用方不受影响。
 * - 本类为纯函数工具，无 Spring 依赖，便于后续单测与复用。
 *
 * @author litisle
 */
public final class SearchTextUtils {

    /** 工具类禁止实例化 */
    private SearchTextUtils() {
    }

    /**
     * 把文章正文 HTML 转成可检索的纯文本（规则本体下沉 litisle-api，本方法委托转发）。
     *
     * @param html 正文 HTML（可为空）
     * @return 纯文本（去标签/去占位符/折叠空白）；入参为空时返回空字符串
     */
    public static String toPlainText(String html) {
        // 委托共享单点：search/ai 共用同一 HTML 转纯文本规则，防跨服务口径漂移
        return ArticleVectorTextUtils.toPlainText(html);
    }

    /**
     * 拼接文章向量化输入：标题 + 摘要 + 正文截断片段（规则本体下沉 litisle-api，本方法委托转发）。
     *
     * <p>统一索引写入（发布事件）与全量重建的口径，避免两处拼接规则漂移导致向量语义不一致；
     * 正文按 {@link SearchEmbeddingConstant#EMBEDDING_INPUT_MAX_CHARS} 截断，防超模型输入长度上限。</p>
     *
     * @param title     标题（权重最高）
     * @param summary   摘要（可空）
     * @param plainText 正文纯文本（可空；内部再按上限截断）
     * @return 供 Embedding 使用的单段文本
     */
    public static String buildEmbeddingInput(String title, String summary, String plainText) {
        // 委托共享单点：ai 侧相似推荐查询文本与本方法为同一实现，拼接规则天然一致
        return ArticleVectorTextUtils.buildEmbeddingInput(title, summary, plainText);
    }


    /**
     * 把纯文本按固定字数切分为重叠片段（RAG 知识库切片；规则本体下沉 litisle-api，本方法委托转发）。
     *
     * 切分规则：每段 chunkSize 字，下一段起点 = 上一段起点 + (chunkSize - overlap)，
     * 相邻两段因此重叠 overlap 字，避免语义在边界处被截断。
     *
     * @param text      纯文本
     * @param chunkSize 单段字数
     * @param overlap   相邻段重叠字数
     * @return 切片列表（空文本返回空列表）
     */
    public static List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        // 委托共享单点：切片规则与 ai 侧知识库切片同源
        return ArticleVectorTextUtils.splitIntoChunks(text, chunkSize, overlap);
    }
}

