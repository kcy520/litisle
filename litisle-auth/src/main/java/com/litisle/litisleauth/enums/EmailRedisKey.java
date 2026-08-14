package com.litisle.litisleauth.enums;

public enum EmailRedisKey {

    /**
     * 验证码：ms:user:email:{usage}:{email}
     */
    CODE("ms:user:email:%s:%s"),

    /**
     * 发送频率限制
     */
    LIMIT_EMAIL("ms:user:email:limit:email:%s"),
    LIMIT_IP("ms:user:email:limit:ip:%s");

    private final String template;

    EmailRedisKey(String template) {
        this.template = template;
    }

    public String of(Object... args) {
        return String.format(template, args);
    }
}