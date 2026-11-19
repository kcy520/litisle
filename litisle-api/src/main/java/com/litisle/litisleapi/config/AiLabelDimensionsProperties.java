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
 * <p>【默认词表（2026-11-19 调整：style 限定文学风格、structure 升级为 genre 文学体裁）】
 * style 十六值受控（平实晓畅/严谨专业/幽默诙谐/冷峻犀利/温暖治愈/抒情诗意/华丽典雅/简洁凝练/
 * 口语亲切/学术书面/清新自然/婉约细腻/豪放激昂/沉郁深刻/讽刺辛辣/古朴雅致——仅描述文笔
 * 与语言风格）/ depth 三值 / genre 文学体裁十四值（记叙文/议论文/说明文/散文/诗歌/小说/
 * 书评影评/实用文/游记/书信日记/传记纪实/寓言童话/杂文时评/剧本，文学方向为主，
 * 原 structure 组织形态维度移除）/ topic 语义自由；range 绑 word_count / publish_time；
 * param 含 topK / sort。mood / stance / audience 留位观察不做（随词表扩展直接加配置项）。
 * 词表/维度变更后需执行存量补标（/api/search/admin/relabel-ai-labels，整体覆盖 ai_labels），
 * 旧标签值与旧维度字段（ai_labels.structure）不可命中新词表。</p>
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
        dims.put("style", dimension("filter",
                List.of("平实晓畅", "严谨专业", "幽默诙谐", "冷峻犀利", "温暖治愈",
                        "抒情诗意", "华丽典雅", "简洁凝练", "口语亲切", "学术书面",
                        "清新自然", "婉约细腻", "豪放激昂", "沉郁深刻", "讽刺辛辣", "古朴雅致"),
                null, "文章文学风格（仅描述文笔与语言风格，不含题材、领域与体裁；"
                        + "体裁归 genre 维度，如「故事叙事」「议论文」类表达优先映射 genre。"
                        + "风格互斥原则：一篇文章只取最主导的单一风格，兼顾两种时取更显著者）"));
        dims.put("depth", dimension("filter", List.of("入门", "进阶", "深度"),
                null, "内容深度定位（入门=零基础可读、不依赖前置知识；进阶=需要基础概念或一定实践经验；"
                        + "深度=系统原理或前沿探讨，面向资深读者）"));
        dims.put("genre", dimension("filter",
                List.of("记叙文", "议论文", "说明文", "散文", "诗歌", "小说", "书评影评", "实用文",
                        "游记", "书信日记", "传记纪实", "寓言童话", "杂文时评", "剧本"),
                null, "文章文学体裁（记叙文=以事件或经历叙述为主线；议论文=以观点论证为主线；"
                        + "说明文=以知识讲解或科普为主线；散文=抒情随笔、形散神聚；诗歌=分行或韵律化表达；"
                        + "小说=虚构人物情节叙事；书评影评=针对作品的分析评论；实用文=教程/清单/攻略类；"
                        + "游记=以旅途见闻与风物描写为主线；书信日记=日记体/书信体第一人称记录；"
                        + "传记纪实=真实人物或事件的记述；寓言童话=虚构短篇寄寓道理、语言童趣；"
                        + "杂文时评=针对时事或现象的短评杂感；剧本=以台词与舞台指示为主的戏剧文本。"
                        + "体裁互斥原则：一篇文章只取最主导的单一体裁，混合形态取篇幅占比最大者）"));
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
