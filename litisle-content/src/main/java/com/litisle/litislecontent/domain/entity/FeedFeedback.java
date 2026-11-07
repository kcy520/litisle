package com.litisle.litislecontent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * feed_feedback 推荐流负反馈实体（批次十一 11.11）
 *
 * <p>一条记录 = 某用户对某条内容/某作者/某分类的一次屏蔽。三类维度共用一张表：</p>
 * <ul>
 *   <li>feedbackType=1：不感兴趣，targetId=内容ID，targetKey 为空串；</li>
 *   <li>feedbackType=2：屏蔽作者，targetId=作者ID，targetKey 为空串；</li>
 *   <li>feedbackType=3：屏蔽分类，targetId=0，targetKey=分类名。</li>
 * </ul>
 *
 * <p>唯一键 (user_id, feedback_type, stream, target_id, target_key) 防重复提交；
 * 撤销由删除行实现（重复删除幂等）。</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
@TableName("feed_feedback")
public class FeedFeedback implements Serializable {

    /** 序列化版本号（缓存/跨进程传输兼容标识） */
    private static final long serialVersionUID = 1L;

    /** 主键（数据库自增） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 提交反馈的用户ID（必须登录，匿名无稳定身份无法长期屏蔽） */
    @TableField("user_id")
    private Long userId;

    /** 反馈类型：1-不感兴趣（单条内容） 2-屏蔽作者 3-屏蔽分类 */
    @TableField("feedback_type")
    private Integer feedbackType;

    /** 内容流：article/book（内容ID与作者ID按流区分命名空间） */
    @TableField("stream")
    private String stream;

    /** 内容ID或作者ID；分类类型固定为 0 */
    @TableField("target_id")
    private Long targetId;

    /** 分类名；内容/作者类型固定为空串 */
    @TableField("target_key")
    private String targetKey;

    /** 提交时间（屏蔽时间窗按该列过滤；超过窗口自动失效） */
    @TableField("create_time")
    private LocalDateTime createTime;
}
