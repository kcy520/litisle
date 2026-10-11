package com.litisle.litislereview.service;

import com.litisle.litislereview.enums.Layer1HitEnum;
import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import com.litisle.litislereview.enums.ReviewSceneEnum;

/**
 * 统一建任务 upsert 服务接口（板块三 3-3，Review-Plan §4.2.2）
 *
 * 【落位】review 模块 service 包；发布侧建任务 / 举报转人工 / 消费端重置三路调用共用本入口，
 * 保证 uk_biz_scene(biz_type, biz_id, scene) 唯一键下的幂等语义只有一份实现。
 *
 * 【核心规则（next_update.md 3-3 定稿口径）】
 * 1. 按 uk_biz_scene upsert：无行 → insert（status 按场景初始值、content_hash、retry_count=0）。
 * 2. 命中行分流（★核心）：
 *    a. content_hash 变化（编辑重发）或原任务已终态（status∈{1,3,4}）→ 重置：
 *       旧任务未终态时先追加 moderation_record 标 superseded；然后 update 置目标态
 *       + 清 assignee/decided_by/opinion（及 claim_time/decide_time，防旧认领人残留）
 *       + 更新 content_hash + retry_count 归零 + 清机审证据列
 *       （mod_verdict=0、layer1_hit=0、hit_words=null、layer2_risk=0）。
 *    b. hash 未变且非终态（status∈{0,2}，同内容重复举报/复审）→ 只复用不重置：
 *       保留 status/assignee（不打断已认领在审任务），仅回填调用方关联列（如 report.review_task_id）。
 * 3. 重置目标态按 scene 分列：
 *    - 待机审场景（ARTICLE_FEATURED / ARTICLE_COMMUNITY / COMMENT / MESSAGE / ARTICLE_IMG_RE_REVIEW）
 *      → status=0（重跑机审）；
 *    - 举报复审场景（ARTICLE_RE_REVIEW / COMMENT_RE_REVIEW / MESSAGE_RE_REVIEW）
 *      → status=2（直进人审，不重跑机审）。
 *
 * 【注意事项】
 * - 不清 assignee → 被重置任务的旧认领人无需重新认领即可裁决（decide CAS 以 assignee 判权）；
 *   不清证据列 → 人审看到张冠李戴的旧命中词/风险分级（重置仅因内容变更或终态触发，旧证据属旧内容）。
 * - ARTICLE_IMG_RE_REVIEW 虽带 _RE_REVIEW 后缀但属机审复审（逐图送 SPI），
 *   不得并入「直进人审」一类（见重置目标态分列）。
 * - 多轮举报收敛属调用方职责：decide 回写/反查举报一律带 and status&lt;3 活跃态过滤，
 *   禁裸用 review_task_id 全量 UPDATE（1 行任务对多轮多份 report 是 1:N）。
 * - USERNAME 同步拒绝不建任务（唯一例外），只落 moderation_record(biz_id=0)，防注册失败堆废任务；
 *   本服务对 bizType=USERNAME 的调用直接拒绝（参数校验失败），不产生任何 DB 写入。
 */
public interface ReviewTaskUpsertService {

    /**
     * 按 uk_biz_scene(biz_type, biz_id, scene) 幂等 upsert 审核任务
     *
     * @param bizType     业务类型（1-文章 2-评论 3-用户名 4-作家申请 5-私信 6-文件/图片；
     *                    USERNAME 被本服务拒绝——同步 DFA 拒绝不建任务）
     * @param bizId       业务ID（bizType=6 时为 file_meta.id）
     * @param scene       审核场景（必带：复审与发布任务同 biz 同 id 并存，缺 scene 定位歧义）
     * @param contentHash 内容指纹 SHA256（文本=标题+正文；图片=文件 sha256；文章配图复审=聚合指纹）
     * @param layer1Hit   DFA 一级命中快照（0-未命中 1-HIGH 2-MID；仅 insert/重置时写入，复用分支保留原值）
     * @param hitWords    DFA 命中词快照（应用层截断保护后落库；仅 insert/重置时写入，复用分支保留原值）
     * @return upsert 后任务ID（新建行主键 / 复用行主键），供调用方回填关联列（如 report.review_task_id）
     */
    Long upsert(ModerationBizTypeEnum bizType, Long bizId, ReviewSceneEnum scene,
                String contentHash, Layer1HitEnum layer1Hit, String hitWords);
}