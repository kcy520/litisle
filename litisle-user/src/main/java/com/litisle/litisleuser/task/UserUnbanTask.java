package com.litisle.litisleuser.task;

import com.litisle.commoncore.enums.UserStatus;
import com.litisle.litisleuser.domain.entity.Role;
import com.litisle.litisleuser.domain.entity.User;
import com.litisle.litisleuser.domain.entity.UserBanLog;
import com.litisle.litisleuser.domain.entity.UserRole;
import com.litisle.litisleuser.enums.DelFlag;
import com.litisle.litisleuser.enums.UserBanAction;
import com.litisle.litisleuser.constant.UserInfoConstant;
import com.litisle.litisleuser.service.RoleService;
import com.litisle.litisleuser.service.UserBanLogService;
import com.litisle.litisleuser.service.UserRoleService;
import com.litisle.litisleuser.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 用户自动解封定时任务
 * <p>
 * 后台封号（UserAdminService.changeStatus）会将解封时间吸附到"每日 03:00"粒度，
 * 本任务在每日 03:00 触发一次，一次性扫描所有已到期的封禁用户并自动恢复为正常状态。
 * 由于解封时间统一落在 03:00 边界，任务只需该时刻扫描一次，避免任意时间点逐个触发。
 *
 * @author litisle
 */
@Slf4j
@Component
public class UserUnbanTask {

    /**
     * 每日 03:00 触发；默认值，可用配置 user.unban-task.cron 覆盖
     */
    private static final String CRON_DAILY_3AM = "${user.unban-task.cron:0 0 3 * * ?}";

    private final UserService userService;
    private final UserRoleService userRoleService;
    private final RoleService roleService;
    private final UserBanLogService userBanLogService;

    public UserUnbanTask(UserService userService,
                         UserRoleService userRoleService,
                         RoleService roleService,
                         UserBanLogService userBanLogService) {
        this.userService = userService;
        this.userRoleService = userRoleService;
        this.roleService = roleService;
        this.userBanLogService = userBanLogService;
    }

    /**
     * 自动解封到期的封禁用户
     * <p>
     * 【实现说明】
     * 1. 筛选条件：status=DISABLED 且 frozenUntil 已到（<= 当前时间），且未逻辑删除
     * 2. 一次性批量置 status=NORMAL、frozenUntil=null（单条 UPDATE，不逐行主键加载，避免大表性能问题）
     * 3. 事务包裹，失败整体回滚；更新行数为 0 属正常（当日无到期用户），仅记录信息日志
     */
    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = CRON_DAILY_3AM)
    public void unbanExpiredUsers() {
        // 1. 查询所有已到期的封禁用户（status=DISABLED 且 frozenUntil 已到，且未逻辑删除）
        List<User> expiredUsers = userService.lambdaQuery()
                .eq(User::getStatus, UserStatus.DISABLED)
                .le(User::getFrozenUntil, LocalDateTime.now())
                .eq(User::getDelFlag, DelFlag.NOT_DELETED)
                .list();
        if (expiredUsers.isEmpty()) {
            log.info("时间: {} ———— 用户自动解封任务执行完成，本次无到期用户", LocalDateTime.now());
            return;
        }

        // 2. 批量聚合用户-角色快照，避免逐用户 N+1 查询
        //N+1 查询 是指：为了拿到 N 条主数据 + 它们各自关联的附属数据，执行了 1 + N 次数据库查询。即 N*(N+!)
        List<Long> userIds = expiredUsers.stream().map(User::getId).collect(Collectors.toList());
        // 2.1 批量查 user_role 关联
        List<UserRole> userRoles = userRoleService.lambdaQuery()
                .in(UserRole::getUserId, userIds)
                .list();
        // 2.2 批量查角色编码映射
        List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).distinct().collect(Collectors.toList());
        Map<Long, String> roleCodeById = roleIds.isEmpty()
                ? Collections.emptyMap()
                : roleService.listByIds(roleIds).stream()
                        .collect(Collectors.toMap(Role::getId, Role::getRoleCode));
        // 2.3 组装 userId -> roleCodes
        Map<Long, List<String>> roleCodesByUser = userRoles.stream()
                .collect(Collectors.groupingBy(
                        UserRole::getUserId,
                        Collectors.mapping(ur -> roleCodeById.get(ur.getRoleId()), Collectors.toList())));

        // 3. 逐条组装并批量写入自动解封日志（操作者为系统 0，reason 为空）
        List<UserBanLog> banLogs = expiredUsers.stream().map(user -> {
            String roleCode = roleCodesByUser.getOrDefault(user.getId(), List.of()).stream()
                    .filter(Objects::nonNull) //Objects::nonNull 是什么？即.filter(obj -> obj != null)
                    .collect(Collectors.joining(","));
            UserBanLog logEntry = new UserBanLog();
            logEntry.setUserId(user.getId());
            logEntry.setUsername(user.getUsername());
            logEntry.setRoleCode(roleCode);
            logEntry.setAction(UserBanAction.UNBAN);
            logEntry.setOperatorId(UserInfoConstant.SYSTEM_OPERATOR_ID);
            logEntry.setOperatorName(UserInfoConstant.SYSTEM_OPERATOR_NAME);
            return logEntry;
        }).collect(Collectors.toList());
        userBanLogService.saveBatch(banLogs);

        // 4. 批量置 status=NORMAL、frozenUntil=null（单条 UPDATE，不逐行主键加载）
        userService.lambdaUpdate()
                .set(User::getStatus, UserStatus.NORMAL)
                .set(User::getFrozenUntil, null)
                .in(User::getId, userIds)
                .update();
        log.info("用户自动解封任务执行完成，解封用户数={}", userIds.size());
    }
}