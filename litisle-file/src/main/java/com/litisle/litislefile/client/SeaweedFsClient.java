package com.litisle.litislefile.client;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SeaweedFS S3 客户端封装。
 * <p>
 * 设计目标：
 * 1. 全模块唯一接触 AWS SDK 的地方，业务层不感知 S3 / SeaweedFS 实现细节
 * 2. 后续可平滑替换为 MinIO / OSS / 真实 S3，仅需调整本类
 * <p>
 * 实现要点：
 * 1. 客户端构造（通常由 Spring @Bean 注入）
 *    - endpointOverride：指向 SeaweedFS Filer S3 网关（如 http://localhost:8333）
 *      ⚠️ 此地址是 SDK 内部地址（上传/删除/预签名用），不等于给前端的公开直链地址
 *      公开直链用 FileProperties.publicUrlBase 拼接（见 AccessLevelEnum）
 *    - pathStyleAccess(true)【必须】：SeaweedFS 不支持 bucket 虚拟主机风格，筒名只能放在路径中
 *    - credentials：StaticCredentialsProvider，access-key/secret-key 来自 FileProperties，
 *      需与 docker/init/seaweedfs/s3.json 中配置保持一致
 *    - 签名算法：SigV4，Region 可任意填写（如 us-east-1），SeaweedFS 不校验 Region
 *    - S3Presigner：使用相同 endpoint 和凭证单独构建，专用于生成预签名 URL
 * <p>
 * 2. put（上传文件，两个重载）
 *    - 将 bytes 作为文件上传到指定 bucket/key，设置 contentType，调用 PutObjectRequest → putObject
 *    - 4 参：无预置摘要，携带 ChecksumAlgorithm.SHA256 由 SDK 自算下发（缩略图等场景）
 *    - 5 参：调用方显式传入 SHA256(Base64) 摘要，用 checksumSHA256 下发、SDK 不再自算
 *      （与 checksumAlgorithm 互斥）；不变量「判重键 == 校验值」显式化，口径唯一
 *    - 二者均由 S3 网关侧校验内容完整性（存储侧校验），免去上传后回读比对开销
 *    - 校验失败抛 BizException(FILE_CHECKSUM_MISMATCH)（语义精确 + warn 日志）；
 *      其余异常转 BizException(FILE_STORAGE_UNAVAILABLE)，fail-fast 不重试
 * <p>
 * 3. get（内部读取对象，非下载端点）
 *    - 通过 bucket/key 读取对象内容，使用 getObjectAsByteArray 返回 byte[]
 *    - 仅用于 ≤10MB 的内部读取（如缩略图生成），勿用于大文件（全量载入会 OOM）
 * 3.1 getStream（流式读取对象，支持 Range）
 *    - 返回 ResponseInputStream（不载入内存），可按字节区间定位，供服务端代理下载
 *    - 服务端流式下载 + 断点续传由 FileService#download / FileController 承载；
 *      直连范式（presign/直链）仍是首选，代理仅在必须经服务端中转时使用
 * <p>
 * 4. delete（删除文件）
 *    - 按 bucket + key 删除对象
 *    - 调用 deleteObject
 * <p>
 * 5. presign（生成预签名 URL）
 *    - 基于 GetObjectRequest + Duration 生成临时可访问 URL
 *    - 不暴露 AK/SK，适用于私有文件（如 LICENSE）的限时下载
 *    - ⚠️ presign 是纯本地签名计算，不联网，不保证对象存在（file_meta 已查即可）
 *    - ⚠️ 调用前必须由 Service 层进行业务校验（如 bizType、权限、配额）
 *      示例：LICENSE 文件在超限时拒绝签发，属于纵深防御
 *    - 注意区分：公开文件走 public-url-base 直链拼接（不走 presign），
 *      CDN 回源是公开桶的匿名读机制，与预签名是两套独立访问模式
 * <p>
 * 6. 统一异常处理
 *    - 所有 SdkException 均在此层捕获并转换为 BizException
 *    - 避免 AWS SDK 异常类型向业务层泄漏
 */
@Slf4j
public class SeaweedFsClient {
    /**
     * S3 网关校验失败时返回的 errorCode（内容与 x-amz-checksum-sha256 不符），统一小写做精确匹配；
     * 避免 contains 子串匹配误伤（如任意含 sha256 字样的错误码）。
     * ✅ 已实测定稿（2026-09-27）：SeaweedFS S3 网关【会校验】该头——摘要不符返回 400 + errorCode=BadDigest
     * （message 文案沿用 Content-Md5，但 errorCode 即此值），对象被拒绝写入；正确摘要 200 通过。
     */
    private static final Set<String> CHECKSUM_MISMATCH_CODES =
            Set.of("baddigest");

    private final S3Client s3;           // endpoint 指向 http://localhost:8333 ，pathStyleAccess=true
    private final S3Presigner presigner; // 预签名专用

    /**
     * 客户端由配置类构建后注入（见类注释第 1 点）
     *
     * @param s3        S3 客户端（endpointOverride 指向 SeaweedFS，pathStyleAccess=true）
     * @param presigner 预签名器（与 s3 使用相同 endpoint 和凭证）
     */
    public SeaweedFsClient(S3Client s3, S3Presigner presigner) {
        this.s3 = s3;
        this.presigner = presigner;
    }

    /**
     * 上传文件（4 参重载，同 key 覆盖、幂等——秒传并发/缩略图重复生成都依赖此特性）
     * <p>无预置摘要时携带 ChecksumAlgorithm.SHA256，由 SDK 自算并交 S3 网关侧校验（存储侧校验）；
     * 需摘要来源唯一的上传链路请用 5 参重载显式传入 SHA256(Base64)</p>
     * <p>失败语义见 5 参重载：校验失败 FILE_CHECKSUM_MISMATCH，其余 FILE_STORAGE_UNAVAILABLE；
     * 上传链路中 put 失败后 DB 保留 status=0，由对账任务补偿</p>
     */
    public void put(String bucket, String key, byte[] bytes, String contentType) {
        // 摘要为 null：退回由 SDK 自算 SHA256（checksumAlgorithm），兼容缩略图等无预置摘要的调用方
        put(bucket, key, bytes, contentType, null);
    }

    /**
     * 上传文件（5 参重载，显式携带调用方预计算的 SHA256 Base64 摘要，摘要来源唯一）
     * <p>传入 checksumSha256Base64 时用 checksumSHA256 显式下发，SDK 不再重复计算；
     * 与 checksumAlgorithm 互斥，二者不可同时设置。
     * 不变量「判重键(sha256 hex) == 校验值(SHA256 Base64)」由隐式转为显式，口径唯一。</p>
     * <p>校验失败（网关比对不符）抛 BizException(FILE_CHECKSUM_MISMATCH) 并打 warn 日志，
     * 与「存储不可用」分离；其余 SdkException 转 FILE_STORAGE_UNAVAILABLE，fail-fast 不重试</p>
     */
    public void put(String bucket, String key, byte[] bytes, String contentType, String checksumSha256Base64) {
        // 构建 PutObjectRequest：指定目标桶、对象 key 和 Content-Type（便于浏览器/下载时正确识别类型）
        PutObjectRequest.Builder builder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType);
        if (checksumSha256Base64 != null && !checksumSha256Base64.isBlank()) {
            // 显式下发调用方摘要（SDK 不再自算，避免 Service 与 SDK 重复计算、摘要来源不唯一）
            builder.checksumSHA256(checksumSha256Base64);
        } else {
            // 无预置摘要：声明校验算法，由 SDK 计算 SHA256 随请求下发，S3 网关侧校验
            builder.checksumAlgorithm(ChecksumAlgorithm.SHA256);
        }
        try {
            // 同 key 上传会直接覆盖旧对象（S3 语义），因此天然幂等
            s3.putObject(builder.build(), RequestBody.fromBytes(bytes));
        } catch (S3Exception e) {
            if (isChecksumMismatch(e)) {
                log.warn("文件内容校验失败（存储侧 SHA256 比对不符），bucket={}，key={}，size={}",
                        bucket, key, bytes.length);
                throw new BizException(ErrorCode.FILE_CHECKSUM_MISMATCH, e);
            }
            throw new BizException(ErrorCode.FILE_STORAGE_UNAVAILABLE, e);
        } catch (SdkException e) {
            throw new BizException(ErrorCode.FILE_STORAGE_UNAVAILABLE, e);
        }
    }

    /**
     * 判定 S3Exception 是否属于「内容校验失败」：与已知校验错误码集合精确匹配（忽略大小写），
     * 而非子串匹配，避免误伤其它错误；集合取值见 {@link #CHECKSUM_MISMATCH_CODES}
     */
    private boolean isChecksumMismatch(S3Exception e) {
        String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
        if (code == null) {
            return false;
        }
        return CHECKSUM_MISMATCH_CODES.contains(code.toLowerCase());
    }

    /**
     * 读取对象为 byte[]（getObjectAsByteArray，仅用于 ≤10MB 的内部读取，如缩略图生成）
     * <p>对象不存在抛 BizException(FILE_NOT_FOUND)</p>
     * <p>【勿用于大文件下载】大文件下载走 getStream（流式，不载入内存）或客户端直连 presign/直链；
     * 本方法会全量载入内存，大文件会 OOM</p>
     */
    public byte[] get(String bucket, String key) {
        try {
            // getObjectAsBytes：一次性读完整对象为字节流（SDK 负责读完并关闭流），再转 byte[]
            return s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build())
                    .asByteArray();
        } catch (NoSuchKeyException e) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, e);
        } catch (SdkException e) {
            throw new BizException(ErrorCode.FILE_STORAGE_UNAVAILABLE, e);
        }
    }

    /**
     * 流式读取对象（服务端代理下载用，不整份载入内存）
     * <p>start/end 为闭区间字节范围；均为 null 时读全量（不传 S3 Range 头）。
     * 返回流由调用方负责关闭；对象不存在抛 BizException(FILE_NOT_FOUND)</p>
     */
    public ResponseInputStream<GetObjectResponse> getStream(String bucket, String key, Long start, Long end) {
        try {
            GetObjectRequest.Builder builder = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key);
            // 仅在指定区间时携带 Range 头（S3 侧按字节定位，服务端无需读取前置字节）
            if (start != null && end != null) {
                builder.range("bytes=" + start + "-" + end);
            }
            return s3.getObject(builder.build());
        } catch (NoSuchKeyException e) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, e);
        } catch (SdkException e) {
            throw new BizException(ErrorCode.FILE_STORAGE_UNAVAILABLE, e);
        }
    }

    /**
     * 删除对象（对账任务物理清理用；缩略图派生 key 由调用方经 listKeys 前缀查出后逐个删除）
     * <p>deleteObject 对不存在的 key 同样返回成功（S3 语义），天然幂等</p>
     */
    public void delete(String bucket, String key) {
        try {
            // 按 桶 + key 删除对象；S3 语义下 key 不存在也返回成功，因此可放心重试
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (SdkException e) {
            throw new BizException(ErrorCode.FILE_STORAGE_UNAVAILABLE, e);
        }
    }

    /**
     * 生成预签名 GET URL（纯本地签名计算，不联网、不保证对象存在）
     * <p>业务校验（owner/ADMIN、bizType、有效期上限）必须由 Service 层前置完成</p>
     *
     * <p>【大文件下载口径】本方法签发的 URL 即"下载凭证"，下载默认走直连范式：服务端不代理文件流，
     * 客户端持 URL 直连 SeaweedFS/CDN 拉取；断点续传由 SeaweedFS S3 网关原生 HTTP Range 支持
     * （请求 Range: bytes=offset- → 响应 206 Partial Content + Content-Range + Accept-Ranges + ETag），
     * 服务端零代码。直连为首选；另有服务端流式代理端点（GET /api/file/{fileId}/download，见 getStream）
     * 用于必须经服务端中转的场景（鉴权收紧/防直链/水印），二者并存。</p>
     */
    public String presign(String bucket, String key, Duration expire) {
        try {
            // 先构建普通的 GET 请求（描述"要下载哪个桶的哪个对象"）
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            // 再包装成预签名请求：signatureDuration 指定 URL 有效期；presign 纯本地计算签名，不联网
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .getObjectRequest(getRequest)
                    .signatureDuration(expire)
                    .build();
            // 生成的 URL 内嵌签名参数，持有者在有效期内无需 AK/SK 即可下载
            return presigner.presignGetObject(presignRequest).url().toString();
        } catch (SdkException e) {
            throw new BizException(ErrorCode.FILE_STORAGE_UNAVAILABLE, e);
        }
    }

    /**
     * 探测对象是否存在（headObject）
     * <p>使用场景：缩略图按需生成前探测、对账任务补偿上传（status=0 幽灵记录恢复/废弃）</p>
     */
    public boolean exists(String bucket, String key) {
        try {
            // headObject 只请求对象元数据（大小/类型等），不下载内容，探测存在性比 get 轻量得多
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (SdkException e) {
            throw new BizException(ErrorCode.FILE_STORAGE_UNAVAILABLE, e);
        }
    }


    /**
     * 按前缀列出对象 key（分页遍历）
     * <p>使用场景：物理清理时查出某原图的全部缩略图派生 key（path + "_thumb_"）一并删除</p>
     */
    public List<String> listKeys(String bucket, String prefix) {
        try {
            List<String> keys = new ArrayList<>();
            // listObjectsV2Paginator：SDK 自动分页迭代（每页默认 1000 条），按前缀过滤出所有对象
            s3.listObjectsV2Paginator(ListObjectsV2Request.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .build())
                    .contents()
                    .forEach(obj -> keys.add(obj.key()));
            return keys;
        } catch (SdkException e) {
            throw new BizException(ErrorCode.FILE_STORAGE_UNAVAILABLE, e);
        }
    }



}