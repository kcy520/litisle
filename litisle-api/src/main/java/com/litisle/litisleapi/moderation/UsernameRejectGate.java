package com.litisle.litisleapi.moderation;

import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dfa.DfaHitResult;

/**
 * 用户名注册同步拒绝闸口（板块四 4-7 / Review-Plan §4.2.2）——口径实现
 * （取证落库经 RecordAdapter 由宿主注入，契约层零 ORM 依赖，模式同 MessageRecallGate.RecallCasAdapter）。
 *
 * UsernameRejectGate 是用户名注册被 DFA 黑名单词拦截后的「取证落库闸口」——只负责记录"谁、用什么名字、命中了什么词"，不负责拦截本身，不建审核任务，不进异步队列。
 *
 * 它是注册链路里审核审计的最后一环，和 DfaPublishGate（拦截决策）是解耦的两个类
 *
 * 【业务背景】用户名是全站身份标识（文章作者名/评论昵称等回显源），注册路径必须同步 DFA 闸门把关：
 * HIGH（黑名单词）命中直接拒绝注册，拒绝语义由 DfaPublishGate.decide 给出（blocked=true），
 * 本类只负责「拒绝之后的取证落库」收口——与闸门决策解耦，单一职责。
 *
 * 【4-7.1 不建任务只取证】DFA HIGH 同步拒绝不建 review_task、不进 MQ 异步层，仅落
 * moderation_record 取证行：biz_type={@link ModerationConstant#BIZ_TYPE_USERNAME}（3）+
 * biz_id=0（注册被拒时用户尚不存在，无实体 ID，0 为契约占位口径）+
 * content_hash=SHA-256(用户名)（复用 SubmitTextAssembler 全站口径派生，
 * 换名重试每次按 hash 区分留痕，取证行不落用户名明文）。
 *
 * 【4-7.2 明示不可申诉】拒绝即终态的设计声明（非遗漏）：
 * 1. 无 review_task → 无申诉载体（申诉按 review_task/report/punish 三类关联，本路径三者皆无），
 *    申诉端点天然查不到该记录，无需额外拦截逻辑；
 * 2. 用户侧提示口径：仅告知「用户名含违规内容，请更换后重试」（错误码 70001 由调用方抛出），
 *    禁回显命中词（防词库探测，与 preview-check 禁回吐同纪律）；
 * 3. 无改名路径：注册被拒不产生账号，不存在「先注册后改名绕过审核」的攻击面
 *    （一期无改名功能；若二期引入改名，必须重新过 USERNAME 场景 DFA 同步闸门，届时本声明须复核）。
 *
 * 【取证失败不影响拦截】落库失败仅返回 false 供调用方 error 留痕，注册拒绝结论不变
 * （宁严勿松：取证是审计增强项，不是拦截前置条件；但连续失败须告警排查适配器故障）。
 *
 * 【挂起说明】litisle-auth 注册链路嵌入挂起（契约实现已落）；触发条件同 3-5，
 * auth 模块注册功能开发时随实体层一并落地。
 *
 * @author litisle
 */
public final class UsernameRejectGate {

    private UsernameRejectGate() {
    }

    /**
     * 用户名 HIGH 拒绝取证落库（4-7.1）：不建 review_task，仅落 moderation_record(biz_id=0)。
     *
     * 作用：用户名被 DFA HIGH 拦截后，落一条取证记录到 moderation_record 表。
     *
     * @param username  命中黑名单的用户名原文（仅用于派生 contentHash，禁明文落取证行/禁回显日志）
     * @param hitResult DFA 命中结果（必须是 DfaPublishGate.decide 判定 blocked=true 的 HIGH 命中；
     *                  MID 命中走异步建任务链路，误传本方法将被 fail-fast 拒绝）
     * @param adapter   取证落库适配（宿主注入：按类注口径写 moderation_record）
     * @return true-取证行落库成功；false-落库失败（注册拒绝结论不变，调用方仅 error 留痕）
     */
    public static boolean recordReject(String username, DfaHitResult hitResult, RecordAdapter adapter) {
        // 步骤1：适配器判空 fail-fast（取证静默丢失会让误拦投诉无据可查，宁严勿松）
        if (adapter == null) {
            throw new IllegalArgumentException("RecordAdapter 为空，禁止静默跳过用户名拒绝取证");
        }
        // 步骤2：入参 fail-fast（空白用户名/空命中结果均属调用方误用，直拦防脏取证行）
        if (username == null || username.isBlank() || hitResult == null) {
            throw new IllegalArgumentException("username/hitResult 为空，禁止落空取证行");
        }
        // 步骤3：口径校验——仅 HIGH 拦截命中允许走本闸口（MID 命中属异步建任务链路，
        // 误传进来会制造「未拦截却有 REJECT 取证」的矛盾留痕，fail-fast 暴露误用）
        if (!hitResult.hit()
                || hitResult.maxLevel() == null
                || hitResult.maxLevel() != ModerationConstant.DFA_LEVEL_HIGH) {
            throw new IllegalStateException("仅 DfaPublishGate 判定 blocked 的 HIGH 命中可走用户名拒绝取证");
        }
        // 步骤4：派生 contentHash（复用 SubmitTextAssembler 全站口径：title=null + body=用户名，
        // SHA-256 小写十六进制；hash 为服务端派生值，取证行不落用户名明文）
        String contentHash = SubmitTextAssembler.contentHash(SubmitTextAssembler.assemble(null, username));
        // 步骤5：取证落库（列口径在适配器实现内收口，见 RecordAdapter 注）；
        // 失败返回 false 不抛异常——注册拒绝结论由调用方闸门独立给出，不因取证故障放行
        return adapter.insertRejectRecord(new RejectRecordContext(contentHash, hitResult));
    }

    /**
     * 取证落库上下文（record 对齐 DfaGateDecision 风格，不可变值对象）。
     *
     * 作用：把取证所需的全部数据打包成一个不可变值对象，传给 RecordAdapter。
     *
     * @param contentHash SHA-256(用户名)（SubmitTextAssembler 全站口径派生，64 位小写十六进制）
     * @param hitResult   原始命中结果（hitWords/wordlibVer/maxLevel 供取证列留痕，禁回显用户）
     */
    public record RejectRecordContext(String contentHash, DfaHitResult hitResult) {
    }

    /**
     * 取证落库适配（宿主注入，契约层无 DB 依赖）。
     *
     *作用：和 RecallCasAdapter 同一个模式——契约层定义接口，宿主（auth 模块）注入实现。
     *
     */
    @FunctionalInterface
    public interface RecordAdapter {

        /**
         * 落 moderation_record 取证行（口径见类注）：不建 review_task、不进 MQ。
         * 适配器实现须按以下列口径写入——
         * biz_type={@link ModerationConstant#BIZ_TYPE_USERNAME}（3）、
         * biz_id=0（注册被拒无用户实体，契约占位口径）、
         * scene=USERNAME、mod_type={@link ModerationConstant#MOD_TYPE_AUTO}（1-机审）、
         * verdict={@link ModerationConstant#VERDICT_REJECT}（2-REJECT）、
         * max_level={@link ModerationConstant#DFA_LEVEL_HIGH}（1）、
         * content_hash=context.contentHash()、
         * hit_words=context.hitResult().hitWords()、
         * wordlib_ver=context.hitResult().wordlibVer()。
         *
         * @param context 取证上下文（contentHash + 命中结果）
         * @return true-落库成功；false-落库失败（禁抛异常阻断注册拒绝流程）
         */
        boolean insertRejectRecord(RejectRecordContext context);
    }
}
