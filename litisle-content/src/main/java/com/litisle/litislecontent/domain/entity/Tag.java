package com.litisle.litislecontent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.litislecontent.enums.TagStatusEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * tag 实体（litisle_content 库标签表，全局池、受控新建）
 * <p>设计原因：标签是开放词汇关键词（区别于 category 单选分类），独立池支撑改名/封禁/热门统计；
 * 每篇文章上限 5 个防堆砌，新建过 DFA 闸门；与正文方案B（禁编已发布正文）解耦——标签不影响 content_hash</p>
 */
@Data
@Accessors(chain = true)
@TableName("tag")
public class Tag implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 标签ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 标签名（≤20字符，新建过 DFA 闸门）
     * <p>varchar(20)，唯一索引 uk_name</p>
     */
    @Size(max = 20, message = "标签名长度不能超过 20 个字符")
    @TableField("name")
    private String name;

    /**
     * 引用文章数（冗余计数）
     * <p>异步刷新，索引 idx_use_count 支撑热门 TOP N</p>
     */
    @TableField("use_count")
    private Long useCount;

    /**
     * 状态
     * <p>NORMAL(0)-正常 / BANNED(1)-封禁（违规标签运营处置，封禁后列表侧不再聚合展示）</p>
     */
    @TableField("status")
    private TagStatusEnum status;

    /**
     * 删除标志
     * <p>NOT_DELETED(0)-未删除 / DELETED(1)-已删除</p>
     */
    @TableField("del_flag")
    private DelFlag delFlag;

    /**
     * 创建时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}