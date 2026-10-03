package com.litisle.litislefile.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.commonredis.util.RedisUtil;
import com.litisle.commonsecurity.utils.SaRoleHelper;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.dto.file.FileContentDTO;
import com.litisle.litisleapi.event.moderation.ImageSubmitEvent;
import com.litisle.litislefile.client.SeaweedFsClient;
import com.litisle.litislefile.config.FileProperties;
import com.litisle.litislefile.domain.entity.FileMeta;
import com.litisle.litislefile.domain.dto.FileDownloadResource;
import com.litisle.litislefile.domain.dto.FileQuotaUsage;
import com.litisle.litislefile.domain.entity.FileRef;
import com.litisle.litislefile.domain.vo.FileMetaVO;
import com.litisle.litislefile.domain.vo.FileUploadVO;
import com.litisle.litislefile.domain.vo.PresignVO;
import com.litisle.litislefile.domain.vo.ThumbnailVO;
import com.litisle.litislefile.enums.AccessLevelEnum;
import com.litisle.litislefile.enums.SafeFlagEnum;
import com.litisle.litislefile.enums.StorageBizTypeEnum;
import com.litisle.litislefile.enums.UploadStatusEnum;
import com.litisle.litislefile.mapper.FileMetaMapper;
import com.litisle.litislefile.mapper.FileRefMapper;
import com.litisle.litislefile.service.FileService;
import com.litisle.litislefile.thumbnail.ThumbnailGenerator;
import com.litisle.litislefile.util.FileMagicValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import com.litisle.commonmq.send.MqSendUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 核心实现编排（依赖 SeaweedFsClient / FileMetaMapper / FileProperties / Redisson）。
 *
 * 【上传（含秒传）】最复杂链路（判重键与索引决策见 docs/seaweedfs笔记.md §3.4.5 eg 块）。
 * 1. 校验：size ≤ maxImageSize(10MB)、MIME 白名单（按 bizType 区分：
 *    AVATAR/COVER/ARTICLE_IMG → image/jpeg|png|webp|gif；LICENSE → 放开授权文件类型如 pdf，
 *    避免 LICENSE 非 PDF 被误拦）+ 文件头魔数校验（防伪造 Content-Type 绕过白名单）
 * 2. 流式算 SHA256（DigestInputStream，勿全量读内存）+ 记录 size（联合判重键的一部分）
 * 3. 无锁快查判重：WHERE sha256=? AND size=? AND biz_type=? AND del_flag=0
 *    （PRIVATE/LICENSE 追加 AND owner_id=? —— 按 owner 隔离去重，防跨用户秒传探测泄露隐私；
 *     PUBLIC 全局去重）走 idx_dedup(sha256, size, biz_type, del_flag)
 *    命中 → 直接返回已有 fileId + deduplicated=true（秒传，不落 SeaweedFS）
 *    ⚠️ 命中记录可能是 status=0 在途记录（S3 尚未传完，短暂 404）：已知取舍，
 *    put 秒级完成 + 调用方重试自愈；面试/评审口径见笔记 §3.4.6
 * 4. 未命中 → Redisson 分布式锁 biz:file:sha:{hash}，leaseTime=5s 自动释放（临界区仅查库+写库，5s 足够）
 *    拿锁后双重检查（防等锁期间其他线程已写入）
 * 5. 锁内临界区（只包「查 DB + 写 DB」，不包 IO）：
 *    生成 fileId = UUID（无连字符，32 位十六进制），path = {yyyy/MM/dd}/{fileId}.{ext}
 *    插 file_meta：status=0 占位（上传中）+ ownerId=X-User-Id
 *    access 由 bizType 决定：AVATAR/COVER/ARTICLE_IMG → PUBLIC；LICENSE → PRIVATE（服务层强制覆盖，default 0 仅兜底）
 *    → 释放锁
 * 6. 锁外：SeaweedFsClient.put(bucket, path, bytes, mime)（幂等，同 key 覆盖无副作用，不占锁）
 *    bucket = collections 映射（avatar/cover/article/license）
 * 7. put 成功 → 解析图片宽高（ImageIO.read → width/height，用于前端布局预留）→ update status=1（上传完成）
 *    put 失败 → 保留 status=0，由对账任务清理
 * 8. 违规审核异步送审（Review-Plan §4.2.2 ①②）：sha256 先查 REJECT 拒传黑名单
 *    （命中拒传 90009，不落库不进 MQ）→ 再查审核结果缓存（命中 PASS/REJECT 终局结论直接复用，
 *    跳过 MQ）→ 未命中落 safe_flag=2 占位（default-deny），status=1 后发 image.submit MQ；
 *    审核结果经 moderation.result 事件回写（REJECT→quarantine 物理隔离）
 * 9. 返回 fileId + deduplicated=false
 *
 * 【一致性保障】先锁内占位写库（status=0）再锁外传文件：
 *    若 put 失败，DB 记录可由对账任务接手；若反过来「先 put 后写库」，put 成功但写库失败
 *    会产生无补偿入口的孤儿对象——这是顺序设计的核心原因
 *
 * 【URL 获取】统一入口 getUrl(fileId)：查 file_meta → access 分流
 *    PUBLIC→publicUrlBase + bucket + path 拼直链（以命中/已有记录的 bucket/path 为准；
 *    判重已限定 biz_type，命中记录 bucket 必等于本次映射，无跨桶坏链风险）
 *    PRIVATE→presign + owner/REVIEWER/ADMIN 权限校验 + bizType 二次校验（纵深防御）
 *    【访问闸口（已接入）】getUrl/batchGetUrls/download/presign 返回前校验 safe_flag：
 *    =0 拒绝并提示违规；PUBLIC 高曝光面仅 =1 放行、2/3 返回占位图（曝光面分层，§4.4.5）
 * 【流式代理下载（方案A，非默认）】download(fileId, operatorId, rangeHeader)：
 *    默认下载仍走直连范式（getUrl/presign 的 URL 即凭证）；本链路仅用于“必须经服务端中转”场景
 *    （鉴权收紧/防直链/水印），服务端手动透传 Range：无 Range→200 全量；合法→206；非法→416。
 *    totalSize 取 file_meta.size（上传时已记录，无需 HEAD 往返）；ETag 取 sha256（内容不变，续传一致）
 * 【删除（方案C：解绑语义）】校验 owner/ADMIN →
 *    Step1 删 file_ref 当前调用方引用行（file_id + owner_id）
 *    Step2 file_ref 仍有其他活跃引用 → 结束（文件不动，他人引用不受影响）
 *    Step3 无活跃引用 → del_flag=1 + delete_time=now()（置 1 后退出秒传判重池）
 *    物理清理交对账任务（7 天缓冲，防误删不可恢复；方案C 设计见 seaweedfs笔记.md §3.4）
 * 【对账任务】@Scheduled 每日 02:00（多实例需 Redisson 分布式锁保证单实例执行）：
 *    Step1 补偿上传：扫 status=0 且超 30min → S3 head 有对象则置 status=1，无则 del_flag=1 废弃
 *    Step2 物理清理：扫 del_flag=1 且 delete_time 超 7 天 → 先校验 file_ref 无活跃引用
 *    （仍有引用 → 跳过并告警，引用方漏登记兜底不误删）→ 删 SeaweedFS 本体（含缩略图派生 key）
 *    → 物理删元数据 + 防御性同删该 file_id 的残留 file_ref 行
 *    （正常流程下此时应已无行——前置校验已过；此步仅防"解绑删行失败但 del_flag 已置位"的极端残留）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    /** 批量上传上限（正文多图） */
    private static final int MAX_BATCH_SIZE = 9;

    /** 缩略图宽/高上限（防恶意大尺寸参数撑爆内存/存储） */
    private static final int MAX_THUMBNAIL_DIMENSION = 2000;

    /** 秒传判重分布式锁 Key 前缀（RedissonLock 会追加 "lock:" 全局前缀，实际锁 key 为 lock:file:sha:{hash}） */
    private static final String DEDUP_LOCK_KEY_PREFIX = "file:sha:";

    /** path 日期段格式：{yyyy/MM/dd}/{fileId}.{ext} */
    private static final DateTimeFormatter PATH_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 图片 MIME 白名单（AVATAR/COVER/ARTICLE_IMG） */
    private static final List<String> IMAGE_MIME_WHITELIST =
            List.of("image/jpeg", "image/png", "image/webp", "image/gif");

    /** 授权文件额外放开的 MIME（LICENSE） */
    private static final List<String> LICENSE_EXTRA_MIME_WHITELIST =
            List.of("application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/zip");

    private final FileMetaMapper fileMetaMapper;
    private final FileRefMapper fileRefMapper;
    private final SeaweedFsClient seaweedFsClient;
    private final FileProperties fileProperties;
    private final DistributedLock distributedLock;
    private final ThumbnailGenerator thumbnailGenerator;
    private final RedisUtil redisUtil;
    private final MqSendUtil mqSendUtil;

    /**
     * 单文件上传（含秒传）：校验 → 流式算 SHA256 → 无锁快查判重 → 锁内双检+占位写库（status=0）
     * → 锁外 put（幂等）→ 解析宽高 → 置 status=1；秒传命中直接复用 fileId 不落存储
     * leServiceImpl#upload 的流程是：整份文件一次读进内存 → 直接`put` 到 SeaweedFS， 没有任何"分片"环节 。
     *
     * <p>【瓶颈说明·为何上传保持同步】本方法为 multipart + 事务语义的同步链路（校验/算哈希/写库/put 需同事务编排），
     * 且单文件 ≤10MB、整份读内存后一次 put，耗时可控；异步化会破坏事务边界与异常回传，风险高于收益，故不隔离。
     * 下载侧因面向「大文件 + 慢客户端」阻塞时间长，已改为 StreamingResponseBody 交由 fileIoExecutor 执行
     * （见 FileAsyncConfig），避免占用 Tomcat 工作线程。</p>
     */
    @Override
    public FileUploadVO upload(MultipartFile file, StorageBizTypeEnum bizType, Long ownerId) {
        // 步骤1：校验 size ≤ maxImageSize + MIME 白名单（按 bizType 区分，LICENSE 放开授权文件类型）
        validateUpload(file, bizType);

        // 步骤2：流式算 SHA256（DigestInputStream，勿全量读内存）+ 记录 size（联合判重键）
        String sha256 = sha256Hex(file);
        long size = file.getSize();
        String mime = file.getContentType();

        // 步骤2.5：审核前置检查（仅公开面图片：AVATAR/COVER/ARTICLE_IMG 上传即预审；LICENSE 私有面豁免机审落 1）
        // ① REJECT 拒传黑名单：命中直接拒传，不落库不进 MQ（同图重传不再重烧审核费）
        // ② 审核结果缓存：命中终局结论直接复用（PASS→1 免审直落 / REJECT→拒传），跳过 MQ
        boolean publicFace = resolveAccess(bizType) == AccessLevelEnum.PUBLIC;
        SafeFlagEnum initialSafeFlag = SafeFlagEnum.NORMAL;
        if (publicFace) {
            checkRejectBlacklist(sha256);
            initialSafeFlag = checkAuditCache(sha256);
        }

        // 步骤3：无锁快查判重（走 idx_dedup）；PRIVATE/LICENSE 追加 owner_id 隔离，防跨用户秒传探测泄露隐私
        FileMeta exist = selectByDedupKey(sha256, size, bizType, ownerId);
        if (exist != null) {
            log.info("秒传命中，fileId={}，bizType={}，size={}", exist.getFileId(), bizType, size);
            return buildUploadVO(exist, true);
        }

        // 步骤4：Redisson 分布式锁（leaseTime=5s 自动释放，无看门狗续期；临界区仅查库+写库足够）
        boolean locked = distributedLock.tryLock(DEDUP_LOCK_KEY_PREFIX + sha256, 5, TimeUnit.SECONDS);
        if (!locked) {
            log.warn("秒传判重锁竞争失败，sha256={}，bizType={}", sha256, bizType);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后重试");
        }
        FileMeta meta;
        try {
            // 拿锁后双重检查（防等锁期间其他线程已写入同一摘要文件）
            exist = selectByDedupKey(sha256, size, bizType, ownerId);
            if (exist != null) {
                log.info("锁内双检秒传命中，fileId={}，bizType={}，size={}", exist.getFileId(), bizType, size);
                return buildUploadVO(exist, true);
            }

            // 步骤4.5：per-user 存储配额校验（软限；秒传已在上方 return，不占新增配额）
            checkQuota(ownerId, size);

            // 步骤5：锁内临界区（只包「查 DB + 写 DB」，不包 IO）
            // fileId=UUID 无连字符，path={yyyy/MM/dd}/{fileId}.{ext}，status=0 占位
            String fileId = UUID.randomUUID().toString().replace("-", "");
            String bucket = resolveBucket(bizType);
            String path = LocalDate.now().format(PATH_DATE_FORMAT)
                    + "/" + fileId + "." + resolveExt(file.getOriginalFilename());

            //插入数据库
            meta = new FileMeta()
                    .setFileId(fileId)
                    .setOwnerId(ownerId)
                    .setBizType(bizType)
                    .setBucket(bucket)
                    .setPath(path)
                    .setMime(mime)
                    .setSize(size)
                    .setSha256(sha256)
                    // access 由 bizType 决定：LICENSE 强制 PRIVATE（服务层覆盖，default 仅兑底）
                    .setAccess(resolveAccess(bizType))
                    .setStatus(UploadStatusEnum.UPLOADING)
                    // safe_flag：default-deny 占位 2（缓存 PASS 命中直落 1；LICENSE 豁免机审落 1）
                    .setSafeFlag(initialSafeFlag)
                    .setDelFlag(DelFlag.NOT_DELETED)
                    .setCreateTime(LocalDateTime.now())
                    .setUpdateTime(LocalDateTime.now());
            fileMetaMapper.insert(meta);

        } finally {
            // 步骤6：释放锁
            distributedLock.unlock(DEDUP_LOCK_KEY_PREFIX + sha256);
        }
//        步骤1  校验
//        步骤2  流式算 SHA256 + size
//        步骤3  无锁快查判重 → 命中直接返回（秒传）
//        步骤4  拿分布式锁
//        步骤5  ── 锁内 ── 双检 + 写 DB（status=0 占位）
//        步骤6  释放锁
//        步骤7  ── 锁外 ── put 到 SeaweedFS（真正写存储）
//        步骤8  解析图片宽高
//        步骤9  ── 锁外 ── update DB（status=1 完成）
//        步骤10 返回 VO
        //为什么不在步骤5锁内第一次 insert 的时候就把宽高一起解析了，而是放到锁外单独 update 一次？
        //因为解析宽高需要拿到文件的完整字节（bytes），而 bytes 是在锁外才通过 file.getBytes() 拿到的。
        //为什么不把解析宽高放在锁内？
        //getBytes() 是纯内存 IO + 大对象分配，跟"防并发写 DB"无关，放进锁内只会拉长临界区、增加锁超时风险，而且你省下来的那一次 update 根本不值这个代价。

        // 步骤7：锁外 put（幂等，同 key 覆盖无副作用）
        // put 失败抛 BizException(FILE_STORAGE_UNAVAILABLE)，保留 status=0 由对账任务清理
        
            // getBytes() 慢，但它不在锁内，所以不阻塞别人。并发下的真正风险是内存，不是速度。解决方案是：流式 put 到 SeaweedFS，彻底干掉 getBytes()。
            // 但是现在 ：保持`getBytes()` 即可，10MB 无风险；但 建议在注释里明确“该写法依赖 10MB 上限，放宽上限前必须先改流式/分片” ，避免以后有人调大配置踩雷。

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.FILE_STORAGE_UNAVAILABLE, e);
        }
        seaweedFsClient.put(meta.getBucket(), meta.getPath(), bytes, mime);
        log.info("文件上传成功（锁外 put 完成），fileId={}，bucket={}，path={}，size={}",
                meta.getFileId(), meta.getBucket(), meta.getPath(), size);

        // 解析图片宽高（非图片/解析失败为 null，用于前端布局预留）→ update status=1
        FileMeta update = new FileMeta()
                .setId(meta.getId())
                .setStatus(UploadStatusEnum.COMPLETED)
                .setUpdateTime(LocalDateTime.now());
        Integer[] wh = resolveImageSize(bytes, mime);
        if (wh != null) {
            update.setWidth(wh[0]).setHeight(wh[1]);
        }
        fileMetaMapper.updateById(update);

        // 步骤8：审核埋点（image.submit 异步送审）：仅缓存未命中（safe_flag=2 占位）时发布；
        // 发布失败仅记 error 不回滚上传——safe_flag 停留 2（default-deny 闸口不放行，宁严勿松）
        if (initialSafeFlag == SafeFlagEnum.PENDING) {
            publishImageSubmitEvent(meta, sha256);
        }

        // 步骤9：返回 fileId + deduplicated=false（回填刚解析的宽高）
        FileUploadVO vo = buildUploadVO(meta, false);
        if (wh != null) {
            vo.setWidth(wh[0]);
            vo.setHeight(wh[1]);
        }
        return vo;
    }

    /**
     * 批量上传：校验非空 + 上限 9 张后，逐个复用单文件链路（含秒传）；中途失败已成功的不回滚
     */
    @Override
    public List<FileUploadVO> uploadBatch(List<MultipartFile> files, StorageBizTypeEnum bizType, Long ownerId) {
        // 入参校验：空列表直接拒绝
        if (files == null || files.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "批量上传文件列表不能为空");
        }
        // 数量上限校验（正文多图场景，防刷存储）
        if (files.size() > MAX_BATCH_SIZE) {
            throw new BizException(ErrorCode.PARAM_ERROR, "批量上传上限 " + MAX_BATCH_SIZE + " 张");
        }
        // 逐个复用单文件链路（含秒传），中途失败已成功的不回滚
        return files.stream().map(f -> upload(f, bizType, ownerId)).toList();
    }

    /**
     * 获取访问 URL 统一入口：查元数据后按 access 分流——PUBLIC 拼直链；PRIVATE 先 owner/ADMIN 校验再预签名
     * （调用方无需感知文件访问级别；审核 safe_flag 闸口预留在此单点收口）
     * <p>【大文件下载】返回的 URL 即下载凭证，客户端直连对象存储拉取；断点续传由对象存储原生
     * Range(206) 支持，本模块不代理文件流（详见 SeaweedFsClient#presign）</p>
     */
    @Override
    public String getUrl(String fileId, Long operatorId) {
        // 查活跃元数据（del_flag=0，不存在抛 FILE_NOT_FOUND）
        FileMeta meta = getMetaOrThrow(fileId);
        // 访问闸口（曝光面分层，§4.4.5）：safe_flag=0 违规拒绝；PUBLIC 高曝光面仅 =1 放行，
        // 2/3 待审/存疑返回占位图 URL（不泄露真实直链，前端渲染占位）；PRIVATE 低曝光面
        // 不占位（owner/REVIEWER/ADMIN 按角色放行，审核员看证据图不被占位挡住）
        if (meta.getAccess() == AccessLevelEnum.PUBLIC) {
            if (meta.getSafeFlag() == SafeFlagEnum.VIOLATIVE) {
                throw new BizException(ErrorCode.FILE_VIOLATIVE);
            }
            if (meta.getSafeFlag() != SafeFlagEnum.NORMAL) {
                return fileProperties.getModeration().getPendingPlaceholderUrl();
            }
            return buildPublicUrl(meta.getBucket(), meta.getPath());
        }
        // PRIVATE：owner/REVIEWER/ADMIN 校验 + presign（默认 30min，口径与强制签发一致）
        checkPrivateReadAccess(meta, operatorId);
        return seaweedFsClient.presign(meta.getBucket(), meta.getPath(),
                Duration.ofMinutes(fileProperties.getSeaweedfs().getPresignExpireMinutes()));
    }

    /**
     * 批量获取 fileId→URL 映射（Feign 内部调用，防列表页 N+1）：一次 in 查询后按 access 分流生成；
     * ⚠️ PRIVATE 直接预签不做权限校验——仅限服务间调用，若暴露 REST 必须补鉴权
     */
    @Override
    public Map<String, String> batchGetUrls(List<String> fileIds) {
        // 空列表直接返回空 Map（防 Feign 调用方传空）
        if (fileIds == null || fileIds.isEmpty()) {
            return Map.of();
        }
        // 一次 in 查询避免 N+1（Feign 列表页映射用）
        // ⚠️ PRIVATE 直接预签：本方法仅限内部 Feign 调用（无 REST 入口），
        // 若未来暴露 REST 必须补 operatorId 做 owner/ADMIN 校验
        List<FileMeta> metas = fileMetaMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .in(FileMeta::getFileId, fileIds) //一次查回所有需要的文件元数据
                .eq(FileMeta::getDelFlag, DelFlag.NOT_DELETED));//只查未删除文件
        return metas.stream().collect(Collectors.toMap(FileMeta::getFileId, meta -> {//流式转换为 Map<fileId, url>
            // 访问闸口（曝光面分层，与 getUrl 同口径）：safe_flag=0 违规拒绝；
            // PUBLIC 高曝光面仅 =1 放行，2/3 返回占位图 URL（列表页渲染占位不泄露真实直链）
            if (meta.getAccess() == AccessLevelEnum.PUBLIC) {
                if (meta.getSafeFlag() == SafeFlagEnum.VIOLATIVE) {
                    throw new BizException(ErrorCode.FILE_VIOLATIVE);
                }
                if (meta.getSafeFlag() != SafeFlagEnum.NORMAL) {
                    return fileProperties.getModeration().getPendingPlaceholderUrl();
                }
                return buildPublicUrl(meta.getBucket(), meta.getPath());
            }
            return seaweedFsClient.presign(meta.getBucket(), meta.getPath(),
                    Duration.ofMinutes(fileProperties.getSeaweedfs().getPresignExpireMinutes()));
        }));
    }

    /**
     * 服务端流式代理下载（HTTP Range 断点续传）：查元数据 → PRIVATE 校验 owner/ADMIN → 解析 Range →
     * 无 Range 200 全量 / 合法 Range 206 部分 / 非法 Range 416
     * <p>默认下载走直连范式（getUrl/presign 的 URL 即凭证，客户端直连对象存储）；本链路仅用于
     * “必须经服务端中转”场景（鉴权收紧/防直链/水印），服务端手动透传 Range。
     * totalSize 取 file_meta.size（上传时已记录，无需 HEAD 往返）；ETag 取 sha256。</p>
     *
     * 本方法把「Range 解析结果」翻译成 FileDownloadResource 的三种形态，上层 Controller 只做协议分发：
     *
     * 返回形态              含义                          Controller 怎么用
     * range == null         无 Range/语法非法/多段 → 全量    partial=false → 200 + 整份流
     * range[0] > range[1]   区间不可满足 → 416              rangeInvalid=true → 416（stream=null）
     * 正常区间 {s,e}        合法区间 → 206 部分内容          partial=true → 206 + Content-Range
     *
     * 它把「查元数据、权限校验、区间判定、S3 取流」串成一条直线，让 Controller 无需感知任何业务规则。
     * 确认"能不能下" → 确认"下哪一段" → 按三种情况之一构造返回结果
     *          * 3种不同情况：stream — 最核心的区别
     *          * 200 → 有流，全量
     *          * 206 → 有流，只含请求的那段字节
     *          * 416 → null，没有流
     *          * stream?          start/end        状态码    body?
     *          * 200 全量   →       ✅ 全量     {0, size-1}      200      ✅ 全写
     *          * 206 部分   →       ✅ 区间     {range[0], range[1]}  206  ✅ 写那段
     *          * 416 拒绝   →       ❌ null     {0, 0} 占位      416      ❌ 不写
     */
    @Override
    public FileDownloadResource download(String fileId, Long operatorId, String rangeHeader) {
        //1：查活跃元数据（del_flag=0，不存在抛 FILE_NOT_FOUND）
        FileMeta meta = getMetaOrThrow(fileId);

        //2：访问闸口（曝光面分层，§4.4.5）：safe_flag=0 违规拒绝发放下载流；
        //    PUBLIC 高曝光面 2/3 待审/存疑态无占位流语义，抛 FILE_NOT_READY（占位图仅 getUrl/batchGetUrls 有）
        checkNotViolative(meta);
        if (meta.getAccess() == AccessLevelEnum.PUBLIC && meta.getSafeFlag() != SafeFlagEnum.NORMAL) {
            throw new BizException(ErrorCode.FILE_NOT_READY);
        }
        //    PRIVATE（LICENSE）：owner/REVIEWER/ADMIN 校验（读类口径与 getUrl/presign 统一）
        if (meta.getAccess() == AccessLevelEnum.PRIVATE) {
            checkPrivateReadAccess(meta, operatorId);
        }

        /**
         * 举例：file_meta.size = 5000（对象共 5000 字节），请求头 Range = "bytes=1000-1999"
         *
         * // 第1步：取总大小（上传时已落库，无需 HEAD 往返）
         * totalSize = 5000
         *
         * // 第2步：由 path 尾段推导下载文件名（file_meta 未存原始名）
         * fileName = "abc123.jpg"
         *
         * // 第3步：解析 Range → 闭区间
         * parseSingleRange("bytes=1000-1999", 5000)  →  {1000, 1999}
         *
         * // 第4步：区间合法（start ≤ end）→ 交给 S3 定位并流式取回
         * getStream(bucket, path, 1000, 1999)  →  206 部分内容
         */

        //3：totalSize 以上传时记录的 size 为准；fileName 由 path 尾段推导
        long totalSize = meta.getSize() == null ? 0L : meta.getSize();
        String fileName = resolveFileName(meta);//根据文件路径推导文件名

        //4：解析单段 Range；null 表示无 Range/语法非法/多段 → 回退全量 200
        long[] range = parseSingleRange(rangeHeader, totalSize);

        //5：形态一：无有效 Range → 全量 200（end 用 Math.max(totalSize-1,0) 兜底空文件的 -1）
        if (range == null) {
            // 全量：不传 S3 Range 头，S3 返回整个对象
            return new FileDownloadResource(
                    seaweedFsClient.getStream(meta.getBucket(), meta.getPath(), null, null),
                    meta.getMime(), fileName, meta.getSha256(), totalSize,
                    0, Math.max(totalSize - 1, 0), false, false);
        }

        //6：形态二：start > end（含 start ≥ totalSize / 空文件）→ 区间不可满足 → 416
        //    stream=null：已由 DB 的 size 判定完毕，不再触达 S3；仅回 totalSize 供客户端收敛
        if (range[0] > range[1]) {
            return new FileDownloadResource(
                    null, meta.getMime(), fileName, meta.getSha256(), totalSize, 0, 0, false, true);
        }

        //7：形态三：206 部分内容：S3 侧按 Range 定位，服务端只做流式透传（不读取前置字节）
        return new FileDownloadResource(
                seaweedFsClient.getStream(meta.getBucket(), meta.getPath(), range[0], range[1]),
                meta.getMime(), fileName, meta.getSha256(), totalSize,
                range[0], range[1], true, false);
    }

    /**
     * 解析单段 Range（RFC 7233）：支持 bytes=start-end / bytes=start- / bytes=-suffix
     * <p>返回 {start,end} 闭区间；null = 无 Range/语法非法/多段（调用方回退全量 200）；
     * start>end = 区间不可满足（调用方回 416）。上界超出 totalSize 收敛到 totalSize-1。</p>
     *
     * 返回 long[]，但不是随便一个数组，而是严格编码了三种状态：
     * 返回值	含义	download() 里怎么用
     * null	无 Range / 语法非法 / 多段 → 忽略，回退全量	if (range == null) → 走全量 200 分支
     * {start, end} 且 start ≤ end	合法区间 → 206 部分内容	传给 getStream(start, end) 拿流
     * {start, end} 且 start > end	区间不可满足 → 416 拒绝	if (range[0] > range[1]) → 构造 stream=null 的 resource
     *
     * parseSingleRange 的返回值是"Range
     * 头的翻译结果"——null 告诉调用方"别管 Range 了直接全量返回"，
     * {start, end} 告诉调用方"去取这段字节"，
     * start > end 告诉调用方"拒绝这个请求"。
     * 它把字符串解析、边界校验、上界收敛、三态编码全部封装在一个方法里，让上层只做分发。
     */
    private long[] parseSingleRange(String header, long totalSize) {
        //header：HTTP 请求头里的 Range 值，比如 "bytes=1000-1999"
        //totalSize：文件大小，用于收敛区间
        //返回 {start,end} 闭区间；null = 无 Range/语法非法/多段（调用方回退全量 200）；
        // start>end = 区间不可满足（调用方回 416）。上界超出 totalSize 收敛到 totalSize-1。


        /**
         * 原始 header = "bytes=1000-1999"
         * // 第一步：检查前缀
         * header.startsWith("bytes=")  →  ✅ 通过
         *
         * // 第二步：去掉 "bytes=" 前缀
         * spec = header.substring("bytes=".length()).trim()
         *      = "1000-1999"
         *
         * // 第三步：检查多段
         * spec.contains(",")  →  false（没逗号，不是多段）
         *
         * // 第四步：找横杠位置
         * dash = spec.indexOf('-')  →  4
         *
         * // 第五步：拆左右
         * startStr = spec.substring(0, dash).trim()   →  "1000"
         * endStr   = spec.substring(dash + 1).trim()  →  "1999"
         *
         * // 第六步：解析数字
         * start = Long.parseLong("1000")  →  1000
         * end   = Long.parseLong("1999")  →  1999
         *
         * // 第七步：上界收敛
         * end > totalSize - 1 ? 收敛 : 不变
         *
         * // 第八步：返回
         * return new long[]{1000, 1999};
         */


        //1：前置校验：空值、非 bytes= 开头
        if (header == null || header.isBlank() || !header.startsWith("bytes=")) {
            return null;
        }

        // 2：提取 bytes= 后面的部分
        String spec = header.substring("bytes=".length()).trim();
        
        //3：拒绝多段 Range  ————————如果是多段：复杂度远大于收益——浏览器和下载工具几乎不用多段 Range（它们用单段 + 多线程各自发单段请求）。所以有意降级为全量 200，而不是硬撑一个没人用的特性。
            // 多段（bytes=0-1,5-6）不支持：忽略 Range 回退全量，避免多段拼接的复杂度与成本
        if (spec.contains(",")) {
            return null;
        }

        
        //4：找横杠，拆 start 和 end
        //没有 - → "bytes=1000" → 语法非法 → null  有 - 才能拆成 start-end 两部分。
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }

        String startStr = spec.substring(0, dash).trim();// "-" 左边
        String endStr = spec.substring(dash + 1).trim();// "-" 右边
        
        //5：try-catch 包裹解析，避免 NumberFormatException
        try {
        //6：处理 suffix 形式 bytes=-N
            if (startStr.isEmpty()) {
                // suffix 形式 bytes=-N：取末尾 N 字节  //bytes=-N:"我要文件最后 N 个字节"。
                if (endStr.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(endStr);
                if (suffix <= 0) {
                    return new long[]{totalSize, -1}; // 不可满足 → 416
                }
                //文件一共 totalSize 字节，你要最后 suffix 个字节：这个起点位置就是 totalSize - suffix
                return new long[]{
                        Math.max(totalSize - suffix, 0),  // start: 从哪开始读
                        totalSize - 1                     // end: 读到哪
                };            }
            //7：处理标准形式 bytes=start-end 和 bytes=start-
            long start = Long.parseLong(startStr);
            long end = endStr.isEmpty() ? totalSize - 1 : Long.parseLong(endStr);
            //8：负数校验
            if (start < 0 || end < 0) {
                return null;
            }
            //9：上界收敛到对象末尾
            if (end > totalSize - 1) {
                end = totalSize - 1;
            }
            //10：返回结果
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null; // 数字非法 → 忽略 Range 回退全量
        }
    }

    /** 下载文件名：取 path 尾段（{yyyy/MM/dd}/{fileId}.{ext}）；path 异常兜底 fileId */
    private String resolveFileName(FileMeta meta) {
        String path = meta.getPath();
        if (path == null || path.isBlank()) {
            return meta.getFileId();
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * 强制签发预签名 URL：owner/ADMIN 校验 → PRIVATE 二次校验（纵深防御）→ 有效期夹在 1~配置上限
     * （防传 99999 绕过 LICENSE 限时）→ 生成 URL 并组装 VO
     */
    @Override
    public PresignVO presign(String fileId, Integer expireMinutes, Long operatorId) {
        // 查活跃元数据（del_flag=0，不存在抛 FILE_NOT_FOUND）
        FileMeta meta = getMetaOrThrow(fileId);
        // 访问闸口：safe_flag=0 违规拒绝（预签名 URL 是可传播凭证，与 getUrl/download 同口径收口，
        // 否则违规 PRIVATE 文件可经 presign 绕过闸口）；PRIVATE 低曝光面 2/3 不占位，
        // owner/REVIEWER/ADMIN 按角色放行（审核员看证据图不被占位挡住）
        checkNotViolative(meta);
        // owner/REVIEWER/ADMIN 校验（读类口径与 getUrl 的 PRIVATE 口径统一，预签名 URL 是可传播凭证）
        checkPrivateReadAccess(meta, operatorId);
        // bizType 二次校验（纵深防御）：预签名单面向 PRIVATE，PUBLIC 走直链无需签名
        if (meta.getAccess() != AccessLevelEnum.PRIVATE) {
            throw new BizException(ErrorCode.PARAM_ERROR, "公开文件走直链，无需预签名");
        }
        // expireMinutes ≤ 配置上限（防传 99999 绕过 LICENSE 限时）
        int cap = fileProperties.getSeaweedfs().getPresignExpireMinutes();
        int minutes = expireMinutes == null ? cap : expireMinutes;
        if (minutes < 1 || minutes > cap) {
            throw new BizException(ErrorCode.PARAM_ERROR, "有效期须在 1~" + cap + " 分钟之间");
        }
        // 生成预签名 URL 并组装 VO（expireTime 供前端提前刷新）
        String url = seaweedFsClient.presign(meta.getBucket(), meta.getPath(), Duration.ofMinutes(minutes));
        return PresignVO.builder()
                .fileId(fileId)
                .url(url)
                .expireTime(LocalDateTime.now().plusMinutes(minutes))
                .build();
    }

    /**
     * 元数据查询：非 owner 且非 ADMIN 时脱敏 ownerId（不回显，防枚举他人文件归属），其余字段全量返回
     */
    @Override
    public FileMetaVO getMeta(String fileId, Long operatorId) {
        // 查活跃元数据（del_flag=0，不存在抛 FILE_NOT_FOUND）
        FileMeta meta = getMetaOrThrow(fileId);
        // 脱敏：非 owner 且非 ADMIN 不回显 ownerId（防任意登录用户枚举他人文件归属）
        boolean ownerOrAdmin = (operatorId != null && operatorId.equals(meta.getOwnerId()))
                || SaRoleHelper.isAdmin();
        return FileMetaVO.builder()
                .fileId(meta.getFileId())
                .ownerId(ownerOrAdmin ? meta.getOwnerId() : null)
                .safeFlag(meta.getSafeFlag())
                .bizType(meta.getBizType())
                .access(meta.getAccess())
                .mime(meta.getMime())
                .size(meta.getSize())
                .width(meta.getWidth())
                .height(meta.getHeight())
                .align(meta.getAlign())
                .createTime(meta.getCreateTime())
                .build();
    }

    /**
     * 内部元数据查询（仅服务间 Feign 提供，不走登录态/脱敏）：返回完整 ownerId 供调用方归属校验；
     * 不调 SaRoleHelper（Feign 服务端线程无登录会话）
     */
    @Override
    public FileMetaVO getInternalMeta(String fileId) {
        // 查活跃元数据（del_flag=0，不存在抛 FILE_NOT_FOUND）
        FileMeta meta = getMetaOrThrow(fileId);
        return FileMetaVO.builder()
                .fileId(meta.getFileId())
                .ownerId(meta.getOwnerId())
                .safeFlag(meta.getSafeFlag())
                .bizType(meta.getBizType())
                .access(meta.getAccess())
                .mime(meta.getMime())
                .size(meta.getSize())
                .width(meta.getWidth())
                .height(meta.getHeight())
                .align(meta.getAlign())
                .createTime(meta.getCreateTime())
                .build();
    }

    /**
     * 缩略图 URL：功能开关 + 图片类型校验 → w/h 空取 yml 默认、越界拒绝 → 按需生成/复用派生 key（不落库）→ 拼公开直链
     */
    @Override
    public ThumbnailVO getThumbnail(String fileId, Integer width, Integer height) {
        // 查活跃元数据（del_flag=0，不存在抛 FILE_NOT_FOUND）
        FileMeta meta = getMetaOrThrow(fileId);
        // PRIVATE（LICENSE）拒绝缩略图：预签名 URL 是私有文件唯一出口，
        // 缩略图走公开直链会绕过 owner/ADMIN 校验泄露私有文件（纵深防御，口径与 getUrl/presign 统一）
        if (meta.getAccess() == AccessLevelEnum.PRIVATE) {
            throw new BizException(ErrorCode.PARAM_ERROR, "私有文件不支持缩略图");
        }
        if (!fileProperties.getThumbnail().isEnabled()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缩略图功能未开启");
        }
        if (meta.getMime() == null || !meta.getMime().startsWith("image/")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "非图片文件不支持缩略图");
        }
        // w/h 空取 yml 默认 300×300；上限防大尺寸参数滥用
        int w = width == null ? fileProperties.getThumbnail().getWidth() : width;
        int h = height == null ? fileProperties.getThumbnail().getHeight() : height;
        if (w < 1 || h < 1 || w > MAX_THUMBNAIL_DIMENSION || h > MAX_THUMBNAIL_DIMENSION) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "缩略图尺寸须在 1~" + MAX_THUMBNAIL_DIMENSION + " 之间");
        }
        // 按需生成/复用缩略图（路径约定派生 key，不落库），URL 拼公开直链
        String thumbKey = thumbnailGenerator.getOrCreate(meta.getBucket(), meta.getPath(), w, h);
        return ThumbnailVO.builder()
                .fileId(fileId)
                .url(buildPublicUrl(meta.getBucket(), thumbKey))
                .width(w)
                .height(h)
                .build();
    }

    /**
     * 上传校验：size ≤ maxImageSize + MIME 白名单（按 bizType 区分，LICENSE 放开授权文件类型）
     * + 文件头魔数校验（真实魔数须与声明 MIME 一致）
     */
    private void validateUpload(MultipartFile file, StorageBizTypeEnum bizType) {
        // 空文件校验
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "上传文件不能为空");
        }
        // 大小校验：≤ yml 配置的 maxImageSize（默认 10MB）
        if (file.getSize() > fileProperties.getUpload().getMaxImageSize()) {
            throw new BizException(ErrorCode.FILE_SIZE_EXCEEDED,
                    "文件大小超过限制（上限 " + fileProperties.getUpload().getMaxImageSize() / 1024 / 1024 + "MB）");
        }
        // MIME 白名单校验（按 bizType 区分，忽略大小写）
        String mime = file.getContentType();
        if (mime == null || !resolveMimeWhitelist(bizType).contains(mime.toLowerCase())) {
            throw new BizException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "文件类型不允许：" + mime);
        }
        // 文件头魔数校验：防「改扩展名 + 伪造 Content-Type」绕过白名单（真实魔数须与声明类型一致）
        if (!FileMagicValidator.matches(file, mime)) {
            throw new BizException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "文件内容与声明类型不符：" + mime);
        }
    }

    /** MIME 白名单：LICENSE = 图片类 + 授权文件额外放开类型，其余仅图片类 */
    private List<String> resolveMimeWhitelist(StorageBizTypeEnum bizType) {
        if (bizType == StorageBizTypeEnum.LICENSE) {
            List<String> merged = new ArrayList<>(IMAGE_MIME_WHITELIST);
            merged.addAll(LICENSE_EXTRA_MIME_WHITELIST);
            return merged;
        }
        return IMAGE_MIME_WHITELIST;
    }

    /**
     * DigestInputStream 流式算 SHA256（勿全量读内存）
     */
    private String sha256Hex(MultipartFile file) {
        try (InputStream in = file.getInputStream();
             DigestInputStream digestStream = new DigestInputStream(in, MessageDigest.getInstance("SHA-256"))) {
            byte[] buffer = new byte[8192];

            while (digestStream.read(buffer) != -1) {//用 8KB 的缓冲区循环读取文件流，每读一点字节就自动喂给 SHA-256 摘要计算器，直到文件读完为止。
                // 流式消费，仅驱动摘要计算
            }
            return HexFormat.of().formatHex(digestStream.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new BizException(ErrorCode.FILE_HASH_ERROR, e);
        }
    }

    /**
     * per-user 存储配额校验（方案 α 软限：在 sha256 锁内、写库前执行）：
     * 口径 = del_flag=0 全部记录（含 status=0 占位，即"预占"语义，避免上传中绕过配额）；
     * 秒传命中已在调用前 return，不占新增配额。
     * ⚠️ 锁维度为 sha256 而非 user，同一用户并发上传【不同文件】时不互斥，
     * 最坏可超限约 1×单文件上限（已有 30 次/分钟限流兜底，超限量有界）。
     */
    private void checkQuota(Long ownerId, long incomingSize) {
        // 查询当前用户已使用的存储信息（1次查询，返回总量+总数）
        FileQuotaUsage usage = fileMetaMapper.selectQuotaUsage(ownerId);

        // 已用容量（字节），防止 sum(size) 返回 null
        long usedSize = usage.getUsedSize() == null ? 0L : usage.getUsedSize();

        // 已用文件数量，防止 count(*) 返回 null
        long usedCount = usage.getUsedCount() == null ? 0L : usage.getUsedCount();

        // 从配置中读取用户级配额上限
        long maxTotalSize = fileProperties.getUpload().getMaxTotalSize(); // 总容量上限（字节）
        long maxFileCount = fileProperties.getUpload().getMaxFileCount(); // 文件数量上限

        // ===== 容量配额校验 =====
        if (usedSize + incomingSize > maxTotalSize) {
            throw new BizException(
                    ErrorCode.FILE_QUOTA_EXCEEDED,
                    "存储容量已用尽（已用 " + usedSize / 1024 / 1024 + "MB / 上限 " + maxTotalSize / 1024 / 1024 + "MB）"
            );
        }

        // ===== 文件数量配额校验 =====
        if (usedCount + 1 > maxFileCount) {
            throw new BizException(
                    ErrorCode.FILE_QUOTA_EXCEEDED,
                    "文件数量已达上限（已用 " + usedCount + " / 上限 " + maxFileCount + "）"
            );
        }
        // 校验通过，允许继续写库和上传
    }

    /**
     * 秒传判重查询（走 idx_dedup）：sha256+size+biz_type+del_flag=0；
     * PRIVATE（LICENSE）追加 owner_id 按 owner 隔离，防跨用户秒传探测泄露隐私
     */
    private FileMeta selectByDedupKey(String sha256, long size, StorageBizTypeEnum bizType, Long ownerId) {
        LambdaQueryWrapper<FileMeta> lqw = new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getSha256, sha256)
                .eq(FileMeta::getSize, size)
                .eq(FileMeta::getBizType, bizType)
                .eq(FileMeta::getDelFlag, DelFlag.NOT_DELETED)
                // 判重排除隔离图：safe_flag=0 的隔离证据不参与秒传（黑名单/缓存过期后同图重传
                // 不得秒传命中违规记录拿到永不展示的 fileId，重传由拒传黑名单拦截）
                .ne(FileMeta::getSafeFlag, SafeFlagEnum.VIOLATIVE);
        if (resolveAccess(bizType) == AccessLevelEnum.PRIVATE) {
            lqw.eq(FileMeta::getOwnerId, ownerId);
        }
        // selectList 取首条：脏数据（同判重键多行）时降级复用，而非抛 TooManyResultsException
        return fileMetaMapper.selectList(lqw).stream().findFirst().orElse(null);
    }

    /** bizType → bucket 映射（FileProperties.collections，code 与 yml 键一致） */
    private String resolveBucket(StorageBizTypeEnum bizType) {
        String bucket = fileProperties.getSeaweedfs().getCollections().get(bizType.getCode());
        if (bucket == null || bucket.isBlank()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "bizType 未配置 bucket 映射：" + bizType.getCode());
        }
        return bucket;
    }

    /** 从原始文件名解析扩展名（无扩展名兜底 bin，全小写） */
    private String resolveExt(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "bin";
        }
        int dot = originalFilename.lastIndexOf('.');
        // dot < 0 表示：文件名中不存在扩展名
        // dot == originalFilename.length() - 1 的含义
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "bin";
        }
        return originalFilename.substring(dot + 1).toLowerCase();
    }

    /** access 由 bizType 决定：LICENSE 强制 PRIVATE（服务层覆盖，default 仅兜底） */
    private AccessLevelEnum resolveAccess(StorageBizTypeEnum bizType) {
        return bizType == StorageBizTypeEnum.LICENSE ? AccessLevelEnum.PRIVATE : AccessLevelEnum.PUBLIC;
    }

    /** 解析图片宽高（ImageIO；非图片/webp 解析失败返回 null，用于前端布局预留） */
    private Integer[] resolveImageSize(byte[] bytes, String mime) {
        if (mime == null || !mime.startsWith("image/")) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            return image == null ? null : new Integer[]{image.getWidth(), image.getHeight()};
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 【上传链路·黑名单】REJECT 黑名单（review 侧终局 REJECT 时写入，TTL 30 天；file 侧只读）：
     * 命中直接拒传（不落库不进 MQ），防违规图反复重传消耗审核资源。
     * Redis key 各段均为服务端派生（sha256），不拼接用户原串
     */
    private void checkRejectBlacklist(String sha256) {
        String key = String.format(ModerationConstant.IMG_REJECT_BLACKLIST_KEY, sha256);
        if (redisUtil.hasKey(key)) {
            log.warn("上传命中审核黑名单，拒绝上传，sha256={}", sha256);
            throw new BizException(ErrorCode.FILE_UPLOAD_REJECTED);
        }
    }

    /**
     * 【上传链路·审核缓存】复用 review 侧终局结论（key 含 provider+modelVersion，TTL 72h，review 侧写入）：
     * PASS→safe_flag=1 免审直落（跳过 MQ）；REJECT→直接拒传（与黑名单同语义）；
     * 未命中/非终局结论（如 SUSPICIOUS）→返回 PENDING，走完整送审链路
     */
    private SafeFlagEnum checkAuditCache(String sha256) {
        FileProperties.Moderation mod = fileProperties.getModeration();
        String key = String.format(ModerationConstant.IMG_RESULT_KEY,
                mod.getImageProvider(), mod.getImageModelVersion(), sha256);
        String cached = redisUtil.get(key);
        if (cached == null) {
            return SafeFlagEnum.PENDING;
        }
        try {
            int verdict = Integer.parseInt(cached);
            if (verdict == ModerationConstant.VERDICT_PASS) {
                log.info("上传命中审核缓存 PASS 结论，免审直落，sha256={}", sha256);
                return SafeFlagEnum.NORMAL;
            }
            if (verdict == ModerationConstant.VERDICT_REJECT) {
                log.warn("上传命中审核缓存 REJECT 结论，拒绝上传，sha256={}", sha256);
                throw new BizException(ErrorCode.FILE_UPLOAD_REJECTED);
            }
        } catch (NumberFormatException e) {
            log.warn("审核缓存 value 格式异常，降级走完整送审链路，sha256={}", sha256);
        }
        return SafeFlagEnum.PENDING;
    }

    /**
     * 【上传链路·送审】发布 image.submit 事件（litisle.moderation.exchange → review 侧消费）：
     * 事件体 JSON（两侧共用 Jackson2JsonMessageConverter），bizId=fileId、contentHash=sha256
     * （review 侧按 hash 走内部契约取图，严禁按用户 URL 抓取）。
     * 发布失败仅记 error 不回滚上传：safe_flag 停留 2，default-deny 闸口不放行（宁严勿松）
     */
    private void publishImageSubmitEvent(FileMeta meta, String sha256) {
        try {
            ImageSubmitEvent event = new ImageSubmitEvent();
            event.setEventId(UUID.randomUUID().toString());
            event.setEventTime(LocalDateTime.now());
            event.setTraceId(MDC.get("traceId"));
            event.setVersion(1);
            event.setModType(ModerationConstant.MOD_TYPE_AUTO);
            event.setBizType(ModerationConstant.BIZ_TYPE_IMAGE);
            event.setBizId(meta.getFileId());
            event.setScene(ModerationConstant.SCENE_IMAGE);
            event.setContentHash(sha256);
            event.setRetryCount(0);
            // 事件发布统一收口 common-mq MqSendUtil（traceId 注入 Header + 失败日志口径统一）
            mqSendUtil.send(MqConstant.EXCHANGE_MODERATION,
                    MqConstant.ROUTING_MODERATION_IMAGE_SUBMIT, event);
            log.info("image.submit 事件已发布，fileId={}，sha256={}", meta.getFileId(), sha256);
        } catch (Exception e) {
            log.error("image.submit 发布失败，safe_flag 停留 PENDING 占位，fileId={}", meta.getFileId(), e);
        }
    }

    /**
     * 构建上传返回 VO：PUBLIC→publicUrlBase 直链；PRIVATE→预签名（默认 30min，
     * 与 AccessLevelEnum 分流口径一致）
     */
    private FileUploadVO buildUploadVO(FileMeta meta, boolean deduplicated) {
        String url;
        if (meta.getAccess() == AccessLevelEnum.PRIVATE) {
            url = seaweedFsClient.presign(meta.getBucket(), meta.getPath(),
                    Duration.ofMinutes(fileProperties.getSeaweedfs().getPresignExpireMinutes()));
        } else {
            url = buildPublicUrl(meta.getBucket(), meta.getPath());
        }
        return FileUploadVO.builder()
                .fileId(meta.getFileId())
                .url(url)
                .sha256(meta.getSha256())
                .mime(meta.getMime())
                .size(meta.getSize())
                .width(meta.getWidth())
                .height(meta.getHeight())
                .safeFlag(meta.getSafeFlag())
                .deduplicated(deduplicated)
                .build();
    }


    /**
     * 引用登记（方案C）：幂等插入 file_ref（同四元组已存在则跳过）；与业务表同事务由调用方保证
     */
    @Override
    public void bindRef(String fileId, StorageBizTypeEnum bizType, Long bizId, Long ownerId) {
        // 前置校验：文件必须存在且未删除
        getMetaOrThrow(fileId);
        // 幂等：同一引用（fileId+bizType+bizId+ownerId）已登记则跳过，业务方重试安全
        // 与业务表同事务由调用方保证（业务方方法上加 @Transactional）
        Long count = fileRefMapper.selectCount(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId)
                .eq(FileRef::getBizType, bizType)
                .eq(FileRef::getBizId, bizId)
                .eq(FileRef::getOwnerId, ownerId));
        if (count > 0) {
            return;
        }
        try {
            fileRefMapper.insert(new FileRef()
                    .setFileId(fileId)
                    .setBizType(bizType)
                    .setBizId(bizId)
                    .setOwnerId(ownerId)
                    .setCreateTime(LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            // uk_ref(file_id,biz_type,biz_id,owner_id) 唯一约束兜底：并发下两请求均通过上面的
            // selectCount=0，后到者插入被 DB 拒绝；此处吞掉异常维持幂等语义，
            // 不标记回滚（MySQL 重复键错误仅使该条 insert 失败，当前事务可继续）
            log.info("引用登记并发重复，按幂等跳过：fileId={}，bizType={}，bizId={}，ownerId={}",
                    fileId, bizType, bizId, ownerId);
        }
    }

    /**
     * 解除引用登记（方案C）：按四元组精确定位仅删当前调用方引用行，不动文件本体；行不存在静默结束（幂等）
     */
    @Override
    public void unbindRef(String fileId, StorageBizTypeEnum bizType, Long bizId, Long ownerId) {
        // 前置校验：文件必须存在且未删除
        getMetaOrThrow(fileId);
        // 仅删当前调用方的引用行（四条件精确定位），不动文件本体；幂等：行不存在静默结束
        fileRefMapper.delete(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId)
                .eq(FileRef::getBizType, bizType)
                .eq(FileRef::getBizId, bizId)
                .eq(FileRef::getOwnerId, ownerId));
    }

    /**
     * 删除（方案C 解绑语义）：owner/ADMIN 校验 → Step1 删当前调用方引用行 → Step2 仍有他人引用则不动文件
     * → Step3 无引用才软删（del_flag=1），物理清理交对账任务 7 天缓冲
     */
    @Override
    public void delete(String fileId, Long operatorId) {
        // 查活跃元数据 + owner/ADMIN 校验（删除属敏感操作）
        FileMeta meta = getMetaOrThrow(fileId);
        checkOwnerOrAdmin(meta, operatorId);
        // Step1：仅解除当前调用方引用行（file_id + owner_id，不限 bizType——一次清完名下引用）
        fileRefMapper.delete(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId)
                .eq(FileRef::getOwnerId, operatorId));
        // Step2：仍有其他活跃引用 → 结束（文件不动，他人引用不受影响）
        Long activeRefs = fileRefMapper.selectCount(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId));
        if (activeRefs > 0) {
            return;
        }
        // Step3：无活跃引用 → del_flag=1 + delete_time=now（退出秒传判重池；
        // 物理清理交对账任务，7 天缓冲防误删不可恢复）
        fileMetaMapper.updateById(new FileMeta()
                .setId(meta.getId())
                .setDelFlag(DelFlag.DELETED)
                .setDeleteTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now()));
        log.info("文件软删完成（无活跃引用），fileId={}，operatorId={}", fileId, operatorId);
    }


    /** fileId 查活跃元数据（del_flag=0），不存在抛 FILE_NOT_FOUND */
    private FileMeta getMetaOrThrow(String fileId) {
        FileMeta meta = fileMetaMapper.selectOne(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getFileId, fileId)
                .eq(FileMeta::getDelFlag, DelFlag.NOT_DELETED));
        if (meta == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        return meta;
    }

    /**
     * 违规图隔离（审核链路：moderation 侧 REJECT 时经 FileClient.feign 调用）
     */
    @Override
    public void quarantine(String fileId) {
        moveBetweenQuarantinePrefix(fileId, true);
    }

    /**
     * 隔离恢复（申诉链路：改判 PASS 时经 FileClient.feign 调用）
     */
    @Override
    public void unquarantine(String fileId) {
        moveBetweenQuarantinePrefix(fileId, false);
    }

    /**
     * 隔离/恢复统一实现（S3 无原生 move：copy + delete 组合；两步均幂等，中途失败重试自愈）
     * <p>前缀 SSOT：与对账任务跳过清理共用 litisle.file.reconcile-task.quarantine-prefix（防两处配置漂移）。
     * 移入/移回按前缀可逆推导目标 key（隔离 = quarantine/ + 原 key），无需新增列记录原路径；
     * del_flag 全程保持 0，不破坏 file_ref 不变式（隔离图不是删除，是暂存证据）</p>
     */
    private void moveBetweenQuarantinePrefix(String fileId, boolean toQuarantine) {
        // 查活跃元数据（del_flag=0，不存在抛 FILE_NOT_FOUND）
        FileMeta meta = getMetaOrThrow(fileId);
        String prefix = fileProperties.getReconcileTask().getQuarantinePrefix();
        boolean quarantined = meta.getPath() != null && meta.getPath().startsWith(prefix);
        // 幂等闸口：重复隔离 / 重复恢复直接跳过（调用方与 MQ 重投均安全）
        if (toQuarantine == quarantined) {
            log.info("文件已处于目标状态（quarantined={}），跳过重复操作，fileId={}", quarantined, fileId);
            return;
        }
        // 本体 + 全部缩略图派生 key（_thumb_*）一并移动，防隔离后缩略图仍可经旧 key 直链访问
        List<String> keys = seaweedFsClient.listKeys(meta.getBucket(), meta.getPath());
        for (String key : keys) {
            // 每个对象 key 按同一前缀规则变换：移入 = quarantine/ + key；移回 = 去掉 quarantine/ 前缀
            String targetKey = toQuarantine ? prefix + key : key.substring(prefix.length());
            // 先 copy 后 delete：copy 同 key 覆盖幂等、delete 对不存在 key 也成功，重试安全
            seaweedFsClient.copyObject(meta.getBucket(), key, targetKey);
            seaweedFsClient.delete(meta.getBucket(), key);
        }
        // 存储移动完成后回写元数据：path 指向新前缀 + safe_flag（隔离=0 违规 / 恢复=1 正常）
        String targetPath = toQuarantine ? prefix + meta.getPath() : meta.getPath().substring(prefix.length());
        fileMetaMapper.updateById(new FileMeta()
                .setId(meta.getId())
                .setPath(targetPath)
                .setSafeFlag(toQuarantine ? SafeFlagEnum.VIOLATIVE : SafeFlagEnum.NORMAL)
                .setUpdateTime(LocalDateTime.now()));
        log.info("违规图{}完成，fileId={}，path：{} → {}", toQuarantine ? "隔离" : "恢复", fileId, meta.getPath(), targetPath);
    }

    /**
     * 审核结果回写 safe_flag（file 侧 MQ 消费入口）：按主键定点 update，幂等（已处于目标态直接跳过，
     * MQ 重投安全）；REJECT 分支不走本方法（走 quarantine() 含物理隔离）
     */
    /**
     * 批量读取 safe_flag（file 客户端契约提供方）：仅查活跃记录（del_flag=0），
     * select 只取两列降 IO；查不到的 fileId 不在 Map 中，由调用方按未知态保守处理
     */
    @Override
    public Map<String, Integer> batchGetSafeFlags(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Map.of();
        }
        List<FileMeta> metas = fileMetaMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .select(FileMeta::getFileId, FileMeta::getSafeFlag)
                .in(FileMeta::getFileId, fileIds)
                .eq(FileMeta::getDelFlag, DelFlag.NOT_DELETED));
        return metas.stream()
                .collect(Collectors.toMap(FileMeta::getFileId, m -> m.getSafeFlag().getCode()));
    }

    /**
     * 机审取图（file 客户端契约提供方）：不过 safe_flag 闸口（申诉复核需重取隔离图
     * safe_flag=0）；seaweedFsClient.get 整读（≤10MB，与上传同步瓶颈同源）
     */
    @Override
    public FileContentDTO getInternalContent(String fileId) {
        FileMeta meta = getMetaOrThrow(fileId);
        byte[] bytes = seaweedFsClient.get(meta.getBucket(), meta.getPath());
        FileContentDTO dto = new FileContentDTO();
        dto.setFileId(meta.getFileId());
        dto.setMime(meta.getMime());
        dto.setContent(bytes);
        return dto;
    }

    public void updateSafeFlag(String fileId, SafeFlagEnum target) {
        FileMeta meta = getMetaOrThrow(fileId);
        // 幂等闸口：重复事件/重投直接跳过
        if (meta.getSafeFlag() == target) {
            log.info("safe_flag 已处于目标状态，跳过回写，fileId={}，target={}", fileId, target);
            return;
        }
        int affected = fileMetaMapper.update(null, new LambdaUpdateWrapper<FileMeta>()
                .eq(FileMeta::getId, meta.getId())
                .set(FileMeta::getSafeFlag, target)
                .set(FileMeta::getUpdateTime, LocalDateTime.now()));
        if (affected == 0) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        log.info("safe_flag 回写完成，fileId={}，{} → {}", fileId, meta.getSafeFlag(), target);
    }

    /** 访问闸口（单点收口）：safe_flag=0 违规拒绝发放任何形式的访问入口（直链/预签名/下载流） */
    private void checkNotViolative(FileMeta meta) {
        if (meta.getSafeFlag() == SafeFlagEnum.VIOLATIVE) {
            throw new BizException(ErrorCode.FILE_VIOLATIVE);
        }
    }

    /** owner/ADMIN 口径：operatorId 与 ownerId 一致或 ADMIN，否则 NO_PERMISSION */
    private void checkOwnerOrAdmin(FileMeta meta, Long operatorId) {
        if (operatorId != null && operatorId.equals(meta.getOwnerId())) {
            return;
        }
        if (!SaRoleHelper.isAdmin()) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
    }

    /**
     * 私有面读类权限（getUrl/download/presign）：owner/REVIEWER/ADMIN——
     * 审核员查看证据图（LICENSE/申诉材料等 PRIVATE 面文件）不被权限挡住（§4.4.5）；
     * 删除等敏感写操作不走本方法，仍收口 checkOwnerOrAdmin（REVIEWER 无写权限）
     */
    private void checkPrivateReadAccess(FileMeta meta, Long operatorId) {
        if (operatorId != null && operatorId.equals(meta.getOwnerId())) {
            return;
        }
        if (!SaRoleHelper.isAdmin() && !SaRoleHelper.isReviewer()) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
    }

    /** PUBLIC 直链：publicUrlBase + bucket + path（与 SDK endpoint 分离，接 CDN 仅改配置） */
    private String buildPublicUrl(String bucket, String path) {
        return fileProperties.getSeaweedfs().getPublicUrlBase() + "/" + bucket + "/" + path;
    }
}
