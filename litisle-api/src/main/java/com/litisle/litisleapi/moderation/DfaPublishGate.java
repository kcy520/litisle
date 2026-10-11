package com.litisle.litisleapi.moderation;


import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dfa.DfaHitResult;

/**
 * DFA 同步闸门统一决策器（板块四 4-2 / Review-Plan §4.2.1 ①）——口径实现（契约库 SSOT，发布入口统一复用）。
 *
 * 判断“DFA 扫完这段文本后，到底拦不拦？拦的话怎么拦？不拦的话要不要标记送异步复审？”**
 *
 * 【落位】文章/评论发布（litisle-content）、私信发送（litisle-social）、用户名注册（auth）
 * 各发布入口内、{@link PublishTextPrecheck} 空内容校验之后、异步送审投递之前调用；
 * DfaFacade 为宿主服务本地 Bean（本地执行零网络），本类只做「命中结果 → 拦截/标记」的口径收敛。
 *
 * 【决策口径】（与 DfaHitResult 消费纪律一致，SSOT 收口本类，各链路禁止自行 if 分支）
 * 1. HIGH（maxLevel={@link ModerationConstant#DFA_LEVEL_HIGH}）→ blocked=true，
 *    发布入口抛 BizException(ErrorCode.SENSITIVE_WORD_DETECTED)（70001，仅用于 HIGH）；
 * 2. MID（maxLevel={@link ModerationConstant#DFA_LEVEL_MID}）→ 不拦截，midMark=true，
 *    layer1_hit=2 随 review_task 送异步层（MID 兼新增词灰度观察位，只标记不拦截）；
 * 3. 未命中 → blocked=false、midMark=false 放行。
 *
 * 【链路特殊语义】（由调用方按 decision 编排，本类不代管）
 * - 私信（MESSAGE）：blocked=true 时同步拒发 70001 且不投递 MQ（不进异步库不建任务）；
 *   仅 MID/LLM 走先发后审（4-2.2）；
 * - 用户名（USERNAME）：注册路径同步拒绝即终态，不建 review_task，仅落
 *   moderation_record(biz_id=0) 取证 + 明示不可申诉换名重试（4-2.3 联动 4-7）。
 *
 * 【scene 纪律】scene 传原始值（含 _RE_REVIEW 后缀等），由 DfaFacade.check 内部经
 * DfaScenes 归一，调用方禁止自行映射；图片场景不进本闸门（DfaScenes 归一为 null）。
 *
 * @author litisle
 */
public final class DfaPublishGate {

    private DfaPublishGate() {
    }

    /**
     * 闸门决策：按命中最高级别给出「拦截 / MID 标记」二元结论。
     * 决策口径与 DfaHitResult 消费纪律一致：HIGH 拦截（错误码由入口层抛 70001）、
     * MID 只标记不拦截（兼新增词灰度观察位）、未命中放行；
     * 入参为空时 fail-fast（宁严勿松，禁止默认放行）。
     *
     * @param scene  审核场景原始值（ARTICLE/COMMENT/USERNAME/MESSAGE 及其 _RE_REVIEW 变体；
     *               仅用于异常定位，归一化已由 DfaFacade.check 完成）
     * @param result DfaFacade.check 产物（调用方必须先做空内容校验，本类不校验文本）
     * @return 决策结论（blocked/MID 标记 + 命中结果透传留痕）
     */
    public static DfaGateDecision decide(String scene, DfaHitResult result) {
        // 步骤1：入参判空 fail-fast（DfaFacade.check 正常不返回 null，防御性拦截防误用放行）
        if (result == null) {
            throw new IllegalArgumentException("DfaHitResult 为空，禁止默认放行：scene=" + scene);
        }
        // 步骤2：未命中直接放行
        if (!result.hit()) {
            return new DfaGateDecision(false, false, result);
        }
        // 步骤3：HIGH（黑名单词）→ 拦截，由发布入口统一抛 70001（私信链路同时禁止投递 MQ）
        if (result.maxLevel() != null && result.maxLevel() == ModerationConstant.DFA_LEVEL_HIGH) {
            return new DfaGateDecision(true, false, result);
        }
        // 步骤4：MID 及未知级别 → 只标记 layer1_hit=2 送异步层，不拦截（灰度观察位）
        return new DfaGateDecision(false, true, result);
    }

    /**
     * 闸门决策结论（record 对齐 DfaHitResult 风格，不可变值对象）。
     *
     * @param blocked   true-命中 HIGH，发布入口必须拦截（私信链路同时禁止投递 MQ）
     * @param midMark   true-命中 MID，layer1_hit=2 随任务送异步层（不拦截）
     * @param hitResult 原始命中结果（hitWords/wordlibVer 随 review_task/moderation_record 留痕，禁回显用户）
     */
    public record DfaGateDecision(boolean blocked, boolean midMark, DfaHitResult hitResult) {
    }
}
