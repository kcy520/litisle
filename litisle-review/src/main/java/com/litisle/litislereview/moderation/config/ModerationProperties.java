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

    /**
     * NLM 语义机审提供方（批次1 ⑦ 预留键）：默认空=未激活——激活前无任何链路消费此键，
     * 纯占位不影响现网行为。二期接线位置=异步层（moderation.spi 适配器，与 LLM 并列/级联），
     * 走与 text/image-provider 同一套激活模式：@ConditionalOnProperty 精确匹配 +
     * 启动自检 fail-fast 三件套（配置与 Bean 不匹配即启动失败）。
     * ⚠️ 红线：禁进同步闸门——DFA/AC 同步层「本地毫秒级执行」是硬约束，
     * NLM 语义模型必然远程调用，只允许出现在异步机审链路
     *
     * <p>【激活 checklist（2026-10-30 登记，激活前必须逐项拍板）】</p>
     * <ul>
     *   <li>① 编排语义拍板：与 LLM「并列」（双源各审一遍合成结论）or「级联」（LLM 存疑再送
     *       NLM 复核）二选一——两者对缓存 key 与费用模型影响不同，激活时第一个要回答的问题</li>
     *   <li>② 缓存 key 加 provider 维度：现有文本结果缓存
     *       review:text-result:{wordlibVer}:{fewShotVer}:{scene}:{hash} 的 fewShotVer 是 LLM
     *       专属概念；若走并列编排，NLM 复用此 key 会直接读到 LLM 终局结论（双源退化单源，
     *       NLM 白花钱）——须在 key 中加 provider 段；级联编排可复用 key 但需扩展版本段语义</li>
     *   <li>③ ModerationProperties 补 Nlm 参数内部类（timeout/model-path 等按真实部署形态定：
     *       本地推理 or API），选型前预埋必是猜测，故本预留期不加</li>
     * </ul>
     */
    private String nlmProvider = "";

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

    /** 处罚双向对账 sweeper 配置（5-3.2，cron 由 @Scheduled 占位符直读不入本类） */
    private Reconcile reconcile = new Reconcile();

    /** 审核任务清扫 sweeper 配置（6-1，间隔 interval-seconds 由 @Scheduled 占位符直读不入本类） */
    private Sweeper sweeper = new Sweeper();

    /** 违规图隔离保留期配置（6-3，sweep-cron 由 @Scheduled 占位符直读不入本类） */
    private Quarantine quarantine = new Quarantine();

    /** moderation_record 归档配置（6-4，§4.4.1 归档占位：归档任务二期落地，本期仅绑定配置与设计契约） */
    private Record record = new Record();

    /** 告警阈值配置（7-3，Alertmanager 六规则的阈值外置口；阈值经 AlertThresholdMetrics
     *  暴露为 Prometheus 阈值指标，规则文件引用指标而非写死数字——改 Nacos 即时生效，无需改规则文件） */
    private Alert alert = new Alert();

    /** 拒传黑名单参数（7-4.2）：ttl-days 默认值归 litisle-shared.yaml（§4.7 共享口径，
     *  防后续 content 等服务直探黑名单时 TTL 口径漂移），本地 yml 值仅作环境覆盖 */
    private RejectBlacklist rejectBlacklist = new RejectBlacklist();

    /** 人审认领参数（7-4.2）：max-active-per-reviewer 消费点（认领配额校验）随板块五
     *  claimTask 实现接线；timeout-minutes 口径已收敛至 sweeper 组（7-3 决策）不在此重复绑定 */
    private Claim claim = new Claim();

    /** 举报诬告反制参数（7-4.2）：abuse-threshold 消费点（人审 decide 判诬告后累计反向处罚）
     *  随板块五 decideTask 实现接线，本组先完成配置绑定收尾 */
    private Report report = new Report();

    @Data
    public static class Alert {

        /** 存疑积压阈值（①）：moderation_backlog{status="suspicious"} 持续超该值告警（人审池堆积预警） */
        private int suspiciousBacklogThreshold = 50;

        /** DLQ 堆积阈值（③）：moderation_dlq_backlog 持续超该值告警（死信兜底链路积压，需人工介入） */
        private int dlqBacklogThreshold = 100;

        /** 机审失败率阈值（④）：非 pass 判定占比（reject+suspicious / 总判定）持续超该值告警（模型/词库质量劣化信号） */
        private double moderationFailRate = 0.2;

        /** 通过率相对基线跌幅阈值（⑤）：短窗通过率相对长窗基线的相对跌幅超该值告警（prompt/模型变更后回滑检测） */
        private double passRateDropRatio = 0.3;

        /** 缓存命中率跌落阈值（⑥）：result 层短窗命中率相对长窗基线的相对跌幅超该值告警（省钱效果劣化信号） */
        private double cacheHitDropRatio = 0.5;
    }

    @Data
    public static class RejectBlacklist {

        /** 拒传黑名单保留天数：REJECT 图片结论写入 review:img-blacklist:{sha256} 的 TTL（默认值归 shared.yaml 共享口径） */
        private int ttlDays = 30;
    }

    @Data
    public static class Claim {

        /** 单审核员同时在办任务上限：防单人占满人审队列（消费点随板块五 claimTask 配额校验接线） */
        private int maxActivePerReviewer = 20;
    }

    @Data
    public static class Report {

        /** 诬告反制阈值：同一举报人被计诬告达此次数后触发反向处罚（消费点随板块五 decideTask 接线） */
        private int abuseThreshold = 3;
    }

    @Data
    public static class Quarantine {

        /** 违规图隔离保留期（天）：隔离图超期后由 QuarantineSweeper 委派 file 侧物理删除（6-3.1）；
         * 申诉豁免顺延不受本阈值限制 */
        private int retentionDays = 30;

        /** 单轮候选上限：超期 REJECT 留痕行扫描限批（防全量扫描打满 DB，下轮兑剩余） */
        private int batchLimit = 100;

        /** 删除限速（次/分钟）：批内按 sleep 均摊控速，防批量物理删除打满 SeaweedFS（6-3.3） */
        private int deleteRatePerMinute = 30;

        /** 多实例互斥锁时长（秒）：SETNX 锁 TTL，天级单轮执行远小于该值，锁过期自动放行下轮 */
        private int lockSeconds = 300;
    }

    @Data
    public static class Record {

        /** 留痕热表保留期（月）：《网络安全法》日志留存 ≥6 月合规下限；超期记录由二期归档任务
         *  按 id 区间幂等迁移只读历史存储后删热表行——删前跳过被 appeal.status=0（待裁决申诉）
         *  或 punish_log.status=1（生效处罚）引用的记录；归档存储应用账号仅授 INSERT/SELECT */
        private int retentionMonths = 6;

        /** 归档任务启用开关（二期）：默认关闭，占位类 ModerationRecordArchiveSweeper 仅在
         *  显式置 true 时注册（防一期未实现时被调度空跑） */
        private boolean archiveEnabled = false;

        /** 归档单轮批量上限：最老优先按 id 区间推进，限批防单轮长事务拖垮调度线程与主链路 */
        private int batchLimit = 500;

        /** 归档互斥锁时长（秒）：SETNX 锁 TTL，天级单轮执行远小于该值，锁过期自动放行下轮 */
        private int lockSeconds = 300;
    }

    @Data
    public static class Sweeper {

        /** 认领超时阈值（分钟）：status=2 且 claim_time 早于该阈值 → 回池置回待审（6-1.1） */
        private int claimTimeoutMinutes = 30;

        /** 申诉裁决超时阈值（天）：status=0 待裁决超时 → 自动维持原判（6-1.2，宁严勿松防态悬空） */
        private int appealTimeoutDays = 7;

        /** 待审超时补偿重发阈值（分钟）：status=0 早于该阈值 → 重投 submit 事件（6-2）；
         * 必须大于 MQ 消费端最大退避总时长（retry.max-count 次指数退避累加），防与消费端正常延迟重试双发竞争
         * （双投由 3-1 步骤4 CAS where status=0 去重兑底，此处阈值仅降无谓冲突） */
        private int stuckResubmitMinutes = 30;

        /** 补偿重发分频轮数：每 N 轮 sweep 执行一次（interval-seconds×N 为实际周期，降扫描开销） */
        private int stuckResubmitEveryRounds = 30;

        /** 单轮批量上限：回池/维持扫描均限批（防全量扫描打满 DB，下轮兑剩余） */
        private int batchLimit = 100;

        /** 多实例互斥锁时长（秒）：SETNX 锁 TTL，需大于单轮最长执行时间 */
        private int lockSeconds = 30;
    }

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

        /**
         * 灰度误报率阈值（MID→HIGH 转正量化门槛）：误报率 = 误报上报次数 / 命中观察量，
         * 低于该值视为观察合格。消费点=词库列表建议标记（§5.4 治理建议清单）：
         * HIGH 词命中样本足够且误报率低于该值 → 出「转正确认」标记。
         * 仅是证据参考、禁触发自动动作——转正/降级一律运营手动（复用既有 updateWord）；
         * 代码默认 0.01 与 application.yml 既有键绑定（此前该键悬空未绑定，本字段补齐绑定关系）
         */
        private double grayMisreportRate = 0.01;

        /**
         * 建议标记最小命中样本量：命中观察量达到该值才参与「转正确认/建议转正」标记计算
         * （样本不足=证据不可信，宁可不标记不误导）；HIGH 降级建议不走本门槛
         * （以误报绝对量为主，误报次数>0 即标记）。消费点同 grayMisreportRate
         */
        private int minHitSample = 50;

        /** 词库冷备参数（批次1 ①）：LocalGzipBackupStore 激活与存储位置（快照=热备，备份文件=冷备，角色互补） */
        private Backup backup = new Backup();

        /** 词库误报记录参数：TTL 清理保留期（sweeper 天级清理过期上报行） */
        private Misreport misreport = new Misreport();
    }

    /**
     * 词库误报记录配置组（litisle.moderation.wordlib.misreport.*）
     */
    @Data
    public static class Misreport {

        /** 误报记录保留天数：超过保留期未再上报的行由 sweeper 天级清理
         *  （误报属运营数据非合规硬凭证，聚合结论已沉淀在灰度转正裁决里，过期可删；非正数=保留全量） */
        private int retentionDays = 180;
    }

    /**
     * 词库冷备配置组（litisle.moderation.wordlib.backup.*，批次1 ①）
     */
    @Data
    public static class Backup {

        /** 备份开关：显式置 true 才注册 LocalGzipBackupStore（@ConditionalOnProperty 激活；默认关=纯新增不改变现网行为） */
        private boolean enabled = false;

        /** 备份根目录：⚠️ 必须是挂载持久卷（容器化部署下容器本地盘随实例销毁，备份会丢）；
         *  文件布局 {dir}/{scene}/{scene}-v{version}.csv.gz */
        private String dir = System.getProperty("user.home") + "/.litisle/review/wordlib-backup";

        /** 每 scene 保留最近 N 个版本备份：落盘后自动清理更旧版本文件（防磁盘无限增长；非正数=保留全版本） */
        private int retentionVersions = 50;
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

    @Data
    public static class Reconcile {

        /** 反向补解封撤销行回扫窗口（天）：仅扫近期撤销行防全表膨胀，窗口外残留快照靠自身 TTL 自愈 */
        private int revokeLookbackDays = 7;

        /** 单轮每方向最大扫描行数：防 punish_log 流水膨胀后单轮拖垮调度线程 */
        private int batchLimit = 500;

        /** 对账互斥锁 TTL（秒）：防多实例同轮重复扫描；需 ≥ 单轮最长执行时长，锁过期自动放行下轮 */
        private long lockSeconds = 300;
    }
}
