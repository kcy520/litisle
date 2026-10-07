package com.litisle.litislereview.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.litislereview.domain.dto.AppealDecideDTO;
import com.litisle.litislereview.domain.dto.AppealSubmitDTO;
import com.litisle.litislereview.domain.dto.ReportSubmitDTO;
import com.litisle.litislereview.domain.dto.TaskDecideDTO;
import com.litisle.litislereview.domain.vo.PunishLogVO;
import com.litisle.litislereview.domain.vo.ReportVO;
import com.litisle.litislereview.domain.vo.ReviewTaskVO;
import com.litisle.litislereview.enums.WordLevelEnum;
import com.litisle.litislereview.enums.WordListTypeEnum;
import com.litisle.litislereview.enums.WordSceneEnum;
import com.litisle.litislereview.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * REST 接口层（路径前缀 /api/review，Knife4j 已扫本包）：
 *
 * | 方法   | 路径                       | 说明                                  | 权限            |
 * |--------|---------------------------|---------------------------------------|-----------------|
 * | POST   | /report                   | 提交举报（防自举报+去重+证据登记）      | 登录            |
 * | DELETE | /report/{reportId}        | 撤回举报（仅本人，status<3）           | 登录            |
 * | GET    | /report/my                | 我的举报列表                           | 登录            |
 * | POST   | /appeal                   | 提交申诉（appellant 须为内容 owner）   | 登录            |
 * | POST   | /appeal/{appealId}/judge  | 裁决申诉（ADMIN+回避+confirm 双签）    | ADMIN           |
 * | GET    | /task/queue               | 人审队列                               | REVIEWER/ADMIN  |
 * | GET    | /task/{taskId}            | 任务详情（含机审证据）                 | REVIEWER/ADMIN  |
 * | POST   | /task/{taskId}/claim      | 认领（CAS，70004 已被他人认领）        | REVIEWER/ADMIN  |
 * | POST   | /task/{taskId}/decide     | 裁决（终态 CAS + 联动）                | REVIEWER/ADMIN  |
 * | GET    | /punish/my                | 我的处罚流水                           | 登录            |
 * | POST   | /word                     | 新增敏感词                             | ADMIN           |
 * | PUT    | /word                     | 修改敏感词                             | ADMIN           |
 * | DELETE | /word/{wordId}            | 删除敏感词                             | ADMIN           |
 * | POST   | /word/import              | 词库导入（防 XXE/公式注入）            | ADMIN           |
 * | GET    | /word/export              | 词库导出                               | ADMIN           |
 * | GET    | /word/list                | 词库列表                               | ADMIN           |
 *
 * 要点：
 * - operatorId 统一 StpUtil.getLoginIdAsLong() 取当前登录人（Sa-Token 会话共享口径，与 file 模块一致）
 * - 角色校验（REVIEWER/ADMIN、原裁决人回避、申诉人=owner）在 Service 层统一落地，
 *   Controller 只做 @SaCheckLogin 登录闸门 + 参数校验（@Valid），与 file 模块口径一致
 * - 队列/裁决类接口后续按需加 @RateLimiter 与 FileGlobalQpsInterceptor 同款全站 QPS 拦截器
 * - 所有返回 Result<T>；错误码：70004-任务已被他人认领 等
 */
@Tag(name = "审核服务", description = "举报/申诉/人审队列/裁决/处罚/敏感词库")
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "提交举报", description = "防自导自演（reporter≠目标owner）+ 活跃举报去重；证据图最多9张")
    @PostMapping("/report")
    @SaCheckLogin
    public Result<ReportVO> submitReport(@Valid @RequestBody ReportSubmitDTO dto) {
        return Result.success(reviewService.submitReport(dto, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "撤回举报", description = "仅举报人本人、仅 status<3 可撤回；已建审核任务不撤销")
    @DeleteMapping("/report/{reportId}")
    @SaCheckLogin
    public Result<Void> withdrawReport(@PathVariable("reportId") Long reportId) {
        reviewService.withdrawReport(reportId, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "提交申诉", description = "appellant 须与内容 owner 一致；同内容存在待裁决申诉即拒绝（双层防重）")
    @PostMapping("/appeal")
    @SaCheckLogin
    public Result<Void> submitAppeal(@Valid @RequestBody AppealSubmitDTO dto) {
        reviewService.submitAppeal(dto, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "裁决申诉", description = "仅 ADMIN；原驳回裁决人回避；撤销封禁/清分需 confirm=true 双签留痕")
    @PostMapping("/appeal/{appealId}/judge")
    @SaCheckLogin
    public Result<Void> judgeAppeal(@PathVariable("appealId") Long appealId,
                                    @Valid @RequestBody AppealDecideDTO dto) {
        reviewService.judgeAppeal(appealId, dto, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "人审队列", description = "REVIEWER/ADMIN；按小审风险降序 + 时间升序；单人认领上限20")
    @GetMapping("/task/queue")
    @SaCheckLogin
    public Result<List<ReviewTaskVO>> listQueue() {
        return Result.success(reviewService.listQueue(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "任务详情", description = "REVIEWER/ADMIN；含机审三分态/命中词快照/证据图预签名URL")
    @GetMapping("/task/{taskId}")
    @SaCheckLogin
    public Result<ReviewTaskVO> getTask(@PathVariable("taskId") Long taskId) {
        return Result.success(reviewService.getTask(taskId, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "认领任务", description = "CAS 条件更新；已被他人认领返回 70004")
    @PostMapping("/task/{taskId}/claim")
    @SaCheckLogin
    public Result<Void> claimTask(@PathVariable("taskId") Long taskId) {
        reviewService.claimTask(taskId, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "裁决任务", description = "终态 CAS 天然拦截重复裁决；REJECT 联动处罚/下架/举报回写")
    @PostMapping("/task/{taskId}/decide")
    @SaCheckLogin
    public Result<Void> decideTask(@PathVariable("taskId") Long taskId,
                                   @Valid @RequestBody TaskDecideDTO dto) {
        reviewService.decideTask(taskId, dto, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "我的处罚流水", description = "仅本人；生效+已撤销全量展示")
    @GetMapping("/punish/my")
    @SaCheckLogin
    public Result<List<PunishLogVO>> listMyPunish() {
        return Result.success(reviewService.listMyPunish(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "新增敏感词", description = "ADMIN；词长度上限+字符校验；变更与词库版本+1同事务并Pub广播")
    @PostMapping("/word")
    @SaCheckLogin
    public Result<Void> addWord(@RequestParam("word") String word,
                                @RequestParam("scene") WordSceneEnum scene,
                                @RequestParam("level") WordLevelEnum level,
                                @RequestParam("isBlacklist") WordListTypeEnum isBlacklist) {
        reviewService.addWord(word, scene, level, isBlacklist, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "删除敏感词", description = "ADMIN；物理删除（配置类小表），留 updated_by 审计痕")
    @DeleteMapping("/word/{wordId}")
    @SaCheckLogin
    public Result<Void> deleteWord(@PathVariable("wordId") Long wordId) {
        reviewService.deleteWord(wordId, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "词库导入", description = "ADMIN；限类型大小、解析不启外部实体（防XXE）、逐词校验")
    @PostMapping("/word/import")
    @SaCheckLogin
    public Result<Void> importWords(@RequestParam("file") MultipartFile file,
                                    @RequestParam("scene") WordSceneEnum scene,
                                    @RequestParam("level") WordLevelEnum level,
                                    @RequestParam("isBlacklist") WordListTypeEnum isBlacklist) {
        reviewService.importWords(file, scene, level, isBlacklist, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "词库导出", description = "ADMIN；CSV 公式注入防护（=/+/-/@ 开头单元格前置 '）")
    @GetMapping("/word/export")
    @SaCheckLogin
    public Result<List<com.litisle.litislereview.domain.entity.SensitiveWordLibrary>> exportWords(
            @RequestParam("scene") WordSceneEnum scene) {
        return Result.success(reviewService.exportWords(scene, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "词库列表", description = "ADMIN；多条件筛选，LIKE 通配符转义")
    @GetMapping("/word/list")
    @SaCheckLogin
    public Result<List<com.litisle.litislereview.domain.entity.SensitiveWordLibrary>> listWords(
            @RequestParam(value = "scene", required = false) WordSceneEnum scene,
            @RequestParam(value = "level", required = false) WordLevelEnum level,
            @RequestParam(value = "isBlacklist", required = false) WordListTypeEnum isBlacklist) {
        return Result.success(reviewService.listWords(scene, level, isBlacklist, StpUtil.getLoginIdAsLong()));
    }
}