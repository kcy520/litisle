package com.litisle.litisleapi.vo.search;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户索引命中 VO（板块八 1.2：作者名 → 作者 ID 解析的跨服务契约，普通 Feign 查询非 LLM）
 *
 * <p>【口径】按笔名模糊命中 user_idx 公开资料（笔名/ID 最小集），ai 侧取首个精确或最高相关
 * 命中完成解析；解析失败调用方自行丢弃该条件（不阻断检索）。</p>
 *
 * @author litisle
 */
@Data
public class UserSearchVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long userId;

    /** 笔名（user_idx 公开资料当前值） */
    private String penName;
}
