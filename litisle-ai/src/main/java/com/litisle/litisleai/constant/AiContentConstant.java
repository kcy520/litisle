package com.litisle.litisleai.constant;

/**
 * AI 侧内容口径常量（文章状态 / 可见性的唯一字面量来源）
 *
 * <p>【职责边界】content 模块的状态与可见性枚举只存在于 content 实现内，跨服务契约
 * ContentDTO 只透出 Integer code；AI 侧的摄入门控（RagIngestService）与引用可见性回查
 * （RagSearchService）共用本类常量，禁止各自再写数字字面量。</p>
 *
 * <p>【维护口径】取值必须与 content 模块枚举 code 保持一致，变更需随契约评审同步；
 * 业务类型 bizType 复用 litisle-api 的 {@code ModerationConstant.BIZ_TYPE_ARTICLE}。</p>
 *
 * @author litisle
 */
public final class AiContentConstant {

    private AiContentConstant() {
    }

    /** 文章状态：2-已发布（摄入与引用回查的唯一通过值） */
    public static final int STATUS_PUBLISHED = 2;

    /** 可见性：1-公开（未公开内容不出站，与 D2/V3 出线红线同口径） */
    public static final int VISIBILITY_PUBLIC = 1;

    /** 收藏目标类型：1-文章（板块九 1.2 结果动作工具用；取值与 content FavoriteTargetTypeEnum 对齐） */
    public static final int FAVORITE_TARGET_ARTICLE = 1;

    /** 创作辅助场景：读者预设问题（板块十二 4.3，chat 入口 scene 路由，必须关联文章） */
    public static final String SCENE_READER_QUESTIONS = "reader-questions";

    /** 创作辅助场景：选题灵感（板块十二 4.4，chat 入口 scene 路由，热榜上下文工具） */
    public static final String SCENE_TOPIC_INSPIRATION = "topic-inspiration";

    // ==================== 写作辅助扩展（板块十五） ====================

    /** 改写目标粒度：标题（承载板块十二 4.7 标题/开头多版建议，articleId 入口做归属校验——AI-V6 ⑤/⑦ 口径） */
    public static final String TARGET_TYPE_TITLE = "TITLE";

    /** 改写目标粒度：段落 */
    public static final String TARGET_TYPE_PARAGRAPH = "PARAGRAPH";

    /** 改写目标粒度：句子 */
    public static final String TARGET_TYPE_SENTENCE = "SENTENCE";

    /** 改写目标粒度：词语 */
    public static final String TARGET_TYPE_WORD = "WORD";

    /** 改写目标粒度白名单（15.1 入参校验唯一来源，注册表外粒度拒绝） */
    public static final java.util.Set<String> TARGET_TYPES = java.util.Set.of(
            TARGET_TYPE_TITLE, TARGET_TYPE_PARAGRAPH, TARGET_TYPE_SENTENCE, TARGET_TYPE_WORD);

    /** 写作辅助计量场景前缀（scene=rewrite-{粒度小写}，区分改写粒度；purpose=POLISH 写作辅助桶） */
    public static final String SCENE_REWRITE_PREFIX = "rewrite-";

    /** 写作辅助计量场景：文章草稿生成（板块十五 15.2，purpose=POLISH 桶） */
    public static final String SCENE_DRAFT = "draft";

    /** 一次性请求记忆键前缀（memoryId = tmp:{requestId}，Redis ChatMemory TTL 内自然过期，不落会话库） */
    public static final String TMP_MEMORY_ID_PREFIX = "tmp:";

    /** 会话 USER 消息任务占位文案模板（无用户输入的摘要/标签/结构分析落库占位：%s=用途描述，%s=文章 ID） */
    public static final String SESSION_TASK_MESSAGE_FORMAT = "%s任务（文章 %s）";

    /**
     * 改写目标粒度中文名（prompt 组装用，让模型按中文粒度理解任务）
     *
     * @param targetType 目标粒度（白名单值；注册表外原样返回）
     * @return 粒度中文名（标题/段落/句子/词语）
     */
    public static String targetTypeLabel(String targetType) {
        return switch (targetType) {
            case TARGET_TYPE_TITLE -> "标题";
            case TARGET_TYPE_PARAGRAPH -> "段落";
            case TARGET_TYPE_SENTENCE -> "句子";
            case TARGET_TYPE_WORD -> "词语";
            default -> targetType;
        };
    }

    /**
     * 改写目标粒度对应的计量场景（scene 区分粒度口径：rewrite-title / rewrite-paragraph 等）
     *
     * @param targetType 目标粒度（白名单值）
     * @return 计量场景标识
     */
    public static String rewriteScene(String targetType) {
        return SCENE_REWRITE_PREFIX + targetType.toLowerCase();
    }

    /**
     * 拼接一次性请求的记忆键（memoryId 口径：tmp:{requestId}，一次性上下文不落会话库）
     *
     * @param requestId 请求幂等键
     * @return 一次性记忆键
     */
    public static String tmpMemoryId(String requestId) {
        return TMP_MEMORY_ID_PREFIX + requestId;
    }
}
