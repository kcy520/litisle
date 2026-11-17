package com.litisle.litisleai.provider.embedding;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleai.config.AiRagProperties;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.embedding.Embedding;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Ollama 本地向量通道（板块四 4.1：P3 定档 Ollama + bge-m3，1024 维，零外发零成本）
 *
 * <p>【职责边界】provider 层唯一接触 Ollama SDK 的实现：连接参数全部来自
 * {@link AiRagProperties} 外置配置；rag.enabled=false 时本 Bean 不装配（通道缺席，
 * 调用方 fail-fast 或降级，与文本通道 enabled 口径一致）。</p>
 *
 * <p>【日志纪律】只记文本字符数与耗时，不打印正文全文。</p>
 *
 * <p>【失败语义】调用失败包装为 80005 上抛：摄入链路转消费重试、检索链路转降级无引用回答。</p>
 *
 * <p>【通俗说明】向量化通道的"真身"：连的是本机 Ollama 服务上跑的 bge-m3 模型（1024 维），
 * 数据不出服务器、不花钱。它把 EmbeddingProvider 接口的"文字进、向量出"落到实处：
 * 构造时按 yml 配置拼好连接地址、模型名、超时时间（代码里不写死）；调用时先拒绝空文本
 * （没有意义的输入不值得跑模型），拿到向量后还要核对维度是不是配置说的 1024 维——
 * 维度对不上说明模型配置错了，宁可报错也不能把"畸形坐标"写进检索库。</p>
 *
 * @author litisle
 */
@Slf4j
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    /** RAG 配置（连接参数与维度校验来源） */
    private final AiRagProperties ragProperties;

    /** LangChain4j Ollama 向量模型（构造时按配置装配） */
    private final EmbeddingModel embeddingModel;

    /**
     * 构造装配：连接参数全部取自外置配置，代码内不硬编码端点
     *
     * @param ragProperties RAG 配置
     *
     * <p>【通俗说明】构造时就把 Ollama 客户端按配置装配好（地址、模型、超时全来自 yml），
     * 后续每次调用直接复用这个客户端，不用重复建连接。</p>
     */
    public OllamaEmbeddingProvider(AiRagProperties ragProperties) {
        this.ragProperties = ragProperties;
        this.embeddingModel = OllamaEmbeddingModel.builder()
                .baseUrl(ragProperties.getOllamaBaseUrl())
                .modelName(ragProperties.getOllamaModel())
                .timeout(ragProperties.getEmbedTimeout())
                .build();
    }

    /**
     * 生成文本向量（维度校验兜底：与配置维度不符视为异常，防脏向量写入索引）
     *
     * @param text 待向量化的文本
     * @return 向量
     * @throws BizException 空文本、通道调用失败或维度不符（80005）
     *
     * <p>【通俗说明】四步：① 空文本直接拒（fail-fast 防脏数据）；② 调本地 Ollama
     * 模型算向量；③ 把 SDK 返回的 float 数组转成 List 并核对维度是否与配置一致
     * （不符即报错，防脏向量入库）；④ 记一条只含字数和耗时的日志后返回向量。
     * 任何异常都包装成 80005 上抛，由调用方决定重试还是降级。</p>
     */
    @Override
    public List<Float> embed(String text) {
        // 空文本无向量化意义：fail-fast 防脏数据
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "向量化文本为空");
        }
        long start = System.currentTimeMillis();
        try {
            Response<Embedding> response = embeddingModel.embed(text);
            // SDK 返回 float[]（Arrays.stream 不支持 float 装箱，手动循环转 List<Float>）；维度校验兜底防脏向量写入索引
            float[] raw = response.content().vector();
            List<Float> vector = null;
            if (raw != null) {
                vector = new java.util.ArrayList<>(raw.length);
                for (float v : raw) {
                    vector.add(v);
                }
            }
            if (vector == null || vector.size() != ragProperties.getVectorDimension()) {
                throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                        "向量维度与配置不符：actual=" + (vector == null ? 0 : vector.size())
                                + ", expected=" + ragProperties.getVectorDimension());
            }
            // 日志纪律：只记字符数与耗时
            log.info("Ollama 向量化完成 textChars={} costMs={}", text.length(), System.currentTimeMillis() - start);
            return vector;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // SDK/网络异常 fail-fast：摄入转消费重试、检索转降级
            log.warn("Ollama 向量化失败 textChars={} costMs={}", text.length(), System.currentTimeMillis() - start, e);
            throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "向量通道调用失败", e);
        }
    }
}
