package com.litisle.commonsecurity;

import com.litisle.commonsecurity.crypto.Aes256GcmCrypto;

public class test {
        public static void main(String[] args) {
//            System.out.println(Aes256GcmCrypto.generateKey());

//            public void testEncryptAndDecrypt() {

                        // 1. 生成密钥
                        String base64Key = Aes256GcmCrypto.generateKey();
                        System.out.println("密钥: " + base64Key);

                        // 2. 创建实例
                        Aes256GcmCrypto crypto = new Aes256GcmCrypto(base64Key);

                        // 3. 加密
                        String plainText = "123";
                        String encrypted = crypto.encrypt(plainText);
                        System.out.println("明文: " + plainText);
                        System.out.println("密文: " + encrypted);

                        // 4. 解密
                        String decrypted = crypto.decrypt(encrypted);
                        System.out.println("解密: " + decrypted);

                        // 5. 验证
                        System.out.println("一致: " + plainText.equals(decrypted));
                        System.out.println("matches 匹配: " + crypto.matches("13812345678", encrypted));
                        System.out.println("matches 不匹配: " + crypto.matches("13900000000", encrypted));

        }

}

