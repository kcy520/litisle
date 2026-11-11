package com.litisle.litislesearch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.PageResult;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchQueryConstant;
import com.litisle.litislesearch.domain.entity.SearchHistory;
import com.litisle.litislesearch.domain.vo.SearchHistoryVO;
import com.litisle.litislesearch.mapper.SearchHistoryMapper;
import com.litisle.litislesearch.service.SearchHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索历史服务实现（批次六 6.2）。
 *
 * 核心口径：
 * 1. 同词 UPSERT 仅前进：按 (user_id, keyword) 命中则只刷新 updated_at（唯一键冲突走兜底更新）；
 * 2. 上限懒删：记录后若超过 search.history.max-per-user，按 updated_at 保留最新 N 条、删除其余；
 * 3. 分页统一 Page 对象（禁止 QueryWrapper#last() 拼 SQL）；
 * 4. 隐私：记录/查询/清理全部按 userId 收口，关键词只落 MySQL。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchHistoryServiceImpl implements SearchHistoryService {

    /** 搜索历史 Mapper（search_history 单表 CRUD） */
    private final SearchHistoryMapper searchHistoryMapper;

    /** search 配置（历史上限、分页上限） */
    private final SearchProperties properties;

    /**
     * 异步记录搜索关键词（同词仅刷新最近搜索时间 + 超上限懒删最旧）。
     *
     * <p>异步线程脱离原请求上下文，因此 userId/keyword 必须在调用前取出传入；
     * 内部整体 try/catch，记录失败只告警，绝不影响检索主链路。</p>
     *
     * @param userId  用户 ID（未登录为 null）
     * @param keyword 原始搜索关键词
     */
    @Override
    @Async //被它标记的方法，会在调用时交给 Spring 的线程池去“后台执行”，调用方不用等它跑完，直接继续往下走。
    public void recordAsync(Long userId, String keyword) {
        // 步骤1：未登录不记录（历史仅对本人可见）
        if (userId == null) {
            return;
        }
        // 步骤2：关键词归一化并过滤（空/超长不落库，长度上限与检索词一致）
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.isEmpty() || normalized.length() > SearchQueryConstant.SEARCH_KEYWORD_MAX_LENGTH) {
            return;
        }
        try {
            // 步骤3：同词 UPSERT——已存在只前进 updated_at，不产生重复行
            boolean updated = updateExisting(userId, normalized);
            if (!updated) {
                insertNew(userId, normalized);
            }
            // 步骤4：上限懒删（保留最新 maxPerUser 条）
            trimToLimit(userId);
        } catch (Exception e) {
            // 历史记录属辅助能力：任何异常只告警，不影响检索
            log.warn("[搜索历史] 记录失败（不影响检索主链路），userId={}", userId, e);
        }
    }

    /**
     * 分页查询当前用户搜索历史（按 updated_at 降序）。
     *
     * @param userId   用户 ID
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 分页结果
     */
    @Override
    public PageResult<SearchHistoryVO> list(Long userId, int pageNum, int pageSize) {
        // 步骤1：分页参数校验（与检索接口口径一致：页码≥1，条数≤maxPageSize）
        validatePage(pageNum, pageSize);
        // 步骤2：按用户 + 最近搜索时间降序分页（Page 对象，禁止 last() 拼 SQL）
        Page<SearchHistory> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SearchHistory> wrapper = new LambdaQueryWrapper<SearchHistory>()
                .eq(SearchHistory::getUserId, userId)
                .orderByDesc(SearchHistory::getUpdatedAt)
                .orderByDesc(SearchHistory::getId);
        IPage<SearchHistory> pageResult = searchHistoryMapper.selectPage(page, wrapper);
        // 步骤3：转 VO（不回传 userId）
        return PageResult.of(pageResult, this::toVO);
    }

    /**
     * 清空当前用户全部搜索历史。
     *
     * @param userId 用户 ID
     * @return 删除条数
     */
    @Override
    public int clear(Long userId) {
        // 按 userId 收口删除，绝不出现无 user_id 条件的全表清空
        return searchHistoryMapper.delete(new LambdaQueryWrapper<SearchHistory>()
                .eq(SearchHistory::getUserId, userId));
    }

    // ==================== 私有方法 ====================

    /**
     * 同词 UPSERT 的「已存在则前进时间」分支。
     *
     * @param userId   用户 ID
     * @param keyword  归一化关键词
     * @return true=命中并已更新；false=不存在，需要插入
     */
    private boolean updateExisting(Long userId, String keyword) {
        int rows = searchHistoryMapper.update(null, new LambdaUpdateWrapper<SearchHistory>()
                .eq(SearchHistory::getUserId, userId)
                .eq(SearchHistory::getKeyword, keyword)
                .set(SearchHistory::getUpdatedAt, LocalDateTime.now()));
        return rows > 0;
    }

    /**
     * 插入新关键词记录（并发冲突兜底：唯一键冲突时退化为前进时间）。
     *
     * @param userId  用户 ID
     * @param keyword 归一化关键词
     */
    private void insertNew(Long userId, String keyword) {
        try {
            searchHistoryMapper.insert(new SearchHistory()
                    .setUserId(userId)
                    .setKeyword(keyword)
                    .setUpdatedAt(LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            // 并发下另一线程已插入同词：唯一键冲突不报错，退化为更新最近时间
            updateExisting(userId, keyword);
        }
    }

    /**
     * 上限懒删：单用户记录超过 max-per-user 时，保留最新 N 条并删除其余。
     *
     * @param userId 用户 ID
     */
    private void trimToLimit(Long userId) {
        int maxPerUser = properties.getHistory().getMaxPerUser();
        // 配置非法（<=0）视为不限制
        if (maxPerUser <= 0) {
            return;
        }
        // 未超上限直接返回（绝大多数请求走这里，避免多余查询）
        long total = searchHistoryMapper.selectCount(new LambdaQueryWrapper<SearchHistory>()
                .eq(SearchHistory::getUserId, userId));
        if (total <= maxPerUser) {
            return;
        }
        // 用 Page 取「应保留」的最新 N 条 ID（Page 对象分页，不拼 LIMIT SQL）
        Page<SearchHistory> keepPage = new Page<>(1, maxPerUser, false);
        List<Long> keepIds = searchHistoryMapper.selectPage(keepPage,
                        new LambdaQueryWrapper<SearchHistory>()
                                .eq(SearchHistory::getUserId, userId)
                                .orderByDesc(SearchHistory::getUpdatedAt)
                                .orderByDesc(SearchHistory::getId)
                                .select(SearchHistory::getId))
                .getRecords().stream()
                .map(SearchHistory::getId)
                .toList();
        if (keepIds.isEmpty()) {
            return;
        }
        // 删除不在保留集合内的旧记录（notIn 空集合会生成非法 SQL，故上面已判空）
        searchHistoryMapper.delete(new LambdaQueryWrapper<SearchHistory>()
                .eq(SearchHistory::getUserId, userId)
                .notIn(SearchHistory::getId, keepIds));
    }

    /**
     * 历史分页参数校验（与检索接口口径一致）。
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     */
    private void validatePage(int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1) {
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID);
        }
        if (pageSize > properties.getPage().getMaxPageSize()) {
            throw new BizException(ErrorCode.SEARCH_PAGE_EXCEEDED);
        }
    }

    /**
     * 实体转列表 VO（只暴露 keyword/updatedAt）。
     *
     * @param entity 历史实体
     * @return 历史列表项
     */
    private SearchHistoryVO toVO(SearchHistory entity) {
        SearchHistoryVO vo = new SearchHistoryVO();
        vo.setKeyword(entity.getKeyword());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
