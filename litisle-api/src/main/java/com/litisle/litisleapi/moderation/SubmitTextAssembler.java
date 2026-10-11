package com.litisle.litisleapi.moderation;

import com.litisle.litisleapi.constant.ModerationConstant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 送审文本组装与内容指纹口径（板块四 4-1.2 / Review-Plan §4.2.1 ①′）——口径实现（契约库 SSOT）。
 *
 * 内容审核（Moderation）模块里的“送审文本组装 + 内容指纹计算”工具类
 *
 * 【口径 SSOT】
 * 1. 送审文本 = 标题 + {@link ModerationConstant#TEXT_SUBMIT_SEPARATOR} + 正文
 *    （title 为 null/空串/纯空白时仅取正文；DFA、LLM、content_hash 三者同口径）；
 * 2. content_hash = 送审文本的 SHA-256（小写十六进制）——标题必须一并参与拼接，
 *    防「违规藏标题」只查正文躲过 LLM 漏审；hash 口径变更须同步 review 侧 TEXT_RESULT_KEY 语义（§2.11）；
 * 3. 消费侧对齐：review 侧 ModerationTextService#fetchText 回查后按同口径拼接待审文本，
 *    两侧禁止各自演化（hash 错配会被消费端 ACK 丢弃，见 3-1.4）。
 *
 * 【调用时机】必须晚于 PublishTextPrecheck.check（空内容校验先行，防恒定 hash 污染免审缓存），
 * 早于 TextSubmitEvent 构建（事件体 contentHash 字段取本类产物）。
 *
 * @author litisle
 */
public final class SubmitTextAssembler {

    private SubmitTextAssembler() {
    }

    /**
     * 组装送审文本：标题 + 分隔符 + 正文（title 为 null/空串/纯空白时仅取正文）。
     * DFA、LLM、content_hash 三者同口径；标题必须一并参与，防违规藏标题躲过 LLM 漏审。
     *
     * @param title 标题（可为 null/空，等价于仅正文）
     * @param body  正文/私信内容/用户名（可为 null，按空串处理）
     * @return 送审文本（DFA 与 LLM 同口径输入）
     */
    public static String assemble(String title, String body) {
        // 步骤1：标题空判定与消费侧 fetchText 口径一致（hasText 语义：null/空白均视为无标题）
        if (title == null || title.trim().isEmpty()) {
            return body == null ? "" : body;
        }
        // 步骤2：标题 + 分隔符 + 正文（分隔符 SSOT 复用 ModerationConstant，禁硬编码）
        return title + ModerationConstant.TEXT_SUBMIT_SEPARATOR + (body == null ? "" : body);
    }

    /**
     * 计算内容指纹：SHA-256(送审文本)，小写十六进制。
     *
     * @param assembledText assemble 产物，禁止传入未拼接的原始正文（防口径错位）
     * @return 64 位小写十六进制 contentHash
     */
    public static String contentHash(String assembledText) {
        try {
            // 步骤1：SHA-256 摘要（UTF-8 编码，跨服务字节口径一致）
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(assembledText.getBytes(StandardCharsets.UTF_8));
            // 步骤2：字节转小写十六进制（与 review 侧 ModerationImageService#sha256Hex 口径一致）
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必带 SHA-256，理论不可达；防御性 fail-fast 防静默漏审
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        }
    }
}
