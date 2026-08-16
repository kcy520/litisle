package com.litisle.commonsecurity;

import com.litisle.commonsecurity.crypto.Aes256GcmCrypto;

public class test {
        public static void main(String[] args) {
            System.out.println(Aes256GcmCrypto.generateKey());
        }
    }

