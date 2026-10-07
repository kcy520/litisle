package com.litisle.litislereview.service;



import com.litisle.litislereview.domain.dto.AppealDecideDTO;
import com.litisle.litislereview.domain.dto.AppealSubmitDTO;
import com.litisle.litislereview.domain.dto.ReportSubmitDTO;
import com.litisle.litislereview.domain.dto.TaskDecideDTO;
import com.litisle.litislereview.domain.entity.Report;
import com.litisle.litislereview.domain.entity.SensitiveWordLibrary;
import com.litisle.litislereview.domain.vo.PunishLogVO;
import com.litisle.litislereview.domain.vo.ReportVO;
import com.litisle.litislereview.domain.vo.ReviewTaskVO;
import com.litisle.litislereview.enums.WordLevelEnum;
import com.litisle.litislereview.enums.WordListTypeEnum;
import com.litisle.litislereview.enums.WordSceneEnum;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 审核能力接口（REST 由 ReviewController 调用；未来 ReviewFeignApi 亦复用此接口）：
 *
 * ReportVO submitReport(ReportSubmitDTO dto, Long reporterId)        // 提交举报（防自举报+去重+证据登记）
 * void withdrawReport(Long reportId, Long reporterId)                // 撤回（本人+status<3→4；已建任务不撤销）
 * List<ReviewTaskVO> listQueue(Long operatorId)                      // 人审队列（REVIEWER/ADMIN；按 layer2_risk 排序）
 * ReviewTaskVO getTask(Long taskId, Long operatorId)                 // 任务详情（含机审证据，REVIEWER/ADMIN）
 * void claimTask(Long taskId, Long reviewerId)                       // 认领 CAS（affected=0 → 70004 已被他人认领）
 * void decideTask(Long taskId, TaskDecideDTO dto, Long reviewerId)   // 裁决终态 CAS + 联动（举报回写/处罚/事件）
 * AppealVO submitAppeal(AppealSubmitDTO dto, Long appellantId)       // 提交申诉（归属校验+双层防重）
 * void judgeAppeal(Long appealId, AppealDecideDTO dto, Long judgeId) // ADMIN 裁决（回避+confirm 双签留痕）
 * List<PunishLogVO> listMyPunish(Long userId)                        // 我的处罚流水
 * void addWord / updateWord / deleteWord(...)                        // 词库管理（ADMIN；变更与 version+1 同事务）
 * void importWords / List<SensitiveWordLibrary> exportWords(...)     // 词库导入导出（防注入/XXE 约束见方法注释）
 * List<SensitiveWordLibrary> listWords(WordSceneEnum scene, ...)     // 词库列表（ADMIN）
 *
 * 【不进本接口的链路】机审执行（DFA/LLM/图片模型）与 MQ 消费（moderation.result、
 * image.submit、content.unpublished 等事件）放 listener + 独立 ModerationService，
 * 避免 REST 门面与异步链路耦合（后续按需拆分 Service，本接口先做统一门面）
 */
public interface ReviewService {

    /**
     * 提交举报：校验 reporter≠目标 owner → 活跃举报去重（active_key 兜底）→ 落 report + 证据关联 →
     * 同步 DFA 初审分流（HIGH 命中建 review_task 转人工 / 低风险机审直处理）
     */
    ReportVO submitReport(ReportSubmitDTO dto, Long reporterId);

    /**
     * 撤回举报：仅举报人本人、仅 status<3（CAS where reporter_id=? and status<3 置 4-已撤回）；
     * 已建 review_task 不撤销
     */
    void withdrawReport(Long reportId, Long reporterId);

    /**
     * 人审队列：REVIEWER/ADMIN；返回待审/在审任务（layer2_risk 降序 + create_time 升序），
     * 支持配额校验（max-active-per-reviewer=20 防单人占满队列）
     */
    List<ReviewTaskVO> listQueue(Long operatorId);

    /**
     * 任务详情：REVIEWER/ADMIN；含机审三分态/命中词快照/证据图（证据经 FileClient 批量取预签名 URL）
     */
    ReviewTaskVO getTask(Long taskId, Long operatorId);

    /**
     * 认领任务：CAS where id=? and status=2 and assignee is null，affected=0 抛 70004（已被他人认领）；
     * 写 assignee + claim_time（sweeper 超时回池基准）
     */
    void claimTask(Long taskId, Long reviewerId);

    /**
     * 裁决任务：终态 CAS（where status=2）置 {3,4} + decided_by/opinion/decide_time，天然拦截重复 decide；
     * REJECT 联动：内容下架事件 + punish_log 落行 + violation_score 原子累加 + 举报回写 status=3；
     * 高风险裁决（人审直接封禁）需 confirm=true + 双签留痕（moderation_record mod_type=6）
     */
    void decideTask(Long taskId, TaskDecideDTO dto, Long reviewerId);

    /**
     * 提交申诉：appellant 与内容 owner 一致校验（biz_type→report.target_type 映射回查归属）→
     * 双层防重（关联列级 + 内容级 status=0）→ 落 appeal（biz_type/biz_id 由关联列解析固化）
     */
    void submitAppeal(AppealSubmitDTO dto, Long appellantId);

    /**
     * 裁决申诉：仅 ADMIN（REVIEWER 不可自裁）+ 原驳回裁决人回避 + 改判撤销高风险需 confirm=true 双签；
     * 维持 → 置 status=1；撤销 → 置 status=2 + punish_log 撤销/violation_score 重算/
     * 同内容活跃举报批量回写 status=3（按 target_type+target_id，非仅 appeal.report_id）
     */
    void judgeAppeal(Long appealId, AppealDecideDTO dto, Long judgeId);

    /**
     * 我的处罚流水：仅本人；reason 等 UGC 回显由 Controller/前端 sanitize
     */
    List<PunishLogVO> listMyPunish(Long userId);

    /**
     * 新增敏感词：ADMIN；词长度上限 + 字符校验（DFA 构建前拦截）→ 落词表 + version+1 同事务 → Redis Pub
     */
    void addWord(String word, WordSceneEnum scene, WordLevelEnum level,
                 WordListTypeEnum isBlacklist, Long operatorId);

    /**
     * 修改敏感词（含启停/黑白切换）：ADMIN；同上事务 + Pub 约束
     */
    void updateWord(SensitiveWordLibrary word, Long operatorId);

    /**
     * 删除敏感词（物理删）：ADMIN；同上事务 + Pub 约束
     */
    void deleteWord(Long wordId, Long operatorId);

    /**
     * 词库导入：ADMIN；限类型大小、解析不启外部实体（防 XXE）、逐词长度/字符校验；
     * 单词冲突跳过并计数，整体成功后 version+1 一次
     */
    void importWords(MultipartFile file, WordSceneEnum scene, WordLevelEnum level,
                     WordListTypeEnum isBlacklist, Long operatorId);

    /**
     * 词库导出：ADMIN；CSV 对 =/+/-/@ 开头单元格前置 '（防公式注入）
     */
    List<SensitiveWordLibrary> exportWords(WordSceneEnum scene, Long operatorId);

    /**
     * 词库列表：ADMIN；支持 scene/级别/黑白/状态筛选，LIKE 对 %/_ 转义防通配放大全表扫
     */
    List<SensitiveWordLibrary> listWords(WordSceneEnum scene, WordLevelEnum level,
                                         WordListTypeEnum isBlacklist, Long operatorId);

    /**
     * 内部：按举报ID查原始行（未来 ReviewClient 契约提供方；网关不路由 /api/feign/**）
     */
    Report getInternalReport(Long reportId);

    /**
     * 内部：查用户当前处罚态（登录判定/发帖前拦截用；仅返回 status=1 生效行）
     */
    List<PunishLogVO> getActivePunishes(Long userId);
}