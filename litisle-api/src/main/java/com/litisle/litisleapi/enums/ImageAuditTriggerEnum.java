package com.litisle.litisleapi.enums;


import lombok.Getter;

/**
 * 图片审核触发时机枚举（契约库定义，审核触发维度）
 *
 * 【三枚举正交，禁合并/互相补齐】
 * - StorageBizTypeEnum（litisle-file，存储维度：定 bucket 与访问级别）
 * - ModerationBizTypeEnum（litisle-review，审核维度：review_task.biz_type）
 * - 本枚举（触发时机维度）：解决「biz_type=6（FILE）粗粒度类型下，
 *   不同存储类型的审核触发点差异」
 *
 * 【运行时映射契约】
 * - 入参为 FileClient.getFileInfo 返回的 FileOwnerDTO.bizType
 *   （= StorageBizTypeEnum 的 DictEnum name，如 "AVATAR"）
 * - 依赖方向为 file → api，litisle-api 不可反向依赖 litisle-file，
 *   故 case 值以枚举名字符串对齐，SSOT 为 StorageBizTypeEnum 枚举名，
 *   两侧改名须同步（编译期不拦截，改动时须同步检查本枚举与 next_update.md 对应项）
 *
 * 【fallback 口径】未知/空存储类型按 UPLOAD_PRECHECK 兜底（宁严勿松：
 * 两个触发点全覆盖，误判代价是多一次复审而非漏审）
 *
 * 【使用场景示例】
 * <pre>{@code
 * // review 侧消费 image.submit（biz_type=6，biz_id=fileId）后，按存储类型定触发时机
 * FileOwnerDTO info = fileClient.getFileInfo(event.getBizId()).getData();
 * ImageAuditTriggerEnum trigger = ImageAuditTriggerEnum.fromStorageBizTypeName(info.getBizType());
 * if (trigger == ImageAuditTriggerEnum.UPLOAD_PRECHECK) {
 *     // 机审预审 + 登记发布复审唤醒标记（content.published 时随文复审）
 * }
 * }</pre>
 *
 * @author litisle
 */
@Getter
public enum ImageAuditTriggerEnum {

    /** 上传即审：AVATAR/COVER 无发布链路可挂，上传点拦截（违规即隔离下架） */
    UPLOAD_IMMEDIATE(1, "上传即审"),

    /** 上传预审 + 发布复审：ARTICLE_IMG 预审拦明显违规，发布时随文复审（图与文各自独立审核） */
    UPLOAD_PRECHECK(2, "上传预审+发布复审"),

    /** 私有面豁免：LICENSE 强制 PRIVATE（仅预签名访问），无公开面不进机审 */
    EXEMPT(3, "私有面豁免");

    /** 语义编号（预留：未来如需入库/指标埋点用，当前仅运行时内存使用） */
    private final int code;

    /** 前端展示用：JSON 序列化为 desc */
    private final String desc;

    ImageAuditTriggerEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 存储类型名 → 审核触发时机（运行时映射，SSOT 见类注释）
     *
     * @param storageBizTypeName StorageBizTypeEnum 的 DictEnum name（FileOwnerDTO.bizType）
     * @return 触发时机；未知/空一律回退 UPLOAD_PRECHECK（宁严勿松）
     */
    public static ImageAuditTriggerEnum fromStorageBizTypeName(String storageBizTypeName) {
        if (storageBizTypeName == null || storageBizTypeName.isBlank()) {
            return UPLOAD_PRECHECK;
        }
        return switch (storageBizTypeName) {
            case "AVATAR", "COVER" -> UPLOAD_IMMEDIATE;
            case "ARTICLE_IMG" -> UPLOAD_PRECHECK;
            case "LICENSE" -> EXEMPT;
            default -> UPLOAD_PRECHECK;
        };
    }
}
