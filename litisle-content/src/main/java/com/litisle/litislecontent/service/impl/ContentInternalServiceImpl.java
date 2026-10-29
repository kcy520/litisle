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
 * content 内部服务实现（机审回查/配图反查/计数回写，板块三 [3.2]）。
 *
 * 【同源保证】正文返回入库净化后的 contentHtml（与发布送审时用的同一份）、标题返回剥标签后的
 * 入库标题——这样 review 端重算的指纹和事件里的指纹一定一致；
 * 【不静默降级】回查/反查/回写遇到文章不存在就直接报错（机审靠这些数据才能审，
 * 静默返回空会导致误判"无内容可审"）；
 * 【软删过滤】三个方法都显式带 del_flag=0 条件，防止已删文章被查出来（同 id 复活场景，4-4.2 纪律）
 *
 * @author litisle
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContentInternalServiceImpl implements ContentInternalService {

    /** 文章 Mapper（机审回查/计数回写数据源） */
    private final ArticleMapper articleMapper;

    /** 文章配图 Mapper（配图引用反查数据源） */
    private final ArticleImageMapper articleImageMapper;

    /**
     * 机审回查内容正文。
     *
     * 作用：
     * review 机审前按 bizType+bizId 回查待审内容原文，用于重算文本指纹与送审比对。
     * 仅支持文章域；文章不存在（含已软删）fail-fast 抛 ARTICLE_NOT_FOUND。
     * body 返回入库 sanitize 后 contentHtml，保证 review 重算 hash 与事件 contentHash 一致。
     *
     * @param bizType 内容业务类型（1-文章）
     * @param bizId   内容业务ID（文章ID）
     * @return 内容 DTO（标题/正文/归属人/状态/可见性/指纹）
     */
    @Override
    public ContentDTO getContent(Integer bizType, Long bizId) {

        /**
         * 机审回查流程：
         *
         * ① 域分派：仅支持文章域（评论模块未落地，按契约码返回由消费端丢弃）。
         * ② 查文章：不存在/已软删直接报错（机审核心读依赖，不静默降级）。
         * ③ 组装返回：正文=入库后净化版，与发布送审同源——
         *    保证审核端重算指纹与事件指纹一致；归属人供处罚/申诉用。
         */

        // 步骤1：bizType 分派（评论模块未落地，按约定返回 COMMENT_NOT_FOUND 由消费端丢弃）
        if (ModerationConstant.BIZ_TYPE_ARTICLE != bizType) {
            throw new BizException(ErrorCode.COMMENT_NOT_FOUND);
        }
        // 步骤2：查活跃文章（显式带 del_flag=0 条件，只查未删除的）
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
     * 反查文章配图引用列表。
     *
     * 作用：
     * 图片复审链路按文章ID反查其全部配图引用，review 逐张取图重算 sha256 后聚合。
     * 文章不存在（含已软删）fail-fast 抛 ARTICLE_NOT_FOUND；无活跃配图返回空列表。
     *
     * @param articleId 文章ID
     * @return 配图引用 DTO 列表（仅 fileId，按 position_order 升序）
     */
    @Override
    public List<ArticleImageRefDTO> listArticleImages(Long articleId) {

        /**
         * 配图反查流程：
         *
         * ① 文章存在性校验：不存在/已软删直接报错（脏复审请求）。
         * ② 查配图：按登记顺序（position_order）升序返回。
         * ③ 组装返回：仅回 fileId（review 端自行取图重算指纹聚合）。
         */

        // 步骤1：文章存在性校验（文章不存在说明是脏的复审请求，直接报错，消费端丢弃）
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
     * 聚合判定由 review 消费端负责，本方法只提供反查数据；无引用返回空列表
     * （空列表=真的没有文章引用它，不是出错；真正的调用失败会直接抛异常，两者含义不同）
     *
     * @param fileId 配图文件ID
     * @return 引用该图的未删除文章 ID 列表（无引用返回空列表）
     */
    @Override
    public List<Long> listArticleIdsByFileId(String fileId) {

        /**
         * 按图反查引用流程（图片复审再聚合用）：
         *
         * ① 入参校验：fileId 必填。
         * ② 反查引用：按索引查出引用该图的文章 ID（同文多位置登记去重）。
         * ③ 过滤已删文章：只保留未删除的，已删文章不参与复审聚合。
         */

        // 步骤1：fileId 必填校验（服务间调用，参数不合法直接报错）
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
     * 计数回写。
     *
     * 作用：
     * social 侧互动（浏览/点赞/评论）发生后，回写文章计数的绝对值。
     * 仅更新非空字段（null 字段不进 SET 子句）；文章不存在（含已软删）抛 ARTICLE_NOT_FOUND。
     *
     * 【跨服务契约（阶段10 留档）】viewCnt 当前禁用，social 侧必须永远传 null：
     * PV 归属 content 内部闭环（ViewCounterSupport INCR pending 键 + ViewCountFlushJob
     * SQL 原子自增刷回），本方法对 viewCnt 是绝对值 SET，social 若传值会覆盖丢增量；
     * social 仅允许回写 likeCnt/commentCnt（互动计数 social 侧权威）。
     *
     * @param dto 计数回写入参（articleId + 可空的 viewCnt/likeCnt/commentCnt 绝对值；viewCnt 禁用传 null）
     */
    @Override
    public void updateCounters(ArticleCounterUpdateDTO dto) {

        /**
         * 计数回写流程（social 互动数据同步）：
         *
         * ① 入参校验：文章 ID 必填。
         * ② 绝对值回写：只更新传了的字段（null 不更新）；
         *    注意：浏览量(viewCnt)当前禁用——它由内部定时任务自增刷回，
         *    social 若传值会覆盖丢增量，只能传点赞/评论数。
         * ③ 兑底校验：文章不存在/已软删时更新不生效，报错。
         */

        // 步骤1：articleId 必填校验（服务间契约 fail-fast）
        if (dto == null || dto.getArticleId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 步骤2：按传了哪些字段更新哪些（MyBatis-Plus not_null 策略：null 字段不进 SET 子句）；
        // 更新行数=0 说明文章不存在 → 报 ARTICLE_NOT_FOUND（纯绝对值覆盖写，由 social 单点回写避免互相覆盖）
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
        // TODO（social 落地后接线，阶段14 D10 预留）：likeCnt/commentCnt 回写成功后，
        //  触发该文章热度分增量重算（ZADD 单成员更新，复用 FeedHotRankSupport#computeScore），
        //  消除 5min 全量灌榜造成的榜单滞后；
        //  回收项已登记 docs/next/Temporarily_not_implemented.md「推荐流热度榜 social 信号补齐与调参」
    }
}