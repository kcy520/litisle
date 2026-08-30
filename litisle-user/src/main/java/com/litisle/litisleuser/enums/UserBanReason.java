package com.litisle.litisleuser.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 封号原因枚举（常用原因字典）
 * <p>
 * 用于后台封号操作时提供标准化的"常用封号原因"选项，便于合规审计与原因统计。
 * 若业务允许自定义补充，可保持 DTO 的 reason 为字符串，用 desc 作为默认选项文本。
 *
 * @author litisle
 */
@Getter
public enum UserBanReason {

    ILLEGAL_CONTENT(1, "发布违法违规内容"),
    POLITICAL_SENSITIVE(2, "发布政治敏感内容"),
    ADVERTISEMENT(3, "恶意广告营销"),
    SPAM(4, "刷屏/垃圾信息"),
    ABUSE(5, "辱骂攻击他人"),
    FRAUD(6, "欺诈/诈骗行为"),
    COPYRIGHT(7, "抄袭/侵权"),
    MALICIOUS_REGISTRATION(8, "恶意注册/批量养号"),
    ILLEGAL_TRAFFIC(9, "违规引流"),
    OTHER(99, "其他");

    @EnumValue
    private final int code;

    /** 规范原因文本（前端展示/审计记录用） */
    @JsonValue
    private final String desc;

    UserBanReason(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

//    /**
//     * 反序列化：兼容前端传 desc（如"辱骂攻击他人"）或 code（如 5）
//      指定 JSON → Java 对象时，Jackson 应该调用哪个构造方法或工厂方法来创建对象。
//     */
//    @JsonCreator
//    public static UserBanReason of(String value) {
//        if (value == null) {
//            return null;
//        }
//        for (UserBanReason r : values()) {
//            if (r.desc.equals(value)) {
//                return r;
//            }
//        }
//        try {
//            int code = Integer.parseInt(value);
//            for (UserBanReason r : values()) {
//                if (r.code == code) {
//                    return r;
//                }
//            }
//        } catch (NumberFormatException ignored) {
//        }
//        throw new IllegalArgumentException("未知的封号原因: " + value);
//    }
}