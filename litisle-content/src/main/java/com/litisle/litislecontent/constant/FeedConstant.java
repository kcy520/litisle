package com.litisle.litislecontent.constant;

import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.litislecontent.enums.BookStatusEnum;
import com.litisle.litislecontent.enums.ZoneEnum;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 推荐流（无限刷）常量（批次十 10.1/10.2）
 * <p>只放与推荐流强相关的固定值：匿名标识、召回模式、可推荐门控集合、限流键前缀；
 * 可调参数（权重、快照容量、探索位比例）统一外置 yml {@code litisle.content.feed.stream}，
 * 不放本类，避免改参数要发版</p>
 *
 * @author litisle
 */
public final class FeedConstant {

    /** 私有构造：常量类禁止实例化 */
    private FeedConstant() {
    }

    // ==================== 匿名标识（批次十 10.2，对齐已确认决策第 14 条） ====================

    /**
     * 匿名标识请求头名：前端首次访问生成 UUID v4 存 localStorage，后续请求携带
     * <p>该标识只用于曝光去重/快照隔离，不是身份凭证，服务端不做签名</p>
     */
    public static final String ANON_ID_HEADER = "X-Anon-Id";

    /**
     * 匿名标识请求属性名：{@link com.litisle.litislecontent.filter.AnonIdFilter} 校验/兜底后写入，
     * Controller 从请求属性读取，避免业务层反复解析请求头
     */
    public static final String ANON_ID_ATTRIBUTE = "litisle.content.feed.anonId";

    /**
     * UUID v4 格式正则：8-4-4-4-12，版本位固定 4、变体位 8/9/a/b
     * <p>只做格式校验：缺失或非法时由服务端生成并随响应头下发</p>
     */
    public static final Pattern ANON_ID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    // ==================== 召回模式（批次十已确认决策第 13 条） ====================

    /** 召回模式：数据库四路（P0 默认，本期唯一实现） */
    public static final String RECALL_DB = "db";

    /** 召回模式：search 模块 ES 召回（P1 占位，切换阈值见 next_update 批次十） */
    public static final String RECALL_ES = "es";

    // ==================== 可推荐门控（批次十需求条款 6） ====================

    /**
     * 文章流允许进入推荐的分区集合（zone ∈ {1 普通, 2 作家}）
     * <p>召回查询与展示前回查共用本集合，保证「门控口径统一」，避免两处各写一份</p>
     */
    public static final List<ZoneEnum> ARTICLE_RECOMMENDABLE_ZONES = List.of(ZoneEnum.NORMAL, ZoneEnum.WRITER);

    /**
     * 书籍流允许进入推荐的状态集合（0-连载中 / 1-已完结；下架 2 永不进流）
     * <p>书籍流不参与 zone，按 category 组织（批次十已确认决策）</p>
     */
    public static final List<BookStatusEnum> BOOK_RECOMMENDABLE_STATUSES =
            List.of(BookStatusEnum.SERIALING, BookStatusEnum.COMPLETED);

    // ==================== 游标与分页 ====================

    /** 游标起始偏移（从快照头开始切片，口径 from 恒 0） */
    public static final long CURSOR_START_OFFSET = 0L;

    /** 单页条数上限（入参超限按上限收口，防恶意大分页拖垮查询） */
    public static final int MAX_PAGE_SIZE = 50;

    // ==================== 频控键（批次十需求条款 9） ====================

    /** 登录用户限流键前缀（拼 userId，USER 维度 60 次/60s） */
    public static final String LIMIT_KEY_USER_PREFIX = CacheConstant.RATE_LIMIT + "feed:user:";

    /** 匿名限流键前缀（拼客户端 IP，IP 维度 20 次/60s） */
    public static final String LIMIT_KEY_IP_PREFIX = CacheConstant.RATE_LIMIT + "feed:ip:";

    /** 限流键后缀：文章流与书籍流共用同一配额，对应需求条款 9 的「推荐流总次数」 */
    public static final String LIMIT_KEY_SUFFIX = ":stream";

    // ==================== 快照 / 曝光 / 种子键（批次十 10.3） ====================
    // 统一格式：content:feed:{用途}:{流类型}:{身份}[:{快照代次}]，身份为 u{userId} 或 a{anonId}

    /** 快照数据键前缀：值=FeedSnapshot（条目顺序 + 分数），TTL 由 stream.snapshot-ttl-seconds 控制 */
    public static final String SNAPSHOT_KEY_PREFIX = "content:feed:snapshot:";

    /** 活动快照索引键前缀：List 存同一身份最近生成的快照代次，超出 max-active-snapshots 删最旧 */
    public static final String SNAPSHOT_INDEX_PREFIX = "content:feed:snapshot:index:";

    /** 快照代次计数器键前缀：INCR 得到递增代次，保证 snapshotId 单调递增 */
    public static final String SNAPSHOT_GEN_PREFIX = "content:feed:snapshot:gen:";

    /** 会话种子键前缀：同一身份在一个 TTL 窗口内复用同一 seed，跨会话重新生成 */
    public static final String SNAPSHOT_SEED_PREFIX = "content:feed:seed:";

    /** 当日曝光 Set 键前缀（拼身份与 yyyyMMdd），保证同一天不重复曝光 */
    public static final String EXPOSED_KEY_PREFIX = "content:feed:exposed:";

    /** Redis 可用性探测键：直接走 RedisTemplate 读该键，异常即判定 Redis 不可用 */
    public static final String SNAPSHOT_PROBE_KEY = "content:feed:probe";

    /** 流类型：文章流（分区筛选会追加 :zone:{code} 后缀，避免不同筛选条件共用一份快照） */
    public static final String STREAM_ARTICLE = "article";

    /** 流类型：书籍流（书籍不参与 zone，按 category 组织） */
    public static final String STREAM_BOOK = "book";

    /** 身份前缀：登录用户（拼 userId） */
    public static final String IDENTITY_USER_PREFIX = "u";

    /** 身份前缀：匿名用户（拼 anonId） */
    public static final String IDENTITY_ANON_PREFIX = "a";
}
