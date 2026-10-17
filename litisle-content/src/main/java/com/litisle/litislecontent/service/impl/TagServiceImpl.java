package com.litisle.litislecontent.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.utils.JsonUtil;
import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.util.RedisUtil;
import com.litisle.litisleapi.dfa.DfaFacade;
import com.litisle.litisleapi.dfa.DfaHitResult;
import com.litisle.litisleapi.dfa.DfaScenes;
import com.litisle.litislecontent.constant.ContentLimitConstant;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.ArticleTag;
import com.litisle.litislecontent.domain.entity.Tag;
import com.litisle.litislecontent.domain.vo.TagVO;
import com.litisle.litislecontent.enums.TagStatusEnum;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mapper.ArticleTagMapper;
import com.litisle.litislecontent.mapper.TagMapper;
import com.litisle.litislecontent.service.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 标签域实现（前缀搜索/DFA 闸门/挂载计数，§6.7）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final TagMapper tagMapper;

    private final ArticleTagMapper articleTagMapper;

    private final ArticleMapper articleMapper;

    private final DfaFacade dfaFacade;

    private final RedisUtil redisUtil;

    /**
     * 前缀搜索已有标签池（§6.7：优先命中池内标签，防标签碎片化）
     *
     * @param keyword 搜索前缀关键词
     * @return 命中标签列表
     */
    @Override
    public List<TagVO> searchByPrefix(String keyword) {
        // 空参直接返回空列表（前端联想空串不查池）
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        // 前缀搜索：likeRight 走 name 前缀；仅正常态未删标签出池（封禁/已删不聚合展示）
        List<Tag> tags = tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                .likeRight(Tag::getName, escapeLike(keyword.trim()))
                .eq(Tag::getStatus, TagStatusEnum.NORMAL)
                .eq(Tag::getDelFlag, DelFlag.NOT_DELETED)
                .orderByDesc(Tag::getUseCount)
                .last("LIMIT " + ContentLimitConstant.TAG_SEARCH_RESULT_LIMIT));
        return tags.stream().map(TagServiceImpl::toVO).toList();
    }

    /**
     * 热门标签 TOP N（use_count 倒序；封禁/已删标签不聚合）
     *
     * @return 热门标签列表
     */
    @Override
    public List<TagVO> hotTags() {
        // Redis 短缓存：miss 查库回填，TTL ±10% 抖动防雪崩（§七.6 缓存三防）
        String cacheKey = CacheConstant.CONTENT_TAG_HOT + ContentLimitConstant.TAG_HOT_TOP_N;
        String cached = redisUtil.get(cacheKey);
        if (cached != null) {
            return Arrays.asList(JsonUtil.fromJson(cached, TagVO[].class));
        }
        List<Tag> tags = tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                .eq(Tag::getStatus, TagStatusEnum.NORMAL)
                .eq(Tag::getDelFlag, DelFlag.NOT_DELETED)
                .orderByDesc(Tag::getUseCount)
                .last("LIMIT " + ContentLimitConstant.TAG_HOT_TOP_N));
        List<TagVO> voList = tags.stream().map(TagServiceImpl::toVO).toList();
        redisUtil.set(cacheKey, JsonUtil.toJson(voList), jitteredTtl(), TimeUnit.SECONDS);
        return voList;
    }

    /**
     * 挂载标签（[6.1]：owner 校验 → ≤5 超限拒绝 → 池内命中挂载（uk 冲突幂等跳过）→
     * use_count 原子 +1；池中无命中的新建走 DFA 闸门随 [6.2] 接入）
     *
     * @param articleId 文章ID
     * @param tagNames  标签名列表
     * @param operatorId 操作人ID（owner 校验基准）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void attachTags(Long articleId, List<String> tagNames, Long operatorId) {
        if (tagNames == null || tagNames.isEmpty()) {
            return;
        }
        // owner 校验（标签属元数据：方案B status=2 允许增删，不影响 content_hash 不参与审核聚合）
        requireOwnedArticle(articleId, operatorId);
        // ≤5 上限：已有挂载数 + 本次新增 > 5 拒绝（防关键词堆砌）
        Long existing = articleTagMapper.selectCount(new LambdaQueryWrapper<ArticleTag>()
                .eq(ArticleTag::getArticleId, articleId));
        // 去重（同名单次请求内先收敛，防重复计数）
        Set<String> nameSet = new HashSet<>(tagNames);
        if (existing + nameSet.size() > ContentLimitConstant.TAG_MAX_PER_ARTICLE) {
            throw new BizException(ErrorCode.ARTICLE_TAG_EXCEEDED);
        }
        // 池内全量按名查询（不过滤状态）：正常态可挂；封禁/已删静默跳过；池外名字走 DFA 受控新建
        Map<String, Tag> existMap = tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                        .in(Tag::getName, nameSet)).stream()
                .collect(Collectors.toMap(Tag::getName, Function.identity(), (a, b) -> a));
        List<Tag> toAttach = new ArrayList<>();
        for (String name : nameSet) {
            // 名称规范化：trim 后空串跳过；超长直接拒绝（≤64 与词库词条同口径，不浪费 DFA 匹配）
            String normalized = name == null ? "" : name.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (normalized.length() > ContentLimitConstant.TAG_NAME_MAX_LENGTH) {
                throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
            }
            Tag exist = existMap.get(normalized);
            if (exist != null) {
                // 池内命中：正常态未删才挂（封禁标签拒挂不给违规词引流；已删不复活）
                if (exist.getStatus() == TagStatusEnum.NORMAL
                        && exist.getDelFlag() == DelFlag.NOT_DELETED) {
                    toAttach.add(exist);
                }
                continue;
            }
            // [6.2] 受控新建：同步 DFA 闸门（BIO 短文本词库），任一命中即拒挂（宁严勿松）
            DfaHitResult hitResult = dfaFacade.check(DfaScenes.BIO, normalized);
            if (hitResult.hit()) {
                throw new BizException(ErrorCode.SENSITIVE_WORD_DETECTED);
            }
            // 落池：uk_name 并发同名新建冲突重查幂等（对齐 TagMapper「先查后插，uk_name 兑底」注释）
            Tag created = new Tag()
                    .setName(normalized)
                    .setUseCount(0L)
                    .setStatus(TagStatusEnum.NORMAL)
                    .setDelFlag(DelFlag.NOT_DELETED);
            try {
                tagMapper.insert(created);
                toAttach.add(created);
            } catch (DuplicateKeyException e) {
                Tag concurrent = tagMapper.selectOne(new LambdaQueryWrapper<Tag>()
                        .eq(Tag::getName, normalized));
                if (concurrent != null && concurrent.getStatus() == TagStatusEnum.NORMAL
                        && concurrent.getDelFlag() == DelFlag.NOT_DELETED) {
                    toAttach.add(concurrent);
                }
            }
        }
        if (toAttach.isEmpty()) {
            return;
        }
        // uk(article_id, tag_id) 冲突幂等跳过：先过滤已挂载，剩余逐条插入
        Set<Long> alreadyAttached = articleTagMapper.selectList(new LambdaQueryWrapper<ArticleTag>()
                        .eq(ArticleTag::getArticleId, articleId)
                        .in(ArticleTag::getTagId,
                                toAttach.stream().map(Tag::getId).toList())).stream()
                .map(ArticleTag::getTagId)
                .collect(Collectors.toSet());
        toAttach = toAttach.stream()
                .filter(tag -> !alreadyAttached.contains(tag.getId()))
                .toList();
        for (Tag tag : toAttach) {
            ArticleTag relation = new ArticleTag().setArticleId(articleId).setTagId(tag.getId());
            try {
                articleTagMapper.insert(relation);
                // use_count 原子 +1（挂载成功才计数；并发双挂由 uk 兑底跳过不重复计数）
                tagMapper.update(null, new LambdaUpdateWrapper<Tag>()
                        .eq(Tag::getId, tag.getId())
                        .setSql("use_count = use_count + 1"));
            } catch (DuplicateKeyException e) {
                // 并发双挂同文章同标签：幂等跳过（清单口径，不计数不报错）
                log.info("tag attach idempotent skip, articleId={}, tagId={}", articleId, tag.getId());
            }
        }
    }

    /**
     * 摘除标签（[6.1]：owner 校验 → 仅删本人文章的挂载行 → use_count 原子 -1 兑底不透支为负）
     *
     * @param articleId  文章ID
     * @param tagIds     待摘除标签ID列表
     * @param operatorId 操作人ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void detachTags(Long articleId, List<Long> tagIds, Long operatorId) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        // owner 校验（方案B status=2 允许增删标签）
        requireOwnedArticle(articleId, operatorId);
        // 先查实际存在的挂载行（只对真删掉的计数 -1，幂等重复摘除不多扣）
        List<ArticleTag> relations = articleTagMapper.selectList(new LambdaQueryWrapper<ArticleTag>()
                .eq(ArticleTag::getArticleId, articleId)
                .in(ArticleTag::getTagId, tagIds));
        if (relations.isEmpty()) {
            return;
        }
        List<Long> detachedTagIds = relations.stream()
                .map(ArticleTag::getTagId)
                .distinct()
                .toList();
        articleTagMapper.delete(new LambdaQueryWrapper<ArticleTag>()
                .eq(ArticleTag::getArticleId, articleId)
                .in(ArticleTag::getTagId, detachedTagIds));
        // use_count 原子 -1（GREATEST 兑底不为负，防对账空窗期透支）
        for (Long tagId : detachedTagIds) {
            tagMapper.update(null, new LambdaUpdateWrapper<Tag>()
                    .eq(Tag::getId, tagId)
                    .setSql("use_count = GREATEST(use_count - 1, 0)"));
        }
    }

    /**
     * 文章归属校验复用：文章存在（未软删）且属本人，否则按码拒绝
     * （标签属元数据，方案B status=2 允许增删，不校验状态）
     *
     * @param articleId  文章ID
     * @param operatorId 操作人ID
     * @return 已校验文章实体
     */
    private Article requireOwnedArticle(Long articleId, Long operatorId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null || article.getDelFlag() == DelFlag.DELETED) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        if (!article.getAuthorId().equals(operatorId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return article;
    }

    /**
     * Tag 实体 → VO 窄化映射（不出池内部字段）
     */
    private static TagVO toVO(Tag tag) {
        return new TagVO()
                .setId(tag.getId())
                .setName(tag.getName())
                .setUseCount(tag.getUseCount());
    }

    /**
     * LIKE 前缀转义：%/_/\\ 三字符转义，防用户输入通配符全池拖出
     */
    private static String escapeLike(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 热门缓存 TTL ±10% 抖动（防同批 key 同时过期雪崩）
     */
    private static long jitteredTtl() {
        long base = ContentLimitConstant.TAG_HOT_CACHE_TTL_SECONDS;
        long jitter = (long) (base * 0.1);
        return base + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
    }
}