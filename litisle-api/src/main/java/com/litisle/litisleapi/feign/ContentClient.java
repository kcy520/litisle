package com.litisle.litisleapi.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.config.FeignApiConfig;
import com.litisle.litisleapi.dto.content.ArticleCounterUpdateDTO;
import com.litisle.litisleapi.dto.content.ArticleImageRefDTO;
import com.litisle.litisleapi.dto.content.ArticleIndexDocDTO;
import com.litisle.litisleapi.dto.content.BookIndexDocDTO;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.dto.content.HotArticleDTO;
import com.litisle.litisleapi.dto.content.TagSuggestDTO;
import com.litisle.litisleapi.fallback.ContentClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 内容服务 Feign 客户端（跨服务契约）
 *
 * 【设计说明】
 * 1. 契约先行：本接口与 DTO、降级工厂已就绪；提供方端点待 content 服务实现
 *    TODO litisle-content 实现 GET /api/feign/content/get（bizType+bizId 回查正文）
 * 2. 路径前缀 /api/feign/content 遵循规范：网关未注册该前缀路由，仅服务间可用
 * 3. 回查正文是机审核心读依赖，降级 fail-fast（见 ContentClientFallbackFactory）：
 *    静默降级会让 review 侧误判「无内容可审」，由消费端不 ACK 进延迟重试
 *
 * 【调用方】review 模块（文本机审回查正文）
 *
 * @author litisle
 */
@FeignClient(
        name = "litisle-content",
        configuration = FeignApiConfig.class,
        fallbackFactory = ContentClientFallbackFactory.class
)
public interface ContentClient {

    /**
     * 回查内容正文
     *
     * @param bizType 业务类型（1-文章 2-评论）
     * @param bizId   业务主键
     * @return 内容 DTO；内容不存在时提供方返回 Result.fail，调用方判码处理
     */
    @GetMapping("/api/feign/content/get")
    Result<ContentDTO> getContent(@RequestParam("bizType") Integer bizType,
                                  @RequestParam("bizId") Long bizId);

    /**
     * 反查文章配图引用列表（review 侧文章配图复审 ARTICLE_IMG_RE_REVIEW 用）
     *
     * <p>【使用场景】review 消费 moderation.image.submit（scene=ARTICLE_IMG_RE_REVIEW）后，
     * 按文章 bizId 反查该文章登记的 article_image 类型配图引用（fileId + file_meta.id），
     * 逐张经 FileClient.getContent(fileId) 内部字节流取图送审（严禁按用户 URL 抓取，SSRF 防线），
     * 单图结论落 moderation_record（biz_id=file_meta.id）</p>
     *
     * <p>【设计说明】
     * 1. 契约先行：本接口与 DTO、降级工厂已就绪；提供方端点待 content 服务实现
     *    TODO litisle-content 实现 GET /api/feign/content/article-images（按文章ID回查配图列表）
     * 2. 路径前缀 /api/feign/content 遵循规范：网关未注册该前缀路由，仅服务间可用
     * 3. 反查配图列表是配图复审机审核心读依赖，降级 fail-fast（见 ContentClientFallbackFactory）：
     *    静默降级返回空列表会让复审「无图可审」误判通过，由消费端不 ACK 进延迟重试</p>
     *
     * @param articleId 文章ID
     * @return 配图引用列表（按登记顺序；未登记配图时返回空列表）；文章不存在时提供方返回 Result.fail
     */
    @GetMapping("/api/feign/content/article-images")
    Result<List<ArticleImageRefDTO>> listArticleImages(@RequestParam("articleId") Long articleId);

    /**
     * 按配图 fileId 反查引用该图的文章 ID 列表（review 侧图片复审再聚合用）
     *
     * <p>【使用场景】review 消费单图复审 result（biz_id=file_meta.id）后，按
     * article_image.idx_file_id 反查所有引用该图的文章，重算 ARTICLE_IMG_RE_REVIEW
     * 文章级聚合结论（聚合判定收口 review 消费端，content 仅提供反查数据不做判定）</p>
     *
     * <p>【设计说明】
     * 1. 契约先行：本接口已就绪；提供方端点随板块八 [8.2] 补齐（ContentFeignProviderController）；
     * 2. 路径前缀 /api/feign/content 网关未路由，仅服务间可用；
     * 3. 反查结果是图片复审再聚合核心读依赖，降级 fail-fast（见降级工厂）：
     *    静默返回空列表会让 review 误判「无文章引用」跳过聚合，由消费端不 ACK 进延迟重试；
     * 4. 仅返回未删除文章（del_flag=0），已删文章不参与复审聚合</p>
     *
     * @param fileId 配图文件 ID（article_image.file_id，varchar(64)，索引 idx_file_id 支撑）
     * @return 引用该图的未删除文章 ID 去重列表（无引用返回空列表）
     */
    @GetMapping("/api/feign/content/article-ids-by-file")
    Result<List<Long>> listArticleIdsByFileId(@RequestParam("fileId") String fileId);

    /**
     * 计数回写（social 聚合侧单点回写，§4.6）：view/like/comment_cnt 幂等绝对值 SET
     *
     * <p>【设计说明】
     * 1. 契约先行：提供方端点随板块三 [3.2] 补齐（ContentFeignProviderController）；
     * 2. 幂等绝对值 SET：并发覆盖由 social 聚合侧单点回写规避（冗余计数缓冲→定时聚合→单点 SET），
     *    content 不做增量累加（无 CAS、无 version 比对，重复调用结果收敛）；
     * 3. 仅非空字段更新（MP update-strategy not_null 口径）；文章不存在 fail-fast ARTICLE_NOT_FOUND；
     * 4. 路径前缀 /api/feign/content 网关未路由，仅服务间可用
     *
     * @param dto 计数回写 DTO（articleId 必填，三计数字段按需非空）
     * @return 成功返回 success；文章不存在返回 Result.fail（调用方判码处理）
     */
    @PostMapping("/api/feign/content/counters/update")
    Result<Void> updateCounters(@RequestBody ArticleCounterUpdateDTO dto);

    /**
     * 回查文章建索引所需的全量字段（search 索引同步专用）
     *
     * <p>【使用场景】search 消费 content.published / content.article-updated 事件后，
     * 事件体只带 articleId 定位键，需按本文按 ID 回查标题/正文/标签/作者/分区/分类/计数等，
     * 才能组装 article_idx 索引文档；字段不足时禁止在事件体塞大字段（事件轻量纪律）</p>
     *
     * <p>【设计说明】
     * 1. 路径前缀 /api/feign/content 网关未路由，仅服务间可用；
     * 2. 降级 fail-fast（见 ContentClientFallbackFactory）：回查失败不能静默返回空对象，
     *    否则 search 会把「不存在的文章」当成可索引数据，由消费端不 ACK 进延迟重试；
     * 3. 仅返回未删除文章（del_flag=0）；文章不存在或已删除时提供方返回 Result.fail，
     *    调用方按 SEARCH_SOURCE_NOT_FOUND 处理（记录后丢弃，不重试）</p>
     *
     * @param articleId 文章 ID
     * @return 文章索引回查 DTO（含标签名列表与作者昵称）
     */
    @GetMapping("/api/feign/content/article-index-doc")
    Result<ArticleIndexDocDTO> getArticleIndexDoc(@RequestParam("articleId") Long articleId);

    /**
     * 回查书籍建索引所需的全量字段（search 索引同步专用，批次五 B4）
     *
     * <p>【使用场景】search 消费 content.book-changed 事件后，事件体只带 bookId 定位键，
     * 需按 bookId 回查书名/作者/简介/分类/状态/删除标志，才能组装 book_idx 索引文档；
     * 字段不足时禁止在事件体塞大字段（事件轻量纪律）</p>
     *
     * <p>【设计说明】
     * 1. 路径前缀 /api/feign/content 网关未路由，仅服务间可用；
     * 2. 降级 fail-fast（见 ContentClientFallbackFactory）：回查失败不能静默返回空对象，
     *    否则 search 会把「不存在的书」当成可索引数据，由消费端不 ACK 进延迟重试；
     * 3. 书籍不存在或已软删时提供方返回 Result.fail(BOOK_NOT_FOUND)，
     *    调用方据此删除 book_idx 文档（下架/删除事件的正常收敛路径）</p>
     *
     * @param bookId 书籍 ID
     * @return 书籍索引回查 DTO（含作者 ID 与作者名）
     */
    @GetMapping("/api/feign/content/book-index-doc")
    Result<BookIndexDocDTO> getBookIndexDoc(@RequestParam("bookId") Long bookId);

    /**
     * 前缀搜索 content 标签池（search 联想接口聚合用，批次六 6.1）。
     *
     * <p>【设计口径】标签池唯一数据源在 content，search 不建标签索引、不写标签表；
     * 本接口只读聚合，避免标签双写漂移。</p>
     * <p>【降级语义】联想场景为 best-effort：content 不可用时降级返回空列表，
     * 不影响 ES completion 联想主结果（见 ContentClientFallbackFactory）。</p>
     *
     * @param keyword 关键词前缀（非空，长度上限与查询词一致）
     * @return 标签联想列表（无命中返回空列表）
     */
    @GetMapping("/api/feign/content/tag-suggest")
    Result<List<TagSuggestDTO>> getTagSuggestions(@RequestParam("keyword") String keyword);

    /**
     * 服务间收藏目标（板块九 1.2：AI 结果动作工具经序号映射定位 articleId 后代用户收藏）
     *
     * <p>【使用场景】小悦 Agent 解析「收藏第 2、3 篇」类结果动作指令后，按会话结果集
     * 序号映射定位 articleId，经本契约以当前登录用户身份收藏文章（targetType=1）；
     * 复用 content 收藏域既有幂等/可见性校验语义（重复收藏视为成功）。</p>
     *
     * <p>【设计说明】
     * 1. 契约先行：本接口已就绪；提供方端点随板块九 1.2 补齐（ContentFeignProviderController）；
     * 2. 路径前缀 /api/feign/content 网关未路由，仅服务间可用；
     * 3. 收藏动作为业务写操作，降级 fail-fast（见 ContentClientFallbackFactory）：
     *    静默返回 success 会让 AI 侧误报「收藏成功」，异常穿透由 AI 工具层组装明确失败响应；
     * 4. userId 由调用方显式传入（Feign 服务端无用户会话，不复用 StpUtil）；
     *    目标可见性校验与类型白名单仍由提供方 FavoriteService 收口</p>
     *
     * @param userId     收藏用户 ID（AI 侧取当前登录态，禁止匿名调用）
     * @param targetType 目标类型（1-文章 2-书籍；非法值提供方抛 PARAM_ERROR）
     * @param targetId   目标 ID
     * @return 成功返回 success；目标不存在或不可见返回 Result.fail（调用方判码组装失败响应）
     */
    @PostMapping("/api/feign/content/favorite")
    Result<Void> favorite(@RequestParam("userId") Long userId,
                          @RequestParam("targetType") Integer targetType,
                          @RequestParam("targetId") Long targetId);

    /**
     * 服务间收藏目标 ID 列表（板块十一 3.2：AI 收藏夹语义检索拉取用户收藏的文章 ID 白名单）
     *
     * <p>【使用场景】小悦 Agent 处理「我收藏过的讲 X 的文章」时，先经本契约按收藏时间
     * 倒序拉取用户收藏的文章 ID（targetType=1），再作为 includeArticleIds 白名单 filter
     * 传入 search 条件版语义检索——只返回已收藏且当前公开的文章（visibility=PUBLIC
     * 铁律由 search 侧强制）。</p>
     *
     * <p>【设计说明】
     * 1. 只查 ID 不做多态 join 装配（展示字段由 AI 侧经检索结果回填）；
     * 2. limit 为白名单规模上限（防 terms filter 过大），超过按上限截断；
     * 3. 降级 fail-fast：静默返回空列表会让收藏夹检索误报「没有收藏」，异常穿透由 AI
     *    工具层组装明确失败响应。</p>
     *
     * @param userId 用户 ID（AI 侧取当前登录态）
     * @param limit  取数上限（正数）
     * @return 收藏文章 ID 列表（按收藏时间倒序）；无收藏返回空列表
     */
    @PostMapping("/api/feign/content/favorite/article-ids")
    Result<List<Long>> listFavoriteArticleIds(@RequestParam("userId") Long userId,
                                              @RequestParam("limit") Integer limit);

    /**
     * 查询热门文章列表（search ES 不可用降级链路的数据源，批次八 8.1）。
     *
     * <p>【使用场景】search 检测到 ES 整体不可用且 `litisle.search.fallback.enabled=true` 时，
     * 退化为返回热门推荐列表（source=DEGRADED）；热门榜唯一数据源在 content，
     * 与对外 `/article/hot` 同源（批次E P5 起主路径为 CONTENT_FEED_HOT_ZSET 热度榜，榜空回退 view_cnt 直查），
     * search 不复制热榜逻辑、不直连 content 数据库。</p>
     *
     * <p>【设计说明】
     * 1. 路径前缀 /api/feign/content 网关未路由，仅服务间可用；
     * 2. 降级语义 fail-fast（见 ContentClientFallbackFactory）：热门列表是 ES 不可用时的唯一保底数据，
     *    静默返回空列表会让用户误以为「搜索无结果」，因此调用失败即降级失败，由 search 返回
     *    SEARCH_ES_UNAVAILABLE；
     * 3. 返回轻量字段（不含正文/封面），与列表不取大字段纪律一致。</p>
     *
     * @param limit 取前 N 条（search 传当前 pageSize，提供方再按全局分页上限钳制）
     * @return 热门文章轻量列表（按热榜序；无数据返回空列表）
     */
    @GetMapping("/api/feign/content/hot-articles")
    Result<List<HotArticleDTO>> getHotArticles(@RequestParam("limit") Integer limit);
}
