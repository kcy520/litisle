package com.litisle.litisleapi.moderation;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.dfa.DfaFacade;
import com.litisle.litisleapi.dfa.DfaHitResult;
import com.litisle.litisleapi.dfa.DfaScenes;
import lombok.extern.slf4j.Slf4j;

/**
 * 简介类文本审核闸门（SSOT 门面：bio/创作方向/代表作 共用 BIO 场景词库，单链路实现）
 *
 * <p>【场景定位】简介类字段是公开展示内容但**非身份字段**——判级口径：
 * 同步 DFA 快筛（≤200 字符短文本，语义空间有限，异步 NLP/LLM 收益不抵链路成本）；
 * HIGH 拦截但**不取证落库**（与用户名链路的差异：拒绝仅意味"本次没改成功"，
 * 原值仍在展示无状态收敛问题，无需审计留痕）；不建审核任务（宿主直审场景，
 * 不经 review_task，故 DfaScenes.normalize 无需映射）</p>
 *
 * <p>【清空语义】DTO 为部分更新，空串 = 清空字段——无展示内容即无审核对象，
 * 闸门直接放行（PublishTextPrecheck 的非空口径不适用清空操作，故先判空短路）</p>
 *
 * <p>【时序铁律】与用户名链路一致：审核先于调用方业务校验/写库执行，违规请求零写库</p>
 *
 * @author litisle
 */
@Slf4j
public final class BioModerationGate {

    /** 工具类禁实例化 */
    private BioModerationGate() {
    }

    /**
     * 简介类文本审核链路（同步、本地、零网络）：HIGH 命中抛 70001，MID/未命中静默放行；
     * 空串/null 为清空语义直接放行
     *
     * @param text      待审文本（bio 或 creationDir 或 masterpiece，调用方保证仅在字段携带时调用）
     * @param dfaFacade 宿主本地 DFA 门面（user 宿主装配，BIO 场景引擎随 initAll 自动构建）
     */
    public static void review(String text, DfaFacade dfaFacade) {
        // 步骤0 清空语义短路：空串/null = 清空字段，无展示内容即无审核对象，放行
        if (text == null || text.isBlank()) {
            return;
        }
        // 步骤1 口径层：trim 非空 + 去 HTML 标签后可见文本非空（title 传 null，简介类无标题）
        PublishTextPrecheck.check(null, text);
        // 步骤2 词库层：BIO 场景引擎同步判定（bio/creationDir/masterpiece 一套词库）
        DfaHitResult hitResult = dfaFacade.check(DfaScenes.BIO, text);
        DfaPublishGate.DfaGateDecision decision = DfaPublishGate.decide(DfaScenes.BIO, hitResult);
        // 步骤3 HIGH 拦截：抛 70001、禁回显命中词（防词库探测）；不取证（非身份字段，理由见类注释）
        if (decision.blocked()) {
            throw new BizException(ErrorCode.SENSITIVE_WORD_DETECTED);
        }
    }
}
