package com.litisle.litislecontent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * content 模块配置绑定（对齐 file 模块 FileProperties 模式：@Component + @ConfigurationProperties）：
 *
 * 【绑定前缀】litisle.content（application.yml 本地默认值兜底，环境特定值经 Nacos litisle-content.yaml 覆盖）
 *
 * @author litisle
 */
@Data
@Component
@ConfigurationProperties(prefix = "litisle.content")
public class ContentProperties {

    /** 发布域配置（§6.3 批量发布口径） */
    private Publish publish = new Publish();

    /**
     * 发布域嵌套配置
     */
    @Data
    public static class Publish {

        /** 批量发布单批上限（超限抛 ARTICLE_BATCH_EXCEEDED——防批量刷审，§6.3） */
        private int batchMax = 20;
    }
}
