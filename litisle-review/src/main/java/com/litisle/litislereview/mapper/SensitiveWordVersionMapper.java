package com.litisle.litislereview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.entity.SensitiveWordVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * sensitive_word_version 词库版本表 Mapper（MyBatis-Plus，版本单一来源）
 *
 * 关键链路（实现 SensitiveWordService 时落地）：
 * - 版本推进（原子）：update set version = version + 1, updated_by=?, update_time=now()
 *   where scene=?（与词表变更同事务；主键 scene 为 IdType.INPUT）
 * - 消费端比对：selectById(scene) 取当前版本，newVersion > curVersion 才重建 DFA
 */
@Mapper
public interface SensitiveWordVersionMapper extends BaseMapper<SensitiveWordVersion> {
}
