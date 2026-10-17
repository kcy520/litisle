package com.litisle.litisleapi.moderation;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.dfa.DfaFacade;
import com.litisle.litisleapi.dfa.DfaHitResult;
import com.litisle.litisleapi.dfa.DfaScenes;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户名审核闸门（SSOT 门面：注册与改名两条入口共用的唯一链路实现）
 *
 * <p>【背景】注册（auth）与改名/笔名（user）三条入口的审核代码曾逐行重复，抽此门面收口，
 * 防口径漂移；调用方只保留「场景入口差异」（注册 username 必填直接调；改名/笔名为部分更新
 * 需判空后调，笔名为作者对外身份标识，与用户名同类——复用 USERNAME 场景词库口径，
 * 不另建 PEN_NAME 场景），链路本身禁止在调用方复制</p>
 *
 * <p>【链路五层】② 口径层 PublishTextPrecheck（trim/去标签可见非空）→
 * ③ 词库层 DfaFacade.check（本地 DFA 引擎，零网络，微秒级）→
 * ④ 决策层 DfaPublishGate.decide（HIGH 拦 / MID 标 / 未命中过，SSOT）→
 * ⑤ 取证层 UsernameRejectGate（仅 HIGH：指纹落 review 的 moderation_record，不落明文）。
 * 用户名场景刻意不接异步机审/人审（身份字段必须同步定论，MID 为词库运营观察位）</p>
 *
 * <p>【时序铁律】审核闸门必须先于调用方的业务校验（唯一性查库/验证码/密码加密）执行：
 * 违规请求不消耗一次性资源；空内容校验先于 DFA（空内容 hash 恒定，防 PASS 缓存污染）</p>
 *
 * <p>【扩展点预留】review 将来补充 NLP/LLM 机审能力后，本链路仍不接入，依据：
 * ① 语义级风险依赖长文本上下文，用户名 ≤32 字符无语义空间可分析；
 * ② LLM 为概率性判定（同步链路结论不稳定）+ 百毫秒级延迟直接进注册/改名体验，
 * 违反「同步层只做廉价确定性判定」纪律；③ 若出现变体拆字绕词库（如分隔符/形近字），
 * 唯一扩展位置是步骤 1 与步骤 2 之间新增归一化步骤（仍本地/确定性/微秒级），
 * 本门面禁止被复制改名出 V2 副本（口径 SSOT），异步机审/人审仅适用于文章/评论等 UGC 场景</p>
 *
 * @author litisle
 */
@Slf4j
public final class UsernameModerationGate {

    /** 工具类禁实例化 */
    private UsernameModerationGate() {
    }

    /**
     * 用户名审核链路（同步、本地、零网络）：HIGH 命中抛 70001，MID/未命中静默放行
     *
     * @param username      待审用户名（调用方保证非 null；注册必填直传，改名判空后传）
     * @param dfaFacade     宿主本地 DFA 门面（auth/user 各自装配，引擎实例宿主私有）
     * @param recordAdapter 宿主编织的取证适配器（HIGH 拒绝时落库 review 侧 moderation_record）
     */
    public static void review(String username, DfaFacade dfaFacade, UsernameRejectGate.RecordAdapter recordAdapter) {
        // 步骤1 口径层：trim 非空 + 去 HTML 标签后可见文本非空（title 传 null，用户名场景无标题）
        PublishTextPrecheck.check(null, username);
        // （预留扩展位）若未来需要对抗变体拆字绕过，在此插入归一化步骤（去分隔符/形近字还原）后传入 DFA，
        // 禁止在此接入 NLP/LLM 异步机审（身份字段必须同步定论，依据见类注释【扩展点预留】）
        // 步骤2 词库层：本地引擎同步判定（决策口径 SSOT 收口 DfaPublishGate，禁调用方复制 if 分支）
        DfaHitResult hitResult = dfaFacade.check(DfaScenes.USERNAME, username);
        DfaPublishGate.DfaGateDecision decision = DfaPublishGate.decide(DfaScenes.USERNAME, hitResult);
        // 步骤3 取证层：HIGH 拦截——先取证后抛 70001（取证失败仅 error 留痕不影响拦截结论），
        // 提示禁回显命中词（防词库探测）；MID/未命中放行（用户名无异步送审链路）
        if (decision.blocked()) {
            boolean recorded = UsernameRejectGate.recordReject(username, decision.hitResult(), recordAdapter);
            if (!recorded) {
                log.error("用户名拒绝取证落库失败（拒绝结论不变，详见适配器 error 日志）");
            }
            throw new BizException(ErrorCode.SENSITIVE_WORD_DETECTED);
        }
    }
}
