package com.litisle.litislecontent.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 收藏/取消收藏请求 DTO（阶段16）。
 * <p>targetType 用 Integer 接参，Service 统一 resolve 白名单解析，避免枚举反序列化歧义</p>
 */
@Data
public class FavoriteDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 收藏目标类型
     * <p>1-文章 2-书籍（FavoriteTargetTypeEnum）；具体值合法性由 Service 白名单校验</p>
     */
    @NotNull(message = "收藏目标类型不能为空")
    private Integer targetType;

    /**
     * 收藏目标ID
     * <p>targetType=1 时为文章ID，targetType=2 时为书籍ID</p>
     */
    @NotNull(message = "收藏目标ID不能为空")
    @Positive(message = "收藏目标ID必须为正数")
    private Long targetId;
}
