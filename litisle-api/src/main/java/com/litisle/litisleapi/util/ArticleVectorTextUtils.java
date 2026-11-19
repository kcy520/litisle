package com.litisle.litisleapi.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 文章向量化文本工具（跨服务共享单点）。
 *
 * 【职责边界】本类承载「文章向量输入文本」的两段公共规则：正文 HTML 转纯文本、
 * 向量化输入拼接（标题+摘要+正文截断）。search 侧（SearchTextUtils 委托本类）与
 * ai 侧（相似推荐 buildQueryText 直接调用）共用同一份实现，
 * 保证查询向量与文章向量的输入口径由同一代码约束，防止跨服务口径漂移。
 * 【适配范围】仅限向量化输入文本构造；ES 全文检索字段的纯文本转换同样复用 toPlainText。
 * 【截断口径】正文按 {@link #EMBEDDING_INPUT_MAX_CHARS} 截断，防超模型输入长度上限。
 * 本类为纯函数工具，无 Spring 依赖，便于单测与复用。
 *
 * @author litisle
 */
public final class ArticleVectorTextUtils {

    /**
     * 文章向量化输入最大字符数（默认 2000）：
     * Embedding 模型普遍有输入长度上限，超长正文截断后再向量化，防请求被模型拒绝；
     * 截断只影响向量语义覆盖范围，不影响全文检索字段 content（完整入索引）
     */
    public static final int EMBEDDING_INPUT_MAX_CHARS = 2000;

    /** HTML 配图占位符（{img:文件ID}，content 编辑器约定格式）：索引前剔除，无文本价值 */
    private static final Pattern IMG_PLACEHOLDER = Pattern.compile("\\{img:[^}]*}");

    /** 块级标签（换行语义）：先替换为换行，避免相邻段落文字粘连成一个词 */
    private static final Pattern BLOCK_TAG = Pattern.compile("</?(p|div|br|li|ul|ol|h[1-6]|tr|td|table)[^>]*>",
            Pattern.CASE_INSENSITIVE);

    /** 任意 HTML 标签（剩余内联标签统一剔除，只保留文本） */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    /** 连续空白（含换行/制表）：统一折叠为单个空格，减少无意义分词 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** 常见 HTML 实体替换表（覆盖编辑器产出的主要实体；未覆盖的原样保留） */
    private static final Map<String, String> HTML_ENTITIES = Map.of(
            "&nbsp;", " ",
            "&amp;", "&",
            "&lt;", "<",
            "&gt;", ">",
            "&quot;", "\"",
            "&#39;", "'");

    /** 句子结束边界字符（切片断句依据：优先在句界断开，避免句子被拦腰截断） */
    private static final String SENTENCE_ENDINGS = "。！？!?\n";

    /** 工具类禁止实例化 */
    private ArticleVectorTextUtils() {
    }

    /**
     * 把文章正文 HTML 转成可检索的纯文本。
     *
     * @param html 正文 HTML（可为空）
     * @return 纯文本（去标签/去占位符/折叠空白）；入参为空时返回空字符串
     */
    public static String toPlainText(String html) {
        // 空值兜底：调用方无需判空
        if (html == null || html.isEmpty()) {
            return "";
        }
        // 步骤1：剔除配图占位符（无检索价值，保留只剩噪音）
        String text = IMG_PLACEHOLDER.matcher(html).replaceAll(" ");
        // 步骤2：块级标签转空格（保留段落边界，避免段落粘连）
        text = BLOCK_TAG.matcher(text).replaceAll(" ");
        // 步骤3：剔除剩余所有标签
        text = HTML_TAG.matcher(text).replaceAll(" ");
        // 步骤4：还原常见 HTML 实体
        for (Map.Entry<String, String> entity : HTML_ENTITIES.entrySet()) {
            text = text.replace(entity.getKey(), entity.getValue());
        }
        // 步骤5：折叠连续空白并去首尾
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    /**
     * 拼接文章向量化输入：标题 + 摘要 + 正文截断片段。
     *
     * <p>统一索引写入（发布事件）、全量重建与 ai 侧查询文本的拼接口径，
     * 避免各处拼接规则漂移导致向量语义不一致；正文按
     * {@link #EMBEDDING_INPUT_MAX_CHARS} 截断，防超模型输入长度上限。</p>
     *
     * @param title     标题（权重最高）
     * @param summary   摘要（可空）
     * @param plainText 正文纯文本（可空；内部再按上限截断）
     * @return 供 Embedding 使用的单段文本
     */
    public static String buildEmbeddingInput(String title, String summary, String plainText) {
        // 正文按上限截断：只影响向量语义覆盖范围，不影响全文检索字段 content
        String contentPart = plainText == null ? "" : plainText;
        if (contentPart.length() > EMBEDDING_INPUT_MAX_CHARS) {
            contentPart = contentPart.substring(0, EMBEDDING_INPUT_MAX_CHARS);
        }
        // 三段以换行分隔，保持标题/摘要/正文的语义边界
        return (title == null ? "" : title) + "\n"
                + (summary == null ? "" : summary) + "\n"
                + contentPart;
    }

    /**
     * 把纯文本按固定字数切分为重叠片段（RAG 知识库切片，不带片数上限）。
     *
     * <p>统一 AI 侧（RagIngestService 摄入）与 search 侧（ArticleChunkServiceImpl 摄入）的
     * 切片算法：两服务共享同一实现，防分片口径漂移导致向量语义不一致。</p>
     *
     * @param text      纯文本
     * @param chunkSize 单段字数
     * @param overlap   相邻段重叠字数
     * @return 切片列表（空文本返回空列表）
     */
    public static List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        // 片数不限：上限传整型最大值（兼容既有三参调用方，行为仅增加句界回退优化）
        return splitIntoChunks(text, chunkSize, overlap, Integer.MAX_VALUE);
    }

    /**
     * 把纯文本按固定字数切分为重叠片段（带单篇片数上限）。
     *
     * <p>切分规则：每段 chunkSize 字，下一段起点 = 上一段断点 - overlap，相邻两段因此
     * 重叠 overlap 字，避免语义在边界处被截断；段内命中句子结束符（。！？!?\n）时
     * 回退到最近句界断句，避免句子被拦腰截断；片数达 maxChunks 即截断
     * （防超长正文撑爆索引与 embedding 成本）。</p>
     *
     * @param text      纯文本
     * @param chunkSize 单段字数
     * @param overlap   相邻段重叠字数
     * @param maxChunks 单篇片数上限（非正数视为不切片，返回空列表）
     * @return 切片列表（空文本返回空列表）
     */
    public static List<String> splitIntoChunks(String text, int chunkSize, int overlap, int maxChunks) {
        // 参数防护：文本为空或切片长度非法/片数上限非正时不切片
        if (text == null || text.isEmpty() || chunkSize <= 0 || maxChunks <= 0) {
            return List.of();
        }
        // 步长计算：保证每次至少前进 1 字，防 overlap >= chunkSize 时死循环
        int step = Math.max(1, chunkSize - Math.max(0, overlap));
        // 结果容器：按起始位置逐步推进
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length() && chunks.size() < maxChunks) {
            // 结束位置：不超过文本长度
            int end = Math.min(start + chunkSize, text.length());
            // 未到文末且段内命中句子边界：回退断句点，避免句子被拦腰截断（找不到才按原位置硬切）
            if (end < text.length()) {
                int boundary = lastSentenceEnd(text, start, end);
                if (boundary > start) {
                    end = boundary;
                }
            }
            String chunk = text.substring(start, end).trim();
            // 空切片跳过（边界回退可能产生）
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            // 步进 = size - overlap；边界断句时按实际 end 计算下一步起点，并保底前进 step 防原地打转
            start = end >= text.length() ? end : Math.max(end - Math.max(0, overlap), start + step);
            // 已到文末，结束切分
            if (end >= text.length()) {
                break;
            }
        }
        return chunks;
    }

    /**
     * 在 [start, end) 内从后往前找最近的句子结束边界（断句点=边界字符位置+1）。
     *
     * @param text  纯文本
     * @param start 区间起点
     * @param end   区间终点（不含）
     * @return 断句点位置；未找到返回 -1（表示该段无自然句界，只能按原位置切）
     */
    private static int lastSentenceEnd(String text, int start, int end) {
        // 从右往左扫，找最近的句子结束字符，返回其后位置作为断句点
        for (int i = end - 1; i > start; i--) {
            if (SENTENCE_ENDINGS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }
}
