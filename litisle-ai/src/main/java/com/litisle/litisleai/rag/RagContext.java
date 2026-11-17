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
 * <p>【通俗说明】可以理解为"本次请求的记事本"：AI 聊天的过程中，知识库检索工具每命中
 * 一段参考资料，就往这个记事本上记一条（record）；等最终回答组织完，再把记事本内容
 * 一次性取出来（drain），作为"引用来源"卡片随回答一起返给前端，让用户知道 AI 的话
 * 是从哪些文章里查来的。为什么用 ThreadLocal：每个请求各记各的账本、互不串线；
 * 为什么用完必须清：服务器线程是复用的，不清的话下一个请求会读到上一个请求的引用。</p>
 *
 * @author litisle
 */
public final class RagContext {

    /** 线程内上下文持有器 */
    private static final ThreadLocal<List<Citation>> HOLDER = new ThreadLocal<>();

    /**
     * 引用卡片载体（响应 citations 字段元素）
     *
     * <p>【通俗说明】一条引用卡片上写的信息：来自哪篇文章（articleId）、文章标题、
     * 内容指纹 contentHash（前端可拿它核对原文有没有改版），以及 removed——
     * 检索命中后原文却被删除/下架时置为 true，提示这条引用已失效。</p>
     */
    public record Citation(Long articleId, String title, String contentHash, boolean removed) {
    }

    /**
     * 清空并初始化当前线程上下文（请求入口调用）
     *
     * <p>【通俗说明】请求一进来先"发一本新记账本"：把当前线程的引用列表重置为空，
     * 保证本次请求的引用从零开始记，不会掺进上一次请求的残留数据。</p>
     */
    public static void begin() {
        HOLDER.set(new ArrayList<>());
    }

    /**
     * 记录一条检索命中引用（工具执行线程调用；无上下文时静默忽略——兜底路径）
     *
     * @param citation 引用卡片
     *
     * <p>【通俗说明】检索工具每命中一条参考资料，就往记账本上添一笔。
     * 如果当前线程还没发过记账本（没调过 begin），就静默跳过不记——这是兜底逻辑，
     * 防止未初始化时直接报错炸掉检索流程。</p>
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
     *
     * <p>【通俗说明】"结账"：取出本次请求记下的全部引用，同时把记账本清空
     * （防线程复用串号）。没初始化过就返回空列表，不报错。</p>
     */
    public static List<Citation> drain() {
        List<Citation> citations = HOLDER.get();
        HOLDER.remove();
        return citations == null ? List.of() : citations;
    }
}
