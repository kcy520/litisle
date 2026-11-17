package com.litisle.litislecontent.service;


import com.litisle.litisleapi.dto.content.ArticleCounterUpdateDTO;
import com.litisle.litisleapi.dto.content.ArticleImageRefDTO;
import com.litisle.litisleapi.dto.content.ArticleIndexDocDTO;
import com.litisle.litisleapi.dto.content.BookIndexDocDTO;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.dto.content.HotArticleDTO;
import com.litisle.litisleapi.dto.content.TagSuggestDTO;

import java.util.List;

/**
 * content 内部契约 Service（ContentFeignProviderController 委托收口，对齐 file 模块
 * 「Controller 只做参数转换，业务逻辑收口 Service」范例）。
 *
 * @author litisle
 */
public interface ContentInternalService {

    /**
     * 机审回查内容正文（bizType=1-文章；bizType=2-评论模块未落地返回 COMMENT_NOT_FOUND）
     *
     * @param bizType 业务类型（1-文章 2-评论）
     * @param bizId   业务主键
     * @return 内容 DTO（body=入库 sanitize 后 contentHtml，与发布送审同源保 hash 一致）
     */
    ContentDTO getContent(Integer bizType, Long bizId);

    /**
     * 反查文章配图引用列表（复审反查用，position_order 升序=发布登记顺序）
     *
     * @param articleId 文章ID
     * @return 配图引用列表（无活跃配图返回空列表）
     */
    List<ArticleImageRefDTO> listArticleImages(Long articleId);

    /**
     * 按配图 fileId 反查引用该图的未删除文章 ID 列表（图片复审再聚合用，板块八 [8.2]）
     *
     * @param fileId 配图文件 ID（article_image.file_id，索引 idx_file_id 支撑）
     * @return 引用该图的未删除文章 ID 去重列表（无引用返回空列表）
     */
    List<Long> listArticleIdsByFileId(String fileId);

    /**
     * 计数回写（幂等绝对值 SET，仅非空字段更新）
     *
     * @param dto 计数回写 DTO
     */
    void updateCounters(ArticleCounterUpdateDTO dto);

    /**
     * 回查文章建索引所需的全量字段（search 索引同步专用，批次二 2.1）
     *
     * @param articleId 文章 ID
     * @return 文章索引回查 DTO（含标签名列表与作者昵称）
     */
    ArticleIndexDocDTO getArticleIndexDoc(Long articleId);

    /**
     * 回查书籍建索引所需的全量字段（search 索引同步专用，批次五 B4）
     *
     * 作用：search 消费 content.book-changed 事件后按 bookId 回查书名/作者/简介/分类/状态，
     * 组装 book_idx 索引文档；事件体只带定位键，书籍不存在或已软删时抛 BOOK_NOT_FOUND，
     * 由 search 消费端删除索引文档（下架/删除的正常收敛路径）。
     *
     * @param bookId 书籍 ID
     * @return 书籍索引回查 DTO（含作者 ID 与作者昵称；作者昵称取不到为 null）
     */
    BookIndexDocDTO getBookIndexDoc(Long bookId);

    /**
     * 前缀搜索标签池（search 联想接口聚合用，批次六 6.1）。
     *
     * 作用：标签池 SSOT 在 content，search 不建标签索引；本方法仅按前缀查标签并以轻量 DTO 返回，
     * 供 search 与 ES completion 联想合并去重。
     *
     * @param keyword 关键词前缀
     * @return 标签联想列表（关键词为空/过长时返回空列表）
     */
    List<TagSuggestDTO> searchTagSuggestions(String keyword);

    /**
     * 服务间收藏目标（板块九 1.2：AI 结果动作工具经序号映射定位 articleId 后代用户收藏）。
     *
     * 作用：Feign 服务端线程无用户会话（无 StpUtil），userId 由调用方显式传入；
     * 委托 FavoriteService 收口类型白名单、目标可见性校验与幂等插入语义
     * （重复收藏视为成功，未公开目标不可收藏）。
     *
     * @param userId     收藏用户 ID（调用方从登录态取得，禁止为空）
     * @param targetType 目标类型（1-文章 2-书籍；非法值抛 PARAM_ERROR）
     * @param targetId   目标 ID
     */
    void favorite(Long userId, Integer targetType, Long targetId);

    /**
     * 服务间收藏目标 ID 列表（板块十一 3.2：AI 收藏夹语义检索白名单数据源）。
     *
     * 作用：委托 FavoriteService#listFavoriteTargetIds 收口类型白名单与取数口径
     * （按收藏时间倒序取前 N 条，Page 对象承接不做深翻）；无收藏返回空列表不报错。
     *
     * @param userId 用户 ID（AI 侧从登录态取得，禁止为空）
     * @param limit  取数上限（防白名单 filter 过大）
     * @return 收藏文章 ID 列表（targetType=1 文章，按收藏时间倒序）
     */
    java.util.List<Long> listFavoriteArticleIds(Long userId, Integer limit);

    /**
     * 查询热门文章列表（search ES 不可用降级链路的数据源，批次八 8.1）。
     *
     * 作用：ES 整体不可用时 search 退化为返回热门推荐；热门榜唯一数据源在 content，
     * 本方法复用对外 `/article/hot` 的读链路（content 批次E P5 起主路径为热度榜
     * CONTENT_FEED_HOT_ZSET，榜空回退 view_cnt 直查），以轻量 DTO 返回给 search，
     * 避免 search 复制热榜逻辑或直连 content 数据库。
     *
     * @param limit 取前 N 条（非正数返回空列表；内部再按全局分页上限钳制）
     * @return 热门文章轻量列表（按热榜序；无数据返回空列表）
     */
    List<HotArticleDTO> listHotArticles(int limit);
}
