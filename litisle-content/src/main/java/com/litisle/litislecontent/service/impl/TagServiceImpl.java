package com.litisle.litislecontent.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
 * 标签域实现（§6.7）。
 *
 * 作用：
 * 给写文章的人提供标签前缀搜索和热门标签；新建标签前先过敏感词检查（DFA 闸门）；
 * 文章挂上/摘下标签时维护标签的使用次数。
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
     * 前缀搜索已有标签池（§6.7）
     *
     * 作用：
     * 用户输入前缀时优先从已有标签池里搜，而不是让每篇文章都自创标签
     * （避免同一主题出现一堆写法不同的标签）；只返回正常使用中且未删除的标签。
     *
     * @param keyword 搜索前缀关键词
     * @return 命中标签列表
     */
    @Override
    public List<TagVO> searchByPrefix(String keyword) {

        /**
         * 前缀搜索流程：
         *
         * ① 空参直接返回空列表（前端联想空串不查池）。
         * ② 前缀匹配：按标签名前缀搜索，只出正常态未删除的标签
         *    （封禁/已删不聚合展示，不给违规词引流）；
         *    LIKE 通配符转义（防用户输入 %/_ 全池拖出），按使用次数倒序取前 N。
         */

        // 空参直接返回空列表（前端联想空串不查池）
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        // 前缀搜索：按标签名前缀匹配；只出正常使用中且未删除的标签（被封禁/已删除的不展示，不给违规词引流）
        // 用 Page 替代手拼 LIMIT：分页插件标准方式（searchCount=false 不触发 COUNT），只取前 N 条联想结果
        List<Tag> tags = tagMapper.selectList(Page.of(1, ContentLimitConstant.TAG_SEARCH_RESULT_LIMIT, false),
                new LambdaQueryWrapper<Tag>()
                        .likeRight(Tag::getName, escapeLike(keyword.trim()))
                        .eq(Tag::getStatus, TagStatusEnum.NORMAL)
                        .eq(Tag::getDelFlag, DelFlag.NOT_DELETED)
                        .orderByDesc(Tag::getUseCount));
        return tags.stream().map(TagServiceImpl::toVO).toList();
    }


    /**
     * 热门标签 TOP N（use_count 倒序；封禁/已删标签不聚合）
     *
     * @return 热门标签列表
     */
    @Override
    public List<TagVO> hotTags() {

        /**
         * 热门标签流程：
         *
         * ① 读缓存：命中直接返回。
         * ② 缓存未命中：查库（仅正常态未删，按使用次数倒序取前 N）。
         * ③ 回填缓存：TTL 加 ±10% 抖动（防同批 key 同时过期雪崩）。
         */

        // Redis 短缓存：缓存没有就查库，查完回填；过期时间上下浮动 10%，
        // 防止大量 key 同一时刻过期把数据库压垮（§七.6 缓存三防）
        String cacheKey = CacheConstant.CONTENT_TAG_HOT + ContentLimitConstant.TAG_HOT_TOP_N;
        String cached = redisUtil.get(cacheKey);
        if (cached != null) {
            return Arrays.asList(JsonUtil.fromJson(cached, TagVO[].class));
        }
        // 用 Page 替代手拼 LIMIT：分页插件标准方式（searchCount=false 不触发 COUNT），只取热门 TOP N
        List<Tag> tags = tagMapper.selectList(Page.of(1, ContentLimitConstant.TAG_HOT_TOP_N, false),
                new LambdaQueryWrapper<Tag>()
                        .eq(Tag::getStatus, TagStatusEnum.NORMAL)
                        .eq(Tag::getDelFlag, DelFlag.NOT_DELETED)
                        .orderByDesc(Tag::getUseCount));
        List<TagVO> voList = tags.stream().map(TagServiceImpl::toVO).toList();
        redisUtil.set(cacheKey, JsonUtil.toJson(voList), jitteredTtl(), TimeUnit.SECONDS);
        return voList;
    }

    /**
     * 挂载标签（[6.1]）
     *
     * 作用：
     * 给文章挂标签：先校验是本人文章 → 单篇最多 5 个，超了拒绝 →
     * 标签池里已有的直接挂（重复挂载自动跳过），使用次数加 1；
     * 池里没有的新建（[6.2]）——新建前先过敏感词检查。
     *
     * @param articleId 文章ID
     * @param tagNames  标签名列表
     * @param operatorId 操作人ID（owner 校验基准）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void attachTags(Long articleId, List<String> tagNames, Long operatorId) {

        /**
         * 挂载标签流程：
         *
         * ① 空列表直接返回；归属校验：只能给自己文章挂标签。
         * ② 数量上限：已有挂载数 + 本次新增（去重后）超过 5 拒绝（防关键词堆砌）。
         * ③ 逐个处理标签名（去重后）：
         *    - 空名跳过；超长直接拒绝；
         *    - 池内命中：正常态未删才挂（封禁标签拒挂，已删不复活）；
         *    - 池外新建：先过 DFA 敏感词闸门（命中即拒），插入时并发同名冲突
         *      则退化为查已有标签复用，不让请求失败。
         * ④ 落挂载：先过滤已挂过的（幂等），再逐条插入关系行；
         *    每次插入成功后使用次数 SQL 原子 +1（并发双挂由唯一索引兑底跳过不重复计数）。
         */

        if (tagNames == null || tagNames.isEmpty()) {
            return;
        }
        // owner 校验：只能操作自己的文章。标签属元数据，已发布的文章也允许增删标签
        // （不改正文，不影响内容指纹和审核结果）
        requireOwnedArticle(articleId, operatorId);
        // ≤5 上限：文章已有挂载数 + 本次新增数超过 5 就拒绝（防关键词堆砌）
        Long existing = articleTagMapper.selectCount(new LambdaQueryWrapper<ArticleTag>()
                .eq(ArticleTag::getArticleId, articleId));
        // 去重（同名单次请求内先收敛，防重复计数） set自带去重
        Set<String> nameSet = new HashSet<>(tagNames);
        if (existing + nameSet.size() > ContentLimitConstant.TAG_MAX_PER_ARTICLE) {
            throw new BizException(ErrorCode.ARTICLE_TAG_EXCEEDED);
        }
        // 按名字批量查标签池（不过滤状态，查到什么算什么）：
        // 正常态的可挂；封禁/已删的后面静默跳过；池里没有的名字走受控新建
        //根据一组 name，批量查询 Tag，并把结果转成一个「name → Tag」的 Map，同时处理 name 重复的问题。
        Map<String, Tag> existMap = tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                        .in(Tag::getName, nameSet)).stream()
                .collect(Collectors.toMap(Tag::getName, Function.identity(), (a, b) -> a));//如果 name 重复，保留旧的（a），丢弃新的（b）

        List<Tag> toAttach = new ArrayList<>();

        for (String name : nameSet) {
            // 名称规范化：去首尾空格后为空就跳过；超长直接拒绝
            // （上限 64 和敏感词词库词条一致，明显超长的没必要再浪费一次敏感词检查）
            String normalized = name == null ? "" : name.trim();
            //非空与长度校验
            if (normalized.isEmpty()) {//isEmpty包括 ""
                continue;
            }
            if (normalized.length() > ContentLimitConstant.TAG_NAME_MAX_LENGTH) {
                throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
            }

            Tag exist = existMap.get(normalized);
            if (exist != null) {
                // 池内命中：只有“正常使用中且未删除”的标签才挂
                // （被封禁的拒挂，不给违规词引流；已删除的不复活）
                if (exist.getStatus() == TagStatusEnum.NORMAL
                        && exist.getDelFlag() == DelFlag.NOT_DELETED) {
                    toAttach.add(exist);
                }
                continue;
            }

            // [6.2] 受控新建：新建前先过 DFA 敏感词检查（用短文本词库），
            // 命中任何一个敏感词就拒绝挂载（宁可错杀不可放过）
            DfaHitResult hitResult = dfaFacade.check(DfaScenes.BIO, normalized);
            if (hitResult.hit()) {
                throw new BizException(ErrorCode.SENSITIVE_WORD_DETECTED);
            }
            // 落池：插入新标签；两人同时新建同名标签时靠数据库唯一索引兑底，
            // 冲突方退化为复用已有标签（详见 TagMapper 的“先查后插，唯一索引兑底”注释）
            Tag created = new Tag()
                    .setName(normalized)
                    .setUseCount(0L)
                    .setStatus(TagStatusEnum.NORMAL)
                    .setDelFlag(DelFlag.NOT_DELETED);

            //“插入标签时，如果因为并发导致唯一键冲突，就退化为‘查已存在的标签并复用’，而不是让整个请求失败。”
            try {
                tagMapper.insert(created);
                toAttach.add(created);
            } catch (DuplicateKeyException e) {
                //查询当前标签,选择复用
                Tag concurrent = tagMapper.selectOne(new LambdaQueryWrapper<Tag>()
                        .eq(Tag::getName, normalized));
                //
                if (concurrent != null && concurrent.getStatus() == TagStatusEnum.NORMAL
                        && concurrent.getDelFlag() == DelFlag.NOT_DELETED) {
                    toAttach.add(concurrent);
                }
            }
        }
        if (toAttach.isEmpty()) {
            return;
        }

        //把前面准备好的 toAttach 标签列表，逐条挂到文章上，同时保证「不重复挂载、计数不重、并发安全」。
        // 挂载去重：先过滤掉已经挂过的标签，剩下的逐条插入
        // （重复挂载由唯一索引兑底，幂等跳过）
        //① 查「已经挂过的」标签 ID
        Set<Long> alreadyAttached = articleTagMapper.selectList(new LambdaQueryWrapper<ArticleTag>()
                        .eq(ArticleTag::getArticleId, articleId)
                        .in(ArticleTag::getTagId,
                                toAttach.stream().map(Tag::getId).toList())).stream()
                .map(ArticleTag::getTagId)
                .collect(Collectors.toSet());
        //② 过滤掉已挂载的标签
        toAttach = toAttach.stream()
                .filter(tag -> !alreadyAttached.contains(tag.getId()))
                .toList();
        //③ 逐条插入文章-标签关系
        for (Tag tag : toAttach) {
            ArticleTag relation = new ArticleTag().setArticleId(articleId).setTagId(tag.getId());
            try {

                articleTagMapper.insert(relation);
                //④ 挂载成功 → 使用次数 +1
                // 用 SQL 原子自增（use_count = use_count + 1），不能先查出来在 Java 里算好再写回——
                // 并发挂同一标签时两边都基于旧值算会丢一次 +1（阶段10 修复，和 detachTags 的减法同款做法）；
                // 只有插入成功才计数，并发重复挂载被唯一索引拦下的不重复计数
                tagMapper.update(null,
                        new LambdaUpdateWrapper<Tag>()
                                .eq(Tag::getId, tag.getId())
                                .setSql("use_count = use_count + 1")
                );
            } catch (DuplicateKeyException e) {
                // ⑤ 并发重复挂载：唯一索引拦下 → 幂等跳过（不计数、不报错）
                log.info("tag attach idempotent skip, articleId={}, tagId={}", articleId, tag.getId());
            }
        }
    }


    /**
     * 摘除标签（[6.1]）
     *
     * 作用：
     * 从文章上摘掉标签：只删本人文章的挂载关系行；使用次数用 SQL 原子减 1，
     * 并兑底不允许减成负数。
     *
     * @param articleId  文章ID
     * @param tagIds     待摘除标签ID列表
     * @param operatorId 操作人ID
     * @return 删除标签数量
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int detachTags(Long articleId, List<Long> tagIds, Long operatorId) {

        /**
         * 摘除标签流程：
         *
         * ① 空列表直接返回；归属校验：只能摘自己文章的标签。
         * ② 查实际存在的挂载行（只对真的删掉的关系计数 -1，防重复摘除多扣）。
         * ③ 删除挂载关系：只删当前文章的指定标签，不会误删别家数据。
         * ④ 使用次数 SQL 原子 -1，兑底不为负（并发下最低保 0，防负数不一致）。
         * ⑤ 返回实际摘除的标签数量。
         */

         // 1. 入参防御：空列表直接返回
         // 避免无意义的 DB 查询和 delete 语句，也防止后面 in(empty) 的 SQL 语法问题
        if (tagIds == null || tagIds.isEmpty()) {
            return 0;
        }

        // 2. 权限校验：只能操作自己的文章
        // 方案B：即使文章 status=2（如审核中/已下架）也允许增删标签  todo 改为不允许修改！
        // 内部会校验 article.author_id == operatorId，否则抛 BizException
        requireOwnedArticle(articleId, operatorId);

        // =========================
        // 3. 查「实际存在的挂载行」
        // =========================
        // 目的：
        // - 只对「真的删掉的关系行」做 use_count -1
        // - 防止重复摘除导致计数被多扣（幂等保护）
        // - 用 article_id + tag_id 精确定位，避免误删别的文章数据
        List<ArticleTag> relations = articleTagMapper.selectList(
                new LambdaQueryWrapper<ArticleTag>()
                        .eq(ArticleTag::getArticleId, articleId)
                        .in(ArticleTag::getTagId, tagIds)
        );

        // 没有任何挂载行存在，直接返回（幂等：重复摘除不报错）
        if (relations.isEmpty()) {
            return 0;
        }

        // 4. 提取实际被摘除的 tagId 列表
        // - 去重：防止 tagIds 里有重复 ID 导致 delete 重复执行（虽然 in 不怕，但语义更清晰）
        // - 后续计数 -1 也只对这个列表负责
        List<Long> detachedTagIds = relations.stream()
                .map(ArticleTag::getTagId)
                .distinct()
                .toList();

        // 5. 删除文章-标签挂载关系
        // 只删「当前文章 + 实际存在的 tagId」组合
        // 不会误删其他文章的标签，也不会因为传入了不存在的 tagId 而报错
        articleTagMapper.delete(
                new LambdaQueryWrapper<ArticleTag>()
                        .eq(ArticleTag::getArticleId, articleId)
                        .in(ArticleTag::getTagId, detachedTagIds)
        );

        // =========================
        // 6. use_count 原子 -1（兜底不为负）
        // =========================
        // 为什么用 GREATEST(use_count - 1, 0)：
        // - 并发场景下：查询 → 删除 → 计数 -1 之间有时间窗口
        // - 如果恰好另一个请求也做了摘除，可能出现 count 已经为 0 再 -1 → 变负数
        // - GREATEST 保证最低值为 0，防止对账空窗期出现负数（数据不一致可修复，负数不可接受）
        //
        // 为什么不用 select 后 Java 里判断：
        // - 非原子操作，并发下会丢更新
        // - setSql 直接让 DB 做计算，保证原子性
        for (Long tagId : detachedTagIds) {
            tagMapper.update(null,
                    new LambdaUpdateWrapper<Tag>()
                            .eq(Tag::getId, tagId)
                            .setSql("use_count = GREATEST(use_count - 1, 0)")
            );
        }

        // 7. 返回实际摘除的标签数量
        return detachedTagIds.size();
    }


    /**
     * 文章归属校验复用：文章存在（未删除）且属本人，否则按对应错误码拒绝
     * （标签属元数据，已发布的文章也允许增删标签，所以这里不校验文章状态）
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