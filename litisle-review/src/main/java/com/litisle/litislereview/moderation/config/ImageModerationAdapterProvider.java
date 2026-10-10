package com.litisle.litislereview.moderation.config;

import com.litisle.litislereview.moderation.adapter.OnnxImageModerationAdapter;
import com.litisle.litislereview.moderation.spi.ContentModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 图片机审适配器解析器（解析逻辑收敛单点，业务类只注入本组件，勿再复制 ObjectProvider/类型筛选细节）
 *
 * <p>【为何存在】image-provider=none（dev/local）时适配器 Bean 不创建，required 注入会启动即崩
 * （早于启动自检器规则④「none 放行」执行，显式禁用反而起不来）；故以 ObjectProvider 惰性持有，
 * 启动期仅 WARN，缺失延迟到消费期暴露</p>
 *
 * <p>【失败语义】none/缺失 → IllegalStateException → Consumer nack 入 DLQ 延迟重试，
 * 运维配置好 provider 重启后消息可正常重试，不丢不漏</p>
 *
 * @author litisle
 */
@Component
@RequiredArgsConstructor
public class ImageModerationAdapterProvider {

    /** SPI 适配器惰性持有者（none 时适配器 Bean 不创建，启动期放行） */
    private final ObjectProvider<ContentModerationService> contentModerationProvider;
    /** 机审可调参数（读 image-provider 供禁用态报错定位） */
    private final ModerationProperties moderationProperties;

    /**
     * 解析图片机审 SPI 适配器（消费期惰性解析）。
     *
     * <p>【为何要类型筛选】text/image 两适配器可同时激活（同为实现 SPI 接口的多 Bean），
     * ObjectProvider 直接 getObject 会因多候选抛 NoUniqueBeanDefinitionException，
     * 故按图片适配器类型精确筛选；none 时无候选→orElseThrow 走延迟重试分支</p>
     *
     * @return 图片机审适配器
     */
    public ContentModerationService resolve() {
        return contentModerationProvider.stream()
                .filter(OnnxImageModerationAdapter.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("图片机审适配器未激活（image-provider="
                        + moderationProperties.getImageProvider() + "），延迟重试等待 provider 配置就绪"));
    }
}
