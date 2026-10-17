package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.ArticleImage;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Mapper + extends BaseMapper<ArticleImage>（MyBatis-Plus）
 *
 * 关键查询：
 * - 正文渲染取图：selectList(article_id 或 draft_id，order by position_order) ——
 *   按 position_order 升序替换正文 {img:file_id} 占位符
 * - 图片复审反查：selectList(file_id) —— 走 idx_file_id，按图反查引用文章，
 *   定位 ARTICLE_IMG_RE_REVIEW 复审任务重算聚合结论（Review-Plan §4.2.2④）
 * - 归属切换：update(article_id=发布ID, draft_id=null) where draft_id=? —— 发布归属切换，
 *   与正文落库同事务，防"图在文不在"的半提交
 * - 图片管理页：selectList(owner 维度聚合 article_id in (...)) —— 由业务层先查作者文章再 in 批量取图，防 N+1
 */
@Mapper
public interface ArticleImageMapper extends BaseMapper<ArticleImage> {

}