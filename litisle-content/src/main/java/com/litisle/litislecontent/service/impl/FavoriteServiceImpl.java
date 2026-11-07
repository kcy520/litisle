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
import com.litisle.litislecontent.service.support.ContentStatSupport;
import com.litisle.litislecontent.service.support.FavoriteStateSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * ④ 允许作者收藏自己的作品，但统计口径排除作者自收藏（批次十一 11.9）——收藏记录照常写入，
 *    «仅» article_stat/book_stat 的 collect_count 不计这一条，避免作者自己刷收藏数；
 *    文章作者取 article.author_id、书籍作者取 book.author_id。
 * ⑤ 计数同步（批次十一 11.1）——收藏记录与 stat 计数在同一事务：insert 成功后 +1、
 *    delete 影响行数 > 0 才 -1；重复收藏/重复取消按幂等成功处理且不重复变更计数。
 *    11.9 起加/减都在同一处判定「是否作者自收藏」，不会出现「收藏不计数、取消却扣 1」的不对称。
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

    /** 互动统计写入组件（批次十一 11.1：收藏计数 +1/-1，与收藏记录同事务） */
    private final ContentStatSupport contentStatSupport;

    /**
     * 收藏目标（幂等 + 收藏计数同步）。
     * <p>流程：类型白名单解析 → 目标可见性校验 → insert（唯一键冲突按成功处理）→ 统计表 +1</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void favorite(Long userId, Integer targetType, Long targetId) {
        // 类型白名单：非法值不进后续链路
        FavoriteTargetTypeEnum type = requireValidType(targetType);
        // 同域校验：不允许收藏自己看不到的内容（不存在/不可见 → NOT_FOUND）；
        // 同时拿到目标作者ID，用于判断本次是否为「作者自收藏」
        Long authorId = checkTargetReadable(userId, type, targetId);

        Favorite favorite = new Favorite()
                .setUserId(userId)
                .setTargetType(type.getCode())
                .setTargetId(targetId);
        try {
            favoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            // uk_user_target 冲突=已收藏，幂等按成功处理；已收藏说明计数早已加过，直接返回不重复加
            log.info("重复收藏幂等跳过，userId={}, targetType={}, targetId={}", userId, targetType, targetId);
            return;
        }
        // 作者自收藏：收藏记录已经写入（前端按钮态正常），但统计不收这一条，避免作者自己刷收藏数
        if (!Objects.equals(authorId, userId)) {
            // 普通用户收藏：统计表收藏数原子 +1（与上面 insert 同一事务，失败则一起回滚）
            contentStatSupport.increaseCollect(type, targetId);
        }
    }

    /**
     * 取消收藏（幂等 + 收藏计数同步）。
     * <p>按 uk_user_target 条件 delete；未收藏影响行数 0 也按成功处理且不递减计数</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unfavorite(Long userId, Integer targetType, Long targetId) {
        // 类型白名单：非法值直接拒
        FavoriteTargetTypeEnum type = requireValidType(targetType);
        // 删除前先取目标作者ID：与收藏时的判定来源一致，保证「加了多少就减多少」
        Long authorId = resolveTargetAuthor(type, targetId);
        // 条件删除（不带影响行数断言——未收藏也成功，幂等）；deleted=0 表示本来就没收藏
        int deleted = favoriteMapper.delete(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getTargetType, type.getCode())
                .eq(Favorite::getTargetId, targetId));
        // 仅真正删除了收藏记录才递减计数；连点取消第二次 deleted=0，不会把计数减成负数；
        // 作者自收藏当时没加过计数，取消时同样不递减（对称）
        if (deleted > 0 && !Objects.equals(authorId, userId)) {
            contentStatSupport.decreaseCollect(type, targetId);
        }
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
     * @return 目标作者ID（文章取 article.author_id、书籍取 book.author_id，供调用方判定作者自收藏）
     * @throws BizException ARTICLE_NOT_FOUND / BOOK_NOT_FOUND 目标不存在或不可见 /
     *                      REMOTE_SERVICE_ERROR social 不可用（fail-fast，由 FollowRelationCache 抛出）
     */
    private Long checkTargetReadable(Long userId, FavoriteTargetTypeEnum type, Long targetId) {
        if (type == FavoriteTargetTypeEnum.ARTICLE) {
            return checkArticleReadable(userId, targetId);
        }
        return checkBookReadable(userId, targetId);
    }

    /**
     * 文章目标校验（对齐 getDetail 可见性判定链）。
     *
     * @param userId    收藏用户ID
     * @param articleId 文章ID
     * @return 文章作者ID（article.author_id）
     */
    private Long checkArticleReadable(Long userId, Long articleId) {
        Article article = articleMapper.selectById(articleId);
        // 已删/不存在：按文章不存在处理
        if (article == null || Objects.equals(article.getDelFlag(), 1)) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        boolean owner = article.getAuthorId().equals(userId);
        // 作者收藏自己：允许写入收藏记录，但调用方不会再给统计 +1（11.9 排除作者自收藏）
        if (owner) {
            return article.getAuthorId();
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
        return article.getAuthorId();
    }

    /**
     * 书籍目标校验。
     *
     * @param userId 用户ID
     * @param bookId 书籍ID
     * @return 书籍作者ID（book.author_id）
     */
    private Long checkBookReadable(Long userId, Long bookId) {
        Book book = bookMapper.selectById(bookId);
        // 已删/不存在：按书籍不存在处理
        if (book == null || Objects.equals(book.getDelFlag(), 1)) {
            throw new BizException(ErrorCode.BOOK_NOT_FOUND);
        }
        // 下架书籍仅作者可收藏；其他状态（连载中/已完结）放行
        if (book.getStatus() == BookStatusEnum.TAKEN_DOWN && !book.getAuthorId().equals(userId)) {
            throw new BizException(ErrorCode.BOOK_NOT_FOUND);
        }
        return book.getAuthorId();
    }

    /**
     * 解析收藏目标的作者ID（取消收藏时判定是否为作者自收藏，与收藏时同一来源）。
     *
     * <p>为什么取消时还要查一次：favorite 表只存 userId/targetId，没有作者字段；
     * 只有取到同一 author_id 才能保证「收藏没加、取消也不减」的对称。
     * 目标行已被物理删除（article/book 查不到）时返回 null，按「非本人」处理并照常递减，
     * 保留旧行为——宁可让对账发现偏差，也不静默吞掉一次递减。</p>
     *
     * @param type     目标类型枚举
     * @param targetId 目标ID
     * @return 目标作者ID；目标行物理不存在时为 null
     */
    private Long resolveTargetAuthor(FavoriteTargetTypeEnum type, Long targetId) {
        if (type == FavoriteTargetTypeEnum.ARTICLE) {
            // 文章作者：article.author_id
            Article article = articleMapper.selectById(targetId);
            return article == null ? null : article.getAuthorId();
        }
        // 书籍作者：book.author_id
        Book book = bookMapper.selectById(targetId);
        return book == null ? null : book.getAuthorId();
    }
}
