package com.litisle.litisleapi.vo.search;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 搜索结果 VO（跨服务检索契约出参，SearchClient.search 返回）
 *
 * 【使用场景】SearchClient.search(SearchRequestDTO) 返回体；REST 端点 GET /search 复用
 *
 * 【设计要点】
 * - 命中列表按 type 拆为三个强类型字段（articleList/bookList/userList）：
 *   一次查询按 type 只查一个索引，仅对应列表非空、其余为空集合；
 *   Java 泛型无法在固定方法签名下按 type 动态切换元素类型，三字段是强类型与
 *   Jackson 序列化友好的落地方式（对应文档 list<ArticleDocVO|BookDocVO|UserDocVO>）
 * - source 标记结果来源（ES 常规 / fallback 兜底 / DEGRADED 降级），批次A A1/A3 定案：
 *   非 ES 来源（fallback/DEGRADED）结果调用方不得缓存
 *
 * @author litisle
 */
@Data
public class SearchResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 命中总数（当前筛选条件下的全量计数，供前端分页） */
    private Long total;

    /** 页码（透传请求 pageNum） */
    private Integer pageNum;

    /** 每页条数（透传请求 pageSize） */
    private Integer pageSize;

    /**
     * 结果来源标记：ES-常规检索 ｜ fallback-空结果兜底（查询词 Embedding→KNN，批次A A1）｜
     * DEGRADED-ES 不可用降级热门列表（批次A A3）；非 ES 来源结果不得缓存
     */
    private String source;

    /** 文章命中列表（type=article 时非空，其余场景为空集合） */
    private List<ArticleDocVO> articleList;

    /** 书籍命中列表（type=book 时非空，其余场景为空集合） */
    private List<BookDocVO> bookList;

    /** 用户命中列表（type=user 时非空，其余场景为空集合） */
    private List<UserDocVO> userList;
}
