package com.litisle.litisleai.provider.rerank;

import java.util.List;

/**
 * 文本重排通道端口抽象（板块四 4.2 增强：宽召回 + 精排两段式检索的精排出口）
 *
 * <p>【职责边界】provider 是唯一接触重排模型 SDK/HTTP 的层（与向量通道 EmbeddingProvider
 * 同构）；检索链路只依赖本端口，不直连推理服务；换重排供应商只改实现类与 yml。</p>
 *
 * <p>【失败语义】调用失败 fail-fast 上抛（AI_PROVIDER_UNAVAILABLE），由调用方
 * RagSearchService 降级为向量原始排序，不中断检索会话。</p>
 *
 * <p>【通俗说明】
 * 把"粗筛候选"变成"精排结果"的标准通道。
 * 向量检索是把问题和文档各自编码后比距离（快但粗），重排是把问题和每条候选拼在一起送进 cross-encoder型逐条细算相关性（慢但准）。
 * 本接口只定义"问题 + 一批候选文档 → 每条的相关性
 * 分数"这一件事；具体用哪家模型（当前是本地 Infinity + bge-reranker-v2-m3）由实现类决定。</p>
 *
 * @author litisle
 */
public interface RerankProvider {

// Infinity 是一个开源的 本地 AI 推理服务器 （GitHub:`michaelfeil/infinity` ），专门用来部署 embedding 和 rerank 这两类小模型。
// ## 它解决什么问题
// bge-m3、bge-reranker 这类模型本质是 PyTorch 权重文件，Java 程序没法直接加载运行。
// 需要一个“服务进程”把模型常驻内存、对外暴露 HTTP API，你的 Spring Boot 才能通过网络调用它。Infinity 就是这个服务进程。

// 组件	            角色	       你的用法
// Ollama	         推理服务器	    跑 bge-m3，提供 /api/embed → 查询向量化
// Infinity	         推理服务器	    跑 bge-reranker-v2-m3，提供 /rerank → 检索精排
// Elasticsearch	 存储引擎	    存切片向量，提供 knn 检索


    /**
     * 对候选文档按与查询的相关性精排
     *
     * @param query     用户查询（相关性评判依据）
     * @param documents 候选文档列表（向量粗筛召回的切片正文，顺序即向量原始序）
     * @return 精排结果列表（index 对应 documents 下标，score 为相关性分数，含全部候选）
     * @throws com.litisle.commoncore.exception.BizException 输入为空或通道调用失败（80005）
     *
     * <p>【通俗说明】问题进、候选文档进，每条候选出一个"相关性分数"出。
     * 分数越高说明该候选越能回答这个问题；排序/截断/过滤由调用方执行。</p>
     */
    List<RerankResult> rerank(String query, List<String> documents);

    /**
     * 重排结果载体（候选下标 + 相关性分数）
     *
     * @param index documents 下标（对应粗筛候选位置）
     * @param score 相关性分数（cross-encoder sigmoid 分数量纲，与余弦相似度不同）
     *
     * <p>【通俗说明】一条精排结论：第 index 号候选文档与问题的相关程度是 score。</p>
     */
    record RerankResult(int index, double score) {
    }
}
