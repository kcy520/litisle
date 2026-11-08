package com.litisle.litislefile.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.commonredis.util.RedisUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.moderation.ImageSubmitEvent;
import com.litisle.litislefile.client.SeaweedFsClient;
import com.litisle.litislefile.config.FileProperties;
import com.litisle.litislefile.domain.dto.FileQuotaUsage;
import com.litisle.litislefile.domain.entity.FileMeta;
import com.litisle.litislefile.domain.vo.FileUploadVO;
import com.litisle.litislefile.enums.AccessLevelEnum;
import com.litisle.litislefile.enums.SafeFlagEnum;
import com.litisle.litislefile.enums.StorageBizTypeEnum;
import com.litisle.litislefile.enums.UploadStatusEnum;
import com.litisle.litislefile.mapper.FileMetaMapper;
import com.litisle.litislefile.service.FileUploadService;
import com.litisle.litislefile.service.support.FileMetaSupport;
import com.litisle.litislefile.util.FileMagicValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.litisle.commonmq.send.MqSendUtil;
import org.slf4j.MDC;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 上传域实现（由 FileServiceImpl 按业务域拆分；依赖 SeaweedFsClient / FileMetaMapper /
 * FileProperties / Redisson / FileMetaSupport）。
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
 * 4. 未命中 → Redisson 分布式锁 lock:file:sha:{hash}，waitTime=5s（等锁上限）+ 看门狗续期（临界区仅查库+写库）
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
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileUploadServiceImpl implements FileUploadService {

    /** 批量上传上限（正文多图） */
    private static final int MAX_BATCH_SIZE = 9;

    /** 秒传判重分布式锁 Key 前缀（调用方自带 lock: 全名前缀，实际锁 key 为 lock:file:sha:{hash}） */
    private static final String DEDUP_LOCK_KEY_PREFIX = CacheConstant.LOCK_PREFIX + "file:sha:";

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
    private final SeaweedFsClient seaweedFsClient;
    private final FileProperties fileProperties;
    private final DistributedLock distributedLock;
    private final RedisUtil redisUtil;
    /** MeterRegistry optional 提供器（7-1：moderation_cache_hit{layer=blacklist} 埋点用；未引 actuator 时 getIfAvailable() 返回 null 静默跳过，file 侧无 review 侧 ModerationMetrics 组件故内联注册） */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final MqSendUtil mqSendUtil;
    private final FileMetaSupport fileMetaSupport;

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
        /**
         * 此处为什么要秒传？
         *
         * ## 去掉锁外查，能省到什么？
         * 省一次`idx_dedup` 索引点查—— 微秒级，且每次上传本来就要查 （您并没有省掉它，只是把位置从锁外挪到锁内）。非热点场景下锁无竞争，tryLock 一次成功，锁内查和锁外查的实际体验完全一样。 收益 ≈ 0 。
         * ## 去掉锁外查，会失去什么？
         * 失去的是尾部场景保护。而且头像没您想的那么“冷”：
         * 1. 预设头像/封面是真实热点。 平台通常提供一套预设头像（用户从图库选），如果实现方式是“用户选择→前端把该图作为文件走上传接口”， 成千上万用户上传的是同一张图 ——同一 sha256 全局去重，这正是最典型的秒传热点。
         * 2. ARTICLE_IMG 同为 PUBLIC 全局去重。 表情包、素材图被多篇或多用户文章引用同一张图，撞 sha256 是真实会发生的。
         * 3. 恶意/异常流量。 攻击者或脚本批量上传同一文件时，锁外查让这些请求全部走秒传路径直接返回， 反而消化了攻击流量 ；没有它，这些请求全部涌向 tryLock → 大面积“系统繁忙”报错。
         */

        // 步骤1：校验 size ≤ maxImageSize + MIME 白名单（按 bizType 区分，LICENSE 放开授权文件类型）
        validateUpload(file, bizType);

        // 步骤2：流式算 SHA256（DigestInputStream，勿全量读内存）+ 记录 size（联合判重键）
        String sha256 = sha256Hex(file);
        long size = file.getSize();
        String mime = file.getContentType();

        // 步骤2.5：审核前置检查（仅公开面图片：AVATAR/COVER/ARTICLE_IMG 上传即预审；LICENSE 私有面豁免机审落 1）
        // ① REJECT 拒传黑名单：命中直接拒传，不落库不进 MQ（同图重传不再重烧审核费）
        // ② 审核结果缓存：命中终局结论直接复用（PASS→1 免审直落 / REJECT→拒传），跳过 MQ
        boolean publicFace = resolveAccess(bizType) == AccessLevelEnum.PUBLIC;//public:公开
        SafeFlagEnum initialSafeFlag = SafeFlagEnum.NORMAL;//normal:正常：检测通过
        if (publicFace) {
            //查询redis，是否命中黑名单缓存  命中黑名单则抛出异常【redis查询黑名单缓存】
            checkRejectBlacklist(sha256);
            //查询是否已有此对象的审核结论缓存  如果命中且审核未通过则抛出异常【redis查询审核结论的缓存】
            initialSafeFlag = checkAuditCache(sha256);
        }

        // 步骤3：无锁快查判重（走 idx_dedup）；PRIVATE/LICENSE 追加 owner_id 隔离，防跨用户秒传探测泄露隐私【数据库秒传查重】
            //查询第1次，锁外查询  （锁外查询和拿锁之间存在时间窗口）
        FileMeta exist = selectByDedupKey(sha256, size, bizType, ownerId);
        if (exist != null) {
            log.info("秒传命中，fileId={}，bizType={}，size={}", exist.getFileId(), bizType, size);
            return buildUploadVO(exist, true);
        }
//        查询之后马上就是拿锁了，为什么不直接在锁内查询，丢弃锁外查询？
//        丢弃锁外查 = 把秒传读路径强行塞进写锁 + tryLock 快速失败下热点必报错。
//        这两条任意一条都足以否决这个“简化”。DCL（双重检查锁）在这里的形态是标准且最小化的： 读无锁、写互斥、写前双检

        // 步骤4：Redisson 分布式锁（5 是 waitTime，即最多等锁 5s，抢不到直接失败；
        // 租期传的是 -1 → 启用看门狗续期，锁只有 finally 里的 unlock 才释放；
        // 临界区仅查库+写库，正常毫秒级完成，远小于看门狗续期间隔）
            //tryLock 快速失败，竞争=报错
        boolean locked = distributedLock.tryLock(DEDUP_LOCK_KEY_PREFIX + sha256, 5, TimeUnit.SECONDS);
        if (!locked) {
            log.warn("秒传判重锁竞争失败，sha256={}，bizType={}", sha256, bizType);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后重试");
        }
        FileMeta meta;
        try {
            // 拿锁后双重检查（防等锁期间其他线程已写入同一摘要文件） 【再次查询数据库，检查其他线程是否已经插入此文件】
                //查询第2次，锁内查询  （锁外查询和拿锁之间存在时间窗口）
            exist = selectByDedupKey(sha256, size, bizType, ownerId);
            if (exist != null) {
                log.info("锁内双检秒传命中，fileId={}，bizType={}，size={}", exist.getFileId(), bizType, size);
                return buildUploadVO(exist, true);
            }

            // 步骤4.5：per-user 存储配额校验（软限；秒传已在上方 return，不占新增配额），可配置关闭配额校验
            checkQuota(ownerId, size);

            // 步骤5：锁内临界区（只包「查 DB + 写 DB」，不包 IO）
            // fileId=UUID 无连字符，path={yyyy/MM/dd}/{fileId}.{ext}，status=0 占位
                //生成fileId
            String fileId = UUID.randomUUID().toString().replace("-", "");
                //进行seaweedfs的bucket校验
            String bucket = resolveBucket(bizType);
                //组合文件存储path
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
//        步骤10 异步发送文件审核事件，设置属性为待检查
//        步骤11 返回 VO
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
        //上传seaweedfs
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
        //解析宽高，更新刚才插入的文件(为什么不在插入时解析？原因如上注释所述)
        fileMetaMapper.updateById(update);

        // 步骤8：审核埋点（image.submit 异步送审）：仅缓存未命中（safe_flag=2 占位）时发布；
        // 发布失败仅记 error 不回滚上传——safe_flag 停留 2（default-deny 闸口不放行，宁严勿松）
        //异步发布审核事件
        if (initialSafeFlag == SafeFlagEnum.PENDING) {//preding:待检查
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
        // 配额总开关（quota-enabled=false 时彻底关闭限制，跳过聚合查询直接放行） 配置是否开启文件容量/数量校验
        if (!fileProperties.getUpload().isQuotaEnabled()) {
            return;
        }

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
     * 追加 status=COMPLETED 过滤：put 失败的幽灵记录（status=0、对象不存在）在对账任务收口前
     * 不得参与秒传——否则同文件上传会命中幽灵记录拿到坏链 fileId（对账窗口最长 24h+30min）；
     * PRIVATE（LICENSE）追加 owner_id 按 owner 隔离，防跨用户秒传探测泄露隐私
     */
    private FileMeta selectByDedupKey(String sha256, long size, StorageBizTypeEnum bizType, Long ownerId) {
        LambdaQueryWrapper<FileMeta> lqw = new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getSha256, sha256)
                .eq(FileMeta::getSize, size)
                .eq(FileMeta::getBizType, bizType)
                .eq(FileMeta::getDelFlag, DelFlag.NOT_DELETED)
                // 只允许完成态记录参与秒传：上传中（status=0）可能是 put 失败的幽灵，对象不存在
                .eq(FileMeta::getStatus, UploadStatusEnum.COMPLETED)
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
            // 7-1：moderation_cache_hit{layer=blacklist} 拒传黑名单命中计数（指标名/标签与 review 侧同源契约收口 ModerationConstant，layer 标签区分）
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                Counter.builder(ModerationConstant.METRIC_MODERATION_CACHE_HIT)
                        .tag(ModerationConstant.METRIC_TAG_LAYER, ModerationConstant.METRIC_LAYER_BLACKLIST)
                        .register(registry).increment();
            }
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
            url = fileMetaSupport.buildPublicUrl(meta.getBucket(), meta.getPath());
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
}