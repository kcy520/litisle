package com.litisle.litislefile.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.commonsecurity.utils.SaRoleHelper;
import com.litisle.litislefile.client.SeaweedFsClient;
import com.litisle.litislefile.config.FileProperties;
import com.litisle.litislefile.domain.entity.FileMeta;
import com.litisle.litislefile.domain.dto.FileDownloadResource;
import com.litisle.litislefile.domain.entity.FileRef;
import com.litisle.litislefile.domain.vo.FileMetaVO;
import com.litisle.litislefile.domain.vo.FileUploadVO;
import com.litisle.litislefile.domain.vo.PresignVO;
import com.litisle.litislefile.domain.vo.ThumbnailVO;
import com.litisle.litislefile.enums.AccessLevelEnum;
import com.litisle.litislefile.enums.BizTypeEnum;
import com.litisle.litislefile.enums.UploadStatusEnum;
import com.litisle.litislefile.mapper.FileMetaMapper;
import com.litisle.litislefile.mapper.FileRefMapper;
import com.litisle.litislefile.service.FileService;
import com.litisle.litislefile.thumbnail.ThumbnailGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *    避免 LICENSE 非 PDF 被误拦）
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
 * 8. 【预留｜已定接入，暂未开发】违规信息审核（内容安全扫描）：
 *    status=1 后发异步消息（fileId）→ 消费者执行病毒扫描/违规检测
 *    → 通过 → safe_flag=1；不通过 → safe_flag=0 禁止访问 + 通知管理员
 *    设计详图见 docs/seaweedfs笔记.md §3.6 / §3.6.3（先发后审取舍、接入路径）
 *    TODO: 实现 FileServiceImpl 时在此处留异步发消息埋点
 * 9. 返回 fileId + deduplicated=false
 *
 * 【一致性保障】先锁内占位写库（status=0）再锁外传文件：
 *    若 put 失败，DB 记录可由对账任务接手；若反过来「先 put 后写库」，put 成功但写库失败
 *    会产生无补偿入口的孤儿对象——这是顺序设计的核心原因
 *
 * 【URL 获取】统一入口 getUrl(fileId)：查 file_meta → access 分流
 *    PUBLIC→publicUrlBase + bucket + path 拼直链（以命中/已有记录的 bucket/path 为准；
 *    判重已限定 biz_type，命中记录 bucket 必等于本次映射，无跨桶坏链风险）
 *    PRIVATE→presign + owner/ADMIN 权限校验 + bizType 二次校验（纵深防御）
 *    【预留｜访问闸口】审核启用后 getUrl/batchGetUrls 返回前校验 safe_flag：
 *    =0 拒绝并提示违规（单点收口，不散落到各业务服务；同上见笔记 §3.6）
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

    /**
     * 单文件上传（含秒传）：校验 → 流式算 SHA256 → 无锁快查判重 → 锁内双检+占位写库（status=0）
     * → 锁外 put（幂等）→ 解析宽高 → 置 status=1；秒传命中直接复用 fileId 不落存储
     * leServiceImpl#upload 的流程是：整份文件一次读进内存 → 直接`put` 到 SeaweedFS， 没有任何"分片"环节 。
     */
    @Override
    public FileUploadVO upload(MultipartFile file, BizTypeEnum bizType, Long ownerId) {
        // 步骤1：校验 size ≤ maxImageSize + MIME 白名单（按 bizType 区分，LICENSE 放开授权文件类型）
        validateUpload(file, bizType);

        // 步骤2：流式算 SHA256（DigestInputStream，勿全量读内存）+ 记录 size（联合判重键）
        String sha256 = sha256Hex(file);
        long size = file.getSize();
        String mime = file.getContentType();

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

        // 步骤8：【预留｜已定接入，暂未开发】违规审核异步消息埋点（safe_flag 链路），暂不实现

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
    public List<FileUploadVO> uploadBatch(List<MultipartFile> files, BizTypeEnum bizType, Long ownerId) {
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
        // 【预留｜访问闸口】审核启用后此处校验 safe_flag=0 拒绝（单点收口，见笔记 §3.6）
        if (meta.getAccess() == AccessLevelEnum.PUBLIC) {
            return buildPublicUrl(meta.getBucket(), meta.getPath());
        }
        // PRIVATE：owner/ADMIN 校验 + presign（默认 30min，口径与强制签发一致）
        checkOwnerOrAdmin(meta, operatorId);
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
            // 【预留｜访问闸口】审核启用后此处校验 safe_flag=0 拒绝

            // access 分流：PUBLIC vs PRIVATE  根据文件的访问级别（PUBLIC / PRIVATE），决定返回“哪种 URL”
            if (meta.getAccess() == AccessLevelEnum.PUBLIC) {
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

        //2：【预留｜访问闸口】审核启用后此处校验 safe_flag=0 拒绝（单点收口，见笔记 §3.6）
        //    PRIVATE（LICENSE）：owner/ADMIN 校验（口径与 getUrl/presign 统一）
        if (meta.getAccess() == AccessLevelEnum.PRIVATE) {
            checkOwnerOrAdmin(meta, operatorId);
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
        // owner/ADMIN 校验（与 getUrl 的 PRIVATE 口径统一，预签名 URL 是可传播凭证，入口必须收紧）
        checkOwnerOrAdmin(meta, operatorId);
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
     */
    private void validateUpload(MultipartFile file, BizTypeEnum bizType) {
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
    }

    /** MIME 白名单：LICENSE = 图片类 + 授权文件额外放开类型，其余仅图片类 */
    private List<String> resolveMimeWhitelist(BizTypeEnum bizType) {
        if (bizType == BizTypeEnum.LICENSE) {
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
     * 秒传判重查询（走 idx_dedup）：sha256+size+biz_type+del_flag=0；
     * PRIVATE（LICENSE）追加 owner_id 按 owner 隔离，防跨用户秒传探测泄露隐私
     */
    private FileMeta selectByDedupKey(String sha256, long size, BizTypeEnum bizType, Long ownerId) {
        LambdaQueryWrapper<FileMeta> lqw = new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getSha256, sha256)
                .eq(FileMeta::getSize, size)
                .eq(FileMeta::getBizType, bizType)
                .eq(FileMeta::getDelFlag, DelFlag.NOT_DELETED);
        if (resolveAccess(bizType) == AccessLevelEnum.PRIVATE) {
            lqw.eq(FileMeta::getOwnerId, ownerId);
        }
        // selectList 取首条：脏数据（同判重键多行）时降级复用，而非抛 TooManyResultsException
        return fileMetaMapper.selectList(lqw).stream().findFirst().orElse(null);
    }

    /** bizType → bucket 映射（FileProperties.collections，code 与 yml 键一致） */
    private String resolveBucket(BizTypeEnum bizType) {
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
    private AccessLevelEnum resolveAccess(BizTypeEnum bizType) {
        return bizType == BizTypeEnum.LICENSE ? AccessLevelEnum.PRIVATE : AccessLevelEnum.PUBLIC;
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
                .deduplicated(deduplicated)
                .build();
    }


    /**
     * 引用登记（方案C）：幂等插入 file_ref（同四元组已存在则跳过）；与业务表同事务由调用方保证
     */
    @Override
    public void bindRef(String fileId, BizTypeEnum bizType, Long bizId, Long ownerId) {
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
        fileRefMapper.insert(new FileRef()
                .setFileId(fileId)
                .setBizType(bizType)
                .setBizId(bizId)
                .setOwnerId(ownerId)
                .setCreateTime(LocalDateTime.now()));
    }

    /**
     * 解除引用登记（方案C）：按四元组精确定位仅删当前调用方引用行，不动文件本体；行不存在静默结束（幂等）
     */
    @Override
    public void unbindRef(String fileId, BizTypeEnum bizType, Long bizId, Long ownerId) {
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

    /** owner/ADMIN 口径：operatorId 与 ownerId 一致或 ADMIN，否则 NO_PERMISSION */
    private void checkOwnerOrAdmin(FileMeta meta, Long operatorId) {
        if (operatorId != null && operatorId.equals(meta.getOwnerId())) {
            return;
        }
        if (!SaRoleHelper.isAdmin()) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
    }

    /** PUBLIC 直链：publicUrlBase + bucket + path（与 SDK endpoint 分离，接 CDN 仅改配置） */
    private String buildPublicUrl(String bucket, String path) {
        return fileProperties.getSeaweedfs().getPublicUrlBase() + "/" + bucket + "/" + path;
    }
}
