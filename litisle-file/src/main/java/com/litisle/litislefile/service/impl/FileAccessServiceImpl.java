package com.litisle.litislefile.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonsecurity.utils.SaRoleHelper;
import com.litisle.litisleapi.dto.file.FileOwnerDTO;
import com.litisle.litislefile.client.SeaweedFsClient;
import com.litisle.litislefile.config.FileProperties;
import com.litisle.litislefile.domain.dto.FileDownloadResource;
import com.litisle.litislefile.domain.entity.FileMeta;
import com.litisle.litislefile.domain.vo.FileMetaVO;
import com.litisle.litislefile.domain.vo.PresignVO;
import com.litisle.litislefile.domain.vo.ThumbnailVO;
import com.litisle.litislefile.enums.AccessLevelEnum;
import com.litisle.litislefile.enums.SafeFlagEnum;
import com.litisle.litislefile.mapper.FileMetaMapper;
import com.litisle.litislefile.service.FileAccessService;
import com.litisle.litislefile.service.support.FileMetaSupport;
import com.litisle.litislefile.thumbnail.ThumbnailGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 访问域实现（由 FileServiceImpl 按业务域拆分）。
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
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileAccessServiceImpl implements FileAccessService {

    /** 缩略图宽/高上限（防恶意大尺寸参数撑爆内存/存储） */
    private static final int MAX_THUMBNAIL_DIMENSION = 2000;

    private final FileMetaMapper fileMetaMapper;
    private final SeaweedFsClient seaweedFsClient;
    private final FileProperties fileProperties;
    private final ThumbnailGenerator thumbnailGenerator;
    private final FileMetaSupport fileMetaSupport;

    /**
     * 获取访问 URL 统一入口：查元数据后按 access 分流——PUBLIC 拼直链；PRIVATE 先 owner/ADMIN 校验再预签名
     * （调用方无需感知文件访问级别；审核 safe_flag 闸口预留在此单点收口）
     * <p>【大文件下载】返回的 URL 即下载凭证，客户端直连对象存储拉取；断点续传由对象存储原生
     * Range(206) 支持，本模块不代理文件流（详见 SeaweedFsClient#presign）</p>
     */
    @Override
    public String getUrl(String fileId, Long operatorId) {
        // 查活跃元数据（del_flag=0，不存在抛 FILE_NOT_FOUND）
        FileMeta meta = fileMetaSupport.getMetaOrThrow(fileId);
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
            return fileMetaSupport.buildPublicUrl(meta.getBucket(), meta.getPath());
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
                return fileMetaSupport.buildPublicUrl(meta.getBucket(), meta.getPath());
            }
            return seaweedFsClient.presign(meta.getBucket(), meta.getPath(),
                    Duration.ofMinutes(fileProperties.getSeaweedfs().getPresignExpireMinutes()));
        }));
    }

    /**
     * 批量查询文件归属信息（仅服务间；一次 in 查询收口 N+1，对齐 batchGetUrls 范式）：
     * 查活跃元数据 → 任一 fileId 缺失抛 FILE_NOT_FOUND fail-fast（对齐 batchGetSha256 口径，
     * 静默缺失会让调用方归属校验口径不完整）
     * @param fileIds 调用方“想要查”的文件列表
     * @return 所有 fileId 对应的「文件归属信息列表」，
     */
    @Override
    public List<FileOwnerDTO> batchGetOwners(List<String> fileIds) {
        // 空列表直接返回空列表（防 Feign 调用方传空）
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        // 请求 fileId 去重保序（in 查询不保序，返回后按 fileId 索引，重复项只查一次）

//        系统“必须全部存在”的文件集合
        Set<String> requiredIds = new HashSet<>(fileIds);

        //查询ifled列表中的活跃文件
        // 一次 in 查询仅查活跃记录（del_flag=0，对齐 batchGetUrls 过滤口径）
        List<FileMeta> metas = fileMetaMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .in(FileMeta::getFileId, fileIds)
                .eq(FileMeta::getDelFlag, DelFlag.NOT_DELETED));
        // 缺一不可：用「查询结果 fileId 集合是否包含所有请求 fileId」判定，
        // 比 size 比较更稳健——防 file_meta 重复行撑大 size 导致漏检（fail-fast）
        Set<String> foundIds = new HashSet<>();
        for (FileMeta meta : metas) {
            foundIds.add(meta.getFileId());
        }

        //只要有一个 fileId 不存在或已删除，就直接失败（fail-fast）
        if (!foundIds.containsAll(requiredIds)) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        List<FileOwnerDTO> collect = metas.stream().map(meta -> {
            FileOwnerDTO dto = new FileOwnerDTO();
            dto.setFileId(meta.getFileId());
            dto.setOwnerId(meta.getOwnerId());
            // bizType null 防御：上传时必填，但防御性避免边缘 NPE 拖垮批量校验
            dto.setBizType(meta.getBizType() == null ? null : meta.getBizType().getName());
            return dto;
        }).collect(Collectors.toList());
        return collect;
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
        FileMeta meta = fileMetaSupport.getMetaOrThrow(fileId);

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
        FileMeta meta = fileMetaSupport.getMetaOrThrow(fileId);
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
        FileMeta meta = fileMetaSupport.getMetaOrThrow(fileId);
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
        FileMeta meta = fileMetaSupport.getMetaOrThrow(fileId);
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
        FileMeta meta = fileMetaSupport.getMetaOrThrow(fileId);
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
                .url(fileMetaSupport.buildPublicUrl(meta.getBucket(), thumbKey))
                .width(w)
                .height(h)
                .build();
    }

    /** 访问闸口（单点收口）：safe_flag=0 违规拒绝发放任何形式的访问入口（直链/预签名/下载流） */
    private void checkNotViolative(FileMeta meta) {
        if (meta.getSafeFlag() == SafeFlagEnum.VIOLATIVE) {
            throw new BizException(ErrorCode.FILE_VIOLATIVE);
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
}