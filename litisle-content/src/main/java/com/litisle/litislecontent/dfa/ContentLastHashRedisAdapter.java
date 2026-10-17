package com.litisle.litislecontent.dfa;

import com.litisle.litisleapi.moderation.LastHashStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * last-hash Redis 读写适配器（content 宿主侧，{@link LastHashStore.RedisAdapter} 契约实现）
 *
 * <p>契约层零 spring-data-redis 依赖的宿主注入模式：免审判定（LastHashExemptChecker）
 * 与缓存失效（ContentUnpublishedInvalidator，板块八）经本适配器读写 Redis；
 * key/value 拼装与校验一律收口 LastHashStore，本类只做三原语透传</p>
 *
 * @author litisle
 */
@Component
@RequiredArgsConstructor
public class ContentLastHashRedisAdapter implements LastHashStore.RedisAdapter {

    /** Redis 读写模板（GET/SETEX/DEL 三原语与 LastHashStore 口径对齐） */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * GET：键不存在返回 null
     */
    @Override
    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * SETEX：带 TTL 写入（TTL 与结果缓存同窗，防免审口径漂移）
     */
    @Override
    public void setEx(String key, String value, long ttlSeconds) {
        stringRedisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
    }

    /**
     * DEL：删除键（content.unpublished 三路径 / 申诉改判 PASS 失效用）
     */
    @Override
    public void del(String key) {
        stringRedisTemplate.delete(key);
    }
}