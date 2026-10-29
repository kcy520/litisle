package com.litisle.litislecontent.domain.vo;

import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章详情 VO（ArticleService#getDetail，visibility 判定链收口后的出参）
 * <p>可见性口径（阶段9：密码访问 4 已下线）：</p>
 * <p>- 2-仅自己：非本人按不存在处理（不暴露存在性），不会拿到本 VO</p>
 * <p>- 3-仅关注者：非关注者同理；SocialClient 降级时宁严勿松</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class ArticleDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文章ID */
    private Long id;

    /** 作者用户ID */
    private Long authorId;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 正文HTML（密码访问未通过校验时为 null；渲染前替换配图占位符） */
    private String contentHtml;

    /** 封面文件ID */
    private String coverFileId;

    /** 分类（@JsonValue 序列化输出中文分类名，前端无感） */
    private ArticleCategoryEnum category;

    /** 分区 */
    private ZoneEnum zone;

    /** 状态（作者本人可见流转态；游客侧恒为 PUBLISHED） */
    private ArticleStatusEnum status;

    /** 可见性 */
    private VisibilityEnum visibility;

    /** 浏览量 */
    private Long viewCnt;

    /** 点赞数 */
    private Long likeCnt;

    /** 评论数 */
    private Long commentCnt;

    /** 标签名列表 */
    private List<String> tagNames;

    /** 正文配图列表 */
    private List<ArticleImageVO> images;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最后更新时间 */
    private LocalDateTime updateTime;

    /**
     * 当前查看者是否已收藏该文章
     * <p>true-已收藏 false-未收藏；匿名查看恒为 false；由 getDetail 末尾回填（详情单条查询无 N+1）</p>
     */
    private Boolean favorited;
}