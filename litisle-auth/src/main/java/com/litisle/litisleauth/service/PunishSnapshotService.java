package com.litisle.litisleauth.service;


import com.litisle.litisleapi.dto.moderation.PunishStatusDTO;

/**
 * 禁言/封禁态快照维护服务（临时清单 B3，§4.2.3/§4.5.2）
 *
 * 【定位】快照源在 auth/user，拦截点在 content/social 发布链路（经 user 契约校验）——
 * 本服务维护 auth 侧权威态镜像：punish.result 事件触发回查后「覆盖式」写入
 *
 * 【数据源与纪律】
 * - 权威源：review 侧 GET /api/review/punish/status（事件仅触发信号，快照一律以回查覆盖）
 * - 存储：Redis 快照（key 各段服务端派生，value 一律 JSON 禁 Java 原生反序列化）
 *        + 本地短 TTL 缓存（削 Feign 回查峰值；短 TTL 防快照陈旧）
 * - 撤销语义：回查返回全空快照（banned=false/muted=false）即覆盖写入=同步解封，
 *   不存在「只加不减」分支（防撤销事件丢失后误禁持续——由覆盖式语义天然收敛）
 *
 * @author litisle
 */
public interface PunishSnapshotService {

    /**
     * 覆盖式写入快照（B1 步骤3）：以回查权威态覆盖本地，撤销=全空快照覆盖=同步解封
     *
     * @param status review 侧权威态（禁言/封禁快照），非 null（调用方保证回查成功）
     */
    void overwriteFromAuthority(PunishStatusDTO status);

    /**
     * 读快照（供发布链路拦截/会话校验兜底读取）
     *
     * @return 当前快照；无记录时返回全空快照（不返回 null，避免调用方空指针分支）
     */
    PunishStatusDTO getSnapshot(Long userId);
}
