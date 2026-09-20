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

    @Bean
    public SeaweedFsClient seaweedFsClient(S3Client seaweedS3Client, S3Presigner seaweedS3Presigner) {
        return new SeaweedFsClient(seaweedS3Client, seaweedS3Presigner);
    }
}