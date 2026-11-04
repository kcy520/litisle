package com.litisle.litislesearch.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislesearch.constant.SearchBizConstant;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 搜索历史实体（批次六 6.2，对应 search_history 表，search 唯一自有表）。
 *
 * 【隐私纪律】关键词只落 MySQL 本表，绝不写入任何 ES 索引文档/向量库；
 * 查询接口按当前登录用户 ID 收口，仅本人可见。
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
@TableName(SearchBizConstant.TABLE_SEARCH_HISTORY)
public class SearchHistory {

    /** 主键 ID（数据库自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 ID（sys_user.id；查询/清理均按本列收口，防越权查看他人历史） */
    private Long userId;

    /** 搜索关键词（长度上限与检索词一致：SEARCH_KEYWORD_MAX_LENGTH=64） */
    private String keyword;

    /** 最近搜索时间（同词 UPSERT 仅更新本列；列表按本列降序） */
    private LocalDateTime updatedAt;

    // ==================== 表字段与常量说明（不参与序列化） ====================

    // 唯一键 uk_user_keyword(user_id, keyword)：应用层 UPSERT 冲突时仅前进 updated_at；
    // 上限懒删由应用层实现（单用户超过 history.max-per-user 时删除最旧记录）
}
