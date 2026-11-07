package com.litisle.litislecontent.service.support;

import com.litisle.litislecontent.constant.FeedConstant;
import com.litisle.litislecontent.mapper.FeedExposureDailyMapper;
import com.litisle.litislecontent.metrics.ContentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.Collection;

/**
 * 推荐流曝光/点击归因支撑（批次十一 11.6 + 11.12）
 *
 * <p>职责：把「翻页返回」与「详情点击」两个事件落到 {@code feed_exposure_daily} 日聚合表。</p>
 *
 * <p>口径（2026-11-06 已拍板，不重新讨论）：</p>
 * <ol>
 *   <li>曝光：切片返回处按「流 + 内容 + 身份口径」批量 +1。同一身份同一天同一条只曝光一次，
 *       由现有当日曝光 Redis Set 保证，本类不再另建去重键；</li>
 *   <li>点击：详情接口带齐 {@code from=feed} + {@code snapshotId} 才算推荐流点击；
 *       未带归因参数按普通浏览处理，只计曝光、不计点击；</li>
 *   <li>身份：登录按 userId（identity_type=login），匿名按 X-Anon-Id（identity_type=anon）；
 *       同身份同一天同一条点击最多计 1 次（Redis 点击 Set 去重）；</li>
 *   <li>降级：写失败只记 WARN + 指标「写失败」，绝不向翻页/详情链路抛出异常。</li>
 * </ol>
 *
 * <p>为什么 snapshotId 不落库：计数维度是「日期 + 流 + 内容 + 身份口径」，
 * 不区分快照代次；同身份同日同条点击已去重，快照代次对分子分母都没有影响，
 * 落库只会每行多存一段无用的 UUID 长字符串。因此 snapshotId 只作归因参数「必带」校验。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedExposureSupport {

    /** 曝光/点击日聚合 Mapper（批量曝光 upsert、单行点击 upsert） */
    private final FeedExposureDailyMapper feedExposureDailyMapper;

    /** 快照支撑（复用当日曝光/点击 Set 的曝光成员判断与点击去重） */
    private final FeedSnapshotSupport feedSnapshotSupport;

    /** 观测指标（只记录曝光/点击写失败，不为 CTR 建 Gauge） */
    private final ContentMetrics contentMetrics;

    /**
     * 记录一页实际返回的曝光（批量 upsert，一条 SQL 多行）。
     *
     * @param streamType 流类型（FeedConstant.STREAM_ARTICLE / STREAM_BOOK）
     * @param identity   身份标识（u{userId} / a{anonId}）
     * @param ids        本页实际返回的内容 ID（已按当日曝光 Set 过滤）
     */
    public void recordExposure(String streamType, String identity, Collection<Long> ids) {
        // 空页不写库，避免产生无意义 SQL
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 身份口径由身份前缀推导：u=登录、a=匿名
        String identityType = resolveIdentityType(identity);
        if (identityType == null) {
            // 身份标识非法属于调用方错误：跳过本页曝光，仅告警，不影响返回
            log.warn("推荐流曝光身份非法（跳过写库）：stream={}, identity={}", streamType, identity);
            return;
        }
        try {
            // 一页只发一条 SQL（多行 values + on duplicate key update），避免每页 20 次往返
            feedExposureDailyMapper.batchIncreaseExposure(LocalDate.now(), streamType, identityType, ids);
        } catch (Exception exception) {
            // 批次十一 11.6：曝光写失败不得阻断翻页，只记 WARN 与失败计数
            log.warn("推荐流曝光写库失败（不影响翻页）：stream={}, identityType={}, count={}, error={}",
                    streamType, identityType, ids.size(), exception.getMessage());
            contentMetrics.recordFeedExposureWriteFailure(streamType);
        }
    }

    /**
     * 记录一次推荐流点击（详情接口调用）。
     *
     * <p>只有带齐归因参数（from=feed 且 snapshotId 非空）才计点击；否则视为普通浏览直接返回。
     * 匿名点击必须携带合法的 X-Anon-Id，缺失/非法时无法稳定识别身份，按不计点击处理
     * （对应 2026-11-06 决策第 5 条的前置约束：点击侧必须稳定带同一个匿名标识）。</p>
     *
     * @param streamType 流类型（FeedConstant.STREAM_ARTICLE / STREAM_BOOK）
     * @param targetId   内容 ID（文章 ID 或书籍 ID）
     * @param viewerId   登录用户 ID（未登录传 null）
     * @param anonId     请求头 X-Anon-Id 原值（未登录时使用，可为空）
     * @param from       归因来源参数（仅 "feed" 计点击）
     * @param snapshotId 快照代次（必带，缺失则不计点击；仅作校验，不落库）
     */
    public void recordClick(String streamType, Long targetId, Long viewerId, String anonId,
                            String from, String snapshotId) {
        // 内容 ID 缺失无法归因，直接跳过
        if (targetId == null) {
            return;
        }
        // 归因参数不齐：只当普通浏览，不写点击（曝光已在翻页时计过）
        if (!FeedConstant.ATTRIBUTION_FROM_FEED.equals(from) || !StringUtils.hasText(snapshotId)) {
            return;
        }
        // 身份与身份口径：登录优先，匿名兜底
        String identity;
        String identityType;
        if (viewerId != null) {
            identity = FeedConstant.IDENTITY_USER_PREFIX + viewerId;
            identityType = FeedConstant.IDENTITY_TYPE_LOGIN;
        } else {
            // 匿名详情页不经过 AnonIdFilter（该过滤器只覆盖推荐流端点），这里直接校验请求头格式；
            // 不合法就不计数：若服务端临时生成新标识，每次点击都会变成「新身份」，反而灌大 CTR
            if (!isValidAnonId(anonId)) {
                log.debug("匿名点击缺少合法 X-Anon-Id（不计点击）：stream={}, targetId={}", streamType, targetId);
                return;
            }
            identity = FeedConstant.IDENTITY_ANON_PREFIX + anonId;
            identityType = FeedConstant.IDENTITY_TYPE_ANON;
        }
        try {
            // 前置校验：只有今天确实曝光过的内容才允许计点击，避免伪造参数造出无分母的点击行
            if (!feedSnapshotSupport.isExposedToday(streamType, identity, targetId)) {
                log.debug("点击内容当日未曝光（不计点击）：stream={}, targetId={}, identity={}",
                        streamType, targetId, identity);
                return;
            }
            // 同日同条去重：SADD 返回 0（已点过或 Redis 不可用）时不累计，保证 CTR 分子不被刷新放大
            if (!feedSnapshotSupport.markClicked(streamType, identity, targetId)) {
                return;
            }
            feedExposureDailyMapper.increaseClick(LocalDate.now(), streamType, targetId, identityType);
        } catch (Exception exception) {
            // 批次十一 11.6：点击写失败不得阻断详情返回，只记 WARN 与失败计数
            log.warn("推荐流点击写库失败（不影响详情返回）：stream={}, targetId={}, identityType={}, error={}",
                    streamType, targetId, identityType, exception.getMessage());
            contentMetrics.recordFeedClickWriteFailure(streamType);
        }
    }

    /**
     * 由身份标识推导身份口径（u 前缀=login，a 前缀=anon）。
     *
     * @param identity 身份标识（u{userId} / a{anonId}）
     * @return 身份口径常量；无法识别返回 null
     */
    private static String resolveIdentityType(String identity) {
        if (identity == null || identity.isEmpty()) {
            return null;
        }
        if (identity.startsWith(FeedConstant.IDENTITY_USER_PREFIX)) {
            return FeedConstant.IDENTITY_TYPE_LOGIN;
        }
        if (identity.startsWith(FeedConstant.IDENTITY_ANON_PREFIX)) {
            return FeedConstant.IDENTITY_TYPE_ANON;
        }
        return null;
    }

    /**
     * 校验匿名标识是否为合法 UUID v4（与 AnonIdFilter 使用同一正则，口径一致）。
     *
     * @param anonId 请求头携带的匿名标识，可为空
     * @return true=格式合法
     */
    private static boolean isValidAnonId(String anonId) {
        return StringUtils.hasText(anonId) && FeedConstant.ANON_ID_PATTERN.matcher(anonId).matches();
    }
}
