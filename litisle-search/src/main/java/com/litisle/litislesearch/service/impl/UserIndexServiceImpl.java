package com.litisle.litislesearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.user.UserIndexDocDTO;
import com.litisle.litisleapi.feign.UserClient;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchBizConstant;
import com.litisle.litislesearch.domain.entity.UserIndexDoc;
import com.litisle.litislesearch.service.UserIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户索引同步服务实现（批次五 B5）。
 *
 * 核心铁律：
 * 1. 事件体只带 userId，笔名/简介/状态一律 UserClient 回查权威值（禁信事件体）；
 * 2. 写入门控：状态正常（1）、未删除（0），且至少一个可检索公开字段（笔名/简介）非空才入索引——
 *    新注册用户尚无公开资料，不建空文档（隐私最小化，也避免索引无意义的用户 ID）；
 * 3. 幂等：写入按 userId upsert、删除按 userId delete（不存在视为成功），重复消费结果一致；
 * 4. 异常分流：用户不存在（已删除/注销）→ 删除索引文档后正常返回；
 *    ES/网络等可恢复异常抛非 BizException，由消费端延迟重试。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserIndexServiceImpl implements UserIndexService {

    /** ES 客户端（user_idx 读写入口） */
    private final ElasticsearchClient elasticsearchClient;

    /** user 服务客户端（用户公开资料回查） */
    private final UserClient userClient;

    /** search 配置（索引名等） */
    private final SearchProperties properties;

    /**
     * 处理用户索引同步：回查 → 门控 → upsert 或删除。
     *
     * @param userId 用户 ID
     */
    @Override
    public void handleUpsert(Long userId) {
        // 步骤1：回查权威数据（事件体只有定位键）
        UserIndexDocDTO dto = fetchUser(userId);
        // 步骤2：用户不存在（已删除）→ 删除索引文档并结束（正常收敛路径，非异常）
        if (dto == null) {
            deleteUserDoc(userId);
            log.info("[索引同步] 用户已不存在，已清理索引：userId={}", userId);
            return;
        }
        // 步骤3：写入门控——非正常状态/已删除/无公开资料 → 删除索引文档
        if (!isIndexable(dto)) {
            deleteUserDoc(userId);
            log.info("[索引同步] 用户门控未通过，已清理索引：userId={}, status={}, delFlag={}",
                    userId, dto.getStatus(), dto.getDelFlag());
            return;
        }
        // 步骤4：组装并 upsert 索引文档（按 userId 幂等覆盖）
        upsertUserDoc(buildIndexDoc(dto));
        log.info("[索引同步] 用户索引写入完成：userId={}", userId);
    }

    /**
     * 回查用户公开资料：区分「业务性不存在」与「可恢复故障」。
     *
     * @param userId 用户 ID
     * @return 用户索引回查 DTO；用户不存在/已删除返回 null
     */
    private UserIndexDocDTO fetchUser(Long userId) {
        // 调用 user 内部端点（路径 /api/feign/user/index-doc，网关不路由）
        Result<UserIndexDocDTO> result = userClient.getUserIndexDoc(userId);
        // 成功分支：取数据
        if (result != null && result.isSuccess() && result.getData() != null) {
            return result.getData();
        }
        int code = result == null ? ErrorCode.REMOTE_SERVICE_ERROR.getCode() : result.getCode();
        // 业务性不存在：用户已删除/从未创建，消息无意义 → 返回 null 由调用方删索引（幂等）
        if (code == ErrorCode.USER_NOT_FOUND.getCode()) {
            return null;
        }
        // 其余（远程服务异常/未知错误）属可恢复故障 → 非 BizException，触发延迟重试
        throw new IllegalStateException("回查用户失败（可重试），userId=" + userId + ", code=" + code);
    }

    /**
     * 判断用户是否允许入索引：正常状态、未删除且存在公开可检索字段。
     *
     * @param dto 用户回查数据
     * @return true=允许入索引
     */
    private boolean isIndexable(UserIndexDocDTO dto) {
        // 状态与删除标志门控（禁用/注销/已删除一律不入索引）
        boolean stateOk = SearchBizConstant.USER_STATUS_NORMAL.equals(dto.getStatus())
                && SearchBizConstant.DEL_FLAG_NOT_DELETED.equals(dto.getDelFlag());
        if (!stateOk) {
            return false;
        }
        // 公开资料门控：笔名/简介至少要有一个非空，否则索引文档无检索价值
        boolean hasPenName = dto.getPenName() != null && !dto.getPenName().isBlank();
        boolean hasBio = dto.getBio() != null && !dto.getBio().isBlank();
        return hasPenName || hasBio;
    }

    /**
     * 组装用户索引文档（字段与 EsIndexInitializer#userMapping 对齐）。
     *
     * @param dto 用户回查数据
     * @return 索引文档
     */
    private UserIndexDoc buildIndexDoc(UserIndexDocDTO dto) {
        UserIndexDoc doc = new UserIndexDoc();
        doc.setId(String.valueOf(dto.getUserId()));
        doc.setPenName(dto.getPenName());
        doc.setBio(dto.getBio());
        doc.setUpdateTime(dto.getUpdateTime());
        return doc;
    }

    /**
     * 写入（upsert）用户索引文档。
     *
     * @param doc 索引文档
     */
    private void upsertUserDoc(UserIndexDoc doc) {
        String userIndex = properties.getIndex().getUser();
        try {
            // 按 id=userId 写入：文档已存在则整篇覆盖（事件回查即权威全量）
            elasticsearchClient.index(i -> i.index(userIndex).id(doc.getId()).document(doc));
        } catch (Exception e) {
            // ES 异常包装为非 BizException，触发消费端延迟重试
            throw new IllegalStateException("写入用户索引失败（ES 异常，稍后重试），userId=" + doc.getId(), e);
        }
    }

    /**
     * 删除用户索引文档（不存在视为成功，幂等）。
     *
     * @param userId 用户 ID
     */
    private void deleteUserDoc(Long userId) {
        String userIndex = properties.getIndex().getUser();
        String id = String.valueOf(userId);
        try {
            // 先判断存在性：ES delete 对不存在的文档返回 404，此处视为幂等成功
            boolean exists = elasticsearchClient.exists(e -> e.index(userIndex).id(id)).value();
            if (!exists) {
                log.debug("[索引同步] 待删除用户索引文档不存在（幂等跳过）：userId={}", userId);
                return;
            }
            elasticsearchClient.delete(d -> d.index(userIndex).id(id));
        } catch (Exception e) {
            // 删除失败必须重试：禁用/注销用户残留索引会暴露已下线账号
            throw new IllegalStateException("删除用户索引失败（ES 异常，稍后重试），userId=" + userId, e);
        }
    }
}
