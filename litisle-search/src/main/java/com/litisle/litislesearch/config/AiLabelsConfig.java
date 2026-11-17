package com.litisle.litislesearch.config;

import com.litisle.litisleapi.config.AiLabelDimensionsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * ai_labels 维度注册表装配（板块八 / search 板块二 2.1）
 *
 * <p>【定位】维度注册表配置类位于 litisle-api 契约包（打标/提取/查询三端共读同一配置命名空间，
 * 保证口径同源），服务启动类不扫描 api 包，故经本配置类显式注册绑定
 * litisle.search.ai-labels 前缀。</p>
 *
 * <p>【新增维度口径】改注册表配置（Nacos 下发）+ 全量重建补标，打标与条件查询两端零代码。</p>
 *
 * @author litisle
 */
@Configuration
@EnableConfigurationProperties(AiLabelDimensionsProperties.class)
public class AiLabelsConfig {
}
