package com.litisle.litisleai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litisleai.domain.entity.AiPricing;
import org.apache.ibatis.annotations.Mapper;

/**
 * ai_pricing 模型单价配置 Mapper（MyBatis-Plus）
 *
 * <p>取价走 AiUsageRecordService（按 model_version + effective_from ≤ 归属日取最新一行，
 * 分页插件实现取 1 条），禁止拼 SQL 串。</p>
 *
 * @author litisle
 */
@Mapper
public interface AiPricingMapper extends BaseMapper<AiPricing> {
}
