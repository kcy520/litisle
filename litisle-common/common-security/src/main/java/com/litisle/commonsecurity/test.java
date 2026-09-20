package com.litisle.commonsecurity;

import com.litisle.commonsecurity.crypto.Aes256GcmCrypto;
import com.litisle.commonsecurity.crypto.HmacSha256Crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class test {
        public static void main(String[] args) {
//            System.out.println(Aes256GcmCrypto.generateKey());

//            public void testEncryptAndDecrypt() {

            /// phone/phone_hash 由 test.genSeedValues() 生成，换密钥需重新生成
                        // 1. 生成密钥
                        String base64Key = Aes256GcmCrypto.generateKey();
                        System.out.println("密钥: " + base64Key);

                        // 2. 创建实例
                        Aes256GcmCrypto crypto = new Aes256GcmCrypto(base64Key);

                        // 3. 加密
                        String plainText = "123";
                        String encrypted = crypto.encrypt(plainText);
                        System.out.println("明文: " + plainText);
                        System.out.println("密文: " + encrypted);//XkZkpb1Qznwy1keeWjqeYKysTiKmQIKwo1wwm1eHhA

                        // 4. 解密
                        String decrypted = crypto.decrypt(encrypted);
                        System.out.println("解密: " + decrypted);

                        // 5. 验证
                        System.out.println("一致: " + plainText.equals(decrypted));
                        System.out.println("matches 匹配: " + crypto.matches("13812345678", encrypted));
                        System.out.println("matches 不匹配: " + crypto.matches("13900000000", encrypted));

                        // 6. 生成种子用户真实 phone_hash / phone 密文（对齐 Sql-Document.md 种子 INSERT）
                        genSeedValues();

        }

        /**
         * 生成种子用户真实 phone_hash 与 phone 密文，替换 Sql-Document.md 中的占位值
         *
         * 推导链（与 CryptoAutoConfiguration 完全一致）：
         * 1. 子密钥 = HMAC-SHA256( AES主密钥, "litisle:hmac-subkey:v1" )   ← deriveSubkey 逻辑
         * 2. phone_hash = HmacSha256Crypto.digest( phone )                  ← 与应用登录/查重同源
         * 3. phone 密文 = Aes256GcmCrypto.encrypt( phone )                  ← [IV|密文+标签] Base64
         *
         * 【注意】MASTER_KEY_B64 必须与本地 yml litisle.security.crypto.key 保持一致且此后不变；
         * 换密钥则所有 hash/密文失配，需重新运行本方法并全量刷新 SQL。
         * 生产库种子数据须用生产密钥（LITISLE_CRYPTO_KEY）重新跑一遍。
         */
        private static void genSeedValues() {
                // 与本地 yml 保持一致（仅本地开发；生产走 LITISLE_CRYPTO_KEY 环境变量注入）
                String masterKeyB64 = "RT6uWj/AaWAJ9/dm3l1aBzEHackhrujBqMeAkiKaaac=";
                byte[] masterKey = Base64.getDecoder().decode(masterKeyB64);

                // 对齐 CryptoAutoConfiguration.deriveSubkey：主密钥对固定标签做一次 HMAC 得子密钥
                byte[] subKeyBytes;
                try {
                        Mac mac = Mac.getInstance("HmacSHA256");
                        mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
                        subKeyBytes = mac.doFinal("litisle:hmac-subkey:v1".getBytes(StandardCharsets.UTF_8));
                } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                        throw new IllegalStateException("HMAC 子密钥派生失败", e);
                }

                HmacSha256Crypto hmac = new HmacSha256Crypto(subKeyBytes);
                // 加密实例也用同一把主密钥，保证种子 phone 密文可被应用解密展示
                Aes256GcmCrypto aes = new Aes256GcmCrypto(masterKeyB64);

                long[] ids = {1001, 1002, 1003, 1004, 1005, 1006};
                String[] phones = {
                        "13800010001", "13800010002", "13800010003",
                        "13800010004", "13800010005", "13800010006"
                };

                System.out.println("==================== 种子数据生成结果（直接替换 Sql-Document.md 对应占位值） ====================");
                for (int i = 0; i < phones.length; i++) {
                        String phoneHash = hmac.digest(phones[i]);
                        String phoneCipher = aes.encrypt(phones[i]);
                        System.out.println("-- id=" + ids[i] + " phone=" + phones[i]);
                        System.out.println("--   phone_hash = '" + phoneHash + "'");
                        System.out.println("--   phone(密文) = '" + phoneCipher + "'");
                }
                System.out.println("==================== 复制以上 hash/密文替换 SQL 后，种子账号即可走真实登录链路 ====================");
        }

}

