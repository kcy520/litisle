package com.litisle.litislereview.moderation.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 机审域可调参数（前缀 litisle.moderation，环境差异走 Nacos 覆盖）
 *
 * <p>使用场景：text-provider/image-provider 决定激活哪个 SPI 适配器
 * （@ConditionalOnProperty 精确匹配）；llm.* 约束文本机审的输出确定性。
 * onnx.*（超时/并发）、image.* 参数在板块一后续条目落地时扩充本类</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "litisle.moderation")
public class ModerationProperties {

    /** 文本机审提供方（llm；显式禁用配 none），错配由启动自检拦截（默认值见 §4.7 表） */
    private String textProvider = "llm";

    /** 图片机审提供方（onnx / 二期 aliyun；显式禁用配 none），错配由启动自检拦截 */
    private String imageProvider = "onnx";

    /** 文本 LLM 机审参数 */
    private Llm llm = new Llm();

    /** 图片机审参数 */
    private Image image = new Image();

    /** ONNX 本地推理参数 */
    private Onnx onnx = new Onnx();

    /** 词库/DFA 闸门参数（板块二） */
    private Wordlib wordlib = new Wordlib();

    /** 审核结果缓存参数（§2.11 版本化缓存，仅缓存终局结论） */
    private Cache cache = new Cache();

    /** 在途去重短锁参数（成本护栏 §2.7，防同内容并发送审烧 N 倍模型费） */
    private Dedup dedup = new Dedup();

    /** 全站 QPS 护栏参数（成本护栏 §2.6，防机审调用量洪峰打穿模型后端/DB） */
    private Qps qps = new Qps();

    /** 消费端指数退避重试参数（故障降级 §4.2.4 3-8.1，外置 yml/Nacos 运营可调） */
    private Retry retry = new Retry();

    @Data
    public static class Image {

        /** 图片判定口径版本：ONNX 模型版本 / 云 API 接口版本，纳入图片缓存 key 与留痕（§2.11） */
        private String modelVersion = "v1";
    }

    @Data
    public static class Onnx {

        /** 模型文件路径（NSFW 鉴黄 MobileNet 系转 onnx，开发期本地加载） */
        private String modelPath;

        /** 本地推理超时：CPU 推理可能卡死，超时按故障抛异常走延迟重试（§2.7） */
        private long timeoutMs = 10000;

        /** 推理并发上限（Semaphore permits，默认 CPU 核数，防 CPU 打满集体超时） */
        private int maxConcurrency = Runtime.getRuntime().availableProcessors();

        /** REJECT 阈值：NSFW 综合分 ≥ 此值判违规（运营可调，外置） */
        private double rejectThreshold = 0.80;

        /** SUSPICIOUS 阈值：≥ 此值且低于 REJECT 阈值转人工（宁疑不放） */
        private double suspiciousThreshold = 0.60;
    }

    @Data
    public static class Wordlib {

        /** DFA 词库本地持久化快照目录：表加载失败/为空时回退上次成功词库（§4.4.4 宁严勿松兜底） */
        private String snapshotDir = System.getProperty("user.home") + "/.litisle/review/dfa-snapshot";
    }

    @Data
    public static class Llm {

        /** 采样温度：审核类调用固定 0，保证判定确定性（同输入同输出） */
        private Double temperature = 0.0;

        /** few-shot 提示词版本：变更示例时 +1，消费者侧纳入 model_version 留痕 */
        private String fewShotVersion = "v1";

        /** 单片送审字符上限：超限先按空行段落分片（§4.2.1 分片禁静默截断） */
        private int maxTextChars = 6000;

        /** 段落分片后单片仍超限时字符窗口硬切窗口长度 */
        private int chunkStepChars = 5500;

        /** 字符窗口硬切重叠字符数（防违规词跨切点被拆开漏检） */
        private int chunkOverlapChars = 500;
    }

    @Data
    public static class Cache {

        /** 审核结果缓存 TTL（小时，§2.11：仅缓存终局结论 PASS/REJECT） */
        private long ttlHours = 72;

        /** TTL 抖动比例（±10%，防同批内容集体过期雪崩） */
        private double ttlJitterRatio = 0.1;
    }

    @Data
    public static class Dedup {

        /** 在途去重短锁 TTL（秒）：抢锁失败=原请求在途，后到者延迟重试 */
        private long lockSeconds = 60;
    }

    @Data
    public static class Qps {

        /** 全站固定窗口大小（秒）：review 服务 HTTP 接口总流量上限的计数窗口 */
        private long windowSeconds = 1;

        /** 全站 QPS 上限（窗口内最大请求数）：外置 yml/Nacos，运营可调；
         * 超限抛 TOO_MANY_REQUESTS，Redis 故障期由 RedisRateLimiter 内部切 LocalRateLimiter 本地兑底（宁严勿松） */
        private long maxCount = 200;
    }

    @Data
    public static class Retry {

        /** 消费端延迟重试上限：重试次数达上限仍失败 → nack 入 DLQ 由 3-6 死信消费转人审（绝不自动放行） */
        private int maxCount = 5;

        /** 指数退避基数（毫秒）：第 n 次重试延迟 = baseDelayMs × 2^(n-1)，默认 30s→60s→120s→240s→480s */
        private long baseDelayMs = 30000;
    }
}
