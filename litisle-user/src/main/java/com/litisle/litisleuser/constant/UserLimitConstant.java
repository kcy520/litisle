package com.litisle.litisleuser.constant;

/**
 * user 模块限流阈值常量
 * <p>
 * 【规范】限流阈值定义在业务模块内（而非公共库），与 login/sms 等限流常量保持一致的组织方式。
 *
 * @author litisle
 */
public class UserLimitConstant {

    /**
     * 限流时间窗口（秒）
     */
    public static final long WINDOW_SECONDS = 60L;

    /**
     * 改绑邮箱接口：同一 IP 60 秒内最多 5 次（防撞库/防爆破）
     */
    public static final long EMAIL_BIND_MAX = 5L;
}
