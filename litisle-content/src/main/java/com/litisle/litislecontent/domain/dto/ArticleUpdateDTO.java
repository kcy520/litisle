package com.litisle.litislecontent.domain.dto;

import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
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

    /** 分类（元数据，任意状态可改） */
    @Size(max = 20, message = "分类长度不能超过 20 个字符")
    private String category;

    /** 分区（可空=不修改） */
    private ZoneEnum zone;

    /** 可见性（可空=不修改；转非公开必发 content.unpublished，隐私红线） */
    private VisibilityEnum visibility;

    /** 访问密码明文（visibility=4 时必填；BCrypt 加密落库） */
    @Size(max = 128, message = "访问密码长度不能超过 128 个字符")
    private String accessPassword;

    /** 正文配图（status=2 时禁改；null=不修改，非 null=整体重挂） */
    private List<ArticleImageDTO> images;

    /** 标签名列表（任意状态可改——标签不影响 content_hash，方案B解耦；null=不修改） */
    private List<String> tagNames;
}