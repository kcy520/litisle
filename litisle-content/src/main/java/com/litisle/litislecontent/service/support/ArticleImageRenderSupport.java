package com.litisle.litislecontent.service.support;

import com.litisle.litisleapi.feign.FileClient;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.entity.ArticleImage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * 正文配图占位符渲染支撑组件（文章详情/草稿详情共用，消除双份重复实现）
 * <p>图片元数据（file_id/对齐/顺序）单独存 article_image 表，正文只存 {img:file_id} 占位符；
 * 渲染链路：查配图 → Feign 批量换 URL（防 N+1）→ 占位符替换为带对齐 class 的 &lt;img&gt;</p>
 *
 * @author litisle
 */
@Component
@RequiredArgsConstructor
public class ArticleImageRenderSupport {

    /** 文件服务客户端（fileId → 访问 URL 批量换取，防 N+1） */
    private final FileClient fileClient;

    /**
     * 占位符渲染：{img:file_id} → 带 align class 的 <img>（对齐权威=article_image.align）
     * 未知图（无记录/无 URL）保留占位符降级，不破坏正文结构
     *
     * @param html   净化后正文（含占位符）
     * @param images 配图列表（文章/草稿共用，提供顺序号/对齐权威）
     * @return 渲染后正文（URL 已转义防属性注入）
     */
    public String renderImages(String html, List<ArticleImage> images) {

// 正文中本身允许存储渲染标签，但只允许白名单标签进入存储（重建为无属性纯净标签）；
// 图片元数据（file_id/对齐/顺序）单独存 article_image 表，正文只存 {img:file_id} 占位符

// getDetail 可见性判定通过
//    ├─ ① 查 article：content_html（净化后正文，含 <p><h2>… 与 {img:file_id} 占位符）
//    ├─ ② 查 article_image：这篇文的配图列表（fileId / position_order / align）
//    ├─ ③ renderImages()：只处理占位符，其余 HTML 原样透传
//    │      - Feign 批量换 URL（fileClient.batchGetUrls，防 N+1）
//    │      - {img:xxx} → <img class="li-img-left" src="…">；查不到记录/URL 的占位符原样保留降级
//    └─ ④ ArticleDetailVO 返回给前端 → 前端把 content_html 直接塞进文章容器 DOM

        // 步骤1：Feign 批量换 URL（fileClient.batchGetUrls，防 N+1）
        List<String> fileIds = images.stream().map(ArticleImage::getFileId).toList();
        //返回 fileId → 访问URL 映射
        Map<String, String> urlMap = fileClient.batchGetUrls(fileIds).getData();
        if (urlMap == null) {
            urlMap = Collections.emptyMap();
        }

        // 步骤2：逐个占位符替换为带对齐 class 的 <img>；未知图（无记录/无 URL）保留占位符降级
        //把文章 HTML 里的图片占位符（如 {{img:fileId}}），替换成带对齐样式、安全转义后的 <img> 标签；如果图片数据不完整，则保留原占位符，避免页面渲染异常。
        // 用正则匹配 HTML 中的图片占位符（如 {{img:fileId}}）
        //把正则表达式绑定到一个具体的输入字符串上，返回一个 Matcher，用来对这个字符串做查找、替换、捕获等操作。
        Matcher matcher = ContentBizConstant.IMG_PLACEHOLDER_PATTERN.matcher(html);

        // 用 StringBuilder 重新拼接处理后的 HTML（初始容量设为原 html 长度，减少扩容）
        StringBuilder sb = new StringBuilder(html.length());

        // 循环查找每一个图片占位符
        //find() 的作用是：从当前位置开始，在字符串中「找下一个」匹配正则的子串。 每次调用都会从上一次结束的位置继续往后找
        while (matcher.find()) {

            // 取出正则中捕获组 1 的内容，即 fileId
            String fileId = matcher.group(1);

            // 从配图列表中，找到与 fileId 匹配的那张图片
            ArticleImage img = images.stream()
                    .filter(i -> fileId.equals(i.getFileId()))
                    .findFirst()
                    .orElse(null);

            // 从 URL 缓存/映射中获取该图片的真实访问地址
            String url = urlMap.get(fileId);

            // 最终要替换占位符的 HTML 片段
            String replacement;

            // 如果图片不存在 / 对齐方式未设置 / URL 不存在或为空
            // 👉 说明数据不完整，不能渲染成 <img>，保留原占位符
            if (img == null || img.getAlign() == null || url == null || url.isEmpty()) {

                // Matcher.quoteReplacement 用于转义 $ 和 \，防止 appendReplacement 解析异常
                //把字符串转义成“安全的替换字符串”，防止 $ 和 \` 被appendReplacement()` 当成特殊语法解析。
                // $ → \$     \` →\`

                replacement = Matcher.quoteReplacement(matcher.group(0));

            } else {
                // 对 URL 做安全处理：
                // - & 转义为 &amp;（防止破坏 HTML 属性）
                // - " 转义为 %22（防止属性提前闭合）
                String safeUrl = url.replace("&", "&amp;").replace("\"", "%22");

                // 拼接最终渲染的 <img> 标签
                // class 示例：rendered-img-left / rendered-img-center / rendered-img-right
                replacement = "<img class=\"" + ContentBizConstant.RENDERED_IMG_CLASS_PREFIX
                        + img.getAlign().name().toLowerCase() + "\" src=\"" + safeUrl + "\" alt=\"\">";
            }

            // 将匹配位置之前的内容 + 替换后的内容，追加到 StringBuilder
            matcher.appendReplacement(sb, replacement);
        }

        // 将最后一个匹配之后的剩余 HTML 内容追加到结果中
        //因为:appendReplacement() 只负责处理“匹配位置之前 + 本次替换内容”，它不会处理最后一次匹配之后的尾巴。
        matcher.appendTail(sb);//把「最后一次匹配之后」到「字符串末尾」的剩余原文，追加到 sb 里。

        // 返回最终渲染完成的 HTML
        return sb.toString();
    }
}