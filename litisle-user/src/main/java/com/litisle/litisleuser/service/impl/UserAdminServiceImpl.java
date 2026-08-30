package com.litisle.litisleuser.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.enums.UserStatus;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.PageResult;
import com.litisle.commoncore.utils.PageUtil;
import com.litisle.commonsecurity.crypto.Aes256GcmCrypto;
import com.litisle.litisleuser.constant.UserInfoConstant;
import com.litisle.commonsecurity.enums.RoleCodeEnum;
import com.litisle.commonsecurity.enums.RoleLevel;
import com.litisle.litisleuser.domain.dto.UserQueryDTO;
import com.litisle.litisleuser.domain.dto.UserStatusChangeDTO;
import com.litisle.litisleuser.domain.entity.Role;
import com.litisle.litisleuser.domain.entity.User;
import com.litisle.litisleuser.domain.entity.UserBanLog;
import com.litisle.litisleuser.domain.entity.UserProfile;
import com.litisle.litisleuser.domain.entity.UserRole;
import com.litisle.litisleuser.domain.vo.UserAdminDetailVO;
import com.litisle.litisleuser.domain.vo.UserAdminVO;
import com.litisle.litisleuser.enums.DelFlag;
import com.litisle.litisleuser.enums.UserBanAction;
import com.litisle.litisleuser.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.litisle.litisleuser.constant.UserInfoConstant.DEFAULT_BAN_DAYS;

/**
 * 后台管理员 - 用户管理业务实现
 * <p>
 * 通过组合 UserService/RoleService/UserRoleService/UserProfileService 完成跨表聚合操作，
 * 对目标用户做等级/状态前置校验，防止越权提权；写操作统一由上层事务回滚。
 *
 * @author litisle
 */
@Slf4j
@Service
public class UserAdminServiceImpl implements UserAdminService {

    private final UserService userService;
    private final RoleService roleService;
    private final UserRoleService userRoleService;
    private final UserProfileService userProfileService;
    private final UserBanLogService userBanLogService;
    private final Aes256GcmCrypto aes256GcmCrypto;

    public UserAdminServiceImpl(UserService userService,
                                RoleService roleService,
                                UserRoleService userRoleService,
                                UserProfileService userProfileService,
                                UserBanLogService userBanLogService,
                                Aes256GcmCrypto aes256GcmCrypto) {
        this.userService = userService;
        this.roleService = roleService;
        this.userRoleService = userRoleService;
        this.userProfileService = userProfileService;
        this.userBanLogService = userBanLogService;
        this.aes256GcmCrypto = aes256GcmCrypto;
    }

    /**
     * 分页查询用户列表
     * <p>
     * [实现说明]
     * 1. 使用 PageQuery.toMpPage() 统一构建分页对象（已含页码兜底/上限钳制与排序兜底），避免各业务手拼 SQL
     * 2. 默认只查未删除用户，按需拼接用户名/状态/角色/注册时间筛选
     * 3. 角色筛选需先 join 关联表：由 roleCode 反查 roleId，再查 sys_user_role 拿到 userId 集合作为主表 in 条件
     * 4. 手机号/邮箱为密文存库，列表不做明文回显；映射时仅输出安全字段
     */
    @Override
    public PageResult<UserAdminVO> page(UserQueryDTO query) {
        // 1. 统一构建分页对象（页码/条数兜底、排序兜底均在此完成）
        Page<User> mpPage = query.toMpPage();

        // 2. 构建条件构造器
        LambdaQueryWrapper<User> wrapper = Wrappers.lambdaQuery(User.class);
        // 2.1 默认过滤已逻辑删除用户；admin 传入 includeDeleted=true 时跳过该条件（审计场景查全量）
        if (!Boolean.TRUE.equals(query.getIncludeDeleted())) {
            wrapper.eq(User::getDelFlag, DelFlag.NOT_DELETED);
        }

        // 3. 拼接可选筛选条件
        if (query.getUsername() != null && !query.getUsername().isBlank()) {
            wrapper.like(User::getUsername, query.getUsername().trim());
        }
        if (query.getStatus() != null) {
            wrapper.eq(User::getStatus, query.getStatus());
        }
        if (query.getStartTime() != null) {
            wrapper.ge(User::getCreateTime, query.getStartTime());
        }
        if (query.getEndTime() != null) {
            wrapper.le(User::getCreateTime, query.getEndTime());
        }

        // 4. 角色筛选：roleCode -> roleId -> userId 集合，作为主表 in 条件
        if (query.getRoleCode() != null && !query.getRoleCode().isBlank()) {
            Role role = roleService.getByRoleCode(query.getRoleCode());
            if (role == null) {
                return PageResult.of(mpPage.getCurrent(), mpPage.getSize(), 0, List.of());
            }
            List<Long> userIds = userRoleService.lambdaQuery()
                    .eq(UserRole::getRoleId, role.getId())
                    .list()
                    .stream()
                    .map(UserRole::getUserId)
                    .distinct()//去重
                    .collect(Collectors.toList());
            if (userIds.isEmpty()) {
                return PageResult.of(mpPage.getCurrent(), mpPage.getSize(), 0, List.of());
            }
            wrapper.in(User::getId, userIds);
        }

        // 5. 分页查询
        IPage<User> result = userService.page(mpPage, wrapper);

        // 6. 分页结果转 VO（PageUtil 统一处理），单条映射在 toAdminVO 中完成
        return PageUtil.convert(result, this::toAdminVO);
    }

    /**
     * 用户实体转列表 VO
     * <p>
     * 聚合角色编码；手机号/邮箱密文不做回显；头像标识枚举转 code。
     */
    private UserAdminVO toAdminVO(User user) {
        // 1. 聚合角色编码（复用，与详情页逻辑保持一致）
        List<String> roleCodes = getRoleCodes(user.getId());

        // 2. 组装 VO
        return new UserAdminVO()
                .setId(user.getId())
                .setUsername(user.getUsername())
                .setAvatar(user.getAvatar())
                .setAvatarColor(user.getAvatarColor() != null ? user.getAvatarColor().getCode() : null)
                .setBio(user.getBio())
                .setStatus(user.getStatus() != null ? user.getStatus().getCode() : null)
                .setVipValid(user.getVipExpireTime() != null && user.getVipExpireTime().isAfter(java.time.LocalDateTime.now()))
                .setRoleCodes(roleCodes);
    }

    /**
     * 查询用户详情
     * <p>
     * [实现说明]
     * 1. 查询用户，不存在抛 BizException(USER_NOT_FOUND)
     * 2. 聚合角色编码（复用 getRoleCodes，与列表保持一致，避免逻辑漂移）
     * 3. 仅当用户含 AUTHOR/LEGACY_AUTHOR 角色时才查询作者扩展资料，减少无效 DB 访问
     * 4. 组装详情 VO：VIP 有效期、账号冻结/注销状态一并回显
     */
    @Override
    public UserAdminDetailVO detail(Long userId) {
        // 1. 查询用户，不存在抛业务异常
        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 2. 聚合角色编码
        List<String> roleCodes = getRoleCodes(user.getId());

        // 3. 作者扩展资料（仅作者角色才查，降低非作者用户的 DB 开销）
        UserProfile profile = roleCodes.stream()
                .filter(code -> RoleCodeEnum.AUTHOR.getCode().equals(code)
                        || RoleCodeEnum.LEGACY_AUTHOR.getCode().equals(code))
                .findFirst()//.findFirst() 是必须的，因为 map() 只能接在 Optional 上，而 filter() 返回的是 Stream，不是 Optional
                // _____把 Stream 终结掉，取第一个匹配的元素，包装成 Optional<String>：
                .map(code -> userProfileService.lambdaQuery()
                        .eq(UserProfile::getUserId, user.getId())
                        .one())
                .orElse(null);//如果前面 map 没拿到值（即为 null / 空），就返回你指定的默认值。

        // 4. 组装详情 VO
        return new UserAdminDetailVO()
                .setId(user.getId())
                .setUsername(user.getUsername())
                .setAvatar(user.getAvatar())
                .setAvatarColor(user.getAvatarColor() != null ? user.getAvatarColor().getCode() : null)
                .setBio(user.getBio())
                .setStatus(user.getStatus() != null ? user.getStatus().getCode() : null)
                .setRoleCodes(roleCodes)
                .setVip(user.getVipExpireTime() != null && user.getVipExpireTime().isAfter(LocalDateTime.now()))
                .setVipExpireTime(user.getVipExpireTime())
                .setFrozenUntil(user.getFrozenUntil())
                .setCancelTime(user.getCancelTime())
                .setCreateTime(user.getCreateTime())
                .setPenName(profile != null ? profile.getPenName() : null)
                .setCreationDir(profile != null ? profile.getCreationDir() : null)
                .setMasterpiece(profile != null ? profile.getMasterpiece() : null);
    }

    /**
     * 聚合用户角色编码列表
     * <p>
     * 复用逻辑：列表页（toAdminVO）与详情页均依赖，抽取为私有方法避免重复实现。
     */
    private List<String> getRoleCodes(Long userId) {
        return userRoleService.lambdaQuery()
                .eq(UserRole::getUserId, userId)
                .list()
                .stream()
                .map(ur -> roleService.getById(ur.getRoleId()))
                .filter(Objects::nonNull)
                .map(Role::getRoleCode)
                .collect(Collectors.toList());
    }

//    /**
//     * 管理员修改用户资料
//     */
//    @Override
//    public void updateByAdmin(Long userId, UserAdminUpdateDTO dto) {
//        // 【实现思路】
//        // 1. 查询用户是否存在、是否已注销，异常则抛 BizException
//        // 2. 级改白名单字段（头像/简介等，排除手机号/邮箱/密码）
//        // 3. 乐观锁更新：userService.updateById(user)，失败重试或抛并发异常
//        // TODO 待实现
//        throw new UnsupportedOperationException("UserAdminService.updateByAdmin 待实现");
//    }

    /**
     * 变更用户状态（启用/禁用）。禁用（封号）可选给定到期时间，并将吸附到每日 03:00 粒度。
     */
    @Override
    public void changeStatus(Long userId, UserStatusChangeDTO dto) {
        // 【实现思路】
        // 1. 查询目标用户，前置校验：不存在抛 USER_NOT_FOUND；已注销不允许再变更
        // 2. 等级校验：目标用户等级 >= 当前操作者等级，抛 FORBIDDEN，防止同级/上级被封号
        // 3. 按 dto.getStatus() 分支处理：
        //    - NORMAL(启用)：status=NORMAL，frozenUntil=null（解封）
        //    - DISABLED(封号)：status=DISABLED，解封时间必填缺省默认7日，
        //      统一向上取整到下一个每日 03:00 且必须晚于当前时间
        // 4. 乐观锁更新，成功后记录业务日志（审计日志由切面完成）

        // 1. 查询目标用户并校验
        User user = userService.getById(userId);
        if (user == null || user.getStatus() == UserStatus.CANCELLED) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 2. 等级校验：禁止操作同级或上级管理员，防止越权提权
        Long operatorId = StpUtil.getLoginIdAsLong();
        RoleLevel operatorLevel = maxRoleLevel(getRoleCodes(operatorId));
        RoleLevel targetLevel = maxRoleLevel(getRoleCodes(user.getId()));
        if (targetLevel.ordinal() >= operatorLevel.ordinal()) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        // 2.1 取操作者用户名（审计快照）
        User operator = userService.getById(operatorId);
        String operatorName = operator != null ? operator.getUsername() : null;

        // 3. 按目标状态分支处理
        UserStatus target = dto.getStatus();
        if (target == UserStatus.CANCELLED) {
            // 注销走 logicDelete 独立流程
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        user.setStatus(target);
        if (target == UserStatus.NORMAL) {
            // 启用（解封）：清空封禁截止时间
            user.setFrozenUntil(null);
        } else {
            // 封号：原因必填（审计追溯），解封时间缺省默认 7 日（仍吸附到每日 03:00 粒度，且必须晚于当前时间）
            if (dto.getReason() == null || dto.getReason().isBlank()) {
                throw new BizException(ErrorCode.PARAM_ERROR);
            }

            LocalDateTime frozenUntil = dto.getFrozenUntil() != null
                    ? dto.getFrozenUntil()
                    : LocalDateTime.now().plusDays(DEFAULT_BAN_DAYS);
            LocalDateTime aligned = alignToNextDaily3AM(frozenUntil);
            if (!aligned.isAfter(LocalDateTime.now())) {
                throw new BizException(ErrorCode.FORBIDDEN);
            }
            user.setFrozenUntil(aligned);
        }

        // 4. 乐观锁更新
        if (!userService.updateById(user)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        // 5. 记录封号/解封审计日志（快照：用户名/角色/原因/解封时间/操作者）
        UserBanLog banLog = UserBanLog.builder()
            .userId(user.getId())
            .username(user.getUsername())
            .roleCode(String.join(",", getRoleCodes(user.getId())))
            .action(target == UserStatus.NORMAL ? UserBanAction.UNBAN : UserBanAction.BAN)
            .reason(dto.getReason())
            .frozenUntil(user.getFrozenUntil())
            .operatorId(operatorId)
            .operatorName(operatorName)
            .build();
        userBanLogService.record(banLog);

        log.info("管理员 [{}] 将用户 [{}] 状态变更为 [{}]，frozenUntil=[{}]",
                operatorId, userId, target, user.getFrozenUntil());
    }

    /**
     * 将时间向上取整到下一个每日 03:00（若已过今日 03:00 则顺延到次日）
     * <p>
     * 统一"解封校验"粒度为每日凌晨 3 点：封禁截止时间都会被吸附到 03:00 边界，
     * 定时任务只需在每个 03:00 检查一次，避免任意时间点逐个触发，提升扫描效率。
     */
    private LocalDateTime alignToNextDaily3AM(LocalDateTime time) {
        LocalDateTime today3AM = time.toLocalDate().atTime(3, 0);
        return time.isAfter(today3AM) ? today3AM.plusDays(1) : today3AM;
    }

    /**
     * 取一组角色编码中的最高等级，用于越权校验
     */
    private RoleLevel maxRoleLevel(List<String> roleCodes) {
        return roleCodes.stream()
                .map(RoleCodeEnum::of)
                .filter(Objects::nonNull)
                .map(RoleCodeEnum::getLevel)
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElse(RoleLevel.NORMAL);
    }

    /**
     * 分配用户角色
     */
    @Override
    public void assignRoles(Long userId, RoleAssignDTO dto) {
        // 【实现思路】
        // 1. 校验 dto.getRoleCodes() 不能包含 SUPER_ADMIN（SUPER_ADMIN 角色由系统/Boot阶段初始化，不可后台授予）
        // 2. 校验角色编码合法性：逐个 roleService.getByRoleCode，不存在则抛 BizException
        // 3. 全量覆盖：删除该用户旧 user_role -> 批量插入新 user_role（建议 @Transactional）
        // 4. 若授予/移除 AUTHOR/LEGACY_AUTHOR，联动处理 user_profile（存在/删除作者扩展资料）及头像标识
        // TODO 待实现
        throw new UnsupportedOperationException("UserAdminService.assignRoles 待实现");
    }

    /**
     * 管理用户 VIP
     */
    @Override
    public void manageVip(Long userId, VipUpdateDTO dto) {
        // 【实现思路】
        // 1. 查询用户，已注销用户不允许操作 VIP
        // 2. 按 dto.getOp() 分支处理：
        //    - SET：直接设置 vipExpireTime=dto.getVipExpireTime()
        //    - EXTEND：新到期时间 = max(now, 现有到期时间).plusDays(days)
        //    - CANCEL：vipExpireTime=null
        // 3. 乐观锁更新，写日志
        // TODO 待实现
        throw new UnsupportedOperationException("UserAdminService.manageVip 待实现");
    }

    /**
     * 注销用户
     */
    @Override
    public void logicDelete(Long userId) {
        // 【实现思路】
        // 1. 查询用户，为空或已注销则直接返回/抛异常
        // 2. 设置注销时间 cancel_time=now()、status=CANCELED、delFlag=DELETED，乐观锁更新
        // 3. 若为作者，清零/保留其资产（此处理留钩子，由 account 模块兜底）
        // 4. 清理其在线会话（调 auth 侧退出接口或记录待清理），移除 Redis 中的设备/refreshToken
        // TODO 待实现
        throw new UnsupportedOperationException("UserAdminService.logicDelete 待实现");
    }
}
