package com.litisle.litisleapi.dto.content;


import lombok.Data;

import java.io.Serializable;

/**
 * 发布前预检响应 VO（板块四 4-5.2 / Review-Plan §4.2.1 ②）
 *
 * 【字段纪律】仅 {hit, risk} 两个字段——禁止添加 hitWords/命中词/命中位置等任何
 * 可反推词库内容的字段（命中词表是审核资产，回吐=向攻击者开放词库探测接口，§2.12）；
 * 前端展示文案（如「包含违规风险词，请修改」）由前端按 risk 写死，不从接口取词。
 *
 * 【取值口径】
 * - hit=false：未命中，risk=null（放行提示，发布仍走完整闸门）；
 * - hit=true：risk=1-HIGH（ModerationConstant.DFA_LEVEL_HIGH，发布将被 70001 拦截，
 *   前端提示「包含严重违规内容，请修改」）/ risk=2-MID（DFA_LEVEL_MID，发布放行但
 *   送异步机审，前端提示「内容存在风险，可能被驳回」）。
 *
 * @author litisle
 */
@Data
public class ContentPreviewCheckVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否命中敏感词 */
    private Boolean hit;

    /**
     * 命中风险级别（仅 hit=true 时非空）：1-HIGH 2-MID，
     * 口径同 ModerationConstant.DFA_LEVEL_*；禁回吐命中词
     */
    private Integer risk;
}
