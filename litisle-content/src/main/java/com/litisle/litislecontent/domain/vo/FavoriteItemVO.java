package com.litisle.litislecontent.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 我的收藏列表项出参 VO（阶段16）
 * <p>由 FavoriteMapper#selectMyFavorites 自定义 SQL 多态投影：
 * 文章取 title/cover_file_id/summary，书籍取 title/cover_file_id/intro（作为 summary），
 * author_id 两类目标均有</p>
 */
@Data
public class FavoriteItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 收藏目标类型
     * <p>tinyint 直出（1-文章 2-书籍）：注解 SQL 结果映射不走全局枚举 TypeHandler，
     * 为避免映射不生效，此字段保持 Integer，前端按 code 展示</p>
     */
    private Integer targetType;

    /**
     * 收藏目标ID
     * <p>target_type=1 时为文章ID，target_type=2 时为书籍ID</p>
     */
    private Long targetId;

    /**
     * 目标标题
     * <p>文章标题 / 书名</p>
     */
    private String title;

    /**
     * 封面文件ID
     * <p>SeaweedFS 文件ID，文章封面 / 书籍封面</p>
     */
    private String coverFileId;

    /**
     * 摘要
     * <p>文章取 article.summary；书籍取 book.intro</p>
     */
    private String summary;

    /**
     * 作者用户ID
     * <p>文章作者 / 书籍作者</p>
     */
    private Long authorId;

    /**
     * 收藏时间
     * <p>favorite.create_time，列表按此倒序</p>
     */
    private LocalDateTime favoriteTime;
}
