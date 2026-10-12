package com.litisle.litislefile.service;

import com.litisle.litisleapi.dto.file.FileContentDTO;
import com.litisle.litislefile.enums.SafeFlagEnum;

import java.util.List;
import java.util.Map;

/**
 * 文件审核联动能力接口（由 FileService 按业务域拆分；quarantine/unquarantine 由
 * FileFeignProviderController 提供服务间契约，updateSafeFlag/batchGetSafeFlags/getInternalContent
 * 由 ModerationResultListener（MQ）与 FileFeignProviderController 消费）：
 *
 * void quarantine(String fileId)      // 违规图隔离（moderation 侧 REJECT 时经 Feign 调用）
 * void unquarantine(String fileId)    // 隔离恢复（申诉改判 PASS 时经 Feign 调用）
 * void deleteQuarantined(String fileId) // 隔离图到期删除（review 侧 QuarantineSweeper 保留期收口经 Feign 委派）
 * void updateSafeFlag(String fileId, SafeFlagEnum safeFlag) // 审核结果回写 safe_flag
 * Map<String, Integer> batchGetSafeFlags(List<String> fileIds) // 批量读取 safe_flag
 * FileContentDTO getInternalContent(String fileId) // 机审取图（字节流，SSRF 防护唯一入口）
 */
public interface FileModerationService {

    /**
     * 违规图隔离（审核链路：moderation 侧 REJECT 时经 Feign 调用）：
     * 对象（本体 + 缩略图派生 key）从原前缀整体移入 quarantine/ 前缀，safe_flag=0；
     * del_flag 保持 0 不破坏 file_ref 不变式；幂等（已在隔离前缀则跳过）
     */
    void quarantine(String fileId);

    /**
     * 隔离恢复（申诉链路：改判 PASS 时经 Feign 调用）：
     * 对象从 quarantine/ 前缀移回原前缀，safe_flag 回 1；幂等（不在隔离前缀则跳过）
     */
    void unquarantine(String fileId);

    /**
     * 隔离图到期删除（6-3.2，review 侧 QuarantineSweeper 保留期收口经 Feign 委派）：
     * 前缀校验（必须已在 quarantine/ 前缀，防误删正常文件）→ 删本体 + 缩略图
     * （SeaweedFS 回收站兑底 7 天）→ 软删 del_flag=1 退出活跃池/秒传判重池；
     * 幂等（记录不存在/已软删直接跳过，重复指令与重扫安全）
     */
    void deleteQuarantined(String fileId);

    /**
     * 审核结果回写 safe_flag（仅元数据更新，不移动对象）
     * <p>moderation.result 事件：PASS→1 / SUSPICIOUS→3；REJECT 走 quarantine（含移动+置 0）。
     * 幂等（已处于目标态则跳过）</p>
     */
    void updateSafeFlag(String fileId, SafeFlagEnum safeFlag);

    /**
     * 批量读取 safe_flag（file 客户端契约提供方）：仅供 review 复审聚合/申诉恢复使用，
     * 禁止跨库直查 file_meta；仅返回活跃记录（del_flag=0），查不到的 fileId 不在 Map 中
     *
     * @param fileIds 文件ID列表
     * @return fileId → safe_flag code（SafeFlagEnum：0-违规/1-正常/2-待审/3-存疑）
     */
    Map<String, Integer> batchGetSafeFlags(List<String> fileIds);

    /**
     * 机审取图（file 客户端契约提供方）：按 fileId 读对象原始字节流；
     * 不过 safe_flag 闸口（申诉复核需重取隔离图 safe_flag=0）；仅服务间可用，不占公开面配额
     *
     * @param fileId 文件ID
     */
    FileContentDTO getInternalContent(String fileId);
}
