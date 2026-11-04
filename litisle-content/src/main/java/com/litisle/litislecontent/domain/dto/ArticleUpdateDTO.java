package com.litisle.litislecontent.domain.dto;

import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章编辑入参（ArticleService#update，方案B分域）
 * <p>status=0/3（草稿/驳回）：全字段可改；status=2（已发布）：仅元数据可改——
 * title/contentHtml/images 属正文域，传入即按方案B拒绝（改正文须软删重发）</p>
 * <p>局部更新语义：字段为 null 表示不修改（业务层选择性 set，禁整体覆盖）</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class ArticleUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标题（≤100；status=2 时禁改） */
    @Size(max = 100, message = "标题长度不能超过 100 个字符")
    private String title;

    /** 摘要（≤500，元数据，任意状态可改） */
    @Size(max = 500, message = "摘要长度不能超过 500 个字符")
    private String summary;

    /** 正文HTML（status=2 时禁改，方案B） */
    private String contentHtml;

    /** 封面文件ID（元数据，任意状态可改） */
    @Size(max = 64, message = "封面文件ID长度不能超过 64 个字符")
    private String coverFileId;

    /** 分类（元数据，任意状态可改；null=不修改，枚举反序列化即白名单校验） */
    private ArticleCategoryEnum category;

    /** 分区（可空=不修改） */
    private ZoneEnum zone;

    /** 可见性（可空=不修改；转非公开必发 content.unpublished，隐私红线） */
    private VisibilityEnum visibility;

    /** 正文配图（status=2 时禁改；null=不修改，非 null=整体重挂） */
    private List<ArticleImageDTO> images;

    /** 标签名列表（任意状态可改——标签不影响 content_hash，方案B解耦；null=不修改） */
    private List<String> tagNames;

    /**
     * 定时发布时间（仅 status=0 草稿态可设置；null=不修改）
     * <p>非空=设置/覆盖定时；取消定时传 cancelScheduledPublish=true（与本字段互斥）；
     * 到点由 ArticleScheduledPublishJob 自动走批量送审链路发布</p>
     * <p>@Future 为声明性契约（Controller 未启 @Valid 不触发），实际校验收口 Service 侧</p>
     */
    @Future(message = "定时发布时间必须晚于当前时间")
    private LocalDateTime scheduledPublishTime;

    /**
     * 取消定时发布标志（仅 status=0 草稿态可操作；null/false=不修改）
     * <p>true=将 scheduled_publish_time 置 NULL 取消定时；与 scheduledPublishTime 互斥（同传拒绝）</p>
     */
    private Boolean cancelScheduledPublish;
}