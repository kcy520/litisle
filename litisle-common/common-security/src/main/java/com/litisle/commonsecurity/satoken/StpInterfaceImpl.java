package com.litisle.commonsecurity.satoken;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.constant.SaTokenSessionKeyConstant;
import com.litisle.commonsecurity.enums.RoleCodeEnum;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 角色/权限数据源实现
 *
 * 【设计说明】
 * 1. 实现 Sa-Token 的 StpInterface 接口，为框架提供角色和权限列表
 * 2. Sa-Token 框架在调用 StpUtil.getRoleList() / getPermissionList() 时，会回调本类的对应方法
 * 3. 数据来源：从 Sa-Token Session 中读取（登录时由 litisle-auth 写入）
 *
 * 【为什么从 Session 读取而不是查 DB】
 * - 权限校验是高频操作（每次请求都可能触发），每次都查 DB 性能极差
 * - 登录时一次性查出所有角色/权限写入 Session，后续请求直接从 Session 读取
 * - 角色/权限变更时，通过 Sa-Token 的踢人下线机制强制用户重新登录即可刷新
 *
 * 【Session 数据写入方（litisle-auth 登录时）】
 * SaSession session = StpUtil.getSession();
 * session.set("roles", Arrays.asList("READER", "AUTHOR"));
 * session.set("permissions", Arrays.asList("article:read", "article:write"));
 *
 * 【线程安全】
 * - SaSession 本身非线程安全，但 Sa-Token 框架保证同一 Session 的读写是串行的
 * - 本类无状态，方法可并发调用
 *
 * @author litisle
 */
@Slf4j
public class StpInterfaceImpl implements StpInterface {



    /**
     * 会话注入流程
     * ① 查库聚合（user 模块）
     *    UserFeignProviderController#buildAuthUserDTO (L260-288)
     *    查 user_role 表 → 批量 IN 查 role 表 → 取 roleCode + 权限列表
     *    → 封装进 AuthUserDTO 返回给 auth
     *         │
     *         ▼  Feign：findByAccount / findByEmail / getAuthUserInfo
     * ② 接收并传递（auth 模块）
     *    AuthServiceImpl 各登录入口（注册 L113 / 密码登录 / 邮箱登录）
     *    从 AuthUserDTO 取出 getRoles() / getPermissions() 传入 buildTsuccessenResponse
     *         │
     *         ▼
     * ③ 写入 Session（auth 模块）★ 上上轮修复落地点
     *    AuthServiceImpl#buildTsuccessenResponse (L279-292)
     *    StpUtil.login(userId) 之后：
     *    session.set(SaTokenSessionKeyConstant.ROLES, roles)
     *    session.set(SaTokenSessionKeyConstant.PERMISSIONS, permissions)
     *    → Sa-Token 自动持久化到 Redis，随登录态存活
    * */


    /**
     * 返回指定 loginId 的角色列表
     *
     * 【调用时机】
     * - StpUtil.getRoleList() 被调用时
     * - StpUtil.hasRole() 被调用时
     * - StpUtil.checkRole() 被调用时
     *
     * 【容错策略】
     * - Session 不存在或未设置 roles 时，返回空列表（不抛异常）
     * - 空列表意味着该用户没有任何角色，后续权限校验自然失败
     *
     * @param loginId  登录 ID（业务 userId）
     * @param loginType 登录类型（如 "login"）
     * @return 角色编码列表，如 ["READER", "AUTHOR"]
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        SaSession session = StpUtil.getSessionByLoginId(loginId, false);
        if (session == null) {
            log.debug("用户 {} 的 Session 不存在，返回空角色列表", loginId);
            return Collections.emptyList();
        }

        Object rolesObj = session.get(SaTokenSessionKeyConstant.ROLES);
        if (rolesObj instanceof List<?> list) {
            //常见于泛型类型转换时，编译器无法确定泛型类型在运行时是否安全，因此会提示：
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) list;
            // 超级管理员拥有所有角色：返回全部角色编码，任何角色校验均直接通过
            // 注意：Sa-Token 角色校验不支持 "*" 通配符（仅权限校验支持），故返回全部角色编码
            if (roles.contains(RoleCodeEnum.SUPER_ADMIN.getCode())) {
                log.debug("用户 {} 为超级管理员，授予全部角色", loginId);
                return Arrays.stream(RoleCodeEnum.values())
                        .map(RoleCodeEnum::getCode)
                        .toList();
            }
            log.debug("用户 {} 的角色列表: {}", loginId, roles);
            return roles;
        }

        log.debug("用户 {} 的角色列表为空", loginId);
        return Collections.emptyList();
    }

    /**
     * 返回指定 loginId 的权限列表
     *
     * 【调用时机】
     * - StpUtil.getPermissionList() 被调用时
     * - StpUtil.hasPermission() 被调用时
     * - StpUtil.checkPermission() 被调用时
     *
     * 【容错策略】同 getRoleList
     *
     * @param loginId  登录 ID（业务 userId）
     * @param loginType 登录类型
     * @return 权限编码列表，如 ["article:read", "user:update"]
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        SaSession session = StpUtil.getSessionByLoginId(loginId, false);
        if (session == null) {
            log.debug("用户 {} 的 Session 不存在，返回空权限列表", loginId);
            return Collections.emptyList();
        }

        // 超级管理员拥有所有权限：返回 Sa-Token 通配符 "*"，任何权限校验均直接通过
        // 本实现从 Session 的 roles 列表判断，所以 litisle-auth 登录时，超级管理员的角色列表必须包含 "SUPER_ADMIN"（写入 session.set("roles", ...)），否则判断不生效。
        Object rolesObj = session.get(SaTokenSessionKeyConstant.ROLES);
        if (rolesObj instanceof List<?> roleList && roleList.contains(RoleCodeEnum.SUPER_ADMIN.getCode())) {
            log.debug("用户 {} 为超级管理员，授予全部权限", loginId);
            return List.of("*");
        }

        Object permsObj = session.get(SaTokenSessionKeyConstant.PERMISSIONS);
        if (permsObj instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<String> permissions = (List<String>) list;
            log.debug("用户 {} 的权限列表: {}", loginId, permissions);
            return permissions;
        }

        log.debug("用户 {} 的权限列表为空", loginId);
        return Collections.emptyList();
    }
}