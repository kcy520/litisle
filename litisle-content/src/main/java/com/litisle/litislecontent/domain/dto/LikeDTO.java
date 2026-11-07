package com.litisle.litislecontent.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 点赞/取消点赞请求 DTO（批次十一 11.8）。
 *
 * <p>targetType 用 Integer 接参，Service 统一白名单解析（与 FavoriteDTO 同风格），
 * 避免枚举反序列化歧义。取消点赞（DELETE）走查询参数，不复用本 DTO 的 body 绑定。</p>
 *
 * @author litisle
 */
@Data
public class LikeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 点赞目标类型
     * <p>1-文章 2-书籍（口径同 FavoriteTargetTypeEnum）；具体值合法性由 Service 白名单校验</p>
     */
    @NotNull(message = "点赞目标类型不能为空")
    private Integer targetType;

    /**
     * 点赞目标ID
     * <p>targetType=1 时为文章ID，targetType=2 时为书籍ID</p>
     */
    @NotNull(message = "点赞目标ID不能为空")
    @Positive(message = "点赞目标ID必须为正数")
    private Long targetId;
}
