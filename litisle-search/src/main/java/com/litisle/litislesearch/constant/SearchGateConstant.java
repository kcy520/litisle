package com.litisle.litislesearch.constant;

/**
 * search 模块「入索引门控值」常量：与 content/common 模块枚举 code 对齐的判值收口。
 *
 * 说明：这些 code 值是索引写入/删除的闸门判断依据（写入侧闸门 + 查询侧 filter 双保险），
 * 只收口不适合外置的「枚举对齐值」，避免业务代码魔法数字硬编码
 */
public final class SearchGateConstant {

    /** 工具类禁止实例化 */
    private SearchGateConstant() {
    }

    /**
     * 文章已发布状态 code（content ArticleStatusEnum.PUBLISHED=2）：
     * 乱序门控条件之一——回查后 status 必须为 2（已审核通过或免审直落），否则丢弃不写，
     * 防 unpublished 先到、published 迟到把已删内容写回索引
     */
    public static final Integer ARTICLE_STATUS_PUBLISHED = 2;

    /**
     * 文章公开可见性 code（content VisibilityEnum.PUBLIC=1）：
     * 乱序门控条件之二——仅 PUBLIC 文章入索引（写入侧闸门，查询侧另有 filter 双保险）
     */
    public static final Integer ARTICLE_VISIBILITY_PUBLIC = 1;

    /**
     * 入索引可见性值（写入侧闸门 + 查询侧 filter 双保险，架构文档 §四）：
     * 仅 PUBLIC 文章/书籍/用户文档入索引与向量库，私有/草稿/拒绝永不入索引（与 review 可见性红线同源）
     */
    public static final String VISIBILITY_PUBLIC = "PUBLIC";

    /**
     * 书籍下架状态 code（content BookStatusEnum.TAKEN_DOWN=2）：
     * 下架=对外终止态，回查后命中即删除 book_idx 文档（与软删删除同一收敛路径）
     */
    public static final Integer BOOK_STATUS_TAKEN_DOWN = 2;

    /**
     * 用户正常状态 code（common UserStatus.NORMAL=1）：
     * 仅正常状态用户入 user_idx（禁用/注销用户删除索引文档）
     */
    public static final Integer USER_STATUS_NORMAL = 1;

    /**
     * 未删除标志 code（common DelFlag.NOT_DELETED=0）：
     * 已删除的书籍/用户一律不入索引（回查命中已删除即删索引文档）
     */
    public static final Integer DEL_FLAG_NOT_DELETED = 0;
}
