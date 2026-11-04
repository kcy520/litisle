package com.litisle.litislesearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.content.BookIndexDocDTO;
import com.litisle.litisleapi.feign.ContentClient;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchBizConstant;
import com.litisle.litislesearch.domain.entity.BookIndexDoc;
import com.litisle.litislesearch.service.BookIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 书籍索引同步服务实现（批次五 B4）。
 *
 * 核心铁律：
 * 1. 事件体只带 bookId，书名/作者/简介/状态一律 ContentClient 回查权威值（禁信事件体）；
 * 2. 写入门控：未删除（delFlag=0）且非下架（status≠2）才入索引，否则删除索引文档；
 * 3. 幂等：写入按 bookId upsert、删除按 bookId delete（不存在视为成功），重复消费结果一致；
 * 4. 异常分流：书籍不存在（已软删/已删除）→ 删除索引文档后正常返回；
 *    ES/网络等可恢复异常抛非 BizException，由消费端延迟重试。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookIndexServiceImpl implements BookIndexService {

    /** ES 客户端（book_idx 读写入口） */
    private final ElasticsearchClient elasticsearchClient;

    /** content 服务客户端（书籍建索引元数据回查） */
    private final ContentClient contentClient;

    /** search 配置（索引名等） */
    private final SearchProperties properties;

    /**
     * 处理书籍变更：回查 → 门控 → upsert 或删除。
     *
     * @param bookId 书籍 ID
     */
    @Override
    public void handleChanged(Long bookId) {
        // 步骤1：回查权威数据（事件体只有定位键）
        BookIndexDocDTO dto = fetchBook(bookId);
        // 步骤2：书籍不存在（已软删/已删除）→ 删除索引文档并结束（正常收敛路径，非异常）
        if (dto == null) {
            deleteBookDoc(bookId);
            log.info("[索引同步] 书籍已不存在，已清理索引：bookId={}", bookId);
            return;
        }
        // 步骤3：写入门控——已删除或已下架不对外展示，删除索引文档
        if (!isIndexable(dto)) {
            deleteBookDoc(bookId);
            log.info("[索引同步] 书籍门控未通过，已清理索引：bookId={}, status={}, delFlag={}",
                    bookId, dto.getStatus(), dto.getDelFlag());
            return;
        }
        // 步骤4：组装并 upsert 索引文档（按 bookId 幂等覆盖）
        upsertBookDoc(buildIndexDoc(dto));
        log.info("[索引同步] 书籍索引写入完成：bookId={}", bookId);
    }

    /**
     * 回查书籍建索引数据：区分「业务性不存在」与「可恢复故障」。
     *
     * @param bookId 书籍 ID
     * @return 书籍索引回查 DTO；书籍不存在/已软删返回 null
     */
    private BookIndexDocDTO fetchBook(Long bookId) {
        // 调用 content 内部端点（路径 /api/feign/content/book-index-doc，网关不路由）
        Result<BookIndexDocDTO> result = contentClient.getBookIndexDoc(bookId);
        // 成功分支：取数据
        if (result != null && result.isSuccess() && result.getData() != null) {
            return result.getData();
        }
        int code = result == null ? ErrorCode.REMOTE_SERVICE_ERROR.getCode() : result.getCode();
        // 业务性不存在：书籍已删除/从未创建，消息无意义 → 返回 null 由调用方删索引（幂等）
        if (code == ErrorCode.BOOK_NOT_FOUND.getCode()) {
            return null;
        }
        // 其余（远程服务异常/未知错误）属可恢复故障 → 非 BizException，触发延迟重试
        throw new IllegalStateException("回查书籍失败（可重试），bookId=" + bookId + ", code=" + code);
    }

    /**
     * 判断书籍是否允许入索引：未删除且非下架。
     *
     * @param dto 书籍回查数据
     * @return true=允许入索引
     */
    private boolean isIndexable(BookIndexDocDTO dto) {
        return SearchBizConstant.DEL_FLAG_NOT_DELETED.equals(dto.getDelFlag())
                && !SearchBizConstant.BOOK_STATUS_TAKEN_DOWN.equals(dto.getStatus());
    }

    /**
     * 组装书籍索引文档（字段与 EsIndexInitializer#bookMapping 对齐）。
     *
     * @param dto 书籍回查数据
     * @return 索引文档
     */
    private BookIndexDoc buildIndexDoc(BookIndexDocDTO dto) {
        BookIndexDoc doc = new BookIndexDoc();
        doc.setId(String.valueOf(dto.getBookId()));
        doc.setTitle(dto.getTitle());
        // 作者 ID 转字符串存 keyword（批次五新增字段，读侧再解析回 Long）
        doc.setAuthorId(dto.getAuthorId() == null ? null : String.valueOf(dto.getAuthorId()));
        doc.setAuthorName(dto.getAuthorName());
        doc.setSummary(dto.getSummary());
        doc.setCategory(dto.getCategory());
        doc.setUpdateTime(dto.getUpdateTime());
        return doc;
    }

    /**
     * 写入（upsert）书籍索引文档。
     *
     * @param doc 索引文档
     */
    private void upsertBookDoc(BookIndexDoc doc) {
        String bookIndex = properties.getIndex().getBook();
        try {
            // 按 id=bookId 写入：文档已存在则整篇覆盖（事件回查即权威全量）
            elasticsearchClient.index(i -> i.index(bookIndex).id(doc.getId()).document(doc));
        } catch (Exception e) {
            // ES 异常包装为非 BizException，触发消费端延迟重试
            throw new IllegalStateException("写入书籍索引失败（ES 异常，稍后重试），bookId=" + doc.getId(), e);
        }
    }

    /**
     * 删除书籍索引文档（不存在视为成功，幂等）。
     *
     * @param bookId 书籍 ID
     */
    private void deleteBookDoc(Long bookId) {
        String bookIndex = properties.getIndex().getBook();
        String id = String.valueOf(bookId);
        try {
            // 先判断存在性：ES delete 对不存在的文档返回 404，此处视为幂等成功
            boolean exists = elasticsearchClient.exists(e -> e.index(bookIndex).id(id)).value();
            if (!exists) {
                log.debug("[索引同步] 待删除书籍索引文档不存在（幂等跳过）：bookId={}", bookId);
                return;
            }
            elasticsearchClient.delete(d -> d.index(bookIndex).id(id));
        } catch (Exception e) {
            // 删除失败必须重试：下架/删除内容残留索引属对外展示红线
            throw new IllegalStateException("删除书籍索引失败（ES 异常，稍后重试），bookId=" + bookId, e);
        }
    }
}
