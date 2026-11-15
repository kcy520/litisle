package com.litisle.litisleai.rag;

import com.litisle.litisleai.config.AiRagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 正文清洗与切片器（板块四 4.1：500 字/段、重叠 50 字，AI 自管切片口径）
 *
 * <p>【清洗口径】剥离 HTML 标签与脚本片段、空白字符归一（换行折叠单空格）——
 * 切片进向量库的是干净正文，模型检索读到的也是清洗后文本。</p>
 *
 * <p>【切片口径】滑动窗口按字符切：步长 = chunkSize - chunkOverlap，重叠段保上下文
 * 连续；段落边界（。！？\n）优先断句，避免句子被拦腰截断；单篇切片数超上限则截断
 * （防超长正文撑爆索引与 embedding 成本）。</p>
 *
 * @author litisle
 */
@Component
public class ContentChunker {

    /** HTML 标签剥离（含 script/style 整段移除） */
    private static final String SCRIPT_BLOCK_PATTERN = "(?is)<(script|style)[^>]*>.*?</\\1>";
    private static final String TAG_PATTERN = "<[^>]+>";

    /** 段落边界字符（句子结束优先断句） */
    private static final String SENTENCE_ENDINGS = "。！？!?\n";

    /** RAG 配置（chunk-size / chunk-overlap / 上限来源） */
    private final AiRagProperties ragProperties;

    /**
     * 构造注入
     *
     * @param ragProperties RAG 配置
     */
    public ContentChunker(AiRagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 清洗正文并切片
     *
     * @param rawContent 原始正文（可能含 HTML 标记）
     * @return 切片列表（每片 ≤ chunkSize 字符；空正文返回空列表）
     */
    public List<String> chunk(String rawContent) {
        // ① 清洗：剥脚本/标签、空白归一
        String clean = clean(rawContent);
        if (clean.isBlank()) {
            return List.of();
        }
        // ② 滑动窗口切片（段落边界优先断句）
        return split(clean);
    }

    /**
     * 清洗正文：移除 script/style 整段、剥离其余 HTML 标签、连续空白折叠为单空格
     *
     * @param raw 原始正文
     * @return 清洗后正文
     */
    private String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll(SCRIPT_BLOCK_PATTERN, " ")
                .replaceAll(TAG_PATTERN, " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 滑动窗口切片：优先在段落边界断句，切片数超上限截断
     *
     * @param text 清洗后正文
     * @return 切片列表
     */
    private List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        int size = Math.max(1, ragProperties.getChunkSize());
        int overlap = Math.min(ragProperties.getChunkOverlap(), size - 1);
        int step = size - overlap;
        int start = 0;
        while (start < text.length() && chunks.size() < ragProperties.getMaxChunksPerArticle()) {
            int end = Math.min(start + size, text.length());
            // 未到文本末尾且命中段落边界：回退断句点，避免句子拦腰截断
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
            // 步进 = size - overlap；边界断句时按实际 end 计算下一步起点防重叠越界
            start = end >= text.length() ? end : Math.max(end - overlap, start + step);
            if (end >= text.length()) {
                break;
            }
        }
        return chunks;
    }

    /**
     * 在 [start, end) 内从后往前找最近的段落边界字符（句子结束位置）
     *
     * @param text  正文
     * @param start 区间起点
     * @param end   区间终点（不含）
     * @return 边界字符位置（切点=该位置+1）；未找到返回 -1
     */
    private int lastSentenceEnd(String text, int start, int end) {
        for (int i = end - 1; i > start; i--) {
            if (SENTENCE_ENDINGS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }
}
