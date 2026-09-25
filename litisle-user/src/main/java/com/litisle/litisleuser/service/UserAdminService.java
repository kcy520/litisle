package com.litisle.litisleuser.service;

import com.litisle.commoncore.result.PageResult;
import com.litisle.litisleuser.domain.dto.UserQueryDTO;
import com.litisle.litisleuser.domain.dto.UserStatusChangeDTO;
import com.litisle.litisleuser.domain.vo.UserAdminDetailVO;
import com.litisle.litisleuser.domain.vo.UserAdminVO;

import java.util.List;

/**
 * 后台管理员 - 用户管理业务接口
 * <p>
 * 面向 ADMIN / SUPER_ADMIN，提供用户查询、资料维护、状态管控、角色分配、VIP 管理与注销等能力。
 * 与前台用户资料接口物理隔离，敏感字段（手机号/邮箱明文）查看请走 UserSensitiveService。
 * 【事务规范】接口上不放 @Transactional：Spring Boot 默认 CGLIB 代理下接口注解不被识别
 * （属碰巧生效），且类级粒度会让 page/detail 读方法也白开事务占连接——事务下沉到
 * UserAdminServiceImpl 的写方法（参照 UserProfileServiceImpl 方法级写法）
 *
 * @author litisle
 */
public interface UserAdminService {

    /**
     * 分页查询用户列表
     * <p>
     * 支持按用户名/手机号(密文)/状态/角色/注册时间范围组合筛选，
     * 返回不包含密码与敏感明文字段的用户行数据。
     *
     * @param query 查询条件（分页参数 + 可选筛选条件）
     * @return 分页结果
     */
    PageResult<UserAdminVO> page(UserQueryDTO query);

    /**
     * 查询用户详情
     * <p>
     * 聚合展示用户基本信息、角色列表、VIP 状态、作者扩展资料、冻结/注销状态。
     *
     * @param userId 用户ID
     * @return 用户详情 VO
     */
    UserAdminDetailVO detail(Long userId);

//    /**
//     * 管理员修改用户资料
//     * <p>
//     * 仅允许修改非敏感字段（头像/简介等），手机号/邮箱变更须走独立安全流程。
//     *
//     * @param userId 用户ID
//     * @param dto    待修改字段
//     */
//    void updateByAdmin(Long userId, UserAdminUpdateDTO dto);

    /**
     * 变更用户状态（启用/禁用，禁用即封号）
     * <p>
     * 校验目标用户等级，禁止操作同级或上级管理员；封号时解封时间必填，缺省默认 7 日，
     * 到期时间会被向上取整吸附到每日 03:00 粒度，便于后台按点定时解封。
     *
     * @param userId 用户ID
     * @param dto    目标状态与封禁截止时间（封号必填，缺省默认 7 日）
     */
    void changeStatus(Long userId, UserStatusChangeDTO dto);


    /**
     * 添加用户角色（增量）
     * <p>
     * 在目标用户现有角色基础上追加角色；校验角色合法性与操作等级，禁止授予 SUPER_ADMIN。
     *
     * @param userId    用户ID
     * @param roleCodes 待添加的角色编码集合
     */
    void addRoles(Long userId, List<String> roleCodes);

    /**
     * 移除用户角色（增量）
     * <p>
     * 仅移除目标用户实际拥有的角色；禁止移除 SUPER_ADMIN 与 READER 基础角色。
     *
     * @param userId    用户ID
     * @param roleCodes 待移除的角色编码集合
     */
    void removeRoles(Long userId, List<String> roleCodes);

//    /**
//     * 管理用户 VIP（设置/延长/取消到期时间）
//     * <p>
//     * 已注销用户不允许操作；延长基于现有到期时间累加。
//     *
//     * @param userId 用户ID
//     * @param dto    VIP 操作参数
//     */
//    void manageVip(Long userId, VipUpdateDTO dto);

    /**
     * 注销用户（软删除）
     * <p>
     * 设置注销时间与注销状态，清理其会话与关联数据，触发账号注销后续流程。
     *
     * @param userId 用户ID
     */
    void logicDeleteUserByAdmin(Long userId);
}
