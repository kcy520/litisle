package com.litisle.litisleauth.service.impl;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.constant.SaTokenSessionKeyConstant;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.commonsecurity.desensitize.SensitiveStrategy;
import com.litisle.commonredis.util.RedisUtil;
import com.litisle.commonsecurity.crypto.PasswordCrypto;
import com.litisle.litisleapi.dto.user.AuthUserDTO;
import com.litisle.litisleapi.dto.user.RegisterUserDTO;
import com.litisle.litisleapi.dfa.DfaFacade;
import com.litisle.litisleapi.dfa.DfaHitResult;
import com.litisle.litisleapi.dfa.DfaScenes;
import com.litisle.litisleapi.feign.UserClient;
import com.litisle.litisleapi.moderation.DfaPublishGate;
import com.litisle.litisleapi.moderation.PublishTextPrecheck;
import com.litisle.litisleapi.moderation.UsernameRejectGate;
import com.litisle.litisleauth.config.AuthProperties;
import com.litisle.litisleauth.dfa.UsernameRejectRecordAdapter;
import com.litisle.litisleauth.constant.AuthCacheKey;
import com.litisle.litisleauth.domain.dto.EmailLoginDTO;
import com.litisle.litisleauth.domain.dto.PasswordLoginDTO;
import com.litisle.litisleauth.domain.dto.RefreshTsuccessenDTO;
import com.litisle.litisleauth.domain.dto.RegisterRequestDTO;
import com.litisle.litisleauth.domain.vo.LoginUserInfoVO;
import com.litisle.litisleauth.domain.vo.TsuccessenVO;
import com.litisle.litisleauth.enums.EmailCodeUsage;
import com.litisle.litisleauth.service.AuthService;
import com.litisle.litisleauth.service.CaptchaService;
import com.litisle.litisleauth.service.DeviceSessionService;
import com.litisle.commoncore.constant.CommonConstant;
import com.litisle.commoncore.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class AuthServiceImpl implements AuthService {

    // ==================== 依赖注入 ====================

    private final UserClient userClient;
    private final CaptchaService captchaService;
    private final DeviceSessionService deviceSessionService;
    private final RedisUtil redisUtil;
    private final PasswordCrypto passwordCrypto;
    private final AuthProperties authProperties;
    private final DfaFacade dfaFacade;
    private final UsernameRejectRecordAdapter usernameRejectRecordAdapter;

    // ==================== 注册 ====================

    /**
     * 用户注册（审核链路编排顺序收口，临时清单 A4）
     *
     * <p>【编排顺序铁律】空内容校验（A1，步骤 0）→ DFA 同步闸门（A2，步骤 0.1）→
     * 拒绝取证/放行注册（A3，步骤 0.2/0.3）——顺序收口声明对齐
     * {@link com.litisle.litisleapi.moderation.DfaPublishGate} 类注落位口径：
     * 发布入口内、PublishTextPrecheck 空内容校验之后执行 DFA 同步闸门。</p>
     *
     * <p>【时序铁律依据】① 空内容校验必须最先执行：空内容 content_hash 恒定，
     * 若后置于 DFA/hash 派生，一条 PASS 缓存即污染全部空内容免审；
     * ② DFA 闸门在业务校验（验证码/密码加密）之前执行：违规用户名拦截不消耗
     * 一次性验证码与图形验证码资源（防撞库探测词库的附带资源保护）；
     * ③ HIGH 拦截时先取证后抛 70001（取证失败仅 error 留痕不影响拦截结论），
     * MID/未命中放行注册。</p>
     *
     * @param dto 注册请求（username/email/password/phone/source）
     */
    @Override
    public void register(RegisterRequestDTO dto) {
        log.info("用户注册请求: username={}, email={}", dto.getUsername(), SensitiveStrategy.EMAIL.desensitize(dto.getEmail()));

        // 0. 发布前置校验（板块四 4-1.1 / 临时清单 A1）：用户名场景 title 传 null 仅校验内容——
        //    trim 非空 + 去 HTML 标签后可见文本非空（契约 SSOT 口径，标题≤100 不适用用户名）；
        //    时序铁律：空内容校验必须先于后续 DFA 闸门（A2 已接入，见 0.1）与 content_hash 计算执行；
        //    与 DTO Bean Validation 不互替：注解管格式（3-32 位/字符集），本闸口管审核口径（SSOT 收口）
        PublishTextPrecheck.check(null, dto.getUsername());

        // 0.1 DFA 同步闸门（板块四 4-2.3 / 临时清单 A2）：用户名场景同步 DFA 判定——
        //     本地引擎零网络；拦截决策口径 SSOT 收口 DfaPublishGate.decide（HIGH 拦/MID 标），
        //     本类禁止自行复制 if 决策分支（防口径漂移）
        DfaHitResult hitResult = dfaFacade.check(DfaScenes.USERNAME, dto.getUsername());
        DfaPublishGate.DfaGateDecision decision = DfaPublishGate.decide(DfaScenes.USERNAME, hitResult);

        // 0.2 HIGH 拦截分支（A3 取证）：UsernameRejectGate 收口「拒绝之后的取证」——不建任务只取证
        //     （biz_id=0 占位）、不落用户名明文（仅 SHA-256 指纹）；取证失败仅 error 留痕不影响拦截
        //     结论（宁严勿松：取证是审计增强项，非拦截前置条件），连续失败须告警排查取证链路
        if (decision.blocked()) {
            boolean recorded = UsernameRejectGate.recordReject(
                    dto.getUsername(), decision.hitResult(), usernameRejectRecordAdapter);
            if (!recorded) {
                log.error("用户名拒绝取证落库失败（注册拒绝结论不变，详见适配器 error 日志）");
            }
            // 拒绝语义：70001（仅用于 HIGH），提示禁回显命中词（防词库探测，与 preview-check 同纪律）
            throw new BizException(ErrorCode.SENSITIVE_WORD_DETECTED);
        }
        // 0.3 MID/未命中放行：MID 为新增词灰度观察位（只标记不拦截），用户名无异步送审链路
        //     （4-7.1 仅 HIGH 取证，MID 命中禁止进 UsernameRejectGate），注册放行

        // 1. 校验图形验证码（一次性）
//        captchaService.verifyImgCode(dto.getUuid(), dto.getImgCode());

//        // todo 2. 注册校验验证码（一次性） 方便测试不用邮箱
//        captchaService.verifyEmailCode(dto.getEmail(), EmailCodeUsage.REGISTER, dto.getEmailCode());

        // 3. BCrypt 加密密码
        String encodedPassword = passwordCrypto.encode(dto.getPassword());

        // 4. Feign 调用 userClient.createUser() 创建用户
        RegisterUserDTO registerUser = new RegisterUserDTO()
                .setUsername(dto.getUsername())
                .setPassword(encodedPassword)
                .setPhone(dto.getPhone())
                .setEmail(dto.getEmail())
                .setSource(dto.getSource());

        Result<AuthUserDTO> result = userClient.createUser(registerUser);

        if (!result.isSuccess() || result.getData() == null) {
            log.warn("远程创建用户失败: username={}, reason={}",
                    dto.getUsername(), result.getMessage());
            String errorMsg = result.getMessage() != null
                    ? result.getMessage()
                    : "用户服务暂不可用，注册失败";
            throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, errorMsg);
        }

        // 5. 注册完成即返回，不签发 Token（登录需重新调用 /api/auth/login 建立会话）
        log.info("用户注册成功: userId={}, username={}",
                result.getData().getUserId(), result.getData().getUsername());
    }

    // ==================== 密码登录 ====================

    @Override
    public TsuccessenVO passwordLogin(PasswordLoginDTO dto) {
        log.info("密码登录请求: account={}", dto.getAccount());

        // 1. Feign 调用 userClient.findByAccount() 查询用户
        Result<AuthUserDTO> result = userClient.findByAccount(dto.getAccount());
        if (!result.isSuccess() || result.getData() == null) {
            log.warn("用户不存在: account={}", dto.getAccount());
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        AuthUserDTO authUser = result.getData();
        Long userId = authUser.getUserId();
        String username = authUser.getUsername();
        String encodedPassword = authUser.getPassword();
        Integer status = authUser.getStatus();
        LocalDateTime frozenUntil = authUser.getFrozenUntil();

        // 2. 校验账号状态
        validateUserStatus(status, frozenUntil);

        // 3. 校验密码（BCrypt）
        if (!passwordCrypto.matches(dto.getPassword(), encodedPassword)) {
            log.warn("密码错误: account={}", dto.getAccount());
            throw new BizException(ErrorCode.USER_PASSWORD_ERROR);
        }

        // 4. 签发双 Token
        return buildTsuccessenResponse(userId, username, false, authUser.getUid(),
                authUser.getRoles(), authUser.getPermissions());
    }

    // ==================== 邮箱验证码登录 ====================

    @Override
    public TsuccessenVO emailLogin(EmailLoginDTO dto) {
        log.info("邮箱验证码登录请求: email={}", SensitiveStrategy.EMAIL.desensitize(dto.getEmail()));

        // 1. 校验邮箱验证码（一次性）
        captchaService.verifyEmailCode(dto.getEmail(), EmailCodeUsage.LOGIN, dto.getCode());

        // 2. Feign 调用 userClient.findByEmail() 查询用户
        Result<AuthUserDTO> result = userClient.findByEmail(dto.getEmail());
        if (!result.isSuccess() || result.getData() == null) {
            log.warn("用户不存在: email={}", SensitiveStrategy.EMAIL.desensitize(dto.getEmail()));
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        AuthUserDTO authUser = result.getData();
        Long userId = authUser.getUserId();
        String username = authUser.getUsername();
        Integer status = authUser.getStatus();
        LocalDateTime frozenUntil = authUser.getFrozenUntil();

        // 3. 校验账号状态
        validateUserStatus(status, frozenUntil);

        // 4. 签发双 Token
        return buildTsuccessenResponse(userId, username, false, authUser.getUid(),
                authUser.getRoles(), authUser.getPermissions());
    }

    // ==================== 邮箱验证码登录 ====================

    @SuppressWarnings("unused")
    @Override
    public void logout(String tsuccessen) {
        log.info("退出登录请求");

        // 1. 获取当前登录 userId
        Long userId = StpUtil.getLoginIdAsLong();

        // 2. 删除 Redis 中的 refreshToken
        String refreshToken = redisUtil.get(AuthCacheKey.USER_REFRESH + userId);
        if (refreshToken != null) {
            redisUtil.delete(AuthCacheKey.REFRESH_TOKEN + refreshToken);
            redisUtil.delete(AuthCacheKey.USER_REFRESH + userId);
            log.debug("已清除 refreshToken: userId={}", userId);
        }

        // 2.1 清理当前设备会话记录（尽力而为，不阻塞退出主流程）
        deviceSessionService.removeCurrentDevice(userId);

        // 3. Sa-Token 注销登录态（服务端会话失效，无需额外黑名单）
        StpUtil.logout();
        log.info("用户已退出登录: userId={}", userId);
    }

    // ==================== 强制下线（管理员触发） ====================

    @Override
    public void forceLogout(Long userId) {
        log.info("管理员{}强制下线用户: userId={}", StpUtil.getLoginId(), userId);

        // 1. 清理该用户 refreshToken（Redis）
        String refreshToken = redisUtil.get(AuthCacheKey.USER_REFRESH + userId);
        if (refreshToken != null) {
            redisUtil.delete(AuthCacheKey.REFRESH_TOKEN + refreshToken);
            redisUtil.delete(AuthCacheKey.USER_REFRESH + userId);
            log.debug("已清除强制下线用户的 refreshToken: userId={}", userId);
        }

        // 2. 清理全部在线设备并注销 Sa-Token 会话（尽力而为，内部已容错）
        deviceSessionService.kickAllDevices(userId);

        log.info("用户已强制下线: userId={}", userId);
    }

    // ==================== 刷新令牌 ====================

    @Override
    public TsuccessenVO refreshTsuccessen(RefreshTsuccessenDTO dto) {
        log.info("刷新令牌请求");

        // 1. 从 Redis 读取 refreshToken 对应的 userId
        String redisKey = AuthCacheKey.REFRESH_TOKEN + dto.getRefreshToken();
        String userIdStr = redisUtil.get(redisKey);

        // 2. 校验 refreshToken 有效性
        if (userIdStr == null) {
            log.warn("refreshToken 无效或已过期");
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        Long userId = Long.parseLong(userIdStr);

        // 3. 删除旧 refreshToken（令牌轮换，防重放）
        redisUtil.delete(redisKey);
        redisUtil.delete(AuthCacheKey.USER_REFRESH + userId);

        // 4. 签发新双 Token（refresh 无用户对象，uid 由前端持历史值即可，传 null）
        return buildTsuccessenResponse(userId, null, false, null, null, null);
    }

    // ==================== 当前用户信息 ====================

    /**
     * 获取当前登录用户信息
     * @return
     */
    @Override
    public LoginUserInfoVO currentLoginUser() {
        // 1. 获取当前登录 userId
        Long userId = StpUtil.getLoginIdAsLong();
        log.info("获取当前用户信息: userId={}", userId);

        // 2. Feign 调用 userClient.getAuthUserInfo() 获取聚合信息
        Result<AuthUserDTO> result = userClient.getAuthUserInfo(userId);
        if (!result.isSuccess() || result.getData() == null) {
            log.warn("获取用户信息失败: userId={}", userId);
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        AuthUserDTO authUser = result.getData();

        // 3. 组装 LoginUserInfoVO（phone/email 传解密明文，由 VO 字段上的 @Sensitive 注解在序列化时自动脱敏）
        LoginUserInfoVO vo = new LoginUserInfoVO()
                .setUsername(authUser.getUsername())
                .setUid(authUser.getUid())
                // ---------建议删除此注释（desensitizePhone/desensitizeEmail 已被 @Sensitive 注解替代，请手动确认）
//                .setPhone(desensitizePhone(authUser.getPhone()))
//                .setEmail(desensitizeEmail(authUser.getEmail()))
                .setPhone(authUser.getPhone())
                .setEmail(authUser.getEmail())
                .setAvatarFileId(authUser.getAvatarFileId())
                .setAvatarColor(authUser.getAvatarColor())
                .setBio(authUser.getBio())
                .setVip(authUser.getVip())
                .setVipExpireTime(authUser.getVipExpireTime())
                .setStatus(authUser.getStatus())
                .setRoles(authUser.getRoles());

        // 4. 作家扩展资料（非作家为 null）
        if (authUser.getPenName() != null || authUser.getCreationDir() != null || authUser.getMasterpiece() != null) {
            LoginUserInfoVO.AuthorProfile author = new LoginUserInfoVO.AuthorProfile();
            author.setPenName(authUser.getPenName());
            author.setCreationDir(authUser.getCreationDir());
            author.setMasterpiece(authUser.getMasterpiece());
            vo.setAuthor(author);
        }

        return vo;
    }

    // ==================== 私有方法 ====================

    /**
     * 签发双 Token（accessToken + refreshToken）
     *
     * z注入用户信息至session
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param newUser  是否新注册用户
     * @param uid      对外UID（注册/登录场景回传；刷新令牌场景可能为 null）
     * @return 令牌响应 VO
     */
    private TsuccessenVO buildTsuccessenResponse(Long userId, String username, boolean newUser, String uid,
                                                  List<String> roles, List<String> permissions) {
        // 1. Sa-Token 建立登录态
        StpUtil.login(userId);

        // 1.1 角色/权限写入 Sa-Token Session（供 common-security 的 StpInterfaceImpl 鉴权时读取）
        //     键名统一引用 SaTokenSessionKeyConstant，与读取方保持一致
        //     刷新令牌场景传 null：Session 随登录态已存在且未变更，无需重写
        if (roles != null || permissions != null) {
            SaSession session = StpUtil.getSession();
            if (roles != null) {
                session.set(SaTokenSessionKeyConstant.ROLES, roles);
            }
            if (permissions != null) {
                session.set(SaTokenSessionKeyConstant.PERMISSIONS, permissions);
            }
        }

        // 2. 获取 Sa-Token 生成的 accessToken —— Sa-Token 生成的访问令牌
        String accessToken = StpUtil.getTokenValue();

        // 3. 生成 refreshToken（UUID，无连字符） —— 只在刷新令牌时用到
        String refreshToken = UUID.randomUUID().toString().replace("-", "");

        // 4. refreshToken 存 Redis（TTL 可配置，默认 7 天），用于令牌刷新
        long ttl = authProperties.getRefreshTokenExpire();
        //auth:refresh:#{refreshToken——#{userId}
        redisUtil.set(AuthCacheKey.REFRESH_TOKEN + refreshToken,
                String.valueOf(userId), ttl, TimeUnit.SECONDS);

        // 5. 存储 userId→refreshToken 映射，便于退出时清理
        redisUtil.set(AuthCacheKey.USER_REFRESH + userId,
                refreshToken, ttl, TimeUnit.SECONDS);

        // 5.1 注册/更新当前设备会话（deviceId 优先取请求头 X-Device-Id，未携带则服务端生成；
        //     刷新令牌场景复用同一 deviceId，仅更新 tokenValue）
        String deviceId = deviceSessionService.registerDevice(userId, accessToken);

        // 6. 获取 accessToken 剩余有效期（秒）
        long expiresIn = StpUtil.getTokenTimeout();

        log.debug("双 Token 签发成功: userId={}, expiresIn={}s", userId, expiresIn);

        // 7. 构建响应
        return TsuccessenVO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(expiresIn)
                .tokenType(CommonConstant.TOKEN_TYPE_BEARER)
                .username(username)
                .uid(uid)
                .deviceId(deviceId)
                .newUser(newUser)
                .issuedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 校验账号状态
     *
     * @param status      账号状态（0-禁用 1-正常 2-注销）
     * @param frozenUntil 冻结到期时间（null 表示未冻结）
     */
    private void validateUserStatus(Integer status, LocalDateTime frozenUntil) {
        if (status == null) {
            return;
        }

        // 账号已注销（UserStatus.CANCELLED）
        if (status == UserStatus.CANCELLED.getCode()) {
            throw new BizException(ErrorCode.USER_LOGGED_OFF);
        }

        // 账号被冻结（UserStatus.DISABLED）且冻结期未过
        if (status == UserStatus.DISABLED.getCode()
                && frozenUntil != null
                && frozenUntil.isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.USER_FROZEN,
                    "账号已被冻结至 " + frozenUntil);
        }
    }
}
