package com.litisle.litislereview.moderation.config;


import com.litisle.litislereview.moderation.adapter.LlmTextModerationAdapter;
import com.litisle.litislereview.moderation.adapter.OnnxImageModerationAdapter;
import com.litisle.litislereview.moderation.spi.ContentModerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 机审 provider 启动自检（§4.3 fail-fast）
 *
 * <p>【检查时机】SmartInitializingSingleton：全部单例 Bean 就绪后、对外服务前执行，
 * 任何检查不过即抛异常中断启动——@ConditionalOnProperty 拼写错误导致的「适配器未激活、
 * 机审静默漏审」在启动期暴露，而非运行期空转</p>
 *
 * <p>【检查规则（宁严勿松）】
 * ① 未配置（blank）→ 启动失败：机审未激活 = 内容全部滞留待审态，属配置缺失不是合法状态；
 * ② 值不在注册表 → 启动失败：防拼写错误静默绕过；
 * ③ 值合法但对应适配器 Bean 不存在 → 启动失败：provider 值与 @ConditionalOnProperty 错配；
 * ④ 显式 none → 放行但强告警：唯一合法的「明确禁用」通道——确需跳过机审须显式声明并
 *    承担内容全走人审/滞留待审的代价，杜绝隐式漏审</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationProviderStartupChecker implements SmartInitializingSingleton {

    /** 规则④特判字面量：显式禁用的唯一合法通道（WARN 强告警后放行，代价是内容全滞留待审/走人审） */
    private static final String PROVIDER_NONE = "none";
    /** 文本侧「配置值 → 适配器类」静态注册表（自检规则②③的比对基准；二期扩 provider 在此加一行映射） */
    private static final Map<String, Class<? extends ContentModerationService>> TEXT_PROVIDERS =
            Map.of("llm", LlmTextModerationAdapter.class);
    /** 图片侧「配置值 → 适配器类」静态注册表（value 存 Class 而非 Bean 实例：注册表须编译期静态可构建，运行期再经容器探测该类 Bean 是否真被条件装配激活） */
    private static final Map<String, Class<? extends ContentModerationService>> IMAGE_PROVIDERS =
            Map.of("onnx", OnnxImageModerationAdapter.class);

    /** 规则③探测器：getBeansOfType 反查容器，验证适配器 Bean 真被 @ConditionalOnProperty 激活（区分「值合法但未激活」与「Bean 正常存在」） */
    private final ApplicationContext applicationContext;
    /** 读取 litisle.moderation.text-provider / image-provider 实际配置值 */
    private final ModerationProperties properties;

    @Override
    public void afterSingletonsInstantiated() {
        String activeText = check("text", properties.getTextProvider(), TEXT_PROVIDERS);
        String activeImage = check("image", properties.getImageProvider(), IMAGE_PROVIDERS);
        log.info("机审 provider 启动自检通过：text-provider={}, image-provider={}", activeText, activeImage);
    }

    /**
     * 单侧校验：blank / 未知值 / Bean 缺失均中断启动，显式 none 强告警放行
     *
     * <p>【执行时机】SmartInitializingSingleton 回调（全部单例 Bean 就绪后、对外服务前）：
     * 晚于 Bean 创建才能经 getBeansOfType 拿到条件装配的最终结果（太早探测有假阴性）；
     * 早于端口开放才能 fail-fast 中断启动，把 @ConditionalOnProperty「拼错即静默不激活」
     * 的失败模式从运行期静默漏审前移到启动期暴露（配置防呆）</p>
     *
     * @param capability 能力侧别名（"text"/"image"），仅用于拼配置 key 与错误信息可读化
     * @param provider   该侧 yml 实际配置值（litisle.moderation.{capability}-provider）
     * @param registry   该侧「配置值 → 适配器类」静态注册表（TEXT_PROVIDERS/IMAGE_PROVIDERS）
     * @return 校验通过的 provider 原值（仅供外层打印自检通过日志）
     */
    private String check(String capability, String provider,
                         Map<String, Class<? extends ContentModerationService>> registry) {
        String key = "litisle.moderation." + capability + "-provider";
        // 规则①：未配置（blank）→ 启动失败——机审未激活 = 内容全部滞留待审，属配置缺失不是合法状态
        if (provider == null || provider.isBlank()) {
            throw new IllegalStateException(key + " 未配置，机审闸口将静默漏审，启动失败（显式禁用请配置 none）");
        }
        // 规则④：显式 none → WARN 强告警后放行——唯一合法的「明确禁用」通道，杜绝隐式漏审
        if (PROVIDER_NONE.equals(provider)) {
            log.warn("机审 {} 侧已被显式禁用（provider=none）：全部内容将滞留待审态/走人审，请确认符合当前环境预期", capability);
            return provider;
        }
        // 规则②：值不在注册表 → 启动失败——防拼写错误静默绕过
        Class<? extends ContentModerationService> adapterClass = registry.get(provider);
        if (adapterClass == null) {
            throw new IllegalStateException(key + "=" + provider + " 不受支持，已注册 provider：" + registry.keySet());
        }
        // 规则③：值合法但对应适配器 Bean 不存在 → 启动失败——最隐蔽的错配（provider 值与 @ConditionalOnProperty 没对上，Bean 根本没创建）
        if (applicationContext.getBeansOfType(adapterClass).isEmpty()) {
            throw new IllegalStateException(key + "=" + provider + " 与已注册适配器 Bean 不匹配（"
                    + adapterClass.getSimpleName() + " 未被激活），请核对 @ConditionalOnProperty 配置一致性");
        }
        // 四规则全过 → 返回原值供外层打印自检通过日志
        return provider;
    }
}
