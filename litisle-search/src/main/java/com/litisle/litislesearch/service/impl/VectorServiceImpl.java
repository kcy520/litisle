package com.litisle.litislesearch.service.impl;

import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchEmbeddingConstant;
import com.litisle.litislesearch.embedding.EmbeddingProvider;
import com.litisle.litislesearch.service.VectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 向量化服务实现（批次七：Embedding 提供方 SPI 统一入口）。
 *
 * 【实现边界】批次C C1 定「两套向量统一同一单模型、维度 1024」，具体模型/提供方待选型确认；
 * 本类按配置（litisle.search.embedding.*）挑选已注册的 {@link EmbeddingProvider}：
 * - 开关关闭 / 提供方未接入 → 返回 null，索引链路跳过 vec_dense/chunk_vector（其余字段不受影响）；
 * - 提供方已接入但调用失败/维度不符 → 返回 null 并告警，不阻断索引写入；
 * - 维度校验必须通过，防止错误维度写入 dense_vector 导致整篇文档写入失败。
 *
 * 【批次七替换点】选型确认后新增提供方适配器（实现 EmbeddingProvider 并在 providerName 中登记），
 * 本类无需改动；随后执行 VectorRebuildService 全量重建两索引向量（模型语义漂移，架构文档 §八-8）。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorServiceImpl implements VectorService {

    /** search 配置（读取 litisle.search.embedding.* 开关、提供方、维度） */
    private final SearchProperties properties;

    /** Embedding 提供方集合（按配置精确匹配选择；无实现时集合为空，不阻塞启动） */
    private final ObjectProvider<EmbeddingProvider> embeddingProviders;

    /** 「提供方未就绪」告警只打一次，避免逐篇索引时日志刷屏 */
    private final AtomicBoolean unavailableWarned = new AtomicBoolean(false);

    /**
     * 判断向量化能力是否就绪。
     *
     * @return true=开关打开且配置的提供方已注册并可用
     */
    @Override
    public boolean available() {
        return properties.getEmbedding().isEnabled() && resolveProvider().isPresent();
    }

    /**
     * 文本转向量（失败一律返回 null，不阻断索引/检索主链路）。
     *
     * @param text 待向量化文本
     * @return 与配置维度一致的向量；未启用/未接入/空文本/调用失败返回 null
     */
    @Override
    public List<Float> embed(String text) {

        /*
         * 业务逻辑步骤：
         * 1. 空文本/开关关闭 → 直接返回 null（无需向量化，开关关闭属正常配置不告警）；
         * 2. 按配置解析提供方 → 未接入仅首次告警后返回 null（避免逐篇索引日志刷屏）；
         * 3. 调用提供方向量化 → 调用失败（抖动/超时）告警后返回 null，索引链路跳过向量字段；
         * 4. 维度校验 → 输出维度与配置不符时拒绝写入（error 留痕），防错误维度写坏 dense_vector；
         * 5. 校验通过返回向量；任何失败路径都是「返回 null」，绝不向调用方抛异常。
         */

        // 步骤1：文本为空无需向量化
        if (text == null || text.isBlank()) {
            return null;
        }
        // 开关关闭属正常配置，直接跳过（不告警）
        if (!properties.getEmbedding().isEnabled()) {
            return null;
        }
        // 步骤2：调用 resolveProvider 按配置精确挑选提供方；未接入时仅首次告警，避免逐篇索引日志刷屏
        Optional<EmbeddingProvider> provider = resolveProvider();
        if (provider.isEmpty()) {
            // compareAndSet 保证多线程并发时告警只打一次（首个到达的线程打，后续直接跳过）
            if (unavailableWarned.compareAndSet(false, true)) {
                log.warn("[向量化] embedding.enabled=true 但未找到可用提供方（provider={}），本次起跳过向量写入；"
                                + "选型后再实现 EmbeddingProvider 适配器",
                        properties.getEmbedding().getProvider());
            }
            return null;
        }
        try {
            // 步骤3：调用提供方把文本转向量（返回 null 属提供方约定语义，与调用失败同样按跳过处理）
            // 变量：模型输出的原始向量（尚未经过维度校验）
            List<Float> vector = provider.get().embed(text);
            if (vector == null) {
                return null;
            }
            // 变量：配置声明的期望维度（与索引 mapping 的 dense_vector dims 对齐）
            int expected = properties.getEmbedding().getDimension();
            // 步骤4：维度校验必须通过——错误维度写入 dense_vector 会让整篇文档写入失败
            if (vector.size() != expected) {
                log.error("[向量化] 提供方输出维度与配置不一致，拒绝写入：model={}, expected={}, actual={}",
                        provider.get().modelName(), expected, vector.size());
                return null;
            }
            // 步骤5：校验通过，返回可用于 ES 写入的向量
            return vector;
        } catch (Exception e) {
            // 模型服务抖动/超时：返回 null 由索引链路跳过向量字段，不影响文章其余字段写入
            log.warn("[向量化] 提供方调用失败，本次跳过向量写入：provider={}, model={}",
                    provider.get().providerName(), provider.get().modelName(), e);
            return null;
        }
    }

    /**
     * 按配置解析可用提供方：provider=none 或未配置时视为未接入。
     *
     * @return 匹配配置且本地配置完整的提供方；无则 empty
     */
    private Optional<EmbeddingProvider> resolveProvider() {
        String configured = properties.getEmbedding().getProvider();
        // 未配置或显式 none：视为未接入（不要求提供方实现存在）
        if (!StringUtils.hasText(configured)
                || SearchEmbeddingConstant.EMBEDDING_PROVIDER_NONE.equalsIgnoreCase(configured.trim())) {
            return Optional.empty();
        }
        // 按 providerName 精确匹配多条提供方实现，并过滤本地配置未就绪的实现
        return embeddingProviders.orderedStream()
                .filter(provider -> configured.trim().equalsIgnoreCase(provider.providerName()))
                .filter(EmbeddingProvider::available)
                .findFirst();
    }
}
