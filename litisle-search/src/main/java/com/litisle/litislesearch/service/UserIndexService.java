package com.litisle.litislesearch.service;

/**
 * 用户索引同步服务（批次五 B5）。
 *
 * 作用：消费 user.registered / user.profile-updated 事件后，回查用户公开资料并维护 user_idx：
 * 状态正常且存在可检索公开资料（笔名/简介）时 upsert，否则删除索引文档。
 *
 * @author litisle
 */
public interface UserIndexService {

    /**
     * 处理用户索引同步（注册与资料更新共用）。
     *
     * @param userId 用户 ID
     */
    void handleUpsert(Long userId);
}
