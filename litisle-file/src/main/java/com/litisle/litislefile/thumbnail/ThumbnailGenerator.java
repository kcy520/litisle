package com.litisle.litislefile.thumbnail;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislefile.client.SeaweedFsClient;
import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 缩略图生成器（依赖 Thumbnailator，需在 pom 补 net.coobird:thumbnailator）。
 *
 * 实现思路（路径约定派生，不落库 —— File-Architecture.md §4.1 设计要点 5）：
 * 1. 派生 key：原图 path + "_thumb_{w}_{h}"（如 /avatar/1001.jpg → /avatar/1001.jpg_thumb_300_300）
 * 2. head 探测 SeaweedFS 是否已存在 → 有则直接返回直链
 * 3. 没有 → get 原图 → Thumbnails.resize(w,h) → put 回同 bucket → 返回 URL
 * 4. 并发重复生成无妨（put 幂等），不加锁
 * 5. 未来若改异步生成，file_meta 补 thumb_status 一列标记状态即可
 */
@Component
@RequiredArgsConstructor
public class ThumbnailGenerator {

    private final SeaweedFsClient seaweedFsClient;

    /**
     * 派生缩略图 key：原图 path + "_thumb_{w}_{h}"
     * <p>路径约定派生，不落库（File-Architecture.md §4.1 设计要点 5）</p>
     */
    public String deriveKey(String path, int width, int height) {
        return path + "_thumb_" + width + "_" + height;
    }

    /**
     * 获取或按需生成缩略图，返回缩略图 key
     * <p>1. head 探测 SeaweedFS 已存在 → 直接返回派生 key</p>
     * <p>2. 不存在 → get 原图 → Thumbnails.resize → put 回同 bucket → 返回派生 key</p>
     * <p>3. 并发重复生成无妨（put 幂等，同 key 覆盖），不加锁</p>
     */
    public String getOrCreate(String bucket, String path, int width, int height) {
        // 派生 key：原图 path + "_thumb_{w}_{h}"（纯路径约定，不落库）
        String thumbKey = deriveKey(path, width, height);

        // 已存在则直接返回（head 探测，避免重复生成 IO 开销）
        if (seaweedFsClient.exists(bucket, thumbKey)) {
            return thumbKey;
        }

        // 拉取原图 → 等比缩放至 w×h 内 → put 回同 bucket
        // 拉取原图字节（get 幂等读，仅小文件场景）
        byte[] origin = seaweedFsClient.get(bucket, path);
        try (ByteArrayInputStream in = new ByteArrayInputStream(origin)) {
            // Thumbnailator 等比缩放至 w×h 以内（不拉伸变形，留原始比例）
            BufferedImage thumbnail = Thumbnails.of(in)
                    .size(width, height)
                    .asBufferedImage();
            // 编码为 JPEG 写入内存缓冲区
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(thumbnail, "jpg", out);
            // put 回同 bucket（同 key 覆盖幂等，并发重复生成安全，无需加锁）
            seaweedFsClient.put(bucket, thumbKey, out.toByteArray(), "image/jpeg");
            return thumbKey;
        } catch (IOException e) {
            // 生成/编码失败统一转业务异常（fail-fast，下次请求重新生成）
            throw new BizException(ErrorCode.FILE_STORAGE_UNAVAILABLE, "缩略图生成失败", e);
        }
    }
}
