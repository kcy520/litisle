package com.litisle.litislefile.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * 文件头魔数（magic bytes）校验：防「改扩展名 + 伪造 Content-Type」绕过 MIME 白名单
 * （如 .exe 改名 .jpg 且伪造请求头，仅比对声明类型无法识别）。
 * <p>只读取文件头 {@link #HEADER_LENGTH} 字节，不整份读入内存；由上传校验在
 * MIME 白名单之后调用，真实魔数与声明类型不符即拒绝（FILE_TYPE_NOT_ALLOWED）。</p>
 */
public final class FileMagicValidator {

/// 魔数（Magic Number / Magic Bytes），在文件处理语境下，指的是文件头部的一段固定字节序列，用来唯一标识文件的真实格式。
/// 它不依赖扩展名，也不依赖 HTTP 的 Content-Type，而是直接“看文件本身长什么样”。
/// 魔数 = 文件的“身份证号”，写在文件最开头，改扩展名改不了它。
/// 为什么需要魔数校验？
/// 结论：魔数是防止“扩展名 + Content-Type 双伪造”的最后一道防线。


    /** 私有构造：工具类仅提供静态方法，禁止实例化 */
    private FileMagicValidator() {
    }

    /** 需要读取的最大文件头长度（WEBP 的 WEBP 标记位于 offset 8~12） */
    private static final int HEADER_LENGTH = 12;

    /** 声明 MIME → 可接受的「真实 MIME」集合（docx 为 zip 容器，故与 zip 互相接受） */
    private static final Map<String, Set<String>> ACCEPTED_REAL_MIME = Map.of(
            "image/jpeg", Set.of("image/jpeg"),
            "image/png", Set.of("image/png"),
            "image/gif", Set.of("image/gif"),
            "image/webp", Set.of("image/webp"),
            "application/pdf", Set.of("application/pdf"),
            "application/msword", Set.of("application/msword"),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of("application/zip"),
            "application/zip", Set.of("application/zip"));

    /**
     * 校验文件真实魔数与声明 MIME 是否一致
     * <p>【为什么】仅比对声明 Content-Type 可被伪造（改扩展名 + 伪造请求头即可绕过白名单），
     * 必须回看文件自身字节，确认真实格式与声明一致，才能挡住伪装文件。</p>
     *
     * @param file         待校验文件
     * @param declaredMime 声明的 Content-Type
     * @return true=一致；false=不一致，或声明类型无对应魔数规则（均交由调用方拒绝）
     */
    public static boolean matches(MultipartFile file, String declaredMime) {
        // 步骤1：声明类型为空则无法比对，直接判不一致
        if (declaredMime == null) {
            return false;
        }
        // 步骤2：取声明类型对应的「可接受真实 MIME」集合（忽略大小写）
        Set<String> accepted = ACCEPTED_REAL_MIME.get(declaredMime.toLowerCase());
        // 步骤3：无对应规则（非白名单类型）→ 无法确认，判不一致
        if (accepted == null) {
            return false;
        }
        // 步骤4：读取文件头 → 识别真实 MIME → 判断是否落在可接受集合内
        return accepted.contains(detectMime(readHeader(file)));
    }

    /**
     * 读取文件头（用于魔数识别）
     * <p>【为什么】魔数只位于文件开头，读固定长度即可判定，无需整份读入内存（大文件下防 OOM）。</p>
     * <p>只取前 {@link #HEADER_LENGTH} 字节，避免整份文件读入内存；流用 try-with-resources 自动关闭。</p>
     *
     * @param file 待读取文件
     * @return 文件头字节数组；读取异常时返回空数组（空数组匹配不到任何魔数 → 交由调用方拒绝）
     */
    private static byte[] readHeader(MultipartFile file) {
        // 打开输入流并只读文件头固定长度字节（readNBytes 不要求读满，文件更短时返回实际长度）
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEADER_LENGTH);
        } catch (IOException e) {
            // 读取失败：返回空数组，后续 detectMime 必然返回 null → 判定为不一致（fail-safe）
            return new byte[0];
        }
    }

    /**
     * 按魔数识别文件真实 MIME（与扩展名、Content-Type 无关）
     * <p>【为什么】文件真实格式由头部的固定字节序列（魔数）唯一决定，扩展名与 Content-Type 均可伪造，
     * 因此必须按字节签名识别，而不能信任调用方给出的类型。</p>
     * <p>逐个比对已知格式的文件头签名，命中即返回对应 MIME；全部不命中返回 null。</p>
     *
     * @param h 文件头字节数组
     * @return 识别出的真实 MIME；无法识别返回 null
     */
    private static String detectMime(byte[] h) {
        // JPEG：文件头固定 FF D8 FF
        if (startsWith(h, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        // PNG：8 字节固定签名 89 50 4E 47 0D 0A 1A 0A
        if (startsWith(h, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        // GIF：ASCII 头 "GIF87a" 或 "GIF89a"
        if (startsWith(h, "GIF87a") || startsWith(h, "GIF89a")) {
            return "image/gif";
        }
        // WEBP：RIFF 容器（前 4 字节 "RIFF"）+ offset 8 处 "WEBP" 双条件；
        // 仅 "RIFF" 也是 AVI/WAV 的签名，必须联合校验才不误判
        if (startsWith(h, "RIFF") && matchesAt(h, 8, "WEBP")) {
            return "image/webp";
        }
        // PDF：ASCII 头 "%PDF"
        if (startsWith(h, "%PDF")) {
            return "application/pdf";
        }
        // 旧版 Word(.doc)：OLE2 复合文档签名 D0 CF 11 E0 A1 B1 1A E1
        if (startsWith(h, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
            return "application/msword";
        }
        // ZIP 容器：PK\x03\x04（.zip 与 .docx 共用此签名，故统一识别为 application/zip）
        if (startsWith(h, 0x50, 0x4B, 0x03, 0x04)) {
            return "application/zip";
        }
        // 未知格式：无法识别，返回 null
        return null;
    }

    /**
     * 判断字节数组是否以指定「数值型」前缀开头（用于 JPEG/PNG/OLE2/ZIP 等含不可打印字节的签名）
     * <p>【为什么】这些格式的签名含不可打印字节，用十六进制 int 字面量书写最直观；
     * 统一封装「长度校验 + 逐字节无符号比对」，避免各处重复。</p>
     *
     * @param data   待判断的文件头
     * @param prefix 期望的前缀字节（int 便于直接书写 0xFF 等十六进制字面量）
     * @return true=以该前缀开头
     */
    private static boolean startsWith(byte[] data, int... prefix) {
        // 长度不足必然不匹配
        if (data.length < prefix.length) {
            return false;
        }
        // 逐字节比对；int 与 byte 运算前先 & 0xFF 还原为无符号值，避免负数字节比较错误
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断字节数组是否以指定「ASCII 文本」前缀开头（用于 GIF/RIFF/%PDF 等可读签名）
     * <p>【为什么】这些格式的签名是纯 ASCII，用字符串书写比 int 数组更可读，故单独提供重载复用定点比对。</p>
     *
     * @param data   待判断的文件头
     * @param prefix 期望的 ASCII 前缀字符串
     * @return true=以该前缀开头
     */
    private static boolean startsWith(byte[] data, String prefix) {
        // 等价于从 offset 0 开始的定点比对
        return matchesAt(data, 0, prefix);
    }

    /**
     * 判断字节数组在指定偏移处是否匹配给定 ASCII 文本（WEBP 校验 offset 8 处 "WEBP" 用）
     * <p>【为什么】并非所有签名都在文件最开头（如 WEBP 的 "WEBP" 位于 offset 8），
     * 需支持带偏移的定点比对；同时作为上述两个 startsWith 的公共底座。</p>
     *
     * @param data   待判断的文件头
     * @param offset 起始偏移（从 0 开始）
     * @param ascii  期望的 ASCII 文本
     * @return true=该偏移处完全匹配
     */
    private static boolean matchesAt(byte[] data, int offset, String ascii) {
        // 剩余长度不足以容纳该文本则不匹配
        if (data.length < offset + ascii.length()) {
            return false;
        }
        // 逐字符比对（char 强转 byte，仅 ASCII 场景使用）
        for (int i = 0; i < ascii.length(); i++) {
            if (data[offset + i] != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
