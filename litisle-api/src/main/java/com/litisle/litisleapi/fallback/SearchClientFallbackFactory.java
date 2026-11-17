package com.litisle.litisleapi.fallback;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.search.SearchRequestDTO;
import com.litisle.litisleapi.dto.search.SimilarConditionRequestDTO;
import com.litisle.litisleapi.feign.SearchClient;
import com.litisle.litisleapi.vo.search.SearchResultVO;
import com.litisle.litisleapi.vo.search.SimilarArticleVO;
import com.litisle.litisleapi.vo.search.UserSearchVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;

/**
 * SearchClient 降级工厂
 *
 * 【降级语义】fail-fast：相似推荐是 ai 服务 RAG 核心读依赖，静默返回空列表会让
 * RAG 误判「无相关素材」生成低质回答；异常穿透到调用方由调用方自行决策
 * （前端相关文章可置空展示，RAG 可跳过知识库补充）
 *
 * @author litisle
 */
@Slf4j
public class SearchClientFallbackFactory implements FallbackFactory<SearchClient> {

    @Override
    public SearchClient create(Throwable cause) {
        return new SearchClient() {

            /**
             * 相似推荐为 RAG 核心读依赖 fail-fast：静默空列表会让 RAG 误判「无相关素材」，
             * 异常穿透到调用方由调用方决策降级表现
             */
            @Override
            public Result<List<SimilarArticleVO>> similar(Long articleId, Integer topK) {
                // 记录调用参数与根因，便于熔断/网络故障排查
                log.error("远程调用 search 模块 similar 失败！articleId = {}, topK = {}", articleId, topK, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "搜索服务暂不可用，请稍后重试", cause);
            }

            /**
             * 服务间检索 fail-fast：静默空结果会让调用方误判「无命中」，异常穿透到调用方；
             * 注意 ES 不可用的降级热门列表由 search 服务端返回（source=DEGRADED），不经本工厂
             */
            @Override
            public Result<SearchResultVO> search(SearchRequestDTO dto) {
                // 记录检索请求与根因，便于熔断/网络故障排查
                log.error("远程调用 search 模块 search 失败！request = {}", dto, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "搜索服务暂不可用，请稍后重试", cause);
            }

            /**
             * 条件版相似检索 fail-fast（板块三 3.1）：静默空列表会让 ai 侧误判「条件无命中」，
             * 异常穿透到调用方由调用方决策降级表现（ai 侧降级 L0 普通相似推荐）
             */
            @Override
            public Result<List<SimilarArticleVO>> similarByCondition(SimilarConditionRequestDTO dto) {
                log.error("远程调用 search 模块 similarByCondition 失败！requestId = {}",
                        dto == null ? null : dto.getRequestId(), cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "搜索服务暂不可用，请稍后重试", cause);
            }

            /**
             * 用户笔名查询 fail-fast（板块八 1.2）：作者名解析失败由调用方决策
             * （ai 侧丢弃该条件继续检索，不中断条件管道）
             */
            @Override
            public Result<List<UserSearchVO>> searchUsers(String keyword, Integer limit) {
                log.error("远程调用 search 模块 searchUsers 失败！keywordChars = {}",
                        keyword == null ? 0 : keyword.length(), cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "搜索服务暂不可用，请稍后重试", cause);
            }

            /**
             * 文章 AI 标签回查 fail-fast（板块十二 4.6）：静默空 map 会让风格仿写误判
             * 「无标签可约束」，异常穿透到调用方由 AI 工具层组装明确失败响应
             */
            @Override
            public Result<java.util.Map<String, String>> getArticleAiLabels(Long articleId) {
                log.error("远程调用 search 模块 getArticleAiLabels 失败！articleId = {}", articleId, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "搜索服务暂不可用，请稍后重试", cause);
            }
        };
    }
}
