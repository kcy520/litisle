package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.ArticleTag;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Mapper + extends BaseMapper<ArticleTag>（MyBatis-Plus）
 *
 * 关键查询：
 * - 文章打标签：insert(article_id, tag_id) —— (article_id, tag_id) 唯一键 uk_article_tag，
 *   重复插入靠唯一键冲突幂等跳过（业务层捕获 DuplicateKeyException 忽略，不走先查后插）
 * - 按文章取标签：selectList(article_id) —— 详情/列表聚合标签名（配合 TagMapper join 或两段查询）
 * - 按标签聚合文章：selectList(tag_id) —— 走 idx_tag_id，标签详情页文章列表
 * - 改标签解绑：delete(article_id) —— 文章编辑重打标签前先清后插（同事务），
 *   tag.use_count 冗余计数由业务层异步回刷
 * - 每篇文章上限 5 个标签的校验在业务层做（count 查询或提交侧校验），本 Mapper 不强制
 */
@Mapper
public interface ArticleTagMapper extends BaseMapper<ArticleTag> {

}