package com.litisle.litislefile.config;

/**
 * 配置绑定：@ConfigurationProperties(prefix = "litisle.file") + @Component，
 * 对应 application.yml 的 litisle.file 段：
 * - seaweedfs.endpoint / accessKey / secretKey / presignExpireMinutes（30 分钟）
 * - seaweedfs.collections（bizType→bucket 映射：avatar/cover/article-img→article/license）
 * - upload.maxImageSize（默认 10485760 = 10MB）
 * - thumbnail.enabled / width(300) / height(300)
 */
public class FileProperties {
}
