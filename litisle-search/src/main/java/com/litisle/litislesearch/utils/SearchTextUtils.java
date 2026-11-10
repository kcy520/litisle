package com.litisle.litislesearch.utils;

import com.litisle.litislesearch.constant.SearchEmbeddingConstant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 检索文本处理工具（批次二）：文章正文 HTML 转纯文本 + RAG 知识库切片。
 *
 * 设计说明：
 * - content 的正文以 HTML 存储（编辑器出品，含 &lt;p&gt; 等标签与 {img:文件ID} 占位符），
 *   直接入 ES 全文检索字段会污染分词结果，故索引前统一转纯文本；
 * - 切片按字数切分且相邻重叠，防止关键句被切断造成的断章取义（架构文档 §四）；
 * - 本类为纯函数工具，无 Spring 依赖，便于后续单测与复用。
 *
 * @author litisle
 */
public final class SearchTextUtils {

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

    /** 工具类禁止实例化 */
    private SearchTextUtils() {
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
     * <p>统一索引写入（发布事件）与全量重建的口径，避免两处拼接规则漂移导致向量语义不一致；
     * 正文按 {@link SearchEmbeddingConstant#EMBEDDING_INPUT_MAX_CHARS} 截断，防超模型输入长度上限。</p>
     *
     * @param title     标题（权重最高）
     * @param summary   摘要（可空）
     * @param plainText 正文纯文本（可空；内部再按上限截断）
     * @return 供 Embedding 使用的单段文本
     */
    public static String buildEmbeddingInput(String title, String summary, String plainText) {
        // 正文按上限截断：只影响向量语义覆盖范围，不影响全文检索字段 content
        String contentPart = plainText == null ? "" : plainText;
        if (contentPart.length() > SearchEmbeddingConstant.EMBEDDING_INPUT_MAX_CHARS) {
            contentPart = contentPart.substring(0, SearchEmbeddingConstant.EMBEDDING_INPUT_MAX_CHARS);
        }
        // 三段以换行分隔，保持标题/摘要/正文的语义边界
        return (title == null ? "" : title) + "\n"
                + (summary == null ? "" : summary) + "\n"
                + contentPart;
    }


    /**
     * 把纯文本按固定字数切分为重叠片段（RAG 知识库切片）。
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
        // 参数防护：文本为空或切片长度非法时不切片
        if (text == null || text.isEmpty() || chunkSize <= 0) {
            return List.of();
        }
        // 步长计算：保证每次至少前进 1 字，防 overlap >= chunkSize 时死循环
        int step = Math.max(1, chunkSize - Math.max(0, overlap));
        // 结果容器：按起始位置逐步推进
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += step) {
            // 结束位置：不超过文本长度
            int end = Math.min(text.length(), start + chunkSize);
            String chunk = text.substring(start, end).trim();
            // 过滤纯空白片段（HTML 转文本后可能残留）
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            // 已到文末，结束切分
            if (end >= text.length()) {
                break;
            }
        }
        return chunks;
    }
}
