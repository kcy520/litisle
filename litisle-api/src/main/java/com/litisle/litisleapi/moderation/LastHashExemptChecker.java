package com.litisle.litisleapi.moderation;

import com.litisle.litisleapi.constant.ModerationConstant;

/**
 * 免审 last-hash 三分支判定口径（板块四 4-3.2/4-3.3/4-3.4 / Review-Plan §4.2.1 ②）——口径实现
 * （纯函数判定 + LastHashStore 读取；Redis 读写经 RedisAdapter 由调用方透传，模式同 LastHashStore）。
 *
 * 【判定顺序铁律】发布侧：PublishTextPrecheck.check（空内容）→ 算新 contentHash
 * （SubmitTextAssembler）→ 本类判定 → 三分支编排。空内容校验必须先行（空内容 hash 恒定，
 * 全站共享同一 hash，一条 PASS 键即污染全部空内容免审——4-1 时序铁律在此复现）。
 *
 * 【三分支口径】
 * 1. EXEMPT_PASS：key 存在 + hash 一致 + verdict=PASS + 状态排除通过（4-3.3）
 *    + 含图文章图片连带校验齐备（4-3.4）→ 免审直落 status=2 已发布，不发 MQ，
 *    且 review_task 就地 CAS 置 status=1 终态（缺「置终态」→ sweeper 6-2 把免审任务当
 *    卡死重投 MQ，可能机审 REJECT 触发已发布文章 2→4 下架）；
 * 2. REJECT_HINT：key 存在 + hash 一致 + verdict=REJECT → 前置提示「内容未修改仍将驳回」，
 *    不落库不发 MQ（省一次注定失败的机审）；
 * 3. SUBMIT：key 缺失 / hash 不一致 / verdict=SUSPICIOUS（last-hash 不存 SUSPICIOUS，防御兜底）
 *    → 照常发 MQ（Redis 丢数据宁多审不漏审）。
 *
 * 【状态排除分列（4-3.3）】状态由调用方查好传入（契约层无 DB）：
 * 文章 status∉{3-驳回,4-下架}；评论 status≠2-驳回（评论驳回态=2，套文章口径会漏排除
 * →被驳评论原样重发复活）；均补 del_flag=0（同 id 复活场景，4-4.2）。
 *
 * 【图片连带校验（4-3.4）】含配图文章须同时满足（三者缺一不可）：
 * ① img 连带键存在（LAST_HASH_KEY，bizType=1、scene=ARTICLE_IMG_RE_REVIEW）
 * ② 其 hash=当前配图 sha256 排序拼接后再 sha256 的聚合指纹一致（缺此比对→仅换图不改文
 *    时旧 PASS 键命中，架空图片复审）③ verdict=PASS。
 *
 * @author litisle
 */
public final class LastHashExemptChecker {

    private LastHashExemptChecker() {
    }

    /**
     * 免审三分支判定（发布侧编排唯一入口）。
     *
     * @param wordlibVer    当前 DfaFacade 该 scene 词库版本（查 key 用，防热更窗口旧键命中）
     * @param bizType       审核业务类型（1-文章 2-评论 5-私信）
     * @param scene         审核场景
     * @param bizId         业务 ID
     * @param newHash       本次发布送审文本指纹（SubmitTextAssembler 产物）
     * @param bizStatus     业务方当前状态（文章/评论/私信各表 status，调用方已查）
     * @param delFlag       业务方删除标记（0-正常；1-已删，禁止免审）
     * @param imgReReviewOk 含配图文章图片连带校验结论（无配图/非文章传 true；
     *                      含配图传 {@link #imgReReviewPassed} 结果）
     * @param redis         Redis 读写适配（宿主注入，透传 LastHashStore）
     * @return 三分支决策（EXEMPT_PASS/REJECT_HINT/SUBMIT）
     */
    public static ExemptDecision decide(long wordlibVer, int bizType, String scene, long bizId,
                                        String newHash, int bizStatus, int delFlag,
                                        boolean imgReReviewOk, LastHashStore.RedisAdapter redis) {
        // 步骤1：读 last-hash；键缺失/格式异常 → SUBMIT（Redis 丢数据宁多审不漏审）
        LastHashStore.LastHashValue last = LastHashStore.read(wordlibVer, bizType, scene, bizId, redis);
        if (last == null) {
            return ExemptDecision.SUBMIT;
        }
        // 步骤2：hash 不一致 = 内容已修改（仅改标题/正文任一即失配），旧结论失效 → SUBMIT
        if (!last.contentHash().equals(newHash)) {
            return ExemptDecision.SUBMIT;
        }
        // 步骤3：REJECT → 前置提示分支（不落库不发 MQ，省一次注定失败的机审）
        if (last.verdict() == LastHashStore.VERDICT_REJECT) {
            return ExemptDecision.REJECT_HINT;
        }
        // 步骤4：PASS 才可能免审；SUSPICIOUS/未知值（last-hash 不存 SUSPICIOUS）防御兑底 → SUBMIT
        if (last.verdict() != LastHashStore.VERDICT_PASS) {
            return ExemptDecision.SUBMIT;
        }
        // 步骤5：状态排除（4-3.3）与图片连带校验（4-3.4，调用方传入结论）任一不满足 → 照常发 MQ
        if (!statusExcluded(bizType, bizStatus, delFlag) || !imgReReviewOk) {
            return ExemptDecision.SUBMIT;
        }
        return ExemptDecision.EXEMPT_PASS;
    }

    /**
     * 状态排除判定（4-3.3）：按 bizType 分列比对，纯函数无 IO。
     *
     * @return true-通过排除（允许免审）；false-被排除（照常发 MQ）
     */
    public static boolean statusExcluded(int bizType, int bizStatus, int delFlag) {
        // 同 id 复活场景（4-4.2）：删除标记非 0 一律排除，禁止免审
        if (delFlag != 0) {
            return false;
        }
        // 按 bizType 分列驳回/下架口径：文章驳回=3、下架=4；评论/私信驳回=2（私信驳回态口径见 3-5.5）
        return switch (bizType) {
            case ModerationConstant.BIZ_TYPE_ARTICLE -> bizStatus != 3 && bizStatus != 4;
            case ModerationConstant.BIZ_TYPE_COMMENT, ModerationConstant.BIZ_TYPE_MESSAGE -> bizStatus != 2;
            // 未定义口径的 bizType fail-fast：静默放行 = 免审口径漂移，宁严勿松
            default -> throw new IllegalStateException("statusExcluded 未定义 bizType=" + bizType + " 的状态排除口径");
        };
    }

    /**
     * 含配图文章图片连带校验（4-3.4）。
     *
     * @param wordlibVer        当前 DfaFacade 该 scene 词库版本（与文本免审键同窗，防热更窗口旧键命中）
     * @param articleId        文章 ID
     * @param aggregatedImgHash 当前配图 sha256 排序拼接后再 sha256 的聚合指纹
     * @param redis             Redis 读写适配（宿主注入，透传 LastHashStore）
     * @return true-键存在 + 聚合 hash 一致 + verdict=PASS 三者齐备；false-任一缺失（照常发 MQ）
     */
    public static boolean imgReReviewPassed(long wordlibVer, long articleId, String aggregatedImgHash,
                                            LastHashStore.RedisAdapter redis) {
        // 步骤1：读 img 连带键（复用 LAST_HASH_KEY：bizType=1-文章、scene=ARTICLE_IMG_RE_REVIEW，4-3.4）
        LastHashStore.LastHashValue last = LastHashStore.read(wordlibVer, ModerationConstant.BIZ_TYPE_ARTICLE,
                ModerationConstant.SCENE_ARTICLE_IMG_RE_REVIEW, articleId, redis);
        // 步骤2：键缺失/格式异常 → 未命中（照常发 MQ）
        if (last == null) {
            return false;
        }
        // 步骤3：聚合 hash 不一致（仅换图不改文场景）或非 PASS → 任一不满足即 false，杜绠架空图片复审
        return last.verdict() == LastHashStore.VERDICT_PASS
                && last.contentHash().equals(aggregatedImgHash);
    }

    /**
     * 三分支决策结论。
     */
    public enum ExemptDecision {
        /** 免审直落：status=2 已发布，不发 MQ，review_task CAS 置 status=1 终态 */
        EXEMPT_PASS,
        /** 前置提示「内容未修改仍将驳回」：不落库不发 MQ */
        REJECT_HINT,
        /** 照常发 MQ 送异步机审（键缺失/hash 变化/SUSPICIOUS 兜底） */
        SUBMIT
    }
}
