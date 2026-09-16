package com.litisle.litislefile.enums;

/**
 * 正文图片对齐枚举：LEFT(1)/LEFT_CENTER(2)/CENTER(3)/RIGHT_CENTER(4)/RIGHT(5)
 *
 * 归属约定（跨模块）：file_meta.align 仅作文件维度冗余，
 * 权威来源是 content 库 article_image.align（fileId + position_order + align），
 * 正文 HTML 只存 {img:file_id} 占位符，渲染时按 position_order 替换
 */
public enum AlignEnum {
}
