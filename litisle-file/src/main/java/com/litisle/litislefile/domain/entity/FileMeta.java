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
 * safeFlag      tinyint        —— 【预留｜已定接入，暂未开发】违规信息审核：0-违规 1-正常；
 *                  审核链路：status=1 后异步消息 → 病毒/违规检测 → 写 safe_flag；
 *                  getUrl/batchGetUrls 返回前校验（访问闸口单点收口）。启用时需同步
 *                  Sql-Document §八建表语句加列（见 seaweedfs笔记.md §3.6/§3.6.3）
 * status        tinyint        —— 0-上传中（锁内占位）/ 1-上传完成（锁外 put 成功后更新）；
 *                  put 失败保留 0，由对账任务清理（恢复或废弃）
 * sha256        varchar(64)    —— 联合判重键（sha256 + size + biz_type），索引
 *                  idx_dedup(sha256, size, biz_type, del_flag)；PRIVATE/LICENSE 去重
 *                  追加 owner_id 条件（按 owner 隔离，防跨用户秒传探测泄露隐私）。
 *                  判重限定 biz_type → URL 拼接永不跨桶坏链（见 seaweedfs笔记.md §3.4.5）
 * delFlag       —— 软删除标记；对账任务扫 del_flag=1 且超 7 天 → 物理清理
 *
 * MyBatis-Plus：@TableName("file_meta") + @TableId(value="id", type=AUTO)；
 * 枚举字段靠 yml default-enum-type-handler 自动映射
 */
public class FileMeta {
}
