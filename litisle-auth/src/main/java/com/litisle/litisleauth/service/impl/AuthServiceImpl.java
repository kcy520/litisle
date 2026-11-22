package com.litisle.litisleauth.service.impl;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.commoncore.constant.HeaderConstant;
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
import com.litisle.litisleapi.feign.UserClient;
import com.litisle.litisleapi.moderation.UsernameModerationGate;
import com.litisle.litisleauth.config.AuthProperties;
import com.litisle.litisleauth.dfa.UsernameRejectRecordAdapter;
import com.litisle.litisleauth.constant.AuthCacheKey;
import com.litisle.litisleauth.domain.dto.EmailLoginDTO;
import com.litisle.litisleauth.domain.dto.PasswordLoginDTO;
import com.litisle.litisleauth.domain.dto.RefreshTsuccessenDTO;
import com.litisle.litisleauth.domain.dto.RefreshTokenPayload;
import com.litisle.litisleauth.domain.dto.RegisterRequestDTO;
import com.litisle.litisleauth.domain.vo.LoginUserInfoVO;
import com.litisle.litisleauth.domain.vo.TsuccessenVO;
import com.litisle.litisleauth.enums.EmailCodeUsage;
import com.litisle.litisleauth.service.AuthService;
import com.litisle.litisleauth.service.CaptchaService;
import com.litisle.litisleauth.service.DeviceSessionService;
import com.litisle.litisleauth.utils.DeviceFingerprintUtil;
import com.litisle.commoncore.constant.CommonConstant;
import com.litisle.commoncore.enums.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** JSON 序列化器（Refresh 载荷读写 Redis 用） */
    private final ObjectMapper objectMapper;
    /** 当前 HTTP 请求（request 作用域代理；登录/刷新时提取设备指纹与 deviceId） */
    private final HttpServletRequest request;

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

        // 0. 用户名审核闸门（SSOT 收口 UsernameModerationGate，注册/改名共用同一链路实现）：
        //    口径层 → 词库层 → 决策层 → HIGH 取证拒绝 / MID·未命中放行（链路与纪律见门面类注释）；
        //    与 DTO Bean Validation 不互替：注解管格式（3-32 位/字符集），本闸口管审核口径
        UsernameModerationGate.review(dto.getUsername(), dfaFacade, usernameRejectRecordAdapter);

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

        // 4. 签发双 Token（登录场景：familyId/deviceId 传 null = 新族 + 请求头/服务端生成设备）
        return buildTsuccessenResponse(userId, username, false, authUser.getUid(),
                authUser.getRoles(), authUser.getPermissions(), null, null);
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

        // 4. 签发双 Token（登录场景：familyId/deviceId 传 null = 新族 + 请求头/服务端生成设备）
        return buildTsuccessenResponse(userId, username, false, authUser.getUid(),
                authUser.getRoles(), authUser.getPermissions(), null, null);
    }

    // ==================== 邮箱验证码登录 ====================

    @SuppressWarnings("unused")
    @Override
    public void logout(String tsuccessen) {
        log.info("退出登录请求");

        /* 业务逻辑步骤：
           1. 获取当前登录 userId
           2. 按 X-Device-Id 定位当前设备令牌族并吊销（Refresh 正反向 key + 设备记录 + 该设备 Access 会话）
           3. 请求头缺失无法定位单设备 → 保守吊销该用户全部令牌族（宁严勿松，防 Refresh 残留至 TTL）
           4. Sa-Token 注销当前登录态 */

        // 1. 获取当前登录 userId
        Long userId = StpUtil.getLoginIdAsLong();

        // 2. 请求头携带 deviceId：仅吊销当前设备令牌族（多设备独立族，误伤半径限制单端）
        String deviceId = request.getHeader(HeaderConstant.DEVICE_ID);
        if (deviceId != null && !deviceId.isBlank()) {
            revokeFamily(userId, deviceId);
        } else {
            // 3. 请求头缺失：无法定位单设备族，保守吊销该用户全部令牌族
            log.warn("登出请求未携带 X-Device-Id，吊销该用户全部令牌族: userId={}", userId);
            revokeAllFamilies(userId);
        }

        // 4. Sa-Token 注销当前登录态（服务端会话失效，无需额外黑名单）
        StpUtil.logout();
        log.info("用户已退出登录: userId={}", userId);
    }

    // ==================== 强制下线（管理员触发 / 处罚联动 / 重放兜底复用） ====================

    /**
     * 强制下线指定用户：吊销其全部令牌族（多设备）与全部在线会话
     *
     * <p>【调用方】user 模块处罚/注销/角色变更经 Feign 调用、处罚 MQ 消费联动、
     * Refresh 重放检测兜底（复用本链路，见 refreshTsuccessen）。</p>
     *
     * @param userId 目标用户 ID
     */
    @Override
    public void forceLogout(Long userId) {
        log.info("强制下线用户（吊销全部令牌族与在线会话）: userId={}", userId);

        // 1. 全族吊销：Refresh 正反向 key + 设备记录 + 全部 Access 会话（内部尽力而为）
        revokeAllFamilies(userId);
        log.info("用户已强制下线: userId={}", userId);
    }

    // ==================== 刷新令牌 ====================

    /**
     * 刷新令牌（令牌轮换 + 三段判断防重放）
     *
     * <p>【三段判断铁律】① 先查墓碑（auth:refresh:used:{token}）：命中 = 已轮换票被重放
     * = 盗用信号 → 审计告警 + 吊销该用户全部令牌族（复用 forceLogout 链路）+ 拒绝；
     * ② 再查有效 key（auth:refresh:{token}）：未命中 → 拒绝；③ 均通过 → 原子认领轮换
     * （DEL 返回 true 的一端才允许签发，防并发双签发）→ 写墓碑 → 指纹比对 → 同族轮换签发。</p>
     *
     * <p>【时序铁律】墓碑先查后写：查询必须先于有效 key 删除执行，
     * 确保并发窗口内后到的重放票必命中墓碑。</p>
     *
     * @param dto 刷新请求（refreshToken）
     * @return 新令牌响应（同族轮换，familyId/deviceId 沿用）
     */
    @Override
    public TsuccessenVO refreshTsuccessen(RefreshTsuccessenDTO dto) {
        String refreshToken = dto.getRefreshToken();
        String redisKey = AuthCacheKey.REFRESH_TOKEN + refreshToken;
        log.info("刷新令牌请求");

        /* 业务逻辑步骤：
           1. 查墓碑：命中 = 已轮换票被重放 = 盗用 → 审计告警 + 吊销该用户全部族 + 拒绝
           2. 查有效 key：未命中 → 拒绝（TOKEN_INVALID）
           3. 原子认领：DEL 有效 key，仅删得成功的一端允许签发（防并发双签发）
           4. 写墓碑（TTL 与 Refresh 同周期，覆盖旧票剩余有效期）
           5. 设备指纹比对：不一致 = 盗用信号 → 吊销该族（限当前设备端）+ 拒绝
           6. 同族轮换：familyId/deviceId 沿用旧票，签发新双 Token */

        // 1. 第一段：查墓碑（value 为归属 userId）——先查后写防并发窗口
        String tombstoneUserId = redisUtil.get(AuthCacheKey.REFRESH_USED + refreshToken);
        if (tombstoneUserId != null) {
            // 已轮换票被重放 = 盗用：审计告警 + 吊销该用户全部 Access 会话与 Refresh 正反向 key
            log.error("【审计告警】Refresh Token 重放命中（疑似盗用，已吊销该用户全部会话）: "
                            + "userId={}, token={}", tombstoneUserId, maskToken(refreshToken));
            revokeAllFamilies(Long.parseLong(tombstoneUserId));
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        // 2. 第二段：查有效 key（JSON 载荷）
        String value = redisUtil.get(redisKey);
        if (value == null) {
            // 有效 key 与墓碑均未命中：彻底无效的票，仅拒绝
            log.warn("refreshToken 无效或已过期: token={}", maskToken(refreshToken));
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        RefreshTokenPayload payload = parsePayload(value);

        // 3. 原子认领轮换权：DEL 返回 true 才允许签发；并发窗口内另一端已删 → 直接拒绝（宁严勿松）
        Boolean claimed = redisUtil.getStringRedisTemplate().delete(redisKey);
        if (!Boolean.TRUE.equals(claimed)) {
            log.warn("refreshToken 并发轮换冲突，拒绝本次刷新: token={}", maskToken(refreshToken));
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        // 4. 写墓碑：TTL 与 Refresh 同周期，旧票此后再被使用必命中第一段
        redisUtil.set(AuthCacheKey.REFRESH_USED + refreshToken, String.valueOf(payload.getUserId()),
                authProperties.getRefreshTokenExpire(), TimeUnit.SECONDS);

        // 5. 设备指纹比对：不一致 = 盗用信号（token 被窃取后异环境离线独用）→ 仅吊销该族（限当前设备端）
        String currentFingerprint = DeviceFingerprintUtil.fingerprint(request);
        if (payload.getFingerprint() != null && !payload.getFingerprint().equals(currentFingerprint)) {
            log.error("【审计告警】Refresh 设备指纹不一致（疑似盗用，已吊销该令牌族）: "
                            + "userId={}, deviceId={}", payload.getUserId(), payload.getDeviceId());
            revokeFamily(payload.getUserId(), payload.getDeviceId());
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        // 6. 第三段：同族轮换签发（familyId 沿用旧族；deviceId 沿用旧票绑定设备，保证族内设备连续）
        return buildTsuccessenResponse(payload.getUserId(), null, false, null, null, null,
                payload.getFamilyId(), payload.getDeviceId());
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
     * <p>【令牌族语义】登录场景（familyId 为 null）新生成族 ID——每设备独立族；
     * 刷新轮换场景沿用旧族 ID 与旧票绑定设备，保证族内链路连续。
     * Refresh 正向 key 存 JSON 载荷（userId/familyId/deviceId/fingerprint），
     * 反向 key 为 userId + deviceId 维度（多设备独立族，登出/吊销按族定位）。</p>
     *
     * <p>注入用户信息至 session</p>
     *
     * @param userId      用户 ID
     * @param username    用户名
     * @param newUser     是否新注册用户
     * @param uid         对外UID（注册/登录场景回传；刷新令牌场景传 null）
     * @param roles       角色列表（刷新场景传 null，Session 已存在无需重写）
     * @param permissions 权限列表（刷新场景传 null，Session 已存在无需重写）
     * @param familyId    令牌族 ID（登录场景传 null = 新族；轮换场景传旧族 ID）
     * @param deviceId    指定设备 ID（登录场景传 null = 请求头/服务端生成；轮换场景传旧票绑定设备）
     * @return 令牌响应 VO
     */
    private TsuccessenVO buildTsuccessenResponse(Long userId, String username, boolean newUser, String uid,
                                                  List<String> roles, List<String> permissions,
                                                  String familyId, String deviceId) {
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

        // 3.1 令牌族 ID：登录场景新生成（每设备独立族）；轮换场景沿用旧族（重放/封禁按族吊销半径可控）
        if (familyId == null || familyId.isBlank()) {
            familyId = UUID.randomUUID().toString().replace("-", "");
        }

        // 3.2 注册/更新当前设备会话（deviceId 优先沿用调用方指定值保证族内设备连续；
        //     登录场景由 registerDevice 内部按请求头/服务端生成）
        deviceId = deviceSessionService.registerDevice(userId, accessToken, deviceId);

        // 3.3 提取设备指纹随 Refresh 存储（刷新时比对，堵 token 被窃后异环境离线独用空窗）
        String fingerprint = DeviceFingerprintUtil.fingerprint(request);

        // 4. refreshToken 正向 key 存 Redis（TTL 可配置，默认 7 天），value 为 JSON 载荷
        long ttl = authProperties.getRefreshTokenExpire();
        RefreshTokenPayload payload = new RefreshTokenPayload()
                .setUserId(userId)
                .setFamilyId(familyId)
                .setDeviceId(deviceId)
                .setFingerprint(fingerprint);
        redisUtil.set(AuthCacheKey.REFRESH_TOKEN + refreshToken, toJson(payload), ttl, TimeUnit.SECONDS);

        // 5. 反向 key（userId + deviceId 维度）：登出/按族吊销时由设备定位 Refresh Token
        redisUtil.set(userRefreshKey(userId, deviceId), refreshToken, ttl, TimeUnit.SECONDS);

        // 6. 获取 accessToken 剩余有效期（秒）
        long expiresIn = StpUtil.getTokenTimeout();

        log.debug("双 Token 签发成功: userId={}, familyId={}, deviceId={}, expiresIn={}s",
                userId, familyId, deviceId, expiresIn);

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
     * 吊销单个令牌族（单设备端）：Refresh 正反向 key + 设备记录 + 该设备 Access 会话
     *
     * <p>【失败语义】各步骤尽力而为：Refresh key 已丢失/设备记录已过期均静默跳过，
     * tokenValue 注销异常仅告警，不影响调用方主流程（吊销方向宁严勿松，多删无害）。</p>
     *
     * @param userId   归属用户 ID
     * @param deviceId 设备 ID（族内维度）
     */
    private void revokeFamily(Long userId, String deviceId) {
        // 1. 读取该设备族 Refresh Token：写墓碑 + 删正反向 key（吊销动作覆盖 Access 会话、Refresh 正反向 key 与墓碑记录）
        String refreshToken = redisUtil.get(userRefreshKey(userId, deviceId));
        if (refreshToken != null) {
            // 墓碑：吊销后的旧票再被重放必命中刷新第一段（盗用检测闭环，TTL 与 Refresh 同周期）
            redisUtil.set(AuthCacheKey.REFRESH_USED + refreshToken, String.valueOf(userId),
                    authProperties.getRefreshTokenExpire(), TimeUnit.SECONDS);
            redisUtil.delete(AuthCacheKey.REFRESH_TOKEN + refreshToken);
            redisUtil.delete(userRefreshKey(userId, deviceId));
        }

        // 2. 清理设备 Hash 与用户设备索引（尽力而为，脏数据容忍）
        Map<String, Object> deviceMap = redisUtil.hGetAll(AuthCacheKey.DEVICE_SESSION + deviceId);
        if (!deviceMap.isEmpty()) {
            redisUtil.delete(AuthCacheKey.DEVICE_SESSION + deviceId);
            redisUtil.sRemove(AuthCacheKey.USER_DEVICES + userId, deviceId);
        }

        // 3. 注销该设备 Access 会话（尽力而为：已过期或已注销不影响吊销结论）
        String tokenValue = deviceMap.isEmpty()
                ? null : (String) deviceMap.get(AuthCacheKey.DeviceHashField.TOKEN_VALUE);
        if (tokenValue != null) {
            try {
                StpUtil.logoutByTokenValue(tokenValue);
            } catch (Exception e) {
                log.warn("吊销令牌族时注销 Access 会话异常（可能已过期）: userId={}, deviceId={}, error={}",
                        userId, deviceId, e.getMessage());
            }
        }
        log.info("令牌族吊销完成: userId={}, deviceId={}", userId, deviceId);
    }

    /**
     * 吊销该用户全部令牌族（多设备）：逐设备清理 Refresh 正反向 key + 全部设备记录与 Access 会话
     *
     * <p>【失败语义】逐设备尽力而为，单设备异常不阻断其余设备清理；
     * 收尾统一走 kickAllDevices（内部容错），整链路幂等可重复执行。</p>
     *
     * @param userId 目标用户 ID
     */
    private void revokeAllFamilies(Long userId) {
        // 1. 读取全部在线设备 ID（设备索引为空的脏场景由 kickAllDevices 兜底注销全部 Sa-Token 会话）
        Set<String> deviceIds = redisUtil.sMembers(AuthCacheKey.USER_DEVICES + userId);

        // 2. 逐设备吊销 Refresh 正反向 key + 写墓碑（吊销动作同时覆盖 Access 会话、Refresh 两条 key 与墓碑记录）
        for (String deviceId : deviceIds) {
            String refreshToken = redisUtil.get(userRefreshKey(userId, deviceId));
            if (refreshToken != null) {
                // 墓碑：吊销后的旧票再被重放必命中刷新第一段（盗用检测闭环）
                redisUtil.set(AuthCacheKey.REFRESH_USED + refreshToken, String.valueOf(userId),
                        authProperties.getRefreshTokenExpire(), TimeUnit.SECONDS);
                redisUtil.delete(AuthCacheKey.REFRESH_TOKEN + refreshToken);
                redisUtil.delete(userRefreshKey(userId, deviceId));
            }
        }

        // 3. 设备 Hash / 设备索引 / 全部 Access 会话（kickAllDevices 内部已容错）
        deviceSessionService.kickAllDevices(userId);
        log.info("全族吊销完成: userId={}, 设备数={}", userId, deviceIds.size());
    }

    /**
     * 解析 Refresh 正向 key 的 JSON 载荷
     *
     * <p>【兼容语义】历史版本 value 为纯 userId 字符串：按旧格式降级仅回填 userId，
     * familyId/deviceId/fingerprint 留空（轮换时补齐新字段）。</p>
     *
     * @param value Redis 中存储的载荷字符串
     * @return 载荷对象
     * @throws BizException 载荷格式非法时抛 TOKEN_INVALID
     */
    private RefreshTokenPayload parsePayload(String value) {
        // 1. 历史格式（纯 userId）降级：升级窗口期存量令牌保持可用
        if (!value.startsWith("{")) {
            return new RefreshTokenPayload().setUserId(Long.parseLong(value));
        }

        // 2. JSON 格式解析：失败视为令牌非法（宁严勿松，拒绝刷新）
        try {
            return objectMapper.readValue(value, RefreshTokenPayload.class);
        } catch (JsonProcessingException e) {
            log.warn("Refresh 载荷解析失败，按无效令牌处理: {}", e.getMessage());
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
    }

    /**
     * 反向 key 拼接（userId + deviceId 维度，多设备独立族）
     *
     * @param userId   用户 ID
     * @param deviceId 设备 ID
     * @return auth:refresh:user:{userId}:{deviceId}
     */
    private String userRefreshKey(Long userId, String deviceId) {
        return AuthCacheKey.USER_REFRESH + userId + ":" + deviceId;
    }

    /**
     * Refresh 载荷序列化
     *
     * @param payload 载荷对象
     * @return JSON 字符串
     */
    private String toJson(RefreshTokenPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 序列化失败无法签发有效 Refresh：直接终止（故障显性化，不允许降级为残缺载荷）
            throw new IllegalStateException("Refresh 载荷序列化失败", e);
        }
    }

    /**
     * Refresh Token 日志脱敏（只留前 8 位 + 后 4 位）
     *
     * @param token 令牌原文，可能为 null
     * @return 脱敏后的令牌串
     */
    private String maskToken(String token) {
        if (token == null || token.length() <= 12) {
            return "***";
        }
        return token.substring(0, 8) + "..." + token.substring(token.length() - 4);
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
