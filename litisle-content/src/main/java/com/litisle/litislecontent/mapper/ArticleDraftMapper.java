package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.ArticleDraft;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Mapper + extends BaseMapper<ArticleDraft>（MyBatis-Plus）
 *
 * 关键查询：
 * - 最近草稿：selectOne(author_id + article_id?，order by draft_version desc limit 1) ——
 *   编辑器进入时取最新快照恢复，走 idx_author_id；article_id 为 null 表示纯新草稿
 * - 版本历史列表：selectList(author_id + article_id?，order by draft_version desc) —— 多次编辑历史回溯
 * - 版本号递增：insert 前查 max(draft_version)+1 —— 同作者并发编辑由业务层 CAS/重试兜底
 * - 草稿高频写低频读：自动保存 60s 窗口限流（ContentLimitConstant），不做缓存；
 *   发布成功后按 article_id 归档（article_id 回填），旧快照保留供回滚
 */
@Mapper
public interface ArticleDraftMapper extends BaseMapper<ArticleDraft> {

}