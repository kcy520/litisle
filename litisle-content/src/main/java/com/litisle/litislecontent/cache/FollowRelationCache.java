package com.litisle.litislecontent.cache;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.feign.SocialClient;
import com.litisle.litislecontent.constant.ContentBizConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 关注关系回查缓存（板块四 [4.1] visibility=3 仅关注者判定链）
 *
 * <p>【设计】Redis 短 TTL 缓存（基准 60s ±10% 抖动）兜 social 回查频次，防 social 抖动
 * 放大到内容读链路；缓存读写失败降级直查（不阻断判定链）；miss 才回查 SocialClient
 * （fail-fast：降级工厂抛 REMOTE_SERVICE_ERROR，不静默放行——宁严勿松）</p>
 * <p>【弱一致性】取关后最长 TTL 窗口内仍判关注者属可接受（status.md [4.1] 口径）；
 * follow.changed 事件联动失效留二期（见 Temporarily content 节）</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowRelationCache {

    /** social 关注关系 Feign 契约（提供方端点待 social 实现，见 SocialClient TODO） */
    private final SocialClient socialClient;

    /** Redis 读写模板（GET / 带抖动 TTL SET） */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 判定 viewer 是否关注 owner（visibility=3 仅关注者判定链收口）
     *
     * 作用：
     * 从redis缓存 查询 用户1 是否被用户2关注 ，如果不存在缓存，调用Social模块查询，
     * 然后将查询结果写入缓存，并返回查询结果。
     *
     * @param ownerId  被关注者（文章作者）ID
     * @param viewerId 关注者（查看者）ID（非空，匿名已在 getDetail 判定链前拦截）
     * @return true-已关注放行；false-非关注者（调用方按不存在处理，不暴露存在性）
     * @throws BizException REMOTE_SERVICE_ERROR social 不可用（fail-fast，不静默放行）
     */
    public boolean isFollowing(Long ownerId, Long viewerId) {

        //step1: 从redis查询缓存
        String key = ContentBizConstant.FOLLOW_RELATION_CACHE_KEY_PREFIX + ownerId + ":" + viewerId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                // 命中：1-关注 0-非关注（负缓存同样生效，防高频穿透回查）
                return "1".equals(cached);
            }
        } catch (Exception e) {
            // Redis 读失败降级直查 social（不阻断判定链；fail-fast 语义由 social 回查侧保证）
            log.warn("关注关系缓存读失败，降级直查 social，ownerId={}，viewerId={}", ownerId, viewerId, e);
        }

        // step2: 缓存结果为null，回查Social模块
        // miss：回查 social（业务 fail/数据缺失一律按非关注者处理，宁严勿松）
        Result<Boolean> result = socialClient.isFollowing(ownerId, viewerId);
        boolean following = result != null && result.isSuccess() && Boolean.TRUE.equals(result.getData());

        //step3: 写入缓存 并返回结果
        // 结果写缓存：TTL 基准 60s ±10% 抖动（55~66s），防同批键同刻失效雪崩；写失败仅留痕不阻断
        try {
            long base = ContentBizConstant.FOLLOW_RELATION_CACHE_TTL_SECONDS;
            long ttl = base + ThreadLocalRandom.current().nextLong(-base / 10, base / 10 + 1);
            stringRedisTemplate.opsForValue().set(key, following ? "1" : "0", Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("关注关系缓存写失败（下次回查直查 social），ownerId={}，viewerId={}", ownerId, viewerId, e);
        }


        return following;
    }
}
