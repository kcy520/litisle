package com.litisle.litislecontent.service;

import com.litisle.litislecontent.domain.dto.ArticlePublishDTO;
import com.litisle.litislecontent.domain.dto.ArticleUpdateDTO;
import com.litisle.litislecontent.domain.vo.ArticleCardVO;
import com.litisle.litislecontent.domain.vo.ArticleDetailVO;

import java.util.List;

/**
 * 文章域服务（CRUD + 详情可见性判定 + 公开读），REST 由 ArticleController 调用：
 *
 * Long createAndPublish(ArticlePublishDTO, Long authorId)          // 创建/发布（A1 校验→A2 免审直落→A3 CAS 0→1 + afterCommit 发 content.submitted）
 * void update(Long id, ArticleUpdateDTO, Long operatorId)          // 编辑：status=0/3 全字段；status=2 仅元数据（方案B，正文/标题禁改）
 * void submit(Long id, Long operatorId)                            // 驳回后修改重提（CAS 3→1）
 * void delete(Long id, Long operatorId)                            // 软删（owner/ADMIN）+ 必发 content.unpublished（隐私红线）
 * ArticleDetailVO getDetail(Long id, Long viewerId)                // 详情：visibility 判定链收口（viewerId 可空=匿名，仅公开放行）
 * List<ArticleCardVO> listPublic(ZoneEnum, String, int, int)       // 公开列表（匿名；大字段延迟加载，VO 不带 content_html）
 * List<ArticleCardVO> listHot(int pageSize)                        // 热门文章（匿名）
 *
 * 【职责边界】批量发布/批量可见性/预检/密码校验归 ArticlePublishService（发布编排域），
 * 本接口只管文章本体 CRUD 与公开读，避免两域重复签名
 *
 * 事务铁律：本地事务提交 → afterCommit 事件发送（失败重投/DLQ）；发布 CAS 防重，禁止 2→1
 *
 * @author litisle
 */
public interface ArticleService {

    /**
     * 创建/发布（同步段 A1→A3：校验 + 免审直落判定 + CAS 落库 + afterCommit 发送 content.submitted）
     *
     * @param dto      发布入参（标题/正文/配图/封面/分类/分区/可见性/密码）
     * @param authorId 当前登录作者ID
     * @return 文章ID
     */
    Long createAndPublish(ArticlePublishDTO dto, Long authorId);

    /**
     * 编辑文章（方案B：status=0/3 全字段可编；status=2 仅封面/摘要/分类/visibility 等元数据，改正文须软删重发）
     *
     * @param id         文章ID
     * @param dto        编辑入参
     * @param operatorId 当前操作者ID（owner 校验，防横向越权）
     */
    void update(Long id, ArticleUpdateDTO dto, Long operatorId);

    /**
     * 驳回后修改重提（CAS 3→1；4-下架为终态，无重提入口）
     *
     * @param id         文章ID
     * @param operatorId 当前操作者ID
     */
    void submit(Long id, Long operatorId);

    /**
     * 软删文章（owner/ADMIN）——必同发 content.unpublished + 经 ContentUnpublishedInvalidator 失效 last-hash（下线四路径之一）
     *
     * @param id         文章ID
     * @param operatorId 当前操作者ID
     */
    void delete(Long id, Long operatorId);

    /**
     * 文章详情：visibility 判定链收口——1 公开放行；2 仅自己（非本人按不存在处理，不暴露存在性）；
     * 3 仅关注者（回查 SocialClient，降级=按非关注者处理宁严勿松）；4 密码访问（元信息可见，正文需密码）
     *
     * @param id       文章ID
     * @param viewerId 查看者ID（可空=匿名游客，仅公开放行）
     * @return 详情 VO（大字段 content_html 按可见性决定是否带出）
     */
    ArticleDetailVO getDetail(Long id, Long viewerId);

    /**
     * 公开文章列表（匿名可访问；列表 VO 不带 content_html——大字段延迟加载）
     *
     * @param zone     分区（可空=全部）
     * @param category 分类（可空=全部）
     * @param pageNo   页码（1 起）
     * @param pageSize 页大小
     * @return 卡片 VO 列表
     */
    List<ArticleCardVO> listPublic(Integer zone, String category, int pageNo, int pageSize);

    /**
     * 热门文章（匿名可访问；按 view_cnt 冗余计数排序，免 join）
     *
     * @param pageSize 取 N 条
     * @return 卡片 VO 列表
     */
    List<ArticleCardVO> listHot(int pageSize);
}
