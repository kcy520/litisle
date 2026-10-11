package com.litisle.litislereview.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.commonsecurity.constant.RoleCode;
import com.litisle.litislereview.domain.dto.WordUpdateDTO;
import com.litisle.litislereview.domain.vo.SensitiveWordVO;
import com.litisle.litislereview.enums.WordLevelEnum;
import com.litisle.litislereview.enums.WordListTypeEnum;
import com.litisle.litislereview.enums.WordSceneEnum;
import com.litisle.litislereview.service.WordlibService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * REST 接口层（路径前缀 /api/review，Knife4j 已扫本包）——敏感词库分组：
 *
 * | 方法   | 路径              | 说明                        | 权限  |
 * |--------|-------------------|-----------------------------|-------|
 * | POST   | /word             | 新增敏感词                  | ADMIN |
 * | PUT    | /word             | 修改敏感词                  | ADMIN |
 * | DELETE | /word/{wordId}    | 删除敏感词                  | ADMIN |
 * | POST   | /word/misreport   | 词库误报上报                | 登录  |
 * | DELETE | /word/by-content  | 按场景+词删除敏感词         | ADMIN |
 * | POST   | /word/import      | 词库导入（防 XXE/公式注入） | ADMIN |
 * | GET    | /word/export      | 词库导出                    | ADMIN |
 * | GET    | /word/list        | 词库列表                    | ADMIN |
 *
 * 要点（原 ReviewController 通用约束，拆分后各组保留同一口径）：
 * - operatorId 统一 StpUtil.getLoginIdAsLong() 取当前登录人（Sa-Token 会话共享口径，与 file 模块一致）
 * - 所有返回 Result<T>；错误码：70004-任务已被他人认领 等
 */
@Tag(name = "审核服务-敏感词库", description = "敏感词增删改查/导入导出/误报上报")
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class WordlibController {

    private final WordlibService wordlibService;

    @Operation(summary = "新增敏感词", description = "ADMIN；词长度上限+字符校验；变更与词库版本+1同事务并Pub广播；level 不传默认 MID（灰度观察，§2.5）")
    @PostMapping("/word")
    @SaCheckRole(RoleCode.ADMIN)
    public Result<Void> addWord(@Parameter(description = "敏感词内容；长度≤上限且字符校验，必传")
                                @RequestParam("word") String word,
                                @Parameter(description = "场景；兼容三值：name(ARTICLE/COMMENT/USERNAME/MESSAGE)、code(同 name)、desc(文章/评论/用户名/私信)，必传")
                                @RequestParam("scene") WordSceneEnum scene,
                                @Parameter(description = "级别；兼容三值：name(HIGH/MID)、code(1/2)、desc(高危/中危)；不传默认 MID（灰度观察，§2.5）")
                                @RequestParam(value = "level", required = false) WordLevelEnum level,
                                @Parameter(description = "名单类型；兼容三值：name(WHITELIST/BLACKLIST)、code(0/1)、desc(白名单/黑名单)，必传")
                                @RequestParam("isBlacklist") WordListTypeEnum isBlacklist) {
        wordlibService.addWord(word, scene, level, isBlacklist, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "修改敏感词", description = "ADMIN；入参仅开放可变更字段（word/level/isBlacklist/status，其余字段沿用原值，服务端字段不接受传入）；变更与词库版本+1同事务并Pub广播；灰度降级 HIGH→MID / 加白走此端点（§2.5）")
    @PutMapping("/word")
    @SaCheckRole(RoleCode.ADMIN)
    public Result<Void> updateWord(@Valid @RequestBody WordUpdateDTO dto) {
        // Service 返回 false 表示修改前后一致：未写库、未推进版本，回执区分"修改成功"与"未修改"
        boolean changed = wordlibService.updateWord(dto, StpUtil.getLoginIdAsLong());
        return changed ? Result.success("修改成功", null) : Result.success("修改前后一致，未修改", null);
    }

    @Operation(summary = "词库误报上报", description = "登录用户；HIGH 同步拒发(70001)的内容未发布无法走 appeal，独立入口回流；Counter 为灰度转正唯一数据源（仅 scene 标签）")
    @PostMapping("/word/misreport")
    @SaCheckLogin
    @RateLimiter(time = 1, timeUnit = TimeUnit.MINUTES, count = 5, keyType = RateLimiter.KeyType.USER)
    public Result<Void> reportMisreport(@Parameter(description = "误报词内容，必传")
                                        @RequestParam("word") String word,
                                        @Parameter(description = "场景；兼容三值：name(ARTICLE/COMMENT/USERNAME/MESSAGE)、code(同 name)、desc(文章/评论/用户名/私信)，必传")
                                        @RequestParam("scene") WordSceneEnum scene,
                                        @Parameter(description = "误报说明；非必传")
                                        @RequestParam(value = "reason", required = false) String reason) {
        wordlibService.reportMisreport(word, scene, reason, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "根据id删除敏感词", description = "ADMIN；物理删除（配置类小表），留 updated_by 审计痕")
    @DeleteMapping("/word/{wordId}")
    @SaCheckRole(RoleCode.ADMIN)
    public Result<Void> deleteWord(@PathVariable("wordId") Long wordId) {
        wordlibService.deleteWord(wordId, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "根据场景和词删除敏感词", description = "ADMIN；按 scene+word 精确匹配物理删除（配置类小表），变更与词库版本+1同事务并Pub广播")
    @DeleteMapping("/word/by-content")
    @SaCheckRole(RoleCode.ADMIN)
    public Result<Void> deleteWordByContent(
            @Parameter(description = "场景；兼容三值：name(ARTICLE/COMMENT/USERNAME/MESSAGE)、code(同 name)、desc(文章/评论/用户名/私信)，必传")
            @RequestParam("scene") WordSceneEnum scene,
            @Parameter(description = "敏感词内容，必传")
            @RequestParam("word") String word) {
        wordlibService.deleteWordByContent(scene, word, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "词库导入", description = "ADMIN；限类型大小、解析不启外部实体（防XXE）、逐词校验")
    @PostMapping("/word/import")
    @SaCheckRole(RoleCode.ADMIN)
    public Result<Void> importWords(@Parameter(description = "词库文件（CSV/Excel），必传")
                                    @RequestParam("file") MultipartFile file,
                                    @Parameter(description = "场景；兼容三值：name(ARTICLE/COMMENT/USERNAME/MESSAGE)、code(同 name)、desc(文章/评论/用户名/私信)，必传")
                                    @RequestParam("scene") WordSceneEnum scene,
                                    @Parameter(description = "级别；兼容三值：name(HIGH/MID)、code(1/2)、desc(高危/中危)，必传")
                                    @RequestParam("level") WordLevelEnum level,
                                    @Parameter(description = "名单类型；兼容三值：name(WHITELIST/BLACKLIST)、code(0/1)、desc(白名单/黑名单)，必传")
                                    @RequestParam("isBlacklist") WordListTypeEnum isBlacklist) {
        wordlibService.importWords(file, scene, level, isBlacklist, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    /**
     * 词库导出：Service 生成 CSV 文本（公式注入防护在 escapeCsvCell 落地），
     * 本层负责加 UTF-8 BOM（Excel 本地打开中文不乱码）并以 text/csv 附件头下发
     */
    @Operation(summary = "词库导出", description = "ADMIN；CSV 下载；公式注入防护（=/+/-/@ 开头单元格前置 '）+ 标准转义")
    @GetMapping("/word/export")
    @SaCheckRole(RoleCode.ADMIN)
    public ResponseEntity<byte[]> exportWords(@Parameter(description = "场景；兼容三值：name(ARTICLE/COMMENT/USERNAME/MESSAGE)、code(同 name)、desc(文章/评论/用户名/私信)，必传")
                                              @RequestParam("scene") WordSceneEnum scene) {
        String csv = wordlibService.exportWords(scene, StpUtil.getLoginIdAsLong());
        // UTF-8 BOM 前置：保证 Excel 直接打开不乱码
        byte[] body = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
        String filename = "wordlib_" + scene.name().toLowerCase() + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }

    @Operation(summary = "词库列表", description = "ADMIN；多条件筛选 + keyword 模糊查询（LIKE 通配符转义防全表扫）")
    @GetMapping("/word/list")
    @SaCheckRole(RoleCode.ADMIN)
    public Result<List<SensitiveWordVO>> listWords(
            @Parameter(description = "场景筛选；兼容三值：name(ARTICLE/COMMENT/USERNAME/MESSAGE)、code(同 name)、desc(文章/评论/用户名/私信)；非必传")
            @RequestParam(value = "scene", required = false) WordSceneEnum scene,
            @Parameter(description = "关键词筛选（LIKE 匹配词内容）；非必传")
            @RequestParam(value = "keyword", required = false) String keyword,
            @Parameter(description = "级别筛选；兼容三值：name(HIGH/MID)、code(1/2)、desc(高危/中危)；非必传")
            @RequestParam(value = "level", required = false) WordLevelEnum level,
            @Parameter(description = "名单类型筛选；兼容三值：name(WHITELIST/BLACKLIST)、code(0/1)、desc(白名单/黑名单)；非必传")
            @RequestParam(value = "isBlacklist", required = false) WordListTypeEnum isBlacklist) {
        return Result.success(
                wordlibService.listWords(scene, keyword, level, isBlacklist, StpUtil.getLoginIdAsLong()));
    }
}
