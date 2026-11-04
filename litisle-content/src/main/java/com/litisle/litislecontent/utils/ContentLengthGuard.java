package com.litisle.litislecontent.utils;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislecontent.constant.ContentLimitConstant;

import java.nio.charset.StandardCharsets;

/**
 * 正文长度硬上限校验工具（防滥用兜底）。
 * <p>作用：content_html 在数据库里是 LONGTEXT 类型、本身没有长度限制，
 * 如果有人提交超大正文，会把送审回查、搜索索引同步、AI 知识库摄入等下游链路拖垮。
 * 本类在应用层统一拦截：正文（UTF-8 编码后）超过 256KB 就拒绝保存，
 * 错误信息里带上当前长度和上限，并提示"超长篇内容建议拆成书籍章节发布"
 * （书籍每章独立保存、独立过机审，天然承载长篇创作）。</p>
 * <p>使用方：发布（createAndPublish）、建档（saveDraftRow）、编辑（update）、草稿保存（DraftService.save）
 * 四条写正文路径共用同一上限，防止绕过发布口从其他路径灌入大内容。</p>
 *
 * @author litisle
 */
public final class ContentLengthGuard {

    /** 私有构造：工具类禁止实例化 */
    private ContentLengthGuard() {
    }

    /**
     * 校验正文字节数不超上限，超限抛 CONTENT_TOO_LONG（错误信息含当前长度/上限 + 书籍拆分引导）
     *
     * @param contentHtml 正文 HTML（null 视为无正文直接放行；是否为空的业务校验由调用方负责）
     */
    public static void checkContentLength(String contentHtml) {
        // 无正文直接放行（纯图文章/空正文场景，非空校验归 PublishTextPrecheck 等既有链路）
        if (contentHtml == null) {
            return;
        }
        // 按 UTF-8 编码统计字节数（与 MySQL LONGTEXT 存储字节数同口径）
        int bytes = contentHtml.getBytes(StandardCharsets.UTF_8).length;
        // 超上限拒绝：错误信息提示当前长度与上限，并软引导拆分书籍章节（不做自动拆分——机器拆分会破坏内容语义）
        if (bytes > ContentLimitConstant.ARTICLE_CONTENT_MAX_BYTES) {
            throw new BizException(ErrorCode.CONTENT_TOO_LONG,
                    "正文内容过长（当前 " + bytes + " 字节，上限 "
                            + ContentLimitConstant.ARTICLE_CONTENT_MAX_BYTES + " 字节）；"
                            + "建议作为书籍拆分章节发布，每章独立创作与审核");
        }
    }
}
