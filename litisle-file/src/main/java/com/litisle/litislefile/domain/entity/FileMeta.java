package com.litisle.litislefile.domain.entity;

/**
 * file_meta 实体（litisle_file 库核心表，字段对齐 Sql-Document.md §八）：
 *
 * fileId        varchar(64) UK —— 业务主键（SeaweedFS 文件 ID），与存储后端解耦，可换存储不动业务
 * ownerId       bigint        —— 上传者（取 X-User-Id）
 * bizType       BizTypeEnum   —— AVATAR/COVER/ARTICLE_IMG/LICENSE（决定 bucket 归属）
 * bucket        varchar(32)   —— SeaweedFS collection
 * path          varchar(255)  —— Filer 路径：{yyyy/MM/dd}/{fileId}.{ext}
 * mime/size     —— 类型与字节数
 * width/height  int           —— 图片尺寸（解析图片头获取）
 * align         AlignEnum     —— 文件维度冗余；对齐权威来源在 content 的 article_image 表
 *                  LEFT(1) / LEFT_CENTER(2) / CENTER(3) / RIGHT_CENTER(4) / RIGHT(5)，见 File-Architecture.md §2.2
 * access        AccessLevelEnum —— 0-私有/1-公开；LICENSE 强制 PRIVATE（服务层覆盖，default 0 仅兼底）
 * sha256        varchar(64) IDX —— 去重/秒传查询入口
 * delFlag       —— 软删除标记；定时任务扫 del_flag=1 且超 7 天 → 物理清理
 *
 * MyBatis-Plus：@TableName("file_meta") + @TableId(value="id", type=AUTO)；
 * 枚举字段靠 yml default-enum-type-handler 自动映射
 */
public class FileMeta {
}
