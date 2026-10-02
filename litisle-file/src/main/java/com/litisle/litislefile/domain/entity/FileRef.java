package com.litisle.litislefile.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislefile.enums.StorageBizTypeEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * file_ref 实体（文件引用登记表，方案C）
 * <p>背景：秒传使一条 file_meta 成为多方共享资产（B 秒传命中 A 的记录并把 fileId 写进
 * 业务表），A 触发"del_flag=1 → 7 天后物理清理"会悬空 B 的所有引用——7 天缓冲只防
 * "误删恢复"，不防"共享悬空"</p>
 * <p>规则：业务方绑定/解绑引用时与业务表同事务登记本表；delete 仅解绑当前调用方引用；
 * 对账任务物理清理前置校验本表无活跃引用（详见 Sql-Document §八 / seaweedfs笔记.md §3.4）</p>
 * <p>MyBatis-Plus：@TableName("file_ref") + @TableId(value="id", type=AUTO)</p>
 * <p>枚举字段靠 yml default-enum-type-handler 自动映射</p>
 */
@Data
@Accessors(chain = true)
@TableName("file_ref")
public class FileRef implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 引用的文件ID（file_meta.file_id）
     * <p>varchar(64)，索引 idx_file(file_id)：解绑与物理清理前置校验的查询入口</p>
     */
    @TableField("file_id")
    private String fileId;

    /**
     * 引用方业务类型
     * <p>AVATAR（sys_user.avatar_file_id）/ COVER / ARTICLE_IMG（article_image）/ LICENSE</p>
     */
    @TableField("biz_type")
    private StorageBizTypeEnum bizType;

    /**
     * 引用方业务主键
     * <p>如 article_image.id；换头像场景约定记 sys_user.id（无独立业务表行）</p>
     */
    @TableField("biz_id")
    private Long bizId;

    /**
     * 引用归属用户ID（审计与授权依据）
     * <p>delete 解绑时按 file_id + owner_id 定位当前调用方的引用行</p>
     */
    @TableField("owner_id")
    private Long ownerId;

    /**
     * 引用建立时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}