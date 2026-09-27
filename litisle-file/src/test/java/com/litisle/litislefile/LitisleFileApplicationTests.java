package com.litisle.litislefile;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@SpringBootTest
class LitisleFileApplicationTests {

    /** P0 实测：SeaweedFS 是否比对 x-amz-checksum-sha256（结论回填 SeaweedFsClient#CHECKSUM_MISMATCH_CODES） */
    private static final String ENDPOINT = "http://localhost:8333";
    private static final String ACCESS_KEY = System.getenv().getOrDefault("LITISLE_S3_ACCESS_KEY", "litisle");
    private static final String SECRET_KEY = System.getenv().getOrDefault("LITISLE_S3_SECRET_KEY", "litisle_s3_pass");
    private static final String BUCKET = "avatar";
    /** 32 字节全 0 的 SHA256(Base64)，必然与真实内容不符 */
    private static final String WRONG_SHA256_BASE64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void contextLoads() {
    }

    /** probe1：手写错误校验头（RequestOverrideConfiguration） */
    @Test
    void probe1_手写错误校验头() {
        S3Client s3 = buildClient();
        String key = "probe/checksum-raw-" + UUID.randomUUID() + ".txt";
        PutObjectRequest req = PutObjectRequest.builder().bucket(BUCKET).key(key).contentType("text/plain")
                .overrideConfiguration(o -> o.putHeader("x-amz-checksum-sha256", WRONG_SHA256_BASE64))
                .build();
        doProbe("probe1-手写头", s3, req, key);
    }

    /** probe2：SDK 原生 checksumSHA256(错误值)——与生产 5 参 put 同路径，结论最可信 */
    @Test
    void probe2_原生错误校验值() {
        S3Client s3 = buildClient();
        String key = "probe/checksum-native-" + UUID.randomUUID() + ".txt";
        PutObjectRequest req = PutObjectRequest.builder().bucket(BUCKET).key(key).contentType("text/plain")
                .checksumSHA256(WRONG_SHA256_BASE64)
                .build();
        doProbe("probe2-原生错误值", s3, req, key);
    }

    /** probe3：正确摘要对照，确认桶可写、链路正常 */
    @Test
    void probe3_正确摘要对照() {
        S3Client s3 = buildClient();
        String key = "probe/checksum-ok-" + UUID.randomUUID() + ".txt";
        PutObjectRequest req = PutObjectRequest.builder().bucket(BUCKET).key(key).contentType("text/plain")
                .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                .build();
        doProbe("probe3-正确对照", s3, req, key);
    }

    /** put → 打印 errorCode → headObject 判定是否落盘 → 清理 */
    private void doProbe(String tag, S3Client s3, PutObjectRequest req, String key) {
        System.out.println("\n==== " + tag + " ====");
        try {
            s3.putObject(req, RequestBody.fromBytes("litisle checksum probe".getBytes(StandardCharsets.UTF_8)));
            System.out.println("[" + tag + "] put 成功(200)：网关未因校验失败拒收");
        } catch (S3Exception e) {
            System.out.println("[" + tag + "] S3Exception! status=" + e.statusCode()
                    + " errorCode=" + (e.awsErrorDetails() == null ? "null" : e.awsErrorDetails().errorCode())
                    + " msg=" + (e.awsErrorDetails() == null ? "null" : e.awsErrorDetails().errorMessage()));
        } catch (SdkException e) {
            System.out.println("[" + tag + "] SdkException(可能未发出请求)：" + e.getMessage());
        } finally {
            boolean exists = exists(s3, key);
            System.out.println("[" + tag + "] 对象是否落盘=" + exists
                    + (exists ? " → 网关未拦下错误摘要" : " → 网关拦下并拒绝写入"));
            cleanup(s3, key);
        }
    }

    private boolean exists(S3Client s3, String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
            return true;
        } catch (SdkException e) {
            return false;
        }
    }

    private void cleanup(S3Client s3, String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).build());
        } catch (SdkException ignored) {
        }
    }

    private S3Client buildClient() {
        return S3Client.builder()
                .endpointOverride(URI.create(ENDPOINT))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
