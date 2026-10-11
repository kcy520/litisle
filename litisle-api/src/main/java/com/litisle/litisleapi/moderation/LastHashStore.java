package com.litisle.litisleapi.moderation;


import com.litisle.litisleapi.constant.ModerationConstant;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 免审 last-hash 存取口径（板块四 4-3.1 / 4-4.1 / 5-4.5 / Review-Plan §4.2.1 ②）——口径实现
 * （Redis 读写经 RedisAdapter 由宿主注入，契约层零 spring-data-redis 依赖，模式同 ContentUnpublishedInvalidator）。
 *
 * LastHashStore 负责记录“这篇内容上次送审的结论是什么”，下次发布同样内容时，如果文本指纹没变、词库没升级，就直接复用旧结论，跳过审核。
 *
 * 【key 口径】SSOT 见 {@link ModerationConstant#LAST_HASH_KEY}：
 * review:last-hash:{wordlibVer}:{bizType}:{scene}:{bizId}——四段全部服务端派生；
 * wordlibVer 进 key = 词库热更即整体换 key，旧 PASS/REJECT 结论键不会被新版本发布判定命中，
 * 热更后旧 key 无法枚举清理，等 TTL 自然过期即可（无需扫描清理）。
 *
 * 【value 口径】{hash, verdict} JSON（禁 Java 原生序列化，§2.12）：
 * hash=判定通过时送审文本指纹、verdict=终局判定（1-PASS/2-REJECT，SUSPICIOUS 不写——非终局不进免审）；
 * hash 限定十六进制、verdict 限定数字——值域封闭直拼无转义面，解析经正则强校验、异常一律按未命中处理。
 *
 * 【写入时机】content 消费 moderation.result 事件、终局判定（PASS/REJECT）落定后写入（4-3.1）；
 * TTL 外置 litisle.moderation.last-hash.ttl-hours（与 cache.ttl-hours 对齐 + ±10% 抖动，7-4.1 收尾落地），
 * 与结果缓存 TTL 同窗——防「消费端已过期重审、发布侧仍免审」口径漂移。
 *
 * 【删除时机】4-4.1 content.unpublished（删除/下架/转私密）三路径：删当前 wordlibVer 的
 * last-hash key + 该 content_hash 的 text/img-result 缓存键，防「PASS→人工/法务下架→重发同内容」
 * 命中旧 PASS 绕过下架决定；5-4.5 申诉改判 PASS 后同样须删（平台结论反转以最新为准）。
 *
 * @author litisle
 */
public final class LastHashStore {

    /** 终局判定-通过（口径同 review 侧 VerdictEnum） */
    public static final int VERDICT_PASS = 1;

    /** 终局判定-驳回（口径同 review 侧 VerdictEnum） */
    public static final int VERDICT_REJECT = 2;

    /** 终局判定-存疑（非终局，禁止写入 last-hash） */
    public static final int VERDICT_SUSPICIOUS = 3;

    /** value JSON 强校验：hash 限定十六进制、verdict 限定单个数字，异常 value 一律按未命中处理 */
    private static final Pattern VALUE_PATTERN =
            Pattern.compile("^\\{\"hash\":\"([0-9a-fA-F]+)\",\"verdict\":([0-9])\\}$");

    private LastHashStore() {
    }

    /**
     * Redis 读写抽象（宿主注入，契约层不引 spring-data-redis 依赖）：
     * content 侧用 StringRedisTemplate 实现三方法，单测传内存 Map 实现即可。
     */
    public interface RedisAdapter {

        /** GET：键不存在返回 null */
        String get(String key);

        /** SETEX：带 TTL 写入 */
        void setEx(String key, String value, long ttlSeconds);

        /** DEL：删除键 */
        void del(String key);
    }

    /**
     * 写入 last-hash：content 消费 result 事件、终局判定（PASS/REJECT）落定后调用（4-3.1）。
     *
     * @param wordlibVer  词库版本快照（判定时 DfaFacade 该 scene 版本，与 TEXT_RESULT_KEY 口径一致）
     * @param bizType     审核业务类型（1-文章 2-评论 5-私信）
     * @param scene       审核场景（ARTICLE/COMMENT/MESSAGE 及复审变体）
     * @param bizId       业务 ID
     * @param contentHash 本次判定送审文本指纹（SHA-256 十六进制）
     * @param verdict     终局判定（1-PASS/2-REJECT；SUSPICIOUS 禁止写入）
     * @param ttlSeconds  过期秒数（外置 last-hash.ttl-hours 换算 + ±10% 抖动由调用方算好传入）
     * @param redis       Redis 读写适配（宿主注入）
     */
    public static void write(long wordlibVer, int bizType, String scene, long bizId,
                             String contentHash, int verdict, long ttlSeconds, RedisAdapter redis) {
        // 步骤1：入参 fail-fast（SUSPICIOUS 非终局禁写——防免审放行存疑内容；指纹非十六进制禁写——保证 JSON 无转义面）
        if (redis == null) {
            throw new IllegalArgumentException("RedisAdapter 为空，禁止静默跳过 last-hash 写入");
        }
        if (contentHash == null || !contentHash.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("contentHash 必须为 SHA-256 十六进制指纹：" + contentHash);
        }
        if (verdict != VERDICT_PASS && verdict != VERDICT_REJECT) {
            throw new IllegalArgumentException("last-hash 仅允许终局判定写入，verdict=" + verdict);
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds 必须为正数：" + ttlSeconds);
        }
        // 步骤2：SETEX 带 TTL 写入（与结果缓存 TTL 同窗，防消费端已过期重审、发布侧仍免审的口径漂移）
        redis.setEx(buildKey(wordlibVer, bizType, scene, bizId), buildValue(contentHash, verdict), ttlSeconds);
    }

    /**
     * 读取 last-hash：发布侧免审判定用（4-3.2）。
     *
     * @return LastHashValue；键缺失/value 格式异常返回 null（照常发 MQ，宁多审不漏审）
     */
    public static LastHashValue read(long wordlibVer, int bizType, String scene, long bizId, RedisAdapter redis) {
        // 步骤0：适配器判空 fail-fast（静默跳过会留下免审/免机审攻击窗口）
        if (redis == null) {
            throw new IllegalArgumentException("RedisAdapter 为空，禁止静默跳过 last-hash 读取");
        }
        // 步骤1：GET 取值（键缺失直接按未命中）
        String value = redis.get(buildKey(wordlibVer, bizType, scene, bizId));
        if (value == null || value.isEmpty()) {
            return null;
        }
        // 步骤2：正则强校验解析——脏数据/版本错位 value 一律按未命中处理，不阻断发布
        Matcher matcher = VALUE_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        return new LastHashValue(matcher.group(1), Integer.parseInt(matcher.group(2)));
    }

    /**
     * 删除 last-hash：content.unpublished 三路径（4-4.1）/ 申诉改判 PASS（5-4.5）后调用。
     * 仅删当前 wordlibVer 的 key（热更后旧 key 发布判定不会命中，等 TTL 过期）。
     */
    public static void remove(long wordlibVer, int bizType, String scene, long bizId, RedisAdapter redis) {
        if (redis == null) {
            throw new IllegalArgumentException("RedisAdapter 为空");
        }
        redis.del(buildKey(wordlibVer, bizType, scene, bizId));
    }

    /** 构造 last-hash key（SSOT 常量格式化，禁各调用方自行拼串） */
    private static String buildKey(long wordlibVer, int bizType, String scene, long bizId) {
        return String.format(ModerationConstant.LAST_HASH_KEY, wordlibVer, bizType, scene, bizId);
    }

    /** 构造 value JSON（hash 十六进制/verdict 数字，值域封闭直拼无转义面） */
    private static String buildValue(String contentHash, int verdict) {
        return "{\"hash\":\"" + contentHash + "\",\"verdict\":" + verdict + "}";
    }

    /**
     * last-hash value 结构（record 对齐 DfaHitResult 风格，不可变值对象）。
     *
     * @param contentHash 判定时送审文本指纹
     * @param verdict     终局判定（1-PASS/2-REJECT）
     */
    public record LastHashValue(String contentHash, int verdict) {
    }
}
