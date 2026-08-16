package com.litisle.commonsecurity;

import com.litisle.commonsecurity.crypto.Aes256GcmCrypto;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Slf4j
@SpringBootTest
class CommonSecurityApplicationTests {

    @Test
    void contextLoads() {
    }

    /**
     * 生成 AES-256 密钥（Base64 编码，44 字符）
     * 运行此测试，将控制台输出的密钥复制到 application-local.yaml 中
     */
    @Test
    void generateAesKey() {
        String key = Aes256GcmCrypto.generateKey();
        log.info("========================================");
        log.info("AES-256 Key (Base64, " + key.length() + " chars):{}", key);
        log.info("========================================");
    }

}
