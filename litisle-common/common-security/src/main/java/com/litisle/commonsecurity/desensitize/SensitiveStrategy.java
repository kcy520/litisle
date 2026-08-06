package com.litisle.commonsecurity.desensitize;


/**
 * 脱敏策略枚举
 *
 * 【设计说明】
 * 1. 定义常见敏感数据的脱敏规则
 * 2. 每个策略对应一个脱敏函数，输入原始值，输出脱敏值
 * 3. 新增脱敏类型只需在此枚举添加一项，符合开闭原则
 *
 * 【脱敏规则示例】
 * ├─────────────┼───────────────────┼──────────────────────┤
 * │ 策略        │ 原始值             │ 脱敏后               │
 * ├─────────────┼───────────────────┼──────────────────────┤
 * │ PHONE       │ 13888888888       │ 138****8888          │
 * │ ID_CARD     │ 110101199001011234│ 110***********1234   │
 * │ EMAIL       │ example@xx.com    │ ex***@xx.com         │
 * │ BANK_CARD   │ 6222020200011111  │ 622202********1111   │
 * │ NAME        │ 张三丰             │ 张**                  │
 * │ ADDRESS     │ 北京市海淀区xx路   │ 北京市海淀区***       │
 * └─────────────┴───────────────────┴──────────────────────┘
 *
 * @author litisle
 */
public enum SensitiveStrategy {

    /**
     * 手机号脱敏：保留前3位和后4位
     * 13888888888 → 138****8888
     */
    PHONE {
        @Override
        public String desensitize(String value) {
            if (value == null || value.length() < 7) {
                return "****";
            }
            return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
        }
    },

    /**
     * 身份证脱敏：保留前3位和后4位
     * 110101199001011234 → 110***********1234
     */
    ID_CARD {
        @Override
        public String desensitize(String value) {
            if (value == null || value.length() < 7) {
                return "****";
            }
            int len = value.length();
            return value.substring(0, 3) + "*".repeat(len - 7) + value.substring(len - 4);
        }
    },

    /**
     * 邮箱脱敏：保留前2位和@后域名
     * example@xx.com → ex***@xx.com
     */
    EMAIL {
        @Override
        public String desensitize(String value) {
            if (value == null) {
                return null;
            }
            int atIndex = value.indexOf('@');
            if (atIndex <= 1) {
                return "****" + (atIndex >= 0 ? value.substring(atIndex) : "");
            }
            return value.substring(0, 2) + "***" + value.substring(atIndex);
        }
    },

    /**
     * 银行卡脱敏：保留前6位和后4位
     * 6222020200011111 → 622202********1111
     */
    BANK_CARD {
        @Override
        public String desensitize(String value) {
            if (value == null || value.length() < 10) {
                return "****";
            }
            int len = value.length();
            return value.substring(0, 6) + "*".repeat(len - 10) + value.substring(len - 4);
        }
    },

    /**
     * 姓名脱敏：保留姓，名用 * 替换
     * 张三丰 → 张**
     * 李四 → 李*
     */
    NAME {
        @Override
        public String desensitize(String value) {
            if (value == null || value.isEmpty()) {
                return value;
            }
            if (value.length() == 1) {
                return value;
            }
            return value.charAt(0) + "*".repeat(value.length() - 1);
        }
    },

    /**
     * 地址脱敏：保留前6位，其余用 *** 替换
     * 北京市海淀区中关村路1号 → 北京市海淀区***
     */
    ADDRESS {
        @Override
        public String desensitize(String value) {
            if (value == null || value.length() <= 6) {
                return "***";
            }
            return value.substring(0, 6) + "***";
        }
    };

    /**
     * 脱敏抽象方法（由各枚举实现具体规则）
     *
     * @param value 原始值
     * @return 脱敏后的值
     */
    public abstract String desensitize(String value);
}