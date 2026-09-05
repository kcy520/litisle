package com.litisle.litisleuser.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.litisle.litisleuser.domain.entity.UserRole;
import com.litisle.litisleuser.domain.vo.RoleVO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(rollbackFor = Exception.class)
public interface UserRoleService extends IService<UserRole> {
    /**
     *查询当前系统包含的所有角色
     * @return
     */
    List<RoleVO> listRoles();

    /**
     * 查询当前用户拥有角色
     * @return
     */
    List<RoleVO> currentUserRoles();

    /**
     * 聚合指定用户的角色编码列表
     * <p>
     * 复用逻辑：后台用户管理（列表/详情/等级校验/审计快照）与前台当前用户角色查询均依赖，
     * 抽取到本接口统一维护，避免多处重复实现。
     *
     * @param userId 用户ID
     * @return 角色编码列表，用户无角色时返回空列表
     */
    List<String> getRoleCodes(Long userId);

    List<RoleVO> userRoles(Long userId);

//    List<String> getRoleCodesByUserId(Long userId);
}