package com.litisle.litisleapi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ai_labels 维度注册表配置（板块八 / search 板块二 2.1：打标与提取两端 prompt 动态生成的唯一数据源）
 *
 * <p>【定位】条件化相似推荐的维度字典：每个维度声明 match 类型（filter 词表 / range 数值区间 /
 * param 请求参数 / semantic 自由语义），ai 侧打标与条件提取的 prompt 按本表动态拼装，search 侧
 * 条件版查询按 match 类型路由 ES 查询构造——**新增维度=改本配置（Nacos 下发）+ 全量重建补标，
 * 两端零代码**。</p>
 *
 * <p>【注册方式】本类位于 litisle-api 契约包，litisle-ai 与 litisle-search 各自经
 * @EnableConfigurationProperties 显式注册（服务启动类不扫描 api 包）。</p>
 *
 * <p>【默认词表（2026-11-16 P0 拍板定稿）】style 六值受控 / depth 三值 / structure 四值 /
 * topic 语义自由；range 绑 word_count / publish_time；param 含 topK / sort。mood / stance /
 * audience 留位观察不做（随词表扩展直接加配置项）。</p>
 *
 * <p>【配置】前缀 litisle.search.ai-labels（两服务读同一配置命名空间，保证打标与查询口径同源）。</p>
 *
 * @author litisle
 */
@Data
@ConfigurationProperties(prefix = "litisle.search.ai-labels")
public class AiLabelDimensionsProperties {

    /** 注册表总开关（false 时打标端点与条件查询的 ai_labels 路由整体停用） */
    private boolean enabled = true;

    /** 维度注册表（key=维度名，即 ai_labels 下的对象字段名 / 提取输出 JSON 字段名） */
    private Map<String, Dimension> dimensions = defaultDimensions();

    /**
     * 单个维度定义
     */
    @Data
    public static class Dimension {

        /**
         * match 类型（四类，与 ES 查询构造一一对应）：
         * filter=词表精确过滤（term，默认写 ai_labels.{维度名}）；range=数值/时间区间（range）；
         * param=请求参数映射（topK/sort，不入 ES 字段）；semantic=自由语义（并入检索文本 knn）
         */
        private String match;

        /**
         * 受控词表（filter/param 维度可用）：打标与提取两端 prompt 的取值约束与 service 侧越表校验依据；
         * 空=不受控（如 category/tags 取既有字段值，topic 自由语义）
         */
        private List<String> values;

        /**
         * 绑定的既有 ES 字段（可空）：filter/range 维度缺省写 ai_labels.{维度名}；
         * 声明本字段时改写既有字段（category→category、tags→tags、word_count→word_count、
         * publish_time→updateTime）
         */
        private String esField;

        /** 维度说明（打标与提取 prompt 的人类可读描述，如「文章整体写作风格」） */
        private String prompt;
    }

    /**
     * P0 拍板词表默认值（配置覆盖即可扩展，无需改代码）
     */
    private static Map<String, Dimension> defaultDimensions() {
        Map<String, Dimension> dims = new LinkedHashMap<>();
        dims.put("style", dimension("filter", List.of("轻松易懂", "严谨专业", "故事叙事", "实战干货", "观点评论", "幽默趣味"),
                null, "文章整体写作风格"));
        dims.put("depth", dimension("filter", List.of("入门", "进阶", "深度"),
                null, "内容深度定位"));
        dims.put("structure", dimension("filter", List.of("叙事", "论述", "教程", "清单"),
                null, "文章组织结构形态"));
        dims.put("topic", dimension("semantic", null,
                null, "文章核心主题（自由语义短语，如「大模型微调实战」）"));
        dims.put("category", dimension("filter", null,
                "category", "文章分类名（站内既有分类）"));
        dims.put("tags", dimension("filter", null,
                "tags", "文章主题标签（站内标签池写法）"));
        dims.put("word_count", dimension("range", null,
                "word_count", "正文字数区间"));
        dims.put("publish_time", dimension("range", null,
                "updateTime", "发布时间区间"));
        dims.put("topK", dimension("param", null,
                null, "返回条数上限"));
        dims.put("sort", dimension("param", List.of("related", "newest", "heat", "similarity-first"),
                null, "排序意图（related=按相关性融合 / newest=最新发布 / heat=按热度 / similarity-first=纯向量相似优先）"));
        return dims;
    }

    /**
     * 构造维度定义（装配便捷方法）
     *
     * @param match   match 类型（filter/range/param/semantic）
     * @param values  受控词表（可空）
     * @param esField 绑定的既有 ES 字段（可空）
     * @param prompt  prompt 描述
     * @return 维度定义
     */
    private static Dimension dimension(String match, List<String> values, String esField, String prompt) {
        Dimension d = new Dimension();
        d.setMatch(match);
        d.setValues(values == null ? new ArrayList<>() : values);
        d.setEsField(esField);
        d.setPrompt(prompt);
        return d;
    }
}
