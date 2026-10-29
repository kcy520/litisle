package com.litisle.litislecontent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.constant.PageConstant;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislecontent.cache.FollowRelationCache;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.Book;
import com.litisle.litislecontent.domain.entity.Favorite;
import com.litisle.litislecontent.domain.vo.FavoriteItemVO;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.BookStatusEnum;
import com.litisle.litislecontent.enums.FavoriteTargetTypeEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mapper.BookMapper;
import com.litisle.litislecontent.mapper.FavoriteMapper;
import com.litisle.litislecontent.service.FavoriteService;
import com.litisle.litislecontent.service.support.FavoriteStateSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 收藏域服务实现（阶段16）。
 *
 * 口径：
 * ① 写入前目标校验（同域，对齐文章详情可见性口径）——文章：未删 + 非本人须发布态 +
 *    PUBLIC 放行 / PRIVATE 拒 / FOLLOWERS 回查关注缓存；书籍：未删 + 下架仅作者可收藏；
 *    不存在/不可见统一按 NOT_FOUND 报错（与详情口径一致，不暴露存在性）；
 * ② 幂等——收藏靠 uk_user_target 唯一键，重复收藏捕获 DuplicateKeyException 按成功处理；
 *    取消收藏按条件 delete，影响行数 0 也按成功处理；
 * ③ 分页——Page.of 裸分页（SQL 已按 create_time desc 显式排序，不叠加 PageQuery 默认 update_time 排序）；
 * ④ 允许作者收藏自己的作品（一期无收藏计数，不存在刷计数问题）。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    /** 收藏表 Mapper（写入与分页承接方） */
    private final FavoriteMapper favoriteMapper;

    /** 文章 Mapper（文章目标校验） */
    private final ArticleMapper articleMapper;

    /** 书籍 Mapper（书籍目标校验） */
    private final BookMapper bookMapper;

    /** 关注关系缓存（FOLLOWERS 可见性回查，social 不可用 fail-fast） */
    private final FollowRelationCache followRelationCache;

    /** 收藏状态查询组件（等值 count 单点收口，与写入校验依赖分离防循环依赖） */
    private final FavoriteStateSupport favoriteStateSupport;

    /**
     * 收藏目标（幂等）。
     * <p>流程：类型白名单解析 → 目标可见性校验 → insert（唯一键冲突按成功处理）</p>
     */
    @Override
    public void favorite(Long userId, Integer targetType, Long targetId) {
        // 类型白名单：非法值不进后续链路
        FavoriteTargetTypeEnum type = requireValidType(targetType);
        // 同域校验：不允许收藏自己看不到的内容（不存在/不可见 → NOT_FOUND）
        checkTargetReadable(userId, type, targetId);

        Favorite favorite = new Favorite()
                .setUserId(userId)
                .setTargetType(type.getCode())
                .setTargetId(targetId);
        try {
            favoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            // uk_user_target 冲突=已收藏，幂等按成功处理
            log.info("重复收藏幂等跳过，userId={}, targetType={}, targetId={}", userId, targetType, targetId);
        }
    }

    /**
     * 取消收藏（幂等）。
     * <p>按 uk_user_target 条件 delete；未收藏影响行数 0 也按成功处理</p>
     */
    @Override
    public void unfavorite(Long userId, Integer targetType, Long targetId) {
        // 类型白名单：非法值直接拒
        FavoriteTargetTypeEnum type = requireValidType(targetType);
        // 条件删除（不带影响行数断言——未收藏也成功，幂等）
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getTargetType, type.getCode())
                .eq(Favorite::getTargetId, targetId));
    }

    /**
     * 我的收藏分页。
     * <p>targetType null=全部混合时间线；Page.of 裸分页（排序由 SQL 显式承接）</p>
     */
    @Override
    public Page<FavoriteItemVO> pageMyFavorites(Long userId, Integer targetType, long pageNo, long pageSize) {
        // 类型白名单：null 放行（全部），非空但非法抛 PARAM_ERROR
        Integer typeCode = resolveOptionalType(targetType);
        // 分页参数钳制（Service 直收 long 入参，不经过 PageQuery 兜底）
        long safePageNo = Math.max(pageNo, PageConstant.DEFAULT_PAGE_NO);
        long safePageSize = Math.min(Math.max(pageSize, 1), PageConstant.MAX_PAGE_SIZE);
        // SQL 层完成过滤/排序/分页/total；空历史返回空页
        return favoriteMapper.selectMyFavorites(Page.of(safePageNo, safePageSize), userId, typeCode);
    }

    /**
     * 收藏状态查询（匿名返回 false）。
     * <p>等值查询委托 FavoriteStateSupport 单点收口</p>
     */
    @Override
    public boolean isFavorited(Long userId, Integer targetType, Long targetId) {
        // 匿名无身份，一律未收藏
        if (userId == null) {
            return false;
        }
        // 类型白名单：状态查询对非法值报错（是显式查询不是过滤场景）
        FavoriteTargetTypeEnum type = requireValidType(targetType);
        return favoriteStateSupport.isFavorited(userId, type.getCode(), targetId);
    }

    /**
     * 类型白名单强制解析：非法值抛 PARAM_ERROR。
     *
     * @param targetType 请求入参类型编码
     * @return 对应枚举（不会为 null）
     */
    private FavoriteTargetTypeEnum requireValidType(Integer targetType) {
        FavoriteTargetTypeEnum type = FavoriteTargetTypeEnum.resolve(targetType);
        if (type == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        return type;
    }

    /**
     * 类型白名单可选解析：null=全部放行，非空但非法抛 PARAM_ERROR。
     *
     * @param targetType 请求入参类型编码
     * @return 校验后的类型编码（null=全部）
     */
    private Integer resolveOptionalType(Integer targetType) {
        if (targetType == null) {
            return null;
        }
        return requireValidType(targetType).getCode();
    }

    /**
     * 目标可见性校验（写入前同域判定，不允许收藏自己看不到的内容）。
     * <p>不存在/不可见统一按 NOT_FOUND 报错，与详情可见性口径一致（不暴露存在性）</p>
     *
     * @param userId   收藏用户ID
     * @param type     目标类型枚举
     * @param targetId 目标ID
     * @throws BizException ARTICLE_NOT_FOUND / BOOK_NOT_FOUND 目标不存在或不可见 /
     *                      REMOTE_SERVICE_ERROR social 不可用（fail-fast，由 FollowRelationCache 抛出）
     */
    private void checkTargetReadable(Long userId, FavoriteTargetTypeEnum type, Long targetId) {
        if (type == FavoriteTargetTypeEnum.ARTICLE) {
            checkArticleReadable(userId, targetId);
        } else {
            checkBookReadable(userId, targetId);
        }
    }

    /**
     * 文章目标校验（对齐 getDetail 可见性判定链）。
     *
     * @param userId    收藏用户ID
     * @param articleId 文章ID
     */
    private void checkArticleReadable(Long userId, Long articleId) {
        Article article = articleMapper.selectById(articleId);
        // 已删/不存在：按文章不存在处理
        if (article == null || Objects.equals(article.getDelFlag(), 1)) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        boolean owner = article.getAuthorId().equals(userId);
        // 作者收藏自己：直接放行（一期无收藏计数，不存在刷计数问题）
        if (owner) {
            return;
        }
        // 非本人须发布态（草稿/待审/驳回/下架一律按不存在）
        if (article.getStatus() != ArticleStatusEnum.PUBLISHED) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        // 可见性判定：PUBLIC 放行 / PRIVATE 拒 / FOLLOWERS 回查关注缓存
        VisibilityEnum visibility = article.getVisibility();
        switch (visibility) {
            case PUBLIC -> {
                // 公开：放行
            }
            case FOLLOWERS -> {
                // 仅关注者：回查关注关系（缓存 miss 按 false 处理，宁严勿松）；
                // social 不可用由 isFollowing 抛 REMOTE_SERVICE_ERROR，fail-fast 不静默放行
                if (!followRelationCache.isFollowing(article.getAuthorId(), userId)) {
                    throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
                }
            }
            default -> {
                // PRIVATE（仅自己）：非本人按不存在，不暴露存在性
                throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
            }
        }
    }

    /**
     * 书籍目标校验。
     *
     * @param userId 用户ID
     * @param bookId 书籍ID
     */
    private void checkBookReadable(Long userId, Long bookId) {
        Book book = bookMapper.selectById(bookId);
        // 已删/不存在：按书籍不存在处理
        if (book == null || Objects.equals(book.getDelFlag(), 1)) {
            throw new BizException(ErrorCode.BOOK_NOT_FOUND);
        }
        // 下架书籍仅作者可收藏；其他状态（连载中/已完结）放行
        if (book.getStatus() == BookStatusEnum.TAKEN_DOWN && !book.getAuthorId().equals(userId)) {
            throw new BizException(ErrorCode.BOOK_NOT_FOUND);
        }
    }
}
