package com.litisle.litislesearch.controller;

import com.litisle.commoncore.result.Result;
import com.litisle.commonsecurity.utils.SaRoleHelper;
import com.litisle.litislesearch.domain.vo.VectorRebuildResultVO;
import com.litisle.litislesearch.service.VectorRebuildService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 向量全量重建管理端点（批次七 7.1 触发方式定案：管理端点 + 服务方法手动调用）。
 *
 * | 方法 | 路径                                  | 说明                         | 权限               |
 * |------|--------------------------------------|------------------------------|--------------------|
 * | POST | /api/search/admin/rebuild-vectors    | 重建两索引向量并刷新 _meta   | ADMIN / SUPER_ADMIN |
 *
 * 【安全】网关未放行本路径（需登录态），服务内再用 {@link SaRoleHelper#checkAdmin()} 校验角色；
 * 运维也可直接注入 {@link VectorRebuildService} 调用服务方法（不经 HTTP）。
 * 【执行】同步执行文章索引与切片索引重建；提供方未就绪时两者均返回 0（不做任何写操作）。
 *
 * @author litisle
 */
@Tag(name = "搜索服务-向量运维", description = "Embedding 模型接入/切换后的全量重建")
@RestController
@RequestMapping("/api/search/admin")
@RequiredArgsConstructor
public class VectorRebuildAdminController {

    /** 向量全量重建服务（HTTP 端点与手动调用共用） */
    private final VectorRebuildService vectorRebuildService;

    /**
     * 全量重建向量并刷新索引 _meta。
     *
     * @return 两索引成功更新条数
     */
    @Operation(summary = "全量重建向量", description = "仅 ADMIN/SUPER_ADMIN 可调用；重建 article_idx.vec_dense 与 article_chunks.chunk_vector")
    @PostMapping("/rebuild-vectors")
    public Result<VectorRebuildResultVO> rebuildVectors() {
        // 角色校验：ADMIN / SUPER_ADMIN 才能触发全量重建（未登录/越权由 Sa-Token 抛异常统一处理）
        SaRoleHelper.checkAdmin();
        VectorRebuildResultVO result = new VectorRebuildResultVO();
        // 先文章后切片：文章向量是相似推荐主链路，切片向量供 RAG 使用
        result.setArticleUpdated(vectorRebuildService.rebuildArticleVectors());
        result.setChunkUpdated(vectorRebuildService.rebuildChunkVectors());
        return Result.success(result);
    }
}
