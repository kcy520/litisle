package com.litisle.litislecontent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislecontent.cache.FollowRelationCache;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.Book;
import com.litisle.litislecontent.domain.entity.UserLike;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.BookStatusEnum;
import com.litisle.litislecontent.enums.FavoriteTargetTypeEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mapper.BookMapper;
import com.litisle.litislecontent.mapper.UserLikeMapper;
import com.litisle.litislecontent.service.LikeService;
import com.litisle.litislecontent.service.support.ContentStatSupport;
import com.litisle.litislecontent.service.support.LikeEventSupport;
import com.litisle.litislecontent.service.support.LikeRateLimitSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 点赞域服务实现（批次十一 11.8：一期 content 临时承载点赞，social 模块立项后迁移）。
 *
 * 口径：
 * ① 目标可见性——文章：未删 + 非本人须发布态 + PUBLIC 放行 / PRIVATE 拒 / FOLLOWERS 回查关注缓存；
 *    书籍：未删 + 下架仅作者可见。不可见统一抛 LIKE_TARGET_NOT_VISIBLE（不混用 403，也不暴露存在性差异）。
 * ② 作者自赞——硬禁止：目标作者等于操作人一律抛 LIKE_SELF_FORBIDDEN（独立业务码，不是「不计入统计」）。
 * ③ 幂等——点赞靠 uk_user_target 唯一键，重复点赞捕获 DuplicateKeyException 按成功处理且不重复加计数；
 *    取消点赞按条件 delete，影响行数 0 也按成功处理且不扣减计数。
 * ④ 计数——点赞记录与 article_stat/book_stat.like_count 在同一事务内变更：
 *    insert 成功后 +1，delete 影响行数 > 0 才 -1（递减 SQL 带 GREATEST(cnt-1,0) 下限）。
 * ⑤ 跨模块同步（11.14）——文章点赞/取消点赞在事务提交后发布 social.liked / social.unliked 事件，
 *    search 消费更新 article_idx.like_cnt；书籍点赞不发事件（book_idx 无 likeCnt 字段，只落 book_stat）。
 * ⑥ 频控——单用户维度，点赞与取消点赞共用配额，阈值运行时读 yml（LikeRateLimitSupport）。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {

    /** 点赞表 Mapper（写入与条件删除承接方） */
    private final UserLikeMapper userLikeMapper;

    /** 文章 Mapper（文章目标校验） */
    private final ArticleMapper articleMapper;

    /** 书籍 Mapper（书籍目标校验） */
    private final BookMapper bookMapper;

    /** 关注关系缓存（FOLLOWERS 可见性回查，social 不可用 fail-fast） */
    private final FollowRelationCache followRelationCache;

    /** 互动统计写入组件（article_stat/book_stat 点赞计数 +1/-1，与点赞记录同事务） */
    private final ContentStatSupport contentStatSupport;

    /** 点赞事务后事件支撑（afterCommit 发送 social.liked / social.unliked） */
    private final LikeEventSupport likeEventSupport;

    /** 点赞频控组件（单用户维度、阈值配置化） */
    private final LikeRateLimitSupport likeRateLimitSupport;

    /**
     * 点赞目标（幂等 + 可见性校验 + 作者自赞拦截 + 点赞计数同步 + 提交后事件）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void like(Long userId, Integer targetType, Long targetId) {
        // 步骤1：类型白名单（非法值不进后续链路）
        FavoriteTargetTypeEnum type = requireValidType(targetType);
        // 步骤2：单用户频控（点赞与取消点赞共用配额，超限抛 TOO_MANY_REQUESTS）
        likeRateLimitSupport.check(userId);
        // 步骤3：目标可见性 + 作者自赞校验（不可见/自赞各用独立业务码）
        checkTargetLikeable(userId, type, targetId);

        // 步骤4：插入点赞记录（唯一键冲突=已点赞，幂等按成功处理）
        UserLike userLike = new UserLike()
                .setUserId(userId)
                .setTargetType(type.getCode())
                .setTargetId(targetId);
        try {
            userLikeMapper.insert(userLike);
        } catch (DuplicateKeyException e) {
            // 重复点赞：视为成功，但计数早已加过，不重复加、也不重复发事件
            log.info("重复点赞幂等跳过，userId={}, targetType={}, targetId={}", userId, targetType, targetId);
            return;
        }

        // 步骤5：首次点赞成功，统计表点赞数原子 +1（与上面 insert 同一事务，失败一起回滚）
        contentStatSupport.increaseLike(type, targetId);

        // 步骤6：文章点赞在事务提交后发 search 索引同步事件（+1）；
        // 书籍点赞不发事件——search 的 book_idx 没有 likeCnt 字段，书籍点赞暂不影响搜索索引
        publishLikeEventIfArticle(userId, type, targetId, 1);
    }

    /**
     * 取消点赞（幂等 + 点赞计数同步 + 提交后事件）。
     *
     * <p>不做目标可见性校验：内容被删除/转私密/下架后，用户仍应能清理自己的点赞记录。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlike(Long userId, Integer targetType, Long targetId) {
        // 步骤1：类型白名单（非法值直接拒）
        FavoriteTargetTypeEnum type = requireValidType(targetType);
        // 步骤2：单用户频控（与点赞共用配额，防快速反复切换刷写库）
        likeRateLimitSupport.check(userId);

        // 步骤3：按唯一键条件删除（物理删除，与收藏一致）；deleted=0 表示本来就没点赞
        int deleted = userLikeMapper.delete(new LambdaQueryWrapper<UserLike>()
                .eq(UserLike::getUserId, userId)
                .eq(UserLike::getTargetType, type.getCode())
                .eq(UserLike::getTargetId, targetId));

        // 步骤4：只有真正删除了点赞行才递减计数，重复取消不会把计数减成负数
        if (deleted > 0) {
            contentStatSupport.decreaseLike(type, targetId);
            // 步骤5：文章取消点赞在事务提交后发 search 索引同步事件（-1），书籍不发事件（同点赞口径）
            publishLikeEventIfArticle(userId, type, targetId, -1);
        }
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
     * 点赞目标校验：目标必须可见，且作者不能给自己点赞。
     *
     * @param userId   点赞用户ID
     * @param type     目标类型枚举
     * @param targetId 目标ID
     * @throws BizException LIKE_TARGET_NOT_VISIBLE 目标不存在或不可见 /
     *                      LIKE_SELF_FORBIDDEN 作者给自己点赞 /
     *                      REMOTE_SERVICE_ERROR social 不可用（fail-fast，由 FollowRelationCache 抛出）
     */
    private void checkTargetLikeable(Long userId, FavoriteTargetTypeEnum type, Long targetId) {
        if (type == FavoriteTargetTypeEnum.ARTICLE) {
            checkArticleLikeable(userId, targetId);
        } else {
            checkBookLikeable(userId, targetId);
        }
    }

    /**
     * 文章目标校验（对齐详情页可见性判定链 + 作者自赞硬禁止）。
     *
     * @param userId    点赞用户ID
     * @param articleId 文章ID
     */
    private void checkArticleLikeable(Long userId, Long articleId) {
        Article article = articleMapper.selectById(articleId);
        // 已删/不存在：按不可见处理（统一 LIKE_TARGET_NOT_VISIBLE）
        if (article == null || Objects.equals(article.getDelFlag(), DelFlag.DELETED)) {
            throw new BizException(ErrorCode.LIKE_TARGET_NOT_VISIBLE);
        }
        // 作者禁止给自己点赞：独立业务码硬拦截（不进入后续可见性分支）
        if (article.getAuthorId().equals(userId)) {
            throw new BizException(ErrorCode.LIKE_SELF_FORBIDDEN);
        }
        // 非本人只能看到已发布文章（草稿/待审/驳回/下架一律按不可见）
        if (article.getStatus() != ArticleStatusEnum.PUBLISHED) {
            throw new BizException(ErrorCode.LIKE_TARGET_NOT_VISIBLE);
        }
        // 可见性判定：PUBLIC 放行 / PRIVATE 拒 / FOLLOWERS 回查关注缓存
        VisibilityEnum visibility = article.getVisibility();
        switch (visibility) {
            case PUBLIC -> {
                // 公开：放行
            }
            case FOLLOWERS -> {
                // 仅关注者可见：回查关注关系（缓存 miss 按 false 处理，宁严勿松）；
                // social 不可用由 isFollowing 抛 REMOTE_SERVICE_ERROR，fail-fast 不静默放行
                if (!followRelationCache.isFollowing(article.getAuthorId(), userId)) {
                    throw new BizException(ErrorCode.LIKE_TARGET_NOT_VISIBLE);
                }
            }
            default -> {
                // PRIVATE（仅自己）：非本人按不可见处理
                throw new BizException(ErrorCode.LIKE_TARGET_NOT_VISIBLE);
            }
        }
    }

    /**
     * 书籍目标校验（下架书籍仅作者可见 + 作者自赞硬禁止）。
     *
     * @param userId 点赞用户ID
     * @param bookId 书籍ID
     */
    private void checkBookLikeable(Long userId, Long bookId) {
        Book book = bookMapper.selectById(bookId);
        // 已删/不存在：按不可见处理
        if (book == null || Objects.equals(book.getDelFlag(), DelFlag.DELETED)) {
            throw new BizException(ErrorCode.LIKE_TARGET_NOT_VISIBLE);
        }
        // 作者禁止给自己点赞：独立业务码硬拦截
        if (book.getAuthorId().equals(userId)) {
            throw new BizException(ErrorCode.LIKE_SELF_FORBIDDEN);
        }
        // 下架书籍仅作者可见（非作者点赞按不可见处理）；连载中/已完结放行
        if (book.getStatus() == BookStatusEnum.TAKEN_DOWN) {
            throw new BizException(ErrorCode.LIKE_TARGET_NOT_VISIBLE);
        }
    }

    /**
     * 仅在文章目标时注册提交后事件（书籍点赞不进入搜索索引，不发事件）。
     *
     * <p>书籍理由：search 的 book_idx 索引结构没有 likeCnt 字段，书籍流排序使用 content 侧
     * book_stat.like_count（11.2 已切读），故无需为点赞改动索引结构，也不会造成搜索结果页
     * 书籍点赞数陈旧的问题。</p>
     *
     * @param userId   操作用户ID
     * @param type     目标类型
     * @param targetId 目标ID
     * @param delta    点赞计数增量（1/-1）
     */
    private void publishLikeEventIfArticle(Long userId, FavoriteTargetTypeEnum type, Long targetId, int delta) {
        if (type == FavoriteTargetTypeEnum.ARTICLE) {
            //发送点赞事件
            likeEventSupport.publishLikeEventAfterCommit(userId, targetId, delta);
        } else {
            log.debug("书籍点赞不发布索引事件（book_idx 无 likeCnt），userId={}, bookId={}, delta={}",
                    userId, targetId, delta);
        }
    }
}
