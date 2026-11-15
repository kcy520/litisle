package com.litisle.litisleai.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求级 RAG 引用上下文（板块四 4.2：检索命中与引用卡片的请求内载体）
 *
 * <p>【口径】知识库检索工具执行时把命中引用写入本上下文；chat 编排在终答组装时
 * drain 出引用列表塞进响应 citations 字段（引用卡片回传）；
 * ThreadLocal 承载（Agent 工具执行与编排同线程），finally 必须清理防线程池串号。</p>
 *
 * @author litisle
 */
public final class RagContext {

    /** 线程内上下文持有器 */
    private static final ThreadLocal<List<Citation>> HOLDER = new ThreadLocal<>();

    /**
     * 引用卡片载体（响应 citations 字段元素）
     */
    public record Citation(Long articleId, String title, String contentHash, boolean removed) {
    }

    /**
     * 清空并初始化当前线程上下文（请求入口调用）
     */
    public static void begin() {
        HOLDER.set(new ArrayList<>());
    }

    /**
     * 记录一条检索命中引用（工具执行线程调用；无上下文时静默忽略——兜底路径）
     *
     * @param citation 引用卡片
     */
    public static void record(Citation citation) {
        List<Citation> citations = HOLDER.get();
        if (citations != null) {
            citations.add(citation);
        }
    }

    /**
     * 取出并清空当前线程引用列表（终答组装时调用）
     *
     * @return 引用列表（无上下文返回空列表）
     */
    public static List<Citation> drain() {
        List<Citation> citations = HOLDER.get();
        HOLDER.remove();
        return citations == null ? List.of() : citations;
    }
}
