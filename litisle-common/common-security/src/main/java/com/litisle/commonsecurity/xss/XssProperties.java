package com.litisle.commonsecurity.xss;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * XSS 过滤配置类（yml + JavaBean 绑定）
 *
 * 【设计说明】
 * 1. 读取 yml 中 litisle.xss.* 的配置
 * 2. 支持全局开关和排除路径（富文本接口需排除）
 * 3. 配合 @ConfigurationProperties 实现松散绑定和热更新
 *
 * 【yml 配置示例】
 * litisle:
 *   xss:
 *     enabled: true                    # 全局开关
 *     exclude-paths:                   # 排除路径（富文本接口）
 *       - /api/content/article/publish # 文章发布（需保留 HTML）
 *       - /api/content/comment/rich    # 富文本评论
 *
 * 【富文本处理策略】
 * - 普通接口：XssFilter 清除所有 HTML 标签（防 XSS）
 * - 富文本接口：走 exclude-paths，XssFilter 不处理
 *   由业务层用白名单方式清洗（如只保留 p/img/strong，移除 script/onclick）
 *   推荐使用 OWASP Java HTML Sanitizer 或 Jsoup 的 Cleaner
 *
 * @author litisle
 */
@Data
@Component
@ConfigurationProperties(prefix = "litisle.xss")
public class XssProperties {

    /**
     * 是否启用 XSS 过滤（默认 true）
     *
     * 【场景】
     * - true：全局开启，所有请求（除排除路径）都清洗
     * - false：全局关闭，XssFilter 不注册
     */
    private boolean enabled = true;

    /**
     * 排除路径列表（不进行 XSS 清洗）
     *
     * 【说明】
     * - 支持 Ant 风格通配符（?、*、**）
     * - 富文本编辑器接口必须排除，否则 HTML 标签会被清除
     * - 排除后，业务层需自行用白名单清洗（如 Jsoup）
     *
     * 【典型场景】
     * - 文章发布/编辑：正文含 <p><img><strong> 等合法标签
     * - 富文本评论：允许有限 HTML 标签
     */
    private List<String> excludePaths = new ArrayList<>();
}