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
 * <p>【通俗说明】把一篇长文章加工成适合 AI 检索的"小段资料"，分两步走：
 * 第一步清洗（clean）——网页正文里常混着 HTML 标签、脚本代码和乱七八糟的空格，
 * 先统统洗掉，只留干净的文字；第二步切片（split）——用"滑动窗口"按固定长度切
 * （默认 500 字一段），相邻两段之间留 50 字重叠，保证一段话的关键信息不会刚好
 * 卡在切口上丢失；下刀时优先找句号、问号、换行这些自然的段落边界，尽量不把一句话
 * 拦腰切断；文章太长时切到上限片数就停，防止索引体积和向量计算成本失控。</p>
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
     *
     * <p>【通俗说明】通过构造函数把 RAG 配置注入进来（切片大小、重叠字数、
     * 单篇切片上限都从这里读），本类自己不写死任何数值。</p>
     */
    public ContentChunker(AiRagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 清洗正文并切片
     *
     * @param rawContent 原始正文（可能含 HTML 标记）
     * @return 切片列表（每片 ≤ chunkSize 字符；空正文返回空列表）
     *
     * <p>【通俗说明】对外唯一入口，两步走：先 clean 把正文洗干净；洗完发现是空白
     * 就直接返回空列表（没内容不值得切），否则交给 split 按滑动窗口切片。</p>
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
     *
     * <p>【通俗说明】"洗数据"三连：先整段删掉 script/style 块（里面是代码和样式，
     * 不是正文，不删会污染向量）；再把剩下的 HTML 标签剥掉、只留标签夹着的文字；
     * 最后把连续空白压成一个空格并去掉首尾——得到一篇干净纯文本。</p>
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
     *
     * <p>【通俗说明】滑动窗口切法：从第 0 个字符开始，每次取 chunkSize 长的一段；
     * 如果这段没切到文章末尾，就回头找段内最后一个句子结束符，在那儿断句
     * （找不到才按原位置硬切）。每切完一段，起点往前挪"段长减重叠量"，这样
     * 相邻两段有重叠保上下文。切到文章末尾、或片数达到单篇上限就停。</p>
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
     *
     * <p>【通俗说明】在 [start, end) 范围内从右往左扫，找最近的句子结束字符
     * （。！？!?\n），返回它后面的位置作为断句点；一个都找不到就返回 -1，
     * 表示这段没有自然断句，只能按原位置切。</p>
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
