package com.litisle.litislefile.config;

/**
 * 配置绑定：@ConfigurationProperties(prefix = "litisle.file") + @Component，
 * 对应 application.yml 的 litisle.file 段：
 * - seaweedfs.endpoint：SDK 内部地址（上传/删除/预签名用），如 http://localhost:8333
 * - seaweedfs.publicUrlBase：公开直链拼接基址（给前端用）
 *   ⚠️ 与 endpoint 分离——dev 阶段两者相同（都指向 8333），
 *   接 CDN 后改为 CDN 域名（如 https://img.litisle.com），endpoint 保持内网不变
 * - seaweedfs.accessKey / secretKey / presignExpireMinutes（30 分钟）
 * - seaweedfs.collections（bizType→bucket 映射：avatar/cover/article-img→article/license）
 * - upload.maxImageSize（默认 10485760 = 10MB）
 * - thumbnail.enabled / width(300) / height(300)
 */
public class FileProperties {
}
