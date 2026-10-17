package com.litisle.litisleapi.dfa;


import java.util.List;

/**
 * DFA 词库场景归一映射（review_task.scene → 词库 scene，§4.4.4 定稿口径）
 *
 * <p>SSOT 说明：scene 的枚举载体是 litisle-review 的 WordSceneEnum（DictEnum 三值绑定），
 * 契约库不可反向依赖，本类只认 String code——改名须同步（含 WordSceneEnum 与本类）</p>
 *
 * <p>映射规则（§4.4.4）：review_task.scene 去 _RE_REVIEW 后缀归一——
 * ARTICLE_FEATURED/ARTICLE_COMMUNITY/ARTICLE_RE_REVIEW → ARTICLE；
 * COMMENT/COMMENT_RE_REVIEW → COMMENT；USERNAME/MESSAGE/MESSAGE_RE_REVIEW 同名直映；
 * IMAGE/ARTICLE_IMG_RE_REVIEW 为图片场景不走 DFA，返回 null（加载侧跳过不报错）</p>
 */
public final class DfaScenes {

    //article 文章
    public static final String ARTICLE = "ARTICLE";
    //comment 评论
    public static final String COMMENT = "COMMENT";
    //username 用户名
    public static final String USERNAME = "USERNAME";
    //message 消息
    public static final String MESSAGE = "MESSAGE";
    //bio 简介类短文本（bio/创作方向/代表作 共用一套词库；宿主直审场景不经 review_task，无需 normalize 映射）
    public static final String BIO = "BIO";

    private static final String RE_REVIEW_SUFFIX = "_RE_REVIEW";

    private DfaScenes() {
    }

    /**
     * 归一映射：返回该 review 场景对应的词库 scene；null 表示非文本场景（不走 DFA）
     *
     * 实际业务里，同一个内容类型在 review 系统里有好几个名字：
     * 但它们共用同一份词库，DFA 引擎只按 ARTICLE / COMMENT / USERNAME / MESSAGE 这四个 key 存引擎。
     */
    public static String normalize(String scene) {
        // Step1 空入参直接判非文本场景（调用方跳过 DFA）
        if (scene == null || scene.isBlank()) {
            return null;
        }
        // Step2 去 _RE_REVIEW 复审后缀（复审与首审共用同一份词库）
        String s = scene.endsWith(RE_REVIEW_SUFFIX)
                ? scene.substring(0, scene.length() - RE_REVIEW_SUFFIX.length())
                : scene;
        // Step3 场景归一：文章族三态收敛 ARTICLE；其余按映射表直映；未命中返回 null（图片场景不走 DFA）
        return switch (s) {
            case "ARTICLE", "ARTICLE_FEATURED", "ARTICLE_COMMUNITY" -> ARTICLE;
            case "COMMENT" -> COMMENT;
            case USERNAME, MESSAGE -> s;
            default -> null;
        };
    }

    /**
     * 全部文本词库场景（WordLibraryLoader 逐场景加载 / DfaFacade.initAll 遍历用）
     */
    public static List<String> textScenes() {
        // 固定五场景全集（加载侧逐场景遍历构建引擎的依据；新增场景须同步 review 侧 WordSceneEnum）
        return List.of(ARTICLE, COMMENT, USERNAME, MESSAGE, BIO);
    }
}