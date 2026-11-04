package com.litisle.litislecontent.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litislecontent.domain.dto.DraftSaveDTO;
import com.litisle.litislecontent.domain.vo.DraftCardVO;
import com.litisle.litislecontent.domain.vo.DraftDetailVO;
import com.litisle.litislecontent.domain.vo.DraftSaveVO;
import com.litisle.litislecontent.service.DraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.litisle.litislecontent.constant.ContentLimitConstant.*;

/**
 * REST 接口层——草稿域：
 *
 * | 方法   | 路径          | 说明                                        | 权限 |
 * |--------|--------------|---------------------------------------------|------|
 * | POST   | /draft/save  | 草稿保存（幂等，baseVersion 乐观锁）         | 登录（仅本人） |
 * | GET    | /draft/list  | 我的草稿列表                                 | 登录（仅本人） |
 * | GET    | /draft/{id}  | 草稿详情（含 content_html）                  | 登录（仅本人） |
 * | DELETE | /draft/{id}  | 删除草稿                                     | 登录（仅本人） |
 */
@Tag(name = "内容服务-草稿", description = "草稿自动保存/快照查询")
@RestController
@RequestMapping("/api/content/draft")
@RequiredArgsConstructor
public class DraftController {

    private final DraftService draftService;

    /**
     * 草稿保存（幂等；baseVersion 落后 draft_version 拒绝——乐观锁防丢更新）
     */
    @Operation(summary = "草稿保存", description = "仅本人草稿；限频 10/60s 防高频刷库；版本冲突返回 ARTICLE_DRAFT_CONFLICT")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = DRAFT_SAVE_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @PostMapping("/save")
    public Result<DraftSaveVO> save(@RequestBody DraftSaveDTO dto) {
        // Service 内归属校验 + 乐观锁比对 + 单行覆盖保存
        return Result.success(draftService.save(dto, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 我的草稿列表（按 author_id 归属过滤）
     */
    @Operation(summary = "我的草稿列表", description = "仅本人草稿卡片，不含正文大字段")
    @SaCheckLogin
    @GetMapping("/list")
    public Result<List<DraftCardVO>> listMy() {
        List<DraftCardVO> draftCardVOS = draftService.listMy(StpUtil.getLoginIdAsLong());
        return Result.success(draftCardVOS);
    }

    /**
     * 草稿详情（含 content_html，编辑器回显；非本人按不存在处理）
     */
    @Operation(summary = "草稿详情", description = "仅本人可见")
    @SaCheckLogin
    @GetMapping("/{id}")
    public Result<DraftDetailVO> getDetail(@PathVariable("id") Long id) {
        return Result.success(draftService.getDetail(id, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 删除草稿（仅本人；废弃孤儿图片由 file 对账链路兜底）
     */
    @Operation(summary = "删除草稿", description = "仅本人")
    @SaCheckLogin
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        draftService.delete(id, StpUtil.getLoginIdAsLong());
        return Result.success();
    }
}