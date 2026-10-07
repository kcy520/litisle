package com.litisle.litislereview.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.entity.SensitiveWordLibrary;
import org.apache.ibatis.annotations.Mapper;

/**
 * sensitive_word_library 敏感词库表 Mapper（MyBatis-Plus，DFA 热更数据源）
 *
 * 关键链路（实现 SensitiveWordService 时落地）：
 * - DFA 构建加载：selectList(scene=?, status=1)（按场景过滤，评论加载白名单/用户名不加载白名单）
 * - 写操作（增删改/启停/黑白切换）必须与 sensitive_word_version 的 version+1 同事务，
 *   完成后 Redis Pub 广播新版本（消费端 newVersion > curVersion 才重建，防乱序旧覆盖新）
 * - 物理删除（配置类小表可全量重建，updated_by 留审计痕）
 */
@Mapper
public interface SensitiveWordLibraryMapper extends BaseMapper<SensitiveWordLibrary> {
}
