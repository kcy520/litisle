package com.litisle.litislesearch.service;

import com.litisle.litisleapi.vo.search.UserSearchVO;

import java.util.List;

/**
 * 用户索引同步服务（批次五 B5）+ 用户笔名查询（板块八 1.2）。
 *
 * 作用：消费 user.registered / user.profile-updated 事件后，回查用户公开资料并维护 user_idx：
 * 状态正常且存在可检索公开资料（笔名/简介）时 upsert，否则删除索引文档。
 * 另提供按笔名模糊命中 user_idx 公开资料最小集的查询（作者名 → 作者 ID 解析，
 * 普通 Feign 查询非 LLM，ai 条件提取管道消费）。
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

    /**
     * 按笔名关键词查询用户（板块八 1.2：作者名 → 作者 ID 解析的数据源）。
     *
     * @param keyword 笔名关键词（必传）
     * @param limit   返回条数上限（可空；缺省并封顶 20，防超量拉取）
     * @return 命中用户最小集（userId + penName）；无命中为空列表不报错
     */
    List<UserSearchVO> searchByPenName(String keyword, Integer limit);
}
