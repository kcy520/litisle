package com.litisle.litislefile.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislefile.enums.AccessLevelEnum;
import com.litisle.litislefile.enums.AlignEnum;
import com.litisle.litislefile.enums.BizTypeEnum;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.litislefile.enums.SafeFlagEnum;
import com.litisle.litislefile.enums.UploadStatusEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * file_meta 实体（litisle_file 库核心表）
 * <p>MyBatis-Plus：@TableName("file_meta") + @TableId(value="id", type=AUTO)</p>
 * <p>枚举字段靠 yml default-enum-type-handler 自动映射</p>
 */
@Data
@Accessors(chain = true)
@TableName("file_meta")
public class FileMeta implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * SeaweedFS 文件ID（业务主键，唯一索引）
     * <p>varchar(64) UK</p>
     * <p>业务层只持 fileId，与存储后端解耦，可换存储不动业务</p>
     */
    @Size(max = 64, message = "文件ID长度不能超过 64 个字符")
    @TableField("file_id")
    private String fileId;

    /**
     * 上传者ID（取网关透传的 X-User-Id）
     * <p>bigint</p>
     */
    @TableField("owner_id")
    private Long ownerId;

    /**
     * 业务类型（决定 bucket 归属）
     * <p>LICENSE 强制 PRIVATE 由服务层覆盖</p>
     * <p>AVATAR / COVER / ARTICLE_IMG / LICENSE</p>
     */
    @TableField("biz_type")
    private BizTypeEnum bizType;

    /**
     * SeaweedFS collection（bucket）
     * <p>varchar(32)</p>
     */
    @Size(max = 32, message = "bucket 长度不能超过 32 个字符")
    @TableField("bucket")
    private String bucket;

    /**
     * Filer 路径：{yyyy/MM/dd}/{fileId}.{ext}
     * <p>varchar(255)</p>
     */
    @Size(max = 255, message = "文件路径长度不能超过 255 个字符")
    @TableField("path")
    private String path;

    /**
     * MIME 类型
     * <p>varchar(64)</p>
     */
    @Size(max = 64, message = "MIME 长度不能超过 64 个字符")
    @TableField("mime")
    private String mime;

    /**
     * 文件大小（字节），联合判重键之一
     */
    @TableField("size")
    private Long size;

    /**
     * 图片宽度（非图片为 null）
     * <p>int，解析图片头获取</p>
     */
    @TableField("width")
    private Integer width;

    /**
     * 图片高度（非图片为 null）
     * <p>int，解析图片头获取</p>
     */
    @TableField("height")
    private Integer height;

    /**
     * 正文图片对齐（文件维度冗余）
     * <p>LEFT(1) / LEFT_CENTER(2) / CENTER(3) / RIGHT_CENTER(4) / RIGHT(5)</p>
     * <p>对齐权威来源在 content 库 article_image.align（见 File-Architecture.md §2.2）</p>
     * <p>非图片为 null</p>
     */
    @TableField("align")
    private AlignEnum align;

    /**
     * 访问级别：0-私有（预签名 URL）/ 1-公开（直链）
     * <p>LICENSE 强制 PRIVATE（服务层覆盖，default 0 仅兼底）</p>
     */
    @TableField("access")
    private AccessLevelEnum access;

    /**
     * 上传状态
     * <p>0-上传中（锁内占位）/ 1-上传完成（锁外 put 成功后更新）</p>
     * <p>put 失败保留 0，由对账任务清理（恢复或废弃）</p>
     */
    @TableField("status")
    private UploadStatusEnum status;

    /**
     * 违规审核三态（default-deny）
     * <p>0-违规 / 1-正常 / 2-待检测</p>
     * <p>【预留｜已定接入，暂未开发】</p>
     * <p>审核链路：status=1 后异步消息 → 病毒/违规检测 → 写 safe_flag</p>
     * <p>getUrl/batchGetUrls 返回前校验（访问闸口单点收口）</p>
     * <p>启用时需同步 Sql-Document §八建表语句加列（见 seaweedfs笔记.md §3.6/§3.6.3）</p>
     */
    @TableField("safe_flag")
    private SafeFlagEnum safeFlag;

    /**
     * SHA256 摘要，联合判重键之一
     * <p>varchar(64)，索引 idx_dedup(sha256, size, biz_type, del_flag)</p>
     * <p>PRIVATE/LICENSE 去重追加 owner_id 条件（按 owner 隔离，防跨用户秒传探测泄露隐私）</p>
     * <p>判重限定 biz_type → URL 拼接永不跨桶坏链（见 seaweedfs笔记.md §3.4.5）</p>
     */
    @Size(max = 64, message = "SHA256 长度不能超过 64 个字符")
    @TableField("sha256")
    private String sha256;

    /**
     * 软删除标记
     * <p>0-未删除 / 1-已删除</p>
     * <p>仅当 file_ref 无活跃引用才可置 1（方案C，防秒传共享悬空他人引用）；置 1 后不再参与秒传判重</p>
     * <p>对账任务扫 del_flag=1 且 delete_time 超 7 天且 file_ref 无活跃引用 → 物理清理</p>
     */
    @TableField("del_flag")
    private DelFlag delFlag;

    /**
     * 上传时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField("update_time")
    private LocalDateTime updateTime;

    /**
     * 软删除时间（del_flag 置 1 时写入）
     * <p>物理清理按此判超 7 天（方案C，配套 Sql-Document §八新增列）</p>
     */
    @TableField("delete_time")
    private LocalDateTime deleteTime;
}