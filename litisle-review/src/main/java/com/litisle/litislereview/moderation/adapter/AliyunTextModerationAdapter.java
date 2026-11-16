package com.litisle.litislereview.moderation.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litislereview.enums.ModerationCategoryEnum;
import com.litisle.litislereview.enums.VerdictEnum;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import com.litisle.litislereview.moderation.spi.ContentModerationService;
import com.litisle.litislereview.moderation.spi.ModerationResult;
import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.TextModerationRequest;
import com.aliyun.green20220302.models.TextModerationResponse;
import com.aliyun.green20220302.models.TextModerationResponseBody;
import com.aliyun.teaopenapi.models.Config;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 阿里云内容安全文本机审适配器（板块十 10.1：text-provider=aliyun 时激活，中小项目主流方案）
 *
 * <p>【职责边界】本类只做「云 API 调用 + label→三分态映射」，审核编排（分片/聚合/缓存/落库）
 * 全部在 ModerationTextService，经 SPI 门面接入——编排层零改动；LLM 适配器保留共存，
 * 配置 text-provider 一行可回切。</p>
 *
 * <p>【映射口径】云引擎 label 白名单仅接受 politics/porn/abuse/ad 四类（与 review 既有类目
 * 白名单对齐）；label 命中且置信度 ≥ aliyun.reject-threshold → REJECT；命中但低分或 label
 * 越界（contraband/terrorism 等未映射类）→ SUSPICIOUS 宁疑不放；无命中 → PASS。</p>
 *
 * <p>【宁疑不放】AK/SK 未配置、云 API 异常、超时、响应码非 200、字段缺失——一律返回
 * SUSPICIOUS + 固定 reason 转人工，绝不返回 PASS；与 LLM 适配器及「LLM 熔断转人审」语义一致。</p>
 *
 * <p>【合规出证】云响应 requestId 透传 ModerationResult.providerTraceId（moderation_record
 * 留痕，监管审计凭证）；云侧 dataId 复用计量幂等键 taskId:chunkIndex（确定性，重试可追溯）。</p>
 *
 * <p>【防注入说明】云内容安全引擎为专用判定模型，非 prompt 交互式 LLM，无需 LLM 适配器的
 * 防注入包裹三件套；超长文本分片由消费端负责，本类收到的已是单片。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "litisle.moderation.text-provider", havingValue = "aliyun")
public class AliyunTextModerationAdapter implements ContentModerationService {

    /** label 合法值白名单：仅这 4 个云 label 被映射为 REJECT 类目，其余 label（contraband 等）一律 SUSPICIOUS 转人工 */
    private static final Set<String> VALID_LABELS = Set.of("politics", "porn", "abuse", "ad");

    /** Jackson 序列化器（单例复用，线程安全），用于构造云接口 serviceParameters JSON */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 云客户端单例（SDK Client 线程安全可复用；懒加载避免 AK 未配置时阻断启动——无 key 也能起服务，调用期转人工） */
    private volatile Client client;

    private final ModerationProperties properties;

    /**
     * 文本审核：单片送阿里云内容安全 TextModeration 同步接口，label→三分态映射
     *
     * @param request 单片送审请求（content 必填；taskId/chunkIndex 用于云侧 dataId 与出证溯源）
     * @return 三分态判定结果
     */
    @Override
    public ModerationResult moderateText(com.litisle.litislereview.moderation.spi.dto.TextModerationRequest request) {
        long start = System.currentTimeMillis();
        // 确定性 dataId：复用计量幂等键 taskId:chunkIndex（缺失即调用方接线错误，fail-fast 暴露）
        if (request.getTaskId() == null || request.getChunkIndex() == null) {
            throw new IllegalArgumentException("机审送审缺少 taskId/chunkIndex，无法构造云侧 dataId=taskId:chunkIndex");
        }
        String dataId = request.getTaskId() + ":" + request.getChunkIndex();
        try {
            /* 业务逻辑步骤：
               1. 获取/懒加载云客户端（AK 未配置抛异常 → 下方 catch 转人工）
               2. 构造云请求：service 按 scene 映射（评论/私信/内容发布），serviceParameters 携带 content+dataId
               3. 同步调 TextModeration，校验响应码与数据行
               4. label→三分态映射（白名单 + 置信度阈值），组结果返回 */
            // 步骤1：云客户端懒加载单例（双检锁；线程安全可复用）
            Client aliyunClient = obtainClient();
            // 步骤2：service 按 review scene 映射云检测服务（私信走私聊检测，评论走评论检测，其余按内容发布检测）
            TextModerationRequest cloudReq = new TextModerationRequest()
                    .setService(resolveService(request.getScene()))
                    .setServiceParameters(MAPPER.writeValueAsString(buildServiceParameters(request.getContent(), dataId)));
            // 步骤3：同步调用（SDK 内部读超时取 aliyun.timeout-ms；网络异常直接抛出走下方兜底）
            TextModerationResponse resp = aliyunClient.textModeration(cloudReq);
            TextModerationResponseBody body = resp == null ? null : resp.getBody();
            // 响应码非 200 视为失败：记录云侧码与提示，转人工（宁疑不放）
            if (body == null || body.getCode() == null || body.getCode() != 200) {
                String cloudMsg = body == null ? "响应体为空" : body.getCode() + ":" + body.getMessage();
                log.warn("阿里云文本机审响应异常，按 SUSPICIOUS 转人工，dataId={}，cloud={}", dataId, cloudMsg);
                return suspicious("云机审响应异常，转人工复核", start);
            }
            // data 为单对象（单 content 提交对应一条结果）；缺失同样转人工
            TextModerationResponseBody.TextModerationResponseBodyData data = body.getData();
            if (data == null) {
                return suspicious("云机审返回为空，转人工复核", start);
            }
            // 步骤4：labels→三分态映射（合规凭证 requestId 透传出证）
            return mapVerdict(data.getLabels(),
                    body.getRequestId() == null ? dataId : body.getRequestId(), start);
        } catch (Exception e) {
            // 兜底所有异常（AK 缺失/网络/超时/SDK）：审核语义层统一转 SUSPICIOUS，异常不出模块边界
            log.error("阿里云文本机审调用失败，按 SUSPICIOUS 转人工，dataId={}", dataId, e);
            return suspicious("机审通道异常，转人工复核", start);
        }
    }

    /**
     * 文本适配器不支持图片审核：出现本调用说明 image-provider 与已注册适配器错配，
     * fail-fast 暴露配置错误（禁止隐式放行），与 LLM 适配器同口径
     */
    @Override
    public ModerationResult moderateImage(byte[] imageBytes) {
        throw new UnsupportedOperationException("text-provider=aliyun 适配器不支持图片审核，请检查 litisle.moderation.image-provider 配置");
    }

    /**
     * 懒加载云客户端单例（双检锁）
     *
     * @return 阿里云内容安全 Client
     * @throws Exception AK/SK 未配置或 SDK 初始化失败（调用方统一转人工）
     */
    private Client obtainClient() throws Exception {
        // 双检锁：首次调用才初始化，避免 AK 未配置阻断服务启动（无 key 也能起服务，调用期全量转人工）
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    ModerationProperties.Aliyun conf = properties.getAliyun();
                    // AK 为空即初始化失败：抛出走调用方兜底转人工（与 LLM 通道无 key 语义对称）
                    if (conf.getAccessKeyId() == null || conf.getAccessKeyId().isBlank()) {
                        throw new IllegalStateException("阿里云内容安全 AK/SK 未配置（litisle.moderation.aliyun.access-key-id/secret）");
                    }
                    Config config = new Config()
                            .setAccessKeyId(conf.getAccessKeyId())
                            .setAccessKeySecret(conf.getAccessKeySecret())
                            .setEndpoint(conf.getEndpoint())
                            // 读超时对齐 aliyun.timeout-ms：超时异常走调用方宁疑不放兜底
                            .setReadTimeout(conf.getTimeoutMs());
                    client = new Client(config);
                }
            }
        }
        return client;
    }

    /**
     * review 场景 → 云检测服务映射
     *
     * @param scene review_task.scene code（如 ARTICLE_FEATURED/COMMENT/MESSAGE）
     * @return 云 service 名（comment_detection/chat_detection/moment_detection）
     */
    private String resolveService(String scene) {
        // 私信走私聊检测、评论走评论检测；文章/动态等其余场景统一按内容发布检测
        if (scene != null && scene.contains("MESSAGE")) {
            return "chat_detection";
        }
        if (scene != null && scene.contains("COMMENT")) {
            return "comment_detection";
        }
        return "moment_detection";
    }

    /**
     * 构造云接口 serviceParameters（JSON 字符串）
     *
     * @param content 单片待审文本
     * @param dataId  云侧数据标识（taskId:chunkIndex，出证溯源）
     * @return 参数键值对
     */
    private Map<String, Object> buildServiceParameters(String content, String dataId) {
        // LinkedHashMap 保序：content/dataId 顺序稳定，便于日志比对与排障
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("content", content);
        params.put("dataId", dataId);
        return params;
    }

    /**
     * labels→三分态映射：解析标签串取最高分项，白名单 + 置信度阈值双条件
     *
     * @param labels          云引擎命中标签串（如 "porn:99.5,ad:60"，label:score 逗号分隔，null/空即无命中）
     * @param providerTraceId 云响应 requestId（合规出证凭证）
     * @param start           起始时间戳
     * @return 三分态判定结果
     */
    private ModerationResult mapVerdict(String labels, String providerTraceId, long start) {
        // 无命中标签：云引擎判定正常内容 → PASS（类目强制 NONE，防矛盾组合落库）
        if (labels == null || labels.isBlank()) {
            return new ModerationResult(VerdictEnum.PASS, ModerationCategoryEnum.NONE,
                    "云机审无命中", providerTraceId, costMs(start));
        }
        // 解析 "label:score" 列表取最高分项：多标签命中按最严判定（分高者优先），单项分数非法跳过不阻断
        String topLabel = null;
        double topScore = -1;
        for (String item : labels.split(",")) {
            String[] kv = item.trim().split(":");
            if (kv.length != 2) {
                continue;
            }
            try {
                double score = Double.parseDouble(kv[1]);
                if (score > topScore) {
                    topScore = score;
                    topLabel = kv[0];
                }
            } catch (NumberFormatException ignore) {
                // 单项分数非法仅跳过该项，其余标签继续判定（不因单项脏数据丢弃整体结论）
            }
        }
        // 标签串整体无法解析：不猜测，宁疑不放转人工
        if (topLabel == null) {
            log.warn("阿里云机审 labels 格式无法解析，按 SUSPICIOUS 转人工，labels={}", labels);
            return suspicious("云机审标签格式异常，转人工复核", start);
        }
        // 最高分 label 越白名单（contraband/terrorism 等未映射类）：不猜测映射，宁疑不放转人工
        if (!VALID_LABELS.contains(topLabel)) {
            log.warn("阿里云机审 label 越界，按 SUSPICIOUS 转人工，label={}", topLabel);
            return suspicious("云机审 label 越界，转人工复核", start);
        }
        int score = (int) topScore;
        // 置信度达直驳阈值 → REJECT（类目映射既有白名单）
        if (score >= properties.getAliyun().getRejectThreshold()) {
            return new ModerationResult(VerdictEnum.REJECT, mapCategory(topLabel),
                    "云机审命中 " + topLabel + " 置信度 " + score, providerTraceId, costMs(start));
        }
        // 命中但低分：边界模糊 → SUSPICIOUS 转人工（宁疑不放）
        return suspicious("云机审命中 " + topLabel + " 置信度 " + score + "，转人工复核", start);
    }

    /**
     * 云 label → 审核类目枚举映射（VALID_LABELS 已保证 label 合法，越界不会到达）
     *
     * @param label 云引擎类目
     * @return 审核类目枚举
     */
    private ModerationCategoryEnum mapCategory(String label) {
        for (ModerationCategoryEnum e : ModerationCategoryEnum.values()) {
            // 云 label 与既有类目 code 同名（politics/porn/abuse/ad），命中即返回
            if (e.getCode().equals(label)) {
                return e;
            }
        }
        // 兜底 NONE 仅为理论防御（不可达分支）
        return ModerationCategoryEnum.NONE;
    }

    /**
     * 宁疑不放统一出口：固定 reason（不回吐云原始响应），判定与留痕全走人工链路
     *
     * @param reason 固定转人工文案
     * @param start  起始时间戳
     * @return SUSPICIOUS 判定结果
     */
    private ModerationResult suspicious(String reason, long start) {
        return new ModerationResult(VerdictEnum.SUSPICIOUS, ModerationCategoryEnum.NONE, reason, null, costMs(start));
    }

    private long costMs(long start) {
        return System.currentTimeMillis() - start;
    }
}
