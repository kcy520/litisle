package com.litisle.commonredis.util;



import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis 操作工具类
 * 
 * 【设计说明】
 * 1. 封装 RedisTemplate 的常用操作，简化业务代码
 * 2. 提供 String/Hash/Set/ZSet/List/BitMap 六大数据结构操作
 * 3. 统一处理异常，业务代码无需关心 Redis 异常
 * 
 * 【Redis 六大数据结构速查】
 * ├───────────┼─────────────────────────────────┤
 * │ 数据结构   │ 使用场景                         │
 * ├───────────┼─────────────────────────────────┤
 * │ String    │ 计数、验证码、缓存 JSON           │
 * │ Hash      │ 对象字段（用户信息、商品信息）     │
 * │ List      │ 消息队列、最新消息                │
 * │ Set       │ 关注关系、标签、去重              │
 * │ ZSet      │ 排行榜、延迟队列                 │
 * │ BitMap    │ 签到、在线状态、布隆过滤器底层     │
 * └───────────┴─────────────────────────────────┘
 * 
 * @author litisle
 */
@Slf4j
@RequiredArgsConstructor
public class RedisUtil {

    /**
     * RedisTemplate（通过构造器注入）
     */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * StringRedisTemplate（纯字符串操作）
     */
    private final StringRedisTemplate stringRedisTemplate;

    // ==================== 1. String 操作 ====================

    /**
     * 设置值
     * 
     * @param key   Key
     * @param value Value
     */
    public void set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value);
        } catch (Exception e) {
            log.error("Redis set 失败: key={}", key, e);
        }
    }

    /**
     * 设置值并指定过期时间
     * 
     * @param key     Key
     * @param value   Value
     * @param timeout 过期时间
     * @param unit    时间单位
     */
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, timeout, unit);
        } catch (Exception e) {
            log.error("Redis set 失败: key={}", key, e);
        }
    }

    /**
     * 获取值
     * 
     * @param key Key
     * @param <T> 值类型
     * @return Value（可能为 null）
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        try {
            return (T) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("Redis get 失败: key={}", key, e);
            return null;
        }
    }

    /**
     * 自增（原子操作）
     * 
     * 【使用场景】
     * - 浏览量计数
     * - 点赞数计数
     * - 粉丝数计数
     * 
     * @param key   Key
     * @param delta 增量（正数自增，负数自减）
     * @return 自增后的值
     */
    public Long increment(String key, long delta) {
        try {
            return redisTemplate.opsForValue().increment(key, delta);
        } catch (Exception e) {
            log.error("Redis increment 失败: key={}", key, e);
            return 0L;
        }
    }

    /**
     * 删除 Key
     * 
     * @param key Key
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Redis delete 失败: key={}", key, e);
        }
    }

    /**
     * 批量删除 Key
     * 
     * @param keys Key 集合
     */
    public void delete(Collection<String> keys) {
        try {
            redisTemplate.delete(keys);
        } catch (Exception e) {
            log.error("Redis 批量删除失败", e);
        }
    }

    /**
     * 设置过期时间
     * 
     * @param key     Key
     * @param timeout 过期时间
     * @param unit    时间单位
     * @return true=设置成功，false=Key 不存在
     */
    public boolean expire(String key, long timeout, TimeUnit unit) {
        try {
            return Boolean.TRUE.equals(redisTemplate.expire(key, timeout, unit));
        } catch (Exception e) {
            log.error("Redis expire 失败: key={}", key, e);
            return false;
        }
    }

    /**
     * 获取 Key 的剩余过期时间
     * 
     * @param key  Key
     * @param unit 时间单位
     * @return 剩余时间（-1=永不过期，-2=Key 不存在）
     */
    public long getExpire(String key, TimeUnit unit) {
        try {
            Long expire = redisTemplate.getExpire(key, unit);
            return expire != null ? expire : -2;
        } catch (Exception e) {
            log.error("Redis getExpire 失败: key={}", key, e);
            return -2;
        }
    }

    /**
     * 判断 Key 是否存在
     * 
     * @param key Key
     * @return true=存在，false=不存在
     */
    public boolean hasKey(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.error("Redis hasKey 失败: key={}", key, e);
            return false;
        }
    }

    // ==================== 2. Hash 操作 ====================

    /**
     * 设置 Hash 字段值
     * 
     * 【使用场景】存储对象字段（如用户信息）
     * Redis: hset user:1001 name "张三"
     *        hset user:1001 age 18
     * 
     * @param key   Hash 的 Key
     * @param field 字段名
     * @param value 字段值
     */
    public void hSet(String key, String field, Object value) {
        try {
            redisTemplate.opsForHash().put(key, field, value);
        } catch (Exception e) {
            log.error("Redis hSet 失败: key={}, field={}", key, field, e);
        }
    }

    /**
     * 批量设置 Hash 字段
     * 
     * @param key  Hash 的 Key
     * @param map  字段名-字段值 Map
     */
    public void hPutAll(String key, Map<String, Object> map) {
        try {
            redisTemplate.opsForHash().putAll(key, map);
        } catch (Exception e) {
            log.error("Redis hPutAll 失败: key={}", key, e);
        }
    }

    /**
     * 获取 Hash 字段值
     * 
     * @param key   Hash 的 Key
     * @param field 字段名
     * @param <T>   值类型
     * @return 字段值
     */
    @SuppressWarnings("unchecked")
    public <T> T hGet(String key, String field) {
        try {
            return (T) redisTemplate.opsForHash().get(key, field);
        } catch (Exception e) {
            log.error("Redis hGet 失败: key={}, field={}", key, field, e);
            return null;
        }
    }

    /**
     * 获取整个 Hash
     * 
     * @param key Hash 的 Key
     * @param <T> 值类型
     * @return 字段名-字段值 Map
     */
    @SuppressWarnings("unchecked")
    public <T> Map<String, T> hGetAll(String key) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            Map<String, T> result = new HashMap<>();
            entries.forEach((k, v) -> result.put((String) k, (T) v));
            return result;
        } catch (Exception e) {
            log.error("Redis hGetAll 失败: key={}", key, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 删除 Hash 字段
     * 
     * @param key    Hash 的 Key
     * @param fields 字段名（可变参数）
     */
    public void hDelete(String key, String... fields) {
        try {
            redisTemplate.opsForHash().delete(key, (Object[]) fields);
        } catch (Exception e) {
            log.error("Redis hDelete 失败: key={}", key, e);
        }
    }

    /**
     * 判断 Hash 中是否存在某个字段
     * 
     * @param key   Hash 的 Key
     * @param field 字段名
     * @return true=存在，false=不存在
     */
    public boolean hHasKey(String key, String field) {
        try {
            return redisTemplate.opsForHash().hasKey(key, field);
        } catch (Exception e) {
            log.error("Redis hHasKey 失败: key={}, field={}", key, field, e);
            return false;
        }
    }

    // ==================== 3. Set 操作 ====================

    /**
     * 向 Set 添加元素
     * 
     * 【使用场景】关注关系、标签
     * Redis: sadd user:1001:followers 2001
     *        sadd user:1001:followers 2002
     * 
     * @param key    Set 的 Key
     * @param values 元素值
     * @return 添加成功的元素数量
     */
    public Long sAdd(String key, Object... values) {
        try {
            return redisTemplate.opsForSet().add(key, values);
        } catch (Exception e) {
            log.error("Redis sAdd 失败: key={}", key, e);
            return 0L;
        }
    }

    /**
     * 从 Set 移除元素
     * 
     * @param key    Set 的 Key
     * @param values 元素值
     * @return 移除成功的元素数量
     */
    public Long sRemove(String key, Object... values) {
        try {
            return redisTemplate.opsForSet().remove(key, values);
        } catch (Exception e) {
            log.error("Redis sRemove 失败: key={}", key, e);
            return 0L;
        }
    }

    /**
     * 获取 Set 的所有元素
     * 
     * @param key Set 的 Key
     * @param <T> 元素类型
     * @return 元素集合
     */
    @SuppressWarnings("unchecked")
    public <T> Set<T> sMembers(String key) {
        try {
            Set<Object> members = redisTemplate.opsForSet().members(key);
            Set<T> result = new HashSet<>();
            members.forEach(m -> result.add((T) m));
            return result;
        } catch (Exception e) {
            log.error("Redis sMembers 失败: key={}", key, e);
            return Collections.emptySet();
        }
    }

    /**
     * 判断元素是否在 Set 中
     * 
     * @param key   Set 的 Key
     * @param value 元素值
     * @return true=存在，false=不存在
     */
    public boolean sIsMember(String key, Object value) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, value));
        } catch (Exception e) {
            log.error("Redis sIsMember 失败: key={}", key, e);
            return false;
        }
    }

    /**
     * 获取 Set 的大小
     * 
     * @param key Set 的 Key
     * @return 元素数量
     */
    public Long sSize(String key) {
        try {
            return redisTemplate.opsForSet().size(key);
        } catch (Exception e) {
            log.error("Redis sSize 失败: key={}", key, e);
            return 0L;
        }
    }

    /**
     * 求两个 Set 的交集
     * 
     * 【使用场景】共同关注
     * 
     * @param key1 Set1
     * @param key2 Set2
     * @param <T>  元素类型
     * @return 交集元素集合
     */
    @SuppressWarnings("unchecked")
    public <T> Set<T> sIntersect(String key1, String key2) {
        try {
            Set<Object> intersect = redisTemplate.opsForSet().intersect(key1, key2);
            Set<T> result = new HashSet<>();
            if (intersect != null) {
                intersect.forEach(m -> result.add((T) m));
            }
            return result;
        } catch (Exception e) {
            log.error("Redis sIntersect 失败: key1={}, key2={}", key1, key2, e);
            return Collections.emptySet();
        }
    }

    // ==================== 4. ZSet 操作 ====================

    /**
     * 向 ZSet 添加元素（带分数）
     * 
     * 【使用场景】排行榜
     * Redis: zadd article:hot 1000 1001（文章1001热度1000）
     * 
     * @param key   ZSet 的 Key
     * @param value 元素值
     * @param score 分数（排序依据）
     * @return true=添加成功
     */
    public boolean zAdd(String key, Object value, double score) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForZSet().add(key, value, score));
        } catch (Exception e) {
            log.error("Redis zAdd 失败: key={}", key, e);
            return false;
        }
    }

    /**
     * 获取 ZSet 中指定范围的元素（按分数升序）
     * 
     * @param key   ZSet 的 Key
     * @param start 起始索引（从 0 开始）
     * @param end   结束索引（-1 表示全部）
     * @param <T>   元素类型
     * @return 元素集合
     */
    @SuppressWarnings("unchecked")
    public <T> Set<T> zRange(String key, long start, long end) {
        try {
            Set<Object> range = redisTemplate.opsForZSet().range(key, start, end);
            Set<T> result = new LinkedHashSet<>();
            if (range != null) {
                range.forEach(m -> result.add((T) m));
            }
            return result;
        } catch (Exception e) {
            log.error("Redis zRange 失败: key={}", key, e);
            return Collections.emptySet();
        }
    }

    /**
     * 获取 ZSet 中指定范围的元素（按分数降序）
     * 
     * 【使用场景】排行榜 TOP N
     * 
     * @param key   ZSet 的 Key
     * @param start 起始索引
     * @param end   结束索引
     * @param <T>   元素类型
     * @return 元素集合
     */
    @SuppressWarnings("unchecked")
    public <T> Set<T> zReverseRange(String key, long start, long end) {
        try {
            Set<Object> range = redisTemplate.opsForZSet().reverseRange(key, start, end);
            Set<T> result = new LinkedHashSet<>();
            if (range != null) {
                range.forEach(m -> result.add((T) m));
            }
            return result;
        } catch (Exception e) {
            log.error("Redis zReverseRange 失败: key={}", key, e);
            return Collections.emptySet();
        }
    }

    /**
     * 获取元素的分数
     * 
     * @param key   ZSet 的 Key
     * @param value 元素值
     * @return 分数（null=元素不存在）
     */
    public Double zScore(String key, Object value) {
        try {
            return redisTemplate.opsForZSet().score(key, value);
        } catch (Exception e) {
            log.error("Redis zScore 失败: key={}", key, e);
            return null;
        }
    }

    /**
     * 增加元素的分数
     * 
     * @param key   ZSet 的 Key
     * @param value 元素值
     * @param delta 增量
     * @return 增加后的分数
     */
    public Double zIncrementScore(String key, Object value, double delta) {
        try {
            return redisTemplate.opsForZSet().incrementScore(key, value, delta);
        } catch (Exception e) {
            log.error("Redis zIncrementScore 失败: key={}", key, e);
            return 0.0;
        }
    }

    /**
     * 获取 ZSet 的大小
     * 
     * @param key ZSet 的 Key
     * @return 元素数量
     */
    public Long zSize(String key) {
        try {
            return redisTemplate.opsForZSet().size(key);
        } catch (Exception e) {
            log.error("Redis zSize 失败: key={}", key, e);
            return 0L;
        }
    }

    // ==================== 5. List 操作 ====================

    /**
     * 从左侧推入元素（队列）
     * 
     * 【使用场景】消息队列、最新消息列表
     * Redis: lpush notify:queue msg1
     * 
     * @param key   List 的 Key
     * @param value 元素值
     * @return 列表长度
     */
    public Long lLeftPush(String key, Object value) {
        try {
            return redisTemplate.opsForList().leftPush(key, value);
        } catch (Exception e) {
            log.error("Redis lLeftPush 失败: key={}", key, e);
            return 0L;
        }
    }

    /**
     * 从右侧弹出元素（队列）
     * 
     * @param key List 的 Key
     * @param <T> 元素类型
     * @return 元素值
     */
    @SuppressWarnings("unchecked")
    public <T> T lRightPop(String key) {
        try {
            return (T) redisTemplate.opsForList().rightPop(key);
        } catch (Exception e) {
            log.error("Redis lRightPop 失败: key={}", key, e);
            return null;
        }
    }

    /**
     * 获取列表长度
     * 
     * @param key List 的 Key
     * @return 列表长度
     */
    public Long lSize(String key) {
        try {
            return redisTemplate.opsForList().size(key);
        } catch (Exception e) {
            log.error("Redis lSize 失败: key={}", key, e);
            return 0L;
        }
    }

    /**
     * 获取指定范围的元素
     * 
     * @param key   List 的 Key
     * @param start 起始索引
     * @param end   结束索引（-1 表示全部）
     * @param <T>   元素类型
     * @return 元素列表
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> lRange(String key, long start, long end) {
        try {
            List<Object> range = redisTemplate.opsForList().range(key, start, end);
            List<T> result = new ArrayList<>();
            if (range != null) {
                range.forEach(m -> result.add((T) m));
            }
            return result;
        } catch (Exception e) {
            log.error("Redis lRange 失败: key={}", key, e);
            return Collections.emptyList();
        }
    }

    // ==================== 6. 工具方法 ====================

    /**
     * 获取 RedisTemplate 实例（供高级操作使用）
     * 
     * 【使用场景】当封装的方法不够用时，直接使用 RedisTemplate
     * 
     * @return RedisTemplate 实例
     */
    public RedisTemplate<String, Object> getRedisTemplate() {
        return redisTemplate;
    }

    /**
     * 获取 StringRedisTemplate 实例
     * 
     * @return StringRedisTemplate 实例
     */
    public StringRedisTemplate getStringRedisTemplate() {
        return stringRedisTemplate;
    }
}