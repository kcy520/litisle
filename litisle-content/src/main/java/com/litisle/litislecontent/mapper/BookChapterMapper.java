package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.BookChapter;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Mapper + extends BaseMapper<BookChapter>（MyBatis-Plus）
 *
 * 关键查询：
 * - 章节目录：selectList(book_id，order by chapter_no asc) —— 序号即排序键，目录页一次取全
 * - 挂载防重：insert(book_id, chapter_no, article_id) —— (book_id, chapter_no) 唯一键
 *   uk_book_chapter_no 兜底，业务层捕获冲突重取最大序号+1 重试
 * - 反查章节归属：selectOne(article_id) —— 文章是否已被挂书、挂到哪本书（发布校验/退章）
 * - 退章/重排：delete(book_id + article_id 或 chapter_no) —— 重排为同事务先删后插，
 *   reading_progress 里指向被移除 chapter 的进度由业务层容错（章节缺失按最新章处理）
 */
@Mapper
public interface BookChapterMapper extends BaseMapper<BookChapter> {

}