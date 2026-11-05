package com.litisle.litislecontent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 推荐流分页出参（批次十 10.2 契约，文章流/书籍流共用）
 * <p>游标式无限刷：前端只认 cursor + snapshotId，不传页码；快照失效返回 reset=true，
 * 前端丢弃当前列表并从新快照头部重新拉取</p>
 *
 * @param <T> 流条目类型（文章流为 {@link FeedItemVO}，书籍流为 {@link BookFeedItemVO}）
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class FeedPageVO<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页条目（本快照切片结果，已通过门控回查） */
    private List<T> items;

    /** 下一页游标（快照内偏移的字符串编码；为空表示已到底） */
    private String cursor;

    /** 当前快照版本号（前端下次请求原样回传，用于校验快照是否仍有效） */
    private String snapshotId;

    /** 是否还有下一页（false 时前端停止加载，等下次会话重新建池） */
    private boolean hasMore;

    /** 快照是否已失效（true 表示服务端已重建快照，前端应清空列表重新渲染） */
    private boolean reset;
}
