package com.litisle.litislecontent.controller.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.content.ArticleCounterUpdateDTO;
import com.litisle.litisleapi.dto.content.ArticleImageRefDTO;
import com.litisle.litisleapi.dto.content.ArticleIndexDocDTO;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.feign.ContentClient;
import com.litisle.litislecontent.service.ContentInternalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ContentClient 契约实现（Provider 端，仅供服务间 Feign 调用，板块三 [3.2]）
 *
 * 【设计说明】对齐 file 模块 FileFeignProviderController 范例：
 * 1. implements ContentClient——路径由接口映射注解决定，实现类无需重复标注；
 *    路径前缀 /api/feign/content 网关未路由，外部请求不可达，仅服务间可用
 * 2. 不加 @SaCheckLogin：Feign 服务端线程无用户会话，鉴权由「网关不路由 + 仅服务内可达」保证
 * 3. 只做「日志留痕 + 委托 ContentInternalService + 包装 Result」，不写业务逻辑；
 *    BizException 由全局处理器转 Result.fail，Feign 正常解码不触发降级（fail-fast 语义，
 *    静默降级会让 review 误判「无内容可审」——降级工厂仅网络故障时触发）
 *
 * @author litisle
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ContentFeignProviderController implements ContentClient {

    private final ContentInternalService contentInternalService;

    /**
     * 回查内容正文：委托 Service（机审核心读依赖，fail-fast）
     */
    @Override
    public Result<ContentDTO> getContent(Integer bizType, Long bizId) {
        log.info("远程api调用getContent bizType:{} bizId:{}", bizType, bizId);
        return Result.success(contentInternalService.getContent(bizType, bizId));
    }

    /**
     * 反查文章配图引用列表：委托 Service（配图复审核心读依赖，fail-fast）
     */
    @Override
    public Result<List<ArticleImageRefDTO>> listArticleImages(Long articleId) {
        log.info("远程api调用listArticleImages articleId:{}", articleId);
        return Result.success(contentInternalService.listArticleImages(articleId));
    }

    /**
     * 按配图 fileId 反查引用文章：委托 Service（图片复审再聚合核心读依赖，fail-fast）
     */
    @Override
    public Result<List<Long>> listArticleIdsByFileId(String fileId) {
        log.info("远程api调用listArticleIdsByFileId fileId:{}", fileId);
        return Result.success(contentInternalService.listArticleIdsByFileId(fileId));
    }

    /**
     * 计数回写：委托 Service（social 聚合侧单点回写，幂等绝对值 SET）
     */
    @Override
    public Result<Void> updateCounters(ArticleCounterUpdateDTO dto) {
        log.info("远程api调用updateCounters articleId:{} view:{} like:{} comment:{}",
                dto == null ? null : dto.getArticleId(),
                dto == null ? null : dto.getViewCnt(),
                dto == null ? null : dto.getLikeCnt(),
                dto == null ? null : dto.getCommentCnt());
        contentInternalService.updateCounters(dto);
        return Result.success();
    }

    /**
     * 回查文章建索引全量字段：委托 Service（search 索引同步核心读依赖，fail-fast）
     */
    @Override
    public Result<ArticleIndexDocDTO> getArticleIndexDoc(Long articleId) {
        log.info("远程api调用getArticleIndexDoc articleId:{}", articleId);
        return Result.success(contentInternalService.getArticleIndexDoc(articleId));
    }
}
