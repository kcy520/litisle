package com.litisle.litislefile.thumbnail;

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
public class ThumbnailGenerator {
}
