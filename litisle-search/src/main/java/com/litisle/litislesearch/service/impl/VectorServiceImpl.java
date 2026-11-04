package com.litisle.litislesearch.service.impl;

import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.service.VectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量化服务实现（批次二占位版本）。
 *
 * 【实现边界】批次C C1 只定了「两套向量统一同一单模型、维度 1024」，具体模型/提供方属批次七；
 * 本实现因此只做开关判断并返回 null，让索引链路在没有模型时也能正常写入其余字段
 * （ES 对缺省的 dense_vector 字段直接忽略，不会报错）。
 *
 * 【批次七替换点】接入模型 SDK 或 HTTP 提供方后，只需在本类 assemble 调用处补充真实调用，
 * 并移除「返回 null」分支；届时需按架构文档 §八-8 全量重建两索引（模型语义漂移）。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorServiceImpl implements VectorService {

    /** search 配置（读取 litisle.search.embedding.enabled 开关） */
    private final SearchProperties properties;

    /**
     * 文本转向量（当前无模型提供方，始终返回 null）。
     *
     * @param text 待向量化文本
     * @return 恒为 null（模型未接入）；调用方据此跳过向量字段
     */
    @Override
    public List<Float> embed(String text) {
        // 文本为空无需向量化
        if (text == null || text.isBlank()) {
            return null;
        }
        // 开关打开但提供方未接入：明确告警，避免运维误以为向量已在写入
        if (properties.getEmbedding().isEnabled()) {
            log.warn("[向量化] embedding.enabled=true 但模型提供方尚未接入（批次七落地），本次跳过向量写入");
        }
        // 未接入模型：返回 null，索引链路跳过 vec_dense/chunk_vector 字段
        return null;
    }
}
