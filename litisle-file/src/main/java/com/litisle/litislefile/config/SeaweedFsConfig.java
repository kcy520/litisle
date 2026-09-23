package com.litisle.litislefile.config;


import com.litisle.litislefile.client.SeaweedFsClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * SeaweedFS S3 客户端装配配置。
 * <p>
 * 职责：
 * 1. 按 FileProperties 构建全局单例 S3Client / S3Presigner（线程安全，可复用）
 * 2. 注入 SeaweedFsClient，业务层只依赖封装类，不感知 AWS SDK
 * <p>
 * 构建要点（与 SeaweedFsClient 类注释第 1 点对应）：
 * - endpointOverride 指向 SeaweedFS Filer S3 网关（如 http://localhost:8333 ）
 * - pathStyleAccessEnabled(true)【必须】：SeaweedFS 不支持虚拟主机风格，筒名放路径中
 * - 静态凭证来自 litisle.file.seaweedfs.access-key / secret-key，
 *   需与 docker/init/seaweedfs/s3.json 保持一致
 * - Region 任意填写（us-east-1），SeaweedFS 不校验 Region，仅 SigV4 签名需要
 *
 * 一句话： 它是“配置 → SDK 对象”的翻译官，也是 SeaweedFsClient 的出生地。
 * application.yml                    FileProperties               SeaweedFsConfig                SeaweedFsClient
 * litisle.file.seaweedfs.*  ──绑定──▶  普通JavaBean          ──读取──▶  构建 AWS SDK 客户端  ──注入──▶  业务代码调 put/get/presign
 * (字符串/数字)                      (endpoint、accessKey...)     (S3Client/S3Presigner Bean)
 *
 */
@Configuration
public class SeaweedFsConfig {

    // ============================== Bean 1 ==============================

    /**
     * 【S3 操作客户端 Bean】
     *
     * <p><b>作用：</b>
     * 创建并暴露一个全局单例的 {@link S3Client}，用于执行所有<b>同步 S3 操作</b>：
     * <ul>
     *   <li>创建桶（createBucket）</li>
     *   <li>上传对象（putObject）</li>
     *   <li>下载对象（getObject）</li>
     *   <li>删除对象 / 桶（deleteObject / deleteBucket）</li>
     *   <li>列举对象（listObjects）</li>
     * </ul>
     *
     * <p><b>为什么是 Bean：</b>
     * <ul>
     *   <li>S3Client 是<b>线程安全</b>的，整个 JVM 只需一个实例</li>
     *   <li>避免每次请求都重新建 TCP / TLS 连接，性能最优</li>
     *   <li>由 Spring 统一管理生命周期（容器关闭时自动 close）</li>
     * </ul>
     *
     * <p><b>关键配置说明：</b>
     * <ul>
     *   <li><b>endpointOverride</b>：强制所有请求发往 SeaweedFS Filer S3 网关
     *       （如 http://localhost:8333），而不是 AWS 公网地址</li>
     *   <li><b>pathStyleAccessEnabled(true)</b>：<u>必须</u>
     *       SeaweedFS 不支持虚拟主机风格访问（bucket.s3.xxx.com），
     *       只能使用路径风格（/bucket/key）</li>
     *   <li><b>Region.US_EAST_1</b>：SeaweedFS 不校验 Region，
     *       仅用于 SigV4 签名计算</li>
     *   <li><b>StaticCredentialsProvider</b>：使用配置文件中的 AK/SK，
     *       与 docker/init/seaweedfs/s3.json 中的账号保持一致</li>
     * </ul>
     *
     * <p><b>使用场景：</b>
     * 业务代码中直接 {@code @Autowired S3Client} 或
     * 通过 {@link SeaweedFsClient} 间接使用。
     */
    @Bean
    public S3Client seaweedS3Client(FileProperties properties) {
        FileProperties.Seaweedfs cfg = properties.getSeaweedfs();
        return S3Client.builder()
                .endpointOverride(URI.create(cfg.getEndpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    // ============================== Bean 2 ==============================

    /**
     * 【S3 预签名 URL 生成器 Bean】
     *
     * <p><b>作用：</b>
     * 创建并暴露一个全局单例的 {@link S3Presigner}，专门用于生成
     * <b>预签名 URL（Presigned URL）</b>，包括：
     * <ul>
     *   <li>预签名 PUT URL：允许前端直传文件到 SeaweedFS，不经过后端中转</li>
     *   <li>预签名 GET URL：生成临时下载链接（可设过期时间）</li>
     *   <li>预签名 DELETE URL：允许前端自行删除对象</li>
     * </ul>
     *
     * <p><b>为什么不能复用 S3Client：</b>
     * <ul>
     *   <li>S3Client 用于<b>执行</b> S3 请求</li>
     *   <li>S3Presigner 用于<b>生成</b>签名 URL，不发起实际请求</li>
     *   <li>AWS SDK 中两者是完全独立的组件</li>
     * </ul>
     *
     * <p><b>关键配置说明：</b>
     * <ul>
     *   <li>endpoint / region / credentials 必须与 S3Client 完全一致，
     *       否则生成的签名 URL 在服务端校验时会 403</li>
     *   <li>pathStyleAccessEnabled(true)：生成的 URL 路径格式必须与 S3Client 一致</li>
     * </ul>
     *
     * <p><b>典型使用场景：</b>
     * <pre>
     * // 生成一个 10 分钟有效的上传 URL
     * PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
     *     .putObjectRequest(req -> req.bucket(bucket).key(key))
     *     .signatureDuration(Duration.ofMinutes(10))
     *     .build();
     * URL url = presigner.presignPutObject(presignRequest).url();
     * </pre>
     */
    @Bean
    public S3Presigner seaweedS3Presigner(FileProperties properties) {
        FileProperties.Seaweedfs cfg = properties.getSeaweedfs();
        return S3Presigner.builder()
                .endpointOverride(URI.create(cfg.getEndpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    // ============================== Bean 3 ==============================

    /**
     * 【SeaweedFS 业务客户端 Bean】
     *
     * <p><b>作用：</b>
     * 将底层 AWS SDK 的 {@link S3Client} 和 {@link S3Presigner}
     * 封装成一个<b>面向业务领域</b>的客户端 {@link SeaweedFsClient}，
     * 作为<b>业务层唯一入口</b>。
     *
     * <p><b>为什么需要这一层封装：</b>
     * <ul>
     *   <li><b>隔离 AWS SDK</b>：业务代码不直接依赖 software.amazon.awssdk，
     *       未来切换存储引擎（MinIO / 阿里云 OSS / 本地磁盘）只改这一层</li>
     *   <li><b>收敛接口</b>：屏蔽 SDK 中复杂、冗余的 Request/Response 对象，
     *       暴露语义清晰的方法如 uploadFile / downloadFile / generateUploadUrl</li>
     *   <li><b>统一异常处理</b>：将 S3Exception 转换为业务自定义异常</li>
     *   <li><b>集中横切逻辑</b>：桶自动创建、key 规范化、日志、监控都在这里做</li>
     * </ul>
     *
     * <p><b>依赖关系：</b>
     * <pre>
     * application.yml
     *       ↓
     * FileProperties（配置绑定）
     *       ↓
     * SeaweedFsConfig（构建 SDK 客户端）
     *       ↓
     * Service / Controller（业务代码）
     * </pre>
     *
     * <p><b>一句话总结：</b>
     * 这个 Bean 是<b>“AWS SDK → 业务逻辑”的适配器</b>，
     * 让上层代码只关心文件业务，不关心 S3 协议细节。
     */
    @Bean
    public SeaweedFsClient seaweedFsClient(S3Client seaweedS3Client, S3Presigner seaweedS3Presigner) {
        return new SeaweedFsClient(seaweedS3Client, seaweedS3Presigner);
    }
}