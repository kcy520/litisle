package com.litisle.commonredis.bloom;



import com.litisle.commonredis.constant.CacheConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 布隆过滤器封装类
 * 
 * 【设计说明】
 * 1. 布隆过滤器用于判断"一个元素是否在集合中"
 * 2. 特点：
 *    - 判断不存在：100% 准确（不存在就一定不存在）
 *    - 判断存在：可能存在误判（存在不一定真的存在）
 * 3. 使用场景：防止缓存穿透
 *    - 查询某个 ID 的数据时，先判断布隆过滤器
 *    - 如果不存在，直接返回 null，避免查 DB
 *    - 如果存在，再去查缓存或 DB
 * 
 * 【原理简介】
 * 1. 底层是一个很长的二进制位数组
 * 2. 添加元素时，通过多个哈希函数计算出多个位的位置，置为 1
 * 3. 查询元素时，同样计算哈希位置，只要有一个位为 0，说明不存在
 * 4. 可能存在误判：多个元素的哈希位可能重叠
 * 
 * 【使用场景举例】
 * 1. 防止缓存穿透：用户 ID 布隆过滤器
 *    - 查询用户信息时，先判断布隆过滤器
 *    - 不存在直接返回，避免恶意查询不存在的 ID 导致 DB 压力
 * 
 * 2. 防止重复注册：手机号布隆过滤器
 *    - 注册时如果布隆过滤器判断手机号不存在，直接允许注册
 *    - 误判时，库里查一下即可，影响不大
 * 
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloomFilter {

    /**
     * Redisson 客户端
     */
    private final RedissonClient redissonClient;

    /**
     * 默认布隆过滤器名称
     */
    private static final String DEFAULT_NAME = "default";

    /**
     * 默认预期元素数量
     * 【说明】根据业务预估，这里是 100 万
     */
    private static final long DEFAULT_EXPECTED_INSERTIONS = 1_000_000L;

    /**
     * 默认误判率
     * 【说明】
     * - 越小越精确，但占用的空间越大
     * - 0.03 = 3%，对缓存穿透防护来说足够了
     */
    private static final double DEFAULT_FALSE_PROBABILITY = 0.03;

    /**
     * 初始化布隆过滤器
     * 
     * 【说明】
     * 1. 在项目启动时调用，初始化关键数据的布隆过滤器
     * 2. 从数据库中加载所有 ID 到布隆过滤器
     * 3. 只需初始化一次，后续自动持久化到 Redis
     * 
     * @param name              过滤器名称
     * @param expectedInsertions 预期元素数量
     * @param falseProbability   误判率
     * @param <T>               元素类型
     * @return RBloomFilter 实例
     */
    public <T> RBloomFilter<T> init(String name, long expectedInsertions, double falseProbability) {
        String bloomKey = CacheConstant.BLOOM_PREFIX + name;
        RBloomFilter<T> bloomFilter = redissonClient.getBloomFilter(bloomKey);

        // tryInit 初始化布隆过滤器
        // 参数1：expectedInsertions - 预期元素数量
        // 参数2：falseProbability - 误判率
        // 【注意】tryInit 只在第一次调用时生效，如果已经初始化过就忽略
        bloomFilter.tryInit(expectedInsertions, falseProbability);

        log.info("布隆过滤器初始化完成: name={}, expectedInsertions={}, falseProbability={}",
                name, expectedInsertions, falseProbability);

        return bloomFilter;
    }

    /**
     * 使用默认参数初始化布隆过滤器
     * 
     * @param name 过滤器名称
     * @param <T>  元素类型
     * @return RBloomFilter 实例
     */
    public <T> RBloomFilter<T> init(String name) {
        return init(name, DEFAULT_EXPECTED_INSERTIONS, DEFAULT_FALSE_PROBABILITY);
    }

    /**
     * 添加元素到布隆过滤器
     * 
     * @param name  过滤器名称
     * @param value 元素值
     * @param <T>   元素类型
     */
    public <T> void add(String name, T value) {
        String bloomKey = CacheConstant.BLOOM_PREFIX + name;
        RBloomFilter<T> bloomFilter = redissonClient.getBloomFilter(bloomKey);
        bloomFilter.add(value);
    }

    /**
     * 判断元素是否可能存在
     * 
     * 【返回值说明】
     * - false：元素一定不存在（100% 准确）
     * - true：元素可能存在（有误判概率）
     * 
     * @param name  过滤器名称
     * @param value 元素值
     * @param <T>   元素类型
     * @return true=可能存在，false=一定不存在
     */
    public <T> boolean mightContain(String name, T value) {
        String bloomKey = CacheConstant.BLOOM_PREFIX + name;
        RBloomFilter<T> bloomFilter = redissonClient.getBloomFilter(bloomKey);
//        return bloomFilter.mightContain(value);
        return bloomFilter.contains(value);
    }

    /**
     * 获取布隆过滤器已添加的元素数量
     * 
     * @param name 过滤器名称
     * @return 已添加的元素数量
     */
    public <T> long count(String name) {
        String bloomKey = CacheConstant.BLOOM_PREFIX + name;
        RBloomFilter<T> bloomFilter = redissonClient.getBloomFilter(bloomKey);
        return bloomFilter.count();
    }
}