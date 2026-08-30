package com.litisle.litisleuser.constant;

public class UserInfoConstant {

    /**
     * 默认值
     */
//    public static final String DEFAULT_USER_INFO_VALUE = "无";

    /**
     * 默认封号天数（解封时间缺省时按此累加）
     */
    public static final int DEFAULT_BAN_DAYS = 7;

    /**
     * 系统操作者ID（定时任务自动解封等系统行为记录审计日志时使用，0 表示无真实操作者）
     */
    public static final Long SYSTEM_OPERATOR_ID = 0L;

    /**
     * 系统操作者用户名（自动解封等系统行为记录审计日志时使用）
     */
    public static final String SYSTEM_OPERATOR_NAME = "system";

}
