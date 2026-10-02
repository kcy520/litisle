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

    /** 审核业务类型：6-图片 */
    public static final int BIZ_TYPE_IMAGE = 6;

    /** 图片场景（上传预审/举报重审刻意共用 IMAGE 场景，同对象重审） */
    public static final String SCENE_IMAGE = "IMAGE";

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
}
