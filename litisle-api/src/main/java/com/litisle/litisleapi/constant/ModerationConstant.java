package com.litisle.litisleapi.constant;



/**
 * 审核模块跨服务共享常量（枚举值口径 / Redis key 契约）。
 *
 * 【SSOT 说明】Redis key 由 review 侧写入、file 侧读取，必须收口到契约库常量类，
 * 双方禁止各自拼字符串（防 key 漂移导致缓存永不命中/黑名单失效）。
 * key 各段均为服务端派生值（枚举名/版本号/SHA256），不拼用户原串，符合缓存键注入防护纪律（§2.12）。
 *
 * @author litisle
 */
public class ModerationConstant {

    private ModerationConstant() {
    }

    // ==================== 枚举值口径 ====================

    /** 审核方式：1-机审 */
    public static final int MOD_TYPE_AUTO = 1;

    /** 审核业务类型：1-文章（免审 last-hash / 图片连带校验键的 bizType 段） */
    public static final int BIZ_TYPE_ARTICLE = 1;

    /** 审核业务类型：2-评论 */
    public static final int BIZ_TYPE_COMMENT = 2;

    /** 审核业务类型：3-用户名（注册路径 HIGH 同步拒绝取证行的 bizType 段，biz_id=0 占位，4-7） */
    public static final int BIZ_TYPE_USERNAME = 3;

    /** 审核业务类型：5-私信 */
    public static final int BIZ_TYPE_MESSAGE = 5;

    /** 审核业务类型：6-图片 */
    public static final int BIZ_TYPE_IMAGE = 6;

    /** 图片场景（上传预审/举报重审刻意共用 IMAGE 场景，同对象重审） */
    public static final String SCENE_IMAGE = "IMAGE";

    /** 文章配图复审场景（不走 DFA；bizType=1 复用 LAST_HASH_KEY 做图片连带免审键，4-3.4） */
    public static final String SCENE_ARTICLE_IMG_RE_REVIEW = "ARTICLE_IMG_RE_REVIEW";

    /** 终局判定：1-PASS */
    public static final int VERDICT_PASS = 1;

    /** 终局判定：2-REJECT */
    public static final int VERDICT_REJECT = 2;

    /** 终局判定：3-SUSPICIOUS（非终局，不进结果缓存） */
    public static final int VERDICT_SUSPICIOUS = 3;

    // ==================== Redis key 契约 ====================

    /**
     * 图片拒传黑名单 key（review 侧 REJECT 时写入，TTL 30 天±10% 抖动）；
     * file 上传链路按 sha256 探测，命中直接拒传不落库不进 MQ；
     * 申诉改判 PASS 时 review 侧须移除，否则平反图在 TTL 窗口内仍被拒传（§4.2.3）
     */
    public static final String IMG_REJECT_BLACKLIST_KEY = "review:img-blacklist:%s";

    /**
     * 图片审核结果缓存 key（仅缓存终局结论 1-PASS/2-REJECT，TTL 72h±10% 抖动）；
     * %s 依次为 provider、modelVersion、sha256——版本化防「词库热更/模型切换后旧结论继续放行」（§2.11）
     */
    public static final String IMG_RESULT_KEY = "review:img-result:%s:%s:%s";

    /**
     * 文本审核结果缓存 key（仅缓存终局结论 1-PASS/2-REJECT，TTL 72h±10% 抖动）；
     * %s 依次为 wordlibVer、fewShotVer、scene、contentHash——四段全部服务端派生，
     * 任一版本变更（词库热更 / few-shot 调整）即整体换 key，防旧结论继续放行（§2.11）
     */
    public static final String TEXT_RESULT_KEY = "review:text-result:%s:%s:%s:%s";

    // ==================== Redis Pub/快照契约（词库共享化，§2.5） ====================

    /**
     * 词库变更广播频道（Redis Pub，fire-and-forget）：review 侧词表变更事务提交后发布；
     * 宿主 DFA 的服务（review/content/message）订阅后调 DfaFacade.rebuild(scene)，
     * 内部按 version 乐观比对防乱序旧覆盖新（§2.5）
     */
    public static final String WORDLIB_CHANGE_CHANNEL = "review:wordlib:change";

    /**
     * 词库快照 key（scene 级）：review 变更提交后写入（先于广播发布），
     * content/message 的 WordLibraryLoader 读 Redis 快照实现的数据源；
     * %s 为 scene（ARTICLE/COMMENT/USERNAME/MESSAGE）；value 为 WordlibSnapshot JSON（无 TTL，随版本覆盖）
     */
    public static final String WORDLIB_SNAPSHOT_KEY = "review:wordlib-snapshot:%s";

    // ==================== 发布前置校验口径（板块四 4-1，SSOT） ====================

    /**
     * 标题长度上限（4-1.1，对齐 article.title varchar(100)）：
     * 发布/评论入口 trim 后校验，超限拒绝；
     * 校验器骨架见 PublishTextPrecheck（实现挂起待发布链路开发）
     */
    public static final int TITLE_MAX_LENGTH = 100;

    /**
     * 送审文本标题与正文拼接分隔符（4-1.2）：title + 分隔符 + body（title 空取 body）；
     * 发布侧（SubmitTextAssembler）与消费侧（ModerationTextService#fetchText）必须共用此常量，
     * 禁止两侧各自硬编码（防口径漂移导致消费端 hash 错配 ACK 丢弃）
     */
    public static final String TEXT_SUBMIT_SEPARATOR = "\n";

    // ==================== DFA 判定级别口径（板块四 4-2，SSOT） ====================

    /**
     * DFA 级别 int code：1-HIGH（黑名单词，同步拦截 70001；私信链路同时禁止投递 MQ）；
     * 与 WordEntry.level / DfaHitResult.maxLevel 对齐，闸门决策 SSOT 见 DfaPublishGate
     */
    public static final int DFA_LEVEL_HIGH = 1;

    /**
     * DFA 级别 int code：2-MID（只标记 layer1_hit=2 随任务送异步层，兼新增词灰度观察位，不拦截）；
     * 级别比对禁用魔法值 1/2，统一引用本常量
     */
    public static final int DFA_LEVEL_MID = 2;

    // ==================== 免审 last-hash 契约（板块四 4-3，SSOT） ====================

    /**
     * 免审 last-hash key（content 消费 result 写入 4-3.1，发布侧免审判定 4-3.2，
     * content.unpublished 失效 4-4.1，申诉改判 PASS 同步删 5-4.5）；
     * %s 依次为 wordlibVer、bizType、scene、bizId——四段全部服务端派生；
     * wordlibVer 进 key = 词库热更即整体换 key，旧结论键不会被新版本发布判定命中，
     * 热更后旧 key 无法枚举清理，等 TTL 自然过期即可（无需扫描清理）；
     * 图片连带校验复用本 key（bizType=1、scene=ARTICLE_IMG_RE_REVIEW，4-3.4）；
     * TTL 外置 litisle.moderation.last-hash.ttl-hours（与 cache.ttl-hours 对齐 + ±10% 抖动，7-4.1 收尾落地）
     *
     * review:last-hash:{#wordlibVer}:{#bizType}:{#scene}:{#bizId}
     */
    public static final String LAST_HASH_KEY = "review:last-hash:%s:%s:%s:%s";

    /** last-hash value JSON 字段：判定时送审文本指纹（防「仅换标题/正文任一」旧键命中） */
    public static final String LAST_HASH_FIELD_HASH = "hash";

    /** last-hash value JSON 字段：终局判定（1-PASS/2-REJECT；SUSPICIOUS 非终局禁写，防免审放行存疑内容） */
    public static final String LAST_HASH_FIELD_VERDICT = "verdict";

    // ==================== 私信撤回与陌生人配额契约（板块四 4-6，SSOT） ====================

    /** 私信状态：0-已撤回（撤回 CAS 目标态；不物理删正文，留存审计，对方仅见「消息已撤回」） */
    public static final int MESSAGE_STATUS_RECALLED = 0;

    /** 私信状态：1-正常（已投递；撤回 CAS 前置态，与审核 1→2 CAS 同前置天然互斥） */
    public static final int MESSAGE_STATUS_NORMAL = 1;

    /** 陌生人会话配额上限：单会话累计最多 3 条（REJECT 拦截消息仍占配额——投递行为已发生） */
    public static final int STRANGER_QUOTA_LIMIT = 3;

    /**
     * 陌生人配额 Redis INCR key：%s 为 conversationId（服务端派生数字 ID，不拼用户原串）；
     * INCR 为主计数，Redis 故障降级 DB 原子条件更新（口径 SSOT 见 StrangerQuotaGate）；
     * TTL 口径随 message 模块落地与 conversation 生命周期对齐，当前不设 TTL（会话级累计）
     */
    public static final String STRANGER_QUOTA_KEY = "biz:message:stranger-quota:%s";

    // ==================== 跨服务埋点契约（review 写 / file 写同一指标名，双方禁止各自拼字符串） ====================

    /** moderation_cache_hit 指标名（review 侧审核结果缓存命中 + file 侧拒传黑名单命中共用，layer 标签区分） */
    public static final String METRIC_MODERATION_CACHE_HIT = "moderation.cache.hit";

    /** moderation_cache_hit 的 layer 标签 key */
    public static final String METRIC_TAG_LAYER = "layer";

    /** layer 标签-审核结果缓存命中（review 侧缓存查重复用终局结论时计数） */
    public static final String METRIC_LAYER_RESULT = "result";

    /** layer 标签-拒传黑名单命中（file 侧上传闸门命中黑名单拒传时计数） */
    public static final String METRIC_LAYER_BLACKLIST = "blacklist";
}
