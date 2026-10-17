package com.litisle.litislecontent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dto.content.ArticleCounterUpdateDTO;
import com.litisle.litisleapi.dto.content.ArticleImageRefDTO;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.ArticleImage;
import com.litisle.litislecontent.mapper.ArticleImageMapper;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.service.ContentInternalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * content 内部契约实现（机审回查/配图反查/计数回写，板块三 [3.2]）。
 *
 * 【回查同源口径】body 返回入库 sanitize 后 contentHtml（发布侧 assemble(title, contentHtml)
 * 同源），title 返回入库剥标签标题——review 重算 hash 与事件 contentHash 必然一致；
 * 【fail-fast】回查/反查/回写均文章不存在即抛（机审核心读依赖，静默降级=误判无内容可审）；
 * 【del_flag】三方法均显式补 del_flag=0（同 id 复活场景，4-4.2 纪律）
 *
 * @author litisle
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContentInternalServiceImpl implements ContentInternalService {

    private final ArticleMapper articleMapper;

    private final ArticleImageMapper articleImageMapper;

    /**
     * 机审回查内容正文：bizType 分派，文章查库组装 ContentDTO（回查同源口径）
     */
    @Override
    public ContentDTO getContent(Integer bizType, Long bizId) {
        // 步骤1：bizType 分派（评论模块未落地，按契约口径返回 COMMENT_NOT_FOUND 由消费端 ack 丢弃）
        if (ModerationConstant.BIZ_TYPE_ARTICLE != bizType) {
            throw new BizException(ErrorCode.COMMENT_NOT_FOUND);
        }
        // 步骤2：查活跃文章（del_flag=0 显式补齐，CAS/回查纪律）
        Article article = articleMapper.selectOne(new LambdaQueryWrapper<Article>()
                .eq(Article::getId, bizId)
                .eq(Article::getDelFlag, 0));
        if (article == null) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        // 步骤3：组装 ContentDTO（body=入库 sanitize 后 contentHtml 与发布送审同源；
        // ownerId=authorId 申诉 owner 校验/机审 REJECT 处罚上下文来源）
        ContentDTO dto = new ContentDTO();
        dto.setBizType(bizType);
        dto.setBizId(bizId);
        dto.setTitle(article.getTitle());
        dto.setBody(article.getContentHtml());
        dto.setOwnerId(article.getAuthorId());
        dto.setStatus(article.getStatus().getCode());
        dto.setVisibility(article.getVisibility().getCode());
        dto.setContentHash(article.getContentHash());
        return dto;
    }

    /**
     * 反查文章配图引用列表：文章存在性 fail-fast + article_image 按登记顺序返回
     */
    @Override
    public List<ArticleImageRefDTO> listArticleImages(Long articleId) {
        // 步骤1：文章存在性 fail-fast（回查文章不存在=脏复审请求，消费端 ack 丢弃）
        Long count = articleMapper.selectCount(new LambdaQueryWrapper<Article>()
                .eq(Article::getId, articleId)
                .eq(Article::getDelFlag, 0));
        if (count == null || count == 0) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        // 步骤2：查配图引用（position_order 升序=发布登记顺序；无活跃配图返回空列表）
        List<ArticleImage> images = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                .eq(ArticleImage::getArticleId, articleId)
                .orderByAsc(ArticleImage::getPositionOrder));
        // 步骤3：组装引用 DTO（fileMetaId 置 null：review 单图支线经 FileClient.getFileInfo 自行解析，
        // 复审聚合仅依赖 fileId 取图重算 sha256，不依赖本字段）
        return images.stream().map(img -> {
            ArticleImageRefDTO ref = new ArticleImageRefDTO();
            ref.setFileId(img.getFileId());
            return ref;
        }).toList();
    }

    /**
     * 按配图 fileId 反查引用该图的未删除文章 ID 列表（图片复审再聚合用，板块八 [8.2]）
     * 聚合判定收口 review 消费端，本方法仅提供反查数据；无引用返回空列表（非 fail-fast，
     * 空列表=真实无引用，与降级失败语义区分：降级走降级工厂异常穿透）
     */
    @Override
    public List<Long> listArticleIdsByFileId(String fileId) {
        // 步骤1：fileId 必填校验（服务间契约 fail-fast）
        if (fileId == null || fileId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 步骤2：按 idx_file_id 索引反查引用登记（去重，同一图单文内可多位置登记）
        List<Long> articleIds = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                        .eq(ArticleImage::getFileId, fileId))
                .stream().map(ArticleImage::getArticleId).distinct().toList();
        if (articleIds.isEmpty()) {
            return List.of();
        }
        // 步骤3：过滤已删除文章（del_flag=0），已删文章不参与复审聚合
        return articleMapper.selectList(new LambdaQueryWrapper<Article>()
                        .in(Article::getId, articleIds)
                        .eq(Article::getDelFlag, 0)
                        .select(Article::getId))
                .stream().map(Article::getId).toList();
    }

    /**
     * 计数回写：幂等绝对值 SET（仅非空字段更新），CAS 补 del_flag=0
     */
    @Override
    public void updateCounters(ArticleCounterUpdateDTO dto) {
        // 步骤1：articleId 必填校验（服务间契约 fail-fast）
        if (dto == null || dto.getArticleId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 步骤2：绝对值 SET（MP update-strategy not_null：null 字段不进 SET 子句）；
        // rows=0=文章不存在 → ARTICLE_NOT_FOUND（幂等 SET 无并发分支，social 单点回写规避覆盖）
        LambdaUpdateWrapper<Article> wrapper = new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, dto.getArticleId())
                .eq(Article::getDelFlag, 0)
                .set(dto.getViewCnt() != null, Article::getViewCnt, dto.getViewCnt())
                .set(dto.getLikeCnt() != null, Article::getLikeCnt, dto.getLikeCnt())
                .set(dto.getCommentCnt() != null, Article::getCommentCnt, dto.getCommentCnt());
        int rows = articleMapper.update(null, wrapper);
        if (rows == 0) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        log.info("计数回写完成，articleId={}，view={}，like={}，comment={}",
                dto.getArticleId(), dto.getViewCnt(), dto.getLikeCnt(), dto.getCommentCnt());
    }
}