package com.litisle.litislereview.moderation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.dto.content.ArticleImageRefDTO;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.dto.file.FileContentDTO;
import com.litisle.litisleapi.dto.file.FileOwnerDTO;
import com.litisle.litisleapi.event.moderation.ImageSubmitEvent;
import com.litisle.litisleapi.event.moderation.ModerationResultEvent;
import com.litisle.litisleapi.feign.ContentClient;
import com.litisle.litisleapi.feign.FileClient;
import com.litisle.litisleapi.moderation.MachineRejectPunishGate;
import com.litisle.litislereview.domain.entity.ModerationRecord;
import com.litisle.litislereview.domain.entity.ReviewTask;
import com.litisle.litislereview.enums.Layer2RiskEnum;
import com.litisle.litislereview.enums.ModTypeEnum;
import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import com.litisle.litislereview.enums.ModerationCategoryEnum;
import com.litisle.litislereview.enums.ModerationProviderEnum;
import com.litisle.litislereview.enums.ReviewSceneEnum;
import com.litisle.litislereview.enums.ReviewTaskStatusEnum;
import com.litisle.litislereview.enums.VerdictEnum;
import com.litisle.litislereview.mapper.ModerationRecordMapper;
import com.litisle.litislereview.mapper.ReviewTaskMapper;
import com.litisle.litislereview.moderation.ModerationModelQpsGuard;
import com.litisle.litislereview.moderation.cache.ModerationResultCache;
import com.litisle.litislereview.moderation.config.ImageModerationAdapterProvider;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import com.litisle.litislereview.moderation.spi.ContentModerationService;
import com.litisle.litislereview.moderation.spi.ModerationResult;
import com.litisle.litislereview.mq.publisher.PunishResultPublisher;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 图片审核编排服务（板块三 3-2，§4.2.2 / §2.1）
 *
 * 【职责】由 {@link com.litisle.litislereview.moderation.consumer.ModerationImageConsumer} 调用，
 * 承接图片送审的全部业务编排，含两条支线：单图审（scene=IMAGE）/ 文章配图复审（scene=ARTICLE_IMG_RE_REVIEW）。
 *
 * 【取图唯一入口（SSRF 防线）】一律 {@code FileClient.getContent(fileId)} 内部字节流端点：
 * 返回 FileContentDTO.content（byte[]，经 Jackson 自动 Base64 编解码），该端点不过 safe_flag 闸口
 * （申诉复核需重取隔离图 safe_flag=0）；严禁按任何用户 URL 抓取，二期云 API 同样优先传字节流而非内网 URL（§2.1）。
 *
 * 【缓存 key 版本化】单图结果缓存 key 必须含 provider + modelVersion（§4.2.2）：
 * {@code String.format(ModerationConstant.IMG_RESULT_KEY, provider, modelVersion, sha256)}；
 * provider 取图片通道（ModerationProperties）、modelVersion 取模型版本；切换 provider / 换模型版本后旧结论自动失效。
 *
 * 【分层】Consumer 只做消费编排（ACK/traceId）；本类承载全部审核业务逻辑；
 * SPI 门面 ContentModerationService（onnxImageModerationAdapter）只做单图送审，
 * Semaphore 并发控制与调用超时已在适配器（板块一）内落地，本类只负责「异常 → 延迟重试」编排。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationImageService {

    /** moderation_record.reason 列长度（落库前截断，防超长中断审核写入） */
    private static final int REASON_MAX_LENGTH = 500;

    /**
     * 机审 REJECT 联动处罚记入分值快照（5-1.1）：固定 0——机审 action=3 仅落流水、不参与登录态
     * 阈值判定（PunishLog 实体注释口径）；后续运营若开放内容下架扣分，改此常量即可（分值快照随行固化）
     */
    private static final Integer MACHINE_REJECT_SCORE = 0;

    /** 审核任务表（定位 + CAS 状态流转） */
    private final ReviewTaskMapper reviewTaskMapper;
    /** 审核记录表（append-only 留痕，命中缓存也必追加） */
    private final ModerationRecordMapper moderationRecordMapper;
    /** 文件服务契约（getContent 内部字节流端点=取图唯一入口，SSRF 防线） */
    private final FileClient fileClient;
    /** 内容服务契约（ARTICLE_IMG_RE_REVIEW 反查文章配图引用列表） */
    private final ContentClient contentClient;
    /** 机审 SPI 门面解析器（解析逻辑收敛单点：ObjectProvider/类型筛选/none 延迟重试语义全在 Provider 组件内，本类只管调 resolve()） */
    private final ImageModerationAdapterProvider imageModerationAdapterProvider;
    /** 模型直调全站 QPS 护栏（3-8.3：Redis 降级直调模型仍受全站 QPS+本地兑底计数约束，超限延迟重试绝不放行） */
    private final ModerationModelQpsGuard moderationModelQpsGuard;
    /** 机审可调参数（image-provider / image.model-version / cache.* 等） */
    private final ModerationProperties moderationProperties;
    /** Redis（单图结果缓存读写，key 含 provider+modelVersion 版本化隔离） */
    private final StringRedisTemplate stringRedisTemplate;
    /** JSON 序列化器（缓存 value 一律 JSON，禁 Java 原生序列化） */
    private final ObjectMapper objectMapper;
    /** MQ 发送工具（事务提交后发 result 事件，sendCritical 必达语义） */
    private final MqSendUtil mqSendUtil;
    /** 编程式事务模板（process 内部自调用不走代理，@Transactional 会失效，故用编程式事务） */
    private final TransactionTemplate transactionTemplate;
    /** 机审 REJECT 处罚落库适配器（5-1.1：punish_log 落行 + violation_score 原子累加，契约见 MachineRejectPunishGate.RejectPunishAdapter） */
    private final MachineRejectPunishGate.RejectPunishAdapter machineRejectPunishAdapter;
    /** 处罚结果事件发布器（5-2.1：punish.result 出口收口，闸口 eventSender 注入本组件方法引用） */
    private final PunishResultPublisher punishResultPublisher;
    /** 机审观测埋点组件（7-1：moderation_total/cost/cache_hit 计数计时，ObjectProvider optional 模式静默跳过） */
    private final ModerationMetrics moderationMetrics;

    /**
     * 处理图片送审事件（按 scene 分流：IMAGE 单图审 / ARTICLE_IMG_RE_REVIEW 文章配图复审）。
     *
     * 【步骤1 场景分流】
     * - scene=IMAGE：单图审核（bizType=6；bizId=图片文件标识，file 侧发布即 fileId）；
     * - scene=ARTICLE_IMG_RE_REVIEW：按文章 bizId 经 ContentClient 反查 article_image 配图列表逐张送审，
     *   该任务 content_hash = 全部配图 sha256 排序拼接后再 sha256 的聚合指纹（聚合判定见步骤5）。
     *
     * 【步骤2 取图】逐张 FileClient.getContent(fileId) 拿 byte[]（严禁按任何用户 URL 抓取，SSRF 防线）；
     * IMAGE 场景 fileId 即事件 bizId，复审场景 fileId 取自反查到的配图项。
     *
     * 【步骤3 单图缓存查重】key=String.format(ModerationConstant.IMG_RESULT_KEY, provider, modelVersion, sha256)
     * （即 review:img-result:{provider}:{modelVersion}:{sha256}）；版本化隔离——onnx→aliyun 切换、
     * 同 provider 模型换版均不复用旧结论；miss→送 OnnxImageModerationAdapter（抛异常→延迟重试）；
     * 缓存 value 格式异常→降级送审（宁严勿松，不误放行）。
     *
     * 【步骤4 单图落库】单图结论落 moderation_record（mod_type=3、content_hash=该图文件 sha256）；
     * 单图 REJECT 的 safe_flag=0 + 隔离由 file 侧消费 result 事件处理，review 侧只发事件、不直改 file 库。
     *
     * 【步骤5 复审聚合】ARTICLE_IMG_RE_REVIEW 全部图审完→聚合：全 PASS→PASS / 任一 REJECT→REJECT /
     * 含 SUSPICIOUS→SUSPICIOUS；CAS 流转复审任务（biz_type=1 + scene=ARTICLE_IMG_RE_REVIEW 的 review_task）。
     *
     * 【注意事项】
     * - 图片场景（IMAGE/ARTICLE_IMG_RE_REVIEW）不走 DFA（DfaScenes 归一为 null），勿误调词库；
     * - 逐图送 SPI、不逐图另建 biz_type=6 任务（防任务爆炸），复审仅对文章级任务做一次聚合流转；
     * - Semaphore/超时已在适配器（板块一）落地，本类只需「异常→延迟重试」编排。
     *
     * @param event 图片送审事件（litisle-api 契约）
     */
    public void process(ImageSubmitEvent event) {
        // 步骤1：参数与场景校验——event/scene 必带；缺 scene→error 日志 + 抛 BizException
        //        （MqAckHelper 按业务性失败 ack 丢弃脏消息，防毒丸重投）
        if (event == null || !StringUtils.hasText(event.getScene())) {
            log.error("图片送审事件缺少 scene，确认丢弃，event={}", event);
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 步骤2：场景分流——IMAGE 单图审 / ARTICLE_IMG_RE_REVIEW 文章配图复审
        //        （图片场景不走 DFA：DfaScenes 对图片场景归一为 null，勿误调词库）
        ReviewSceneEnum scene = parseScene(event.getScene());
        if (scene == ReviewSceneEnum.IMAGE) {
            //单图审支线
            processSingleImage(event);
        } else {
            //文章配图复审支线
            processArticleImgReReview(event);
        }
    }

    /**
     * 单图审支线（scene=IMAGE，bizType=6，bizId=fileId）。
     *
     * 【口径】图片场景不建 review_task（防任务爆炸），逐图只落 moderation_record
     * （biz_type=6、biz_id=file_meta.id、无任务关联）；单图 REJECT 的隔离与 safe_flag
     * 回写均由 file 侧消费 result 事件处理，review 侧只发事件、不直改 file 库。
     *
     * @param event 图片送审事件（bizId=图片 fileId，contentHash=文件 sha256）
     */
    private void processSingleImage(ImageSubmitEvent event) {
        // 步骤1：fileId 校验（IMAGE 场景 bizId 即图片 fileId，file 侧 setBizId(meta.getFileId())）
        String fileId = event.getBizId();
        if (!StringUtils.hasText(fileId)) {
            log.error("单图送审事件缺少 bizId(fileId)，确认丢弃，event={}", event);
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 步骤2：取图（SSRF 防线唯一入口 FileClient.getContent 内部字节流，严禁按任何用户 URL 抓取）
        byte[] imageBytes = fetchImageBytes(fileId);
        // 步骤3：本地重算图片 sha256（缓存 key/留痕口径全服务端派生，杜绝拼接事件体原串，§2.12）
        String sha256 = sha256Hex(imageBytes);
        // 步骤4：解析版本依赖（provider=图片通道、modelVersion=模型口径版本，双双纳入缓存 key 版本化隔离）
        ModerationProviderEnum provider = resolveImageProvider();
        String modelVersion = moderationProperties.getImage().getModelVersion();
        // 步骤5：单图缓存查重（key=review:img-result:{provider}:{modelVersion}:{sha256}；
        //        版本化隔离——onnx→aliyun 切换/换模型版本后旧结论自动失效）
        String cacheKey = String.format(ModerationConstant.IMG_RESULT_KEY, provider.getCode(), modelVersion, sha256);
        ImageCacheValue cached = readImageCache(cacheKey);
        ModerationResult result;
        String cacheNote = null;
        if (cached != null) {
            // 步骤6：命中缓存→跳过模型调用，仅复用 verdict/category/reason（留痕行 reason 补记 cacheNote）
            result = new ModerationResult(toVerdict(cached.getVerdict()), toCategory(cached.getCategory()),
                    cached.getReason(), null, 0L);
            cacheNote = "命中图片审核缓存";
            // 7-1：moderation_cache_hit{layer=result} 图片审核结果缓存命中计数（单图审跳过模型费）
            moderationMetrics.incrementCacheHit(ModerationMetrics.LAYER_RESULT);
            log.info("命中图片审核缓存，跳过模型调用，fileId={}，verdict={}", fileId, cached.getVerdict());
        } else {
            // 步骤7：SPI 单图送审（Semaphore/超时已在适配器落地；异常上抛→Consumer 延迟重试）：
            //        送审前先申请模型直调全站 QPS 配额（3-8.3：超限抛非预期异常走延迟重试，绝不放行）
            moderationModelQpsGuard.tryAcquire();
            result = imageModerationAdapterProvider.resolve().moderateImage(imageBytes);
            // 7-1：moderation_cost Timer 记录单图 SPI 真实送审耗时（缓存命中不进入此分支）
            moderationMetrics.recordCost(result.costMs());
        }
        // 步骤8：单图落库 + 事务提交后写缓存/发 result 事件（隔离与 safe_flag 回写由 file 侧消费事件处理）
        // 7-1：moderation_total{verdict} 单图机审三分态计数（缓存命中/真实送审两路均经此收口，落库前统一计数）
        moderationMetrics.incrementTotal(result.verdict());
        persistSingleImage(fileId, sha256, provider, modelVersion, result, cacheNote, cacheKey);
    }

    /**
     * 按 fileId 经 FileClient.getContent 内部字节流取图（SSRF 防线唯一入口，不过 safe_flag 闸口：
     * 申诉复核需重取隔离图 safe_flag=0）。
     *
     * @param fileId 图片文件ID（file_meta.file_id 业务主键）
     * @return 图片原始字节流（≤10MB，Jackson Base64 编解码透明化）
     */
    private byte[] fetchImageBytes(String fileId) {
        Result<FileContentDTO> resp = fileClient.getContent(fileId);
        // 判空 + 判码：文件不存在/字节流为空→业务性失败 BizException，Consumer ack 丢弃（防毒丸）；
        // fallback fail-fast 抛的非 BizException 与调用异常直接上抛，Consumer nack 入 DLQ 延迟重试
        if (resp == null || !resp.isSuccess() || resp.getData() == null
                || resp.getData().getContent() == null || resp.getData().getContent().length == 0) {
            log.error("机审取图失败，确认丢弃，fileId={}，resp={}", fileId, resp);
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return resp.getData().getContent();
    }

    /**
     * 计算内容 SHA256 十六进制指纹（缓存 key/留痕/对账统一口径，全服务端派生）。
     *
     * @param content 原始字节流
     * @return 64 位小写十六进制指纹
     */
    private String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺失 SHA-256 实现", e);
        }
    }

    /**
     * 解析图片机审 provider（yml image-provider → 枚举；none/未知值由启动自检拦截，
     * 运行期兜底直接抛非预期异常走 DLQ，防静默漏审）。
     *
     * @return 图片机审提供方枚举
     */
    private ModerationProviderEnum resolveImageProvider() {
        for (ModerationProviderEnum e : ModerationProviderEnum.values()) {
            if (e.getCode().equals(moderationProperties.getImageProvider())) {
                return e;
            }
        }
        throw new IllegalStateException("未知图片机审 provider：" + moderationProperties.getImageProvider());
    } 

    /**
     * 单图结论落库（编程式事务）+ 事务提交后写缓存、发 result 事件。
     *
     * 【口径】biz_type=6、biz_id=file_meta.id（两套引用口径：biz_id 引用 file_meta.id、
     * fileId 引用 file_meta.file_id 业务主键，见 FileOwnerDTO 注释）；图片场景无任务，
     * review_task_id 置空；append-only 留痕，命中缓存也必追加。
     *
     * @param fileId       图片文件ID（result 事件 bizId 口径，file 侧按其回写 safe_flag）
     * @param sha256       图片指纹（content_hash 留痕）
     * @param provider     图片机审提供方
     * @param modelVersion 判定口径版本
     * @param result       单图判定
     * @param cacheNote    缓存命中备注（null=未命中缓存）
     * @param cacheKey     结果缓存 key（事务提交后回写）
     */
    private void persistSingleImage(String fileId, String sha256, ModerationProviderEnum provider,
                                    String modelVersion, ModerationResult result, String cacheNote, String cacheKey) {
        LocalDateTime now = LocalDateTime.now();
        // 步骤1：查 file_meta.id（biz_type=6 的 biz_id 留痕口径=file_meta.id）；
        //        查询失败/缺失→业务性失败 ack 丢弃（留痕缺失无法补偿，重试无意义）
        Result<FileOwnerDTO> infoResp = fileClient.getFileInfo(fileId);
        if (infoResp == null || !infoResp.isSuccess() || infoResp.getData() == null
                || infoResp.getData().getFileMetaId() == null || infoResp.getData().getOwnerId() == null) {
            log.error("单图落库前查询 file_meta 归属失败，确认丢弃，fileId={}，resp={}", fileId, infoResp);
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        Long fileMetaId = infoResp.getData().getFileMetaId();
        // 归属人（5-1 机审 REJECT 处罚上下文 userId 来源：file_meta 归属=违规责任归属）
        Long ownerId = infoResp.getData().getOwnerId();
        // 步骤2：编程式事务（process 内部自调用不走代理，@Transactional 会失效，故用 TransactionTemplate）
        transactionTemplate.execute(tx -> {
            // 步骤3：追加 moderation_record（mod_type=3-本地图片模型；append-only 留痕，命中缓存也必追加）  ModerationRecord_审核记录表
            moderationRecordMapper.insert(new ModerationRecord()
                    .setModType(ModTypeEnum.LOCAL_IMAGE_MODEL)
                    .setBizType(ModerationBizTypeEnum.FILE)
                    .setBizId(fileMetaId)
                    .setScene(ReviewSceneEnum.IMAGE)
                    .setContentHash(sha256)
                    .setProvider(provider)
                    .setWordlibVer(null)
                    .setModelVersion(modelVersion)
                    .setVerdict(result.verdict())
                    .setCategory(result.category())
                    .setReason(truncate((cacheNote != null ? cacheNote + "；" : "") + result.reason(), REASON_MAX_LENGTH))
                    .setProviderTraceId(result.providerTraceId())
                    .setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY))
                    .setOperatorId(0L)
                    .setOperatorName("system")
                    .setCostMs((int) Math.min(result.costMs(), Integer.MAX_VALUE))
                    .setCreateTime(now));
            // 【板块五 5-1.1 机审 REJECT 联动处罚】单图 verdict=REJECT 时在同一事务内落
            // punish_log(action=3, operator_id=0, score=分值快照, status=1) + violation_score 累计，
            // userId 按 file_meta 归属解析；5-1.2：阈值判定只重放 action∈{1,2}，action=3 仅落流水不计入；
            // 事件发布由闸口 afterCommit 收口（发布失败→DLQ 兑底）
            if (result.verdict() == VerdictEnum.REJECT
                    && !MachineRejectPunishGate.recordRejectPunish(
                    new MachineRejectPunishGate.RejectPunishContext(ownerId, ModerationConstant.BIZ_TYPE_IMAGE,
                            fileMetaId, ReviewSceneEnum.IMAGE.getCode(), null, sha256, MACHINE_REJECT_SCORE,
                            sanitizeReason(result.reason()), MDC.get(MqSendUtil.TRACE_ID_MDC_KEY)),
                    machineRejectPunishAdapter, punishResultPublisher::publish)) {
                // 落库失败：处罚记账静默丢失=违规者零分累计，宁严勿松——回滚整个审核事务（含留痕），延迟重试
                throw new IllegalStateException("单图机审 REJECT 处罚记账落库失败，回滚审核事务，fileId=" + fileId);
            }
            // 步骤4：注册事务提交后回调——写缓存 + 发 result 事件（事务成功提交后才执行，防脏缓存/脏事件）
                //向当前线程的 Spring 事务注册一个“提交后回调”，当事前开启的事务成功提交后，才执行 afterCommit() 里的逻辑。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 单图 REJECT 的隔离与 safe_flag 回写由 file 侧消费 result 事件处理，review 侧只发事件不直改 file 库
                    writeImageCache(cacheKey, result);
                    sendImageResultEvent(ModerationBizTypeEnum.FILE.getCode(), fileId, result.verdict(), null);
                }
            });
            return null;
        });
    }

    /**
     * 读图片审核结果缓存（value 一律 JSON，禁 Java 原生反序列化 §2.12）。
     *
     * @param cacheKey 缓存 key（含 provider+modelVersion 版本化隔离）
     * @return 缓存结论；miss/value 非法一律返回 null 降级送审（宁严勿松，不误放行）
     */
    private ImageCacheValue readImageCache(String cacheKey) {
        String json;
        try {
            json = stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            // 3-8.3 Redis 不可用降级（宁严勿松）：缓存读失败按未命中处理，降级直调模型——
            // 只多花钱不漏审，绝不 default-allow；直调流量仍受模型直调 QPS 护栏约束
            log.warn("图片审核缓存读取异常，降级直调模型（只多花钱不漏审），key={}", cacheKey, e);
            return null;
        }
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            ImageCacheValue value = objectMapper.readValue(json, ImageCacheValue.class);
            // 白名单校验：verdict 非终局值/类目缺失→视为脏缓存，降级送审
            if ((Objects.equals(value.getVerdict(), ModerationConstant.VERDICT_PASS)
                    || Objects.equals(value.getVerdict(), ModerationConstant.VERDICT_REJECT))
                    && StringUtils.hasText(value.getCategory())) {
                return value;
            }
            log.warn("图片审核缓存 value 非法，降级送审，key={}", cacheKey);
            return null;
        } catch (JsonProcessingException e) {
            log.warn("图片审核缓存 value 解析失败，降级送审，key={}", cacheKey, e);
            return null;
        }
    }

    /**
     * 写图片审核结果缓存（仅 PASS/REJECT 终局结论；TTL ±10% 抖动防缓存雪崩 §2.11）。
     *
     * @param cacheKey 缓存 key
     * @param result   单图判定
     */
    private void writeImageCache(String cacheKey, ModerationResult result) {
        if (result.verdict() != VerdictEnum.PASS && result.verdict() != VerdictEnum.REJECT) {
            // SUSPICIOUS 非终局结论不入缓存（§2.11），转人工后由人审/申诉链路定局
            return;
        }
        try {
            ImageCacheValue value = new ImageCacheValue();
            value.setVerdict(result.verdict().getCode());
            value.setCategory(result.category().getCode());
            value.setReason(result.reason());
            long ttlHours = moderationProperties.getCache().getTtlHours();
            double jitter = moderationProperties.getCache().getTtlJitterRatio();
            // TTL ±jitter 抖动：防同批图片同刻写入同刻过期引发缓存雪崩；下限保底 1h 防抖出非正值
            long ttlWithJitter = (long) (ttlHours * (1 + ThreadLocalRandom.current().nextDouble(-jitter, jitter)));
            stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(value),
                    Duration.ofHours(Math.max(ttlWithJitter, 1)));
        } catch (JsonProcessingException e) {
            // 缓存写失败不影响主流程（miss 可回源模型重审），仅记 error
            log.error("图片审核结果缓存写入失败，key={}", cacheKey, e);
        }
    }

    /**
     * 发送图片审核结果事件（file 侧消费后回写 safe_flag / REJECT 隔离；申诉恢复也依赖本事件链路）。
     *
     * @param bizType  业务类型码（单图=6-FILE；文章配图复审=1-ARTICLE）
     * @param bizId    业务ID（单图=fileId；复审=articleId）
     * @param verdict  聚合后判定
     * @param taskId   关联审核任务ID（复审场景必带；单图无任务传 null）
     */
    private void sendImageResultEvent(Integer bizType, String bizId, VerdictEnum verdict, Long taskId) {
        ModerationResultEvent evt = new ModerationResultEvent();
        evt.setEventId(UUID.randomUUID().toString());
        evt.setEventTime(LocalDateTime.now());
        evt.setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
        evt.setVersion(1);
        evt.setBizType(bizType);
        evt.setBizId(bizId);
        evt.setVerdict(verdict.getCode());
        evt.setReviewTaskId(taskId);
        try {
            // sendCritical 必达语义：文件 safe_flag 回写/隔离是审核链路核心写操作，发丢会致违规图放行；
            // 发布失败抛 BizException(MQ_SEND_FAILED)——但 MqAckHelper 会把 BizException 按业务性失败 ack，
            // 事件将静默丢失，故转 IllegalStateException 走非预期分支→nack 入 DLQ 兑底
            mqSendUtil.sendCritical(MqConstant.EXCHANGE_MODERATION, MqConstant.ROUTING_MODERATION_RESULT, evt);
        } catch (BizException e) {
            throw new IllegalStateException("图片审核结果事件发布失败，转 DLQ 兑底", e);
        }
    }

    /**
     * 文章配图复审支线（scene=ARTICLE_IMG_RE_REVIEW，bizType=1，bizId=articleId）。
     *
     * 【口径】复审任务为文章级（biz_type=1 + scene=ARTICLE_IMG_RE_REVIEW 的 review_task，
     * content_hash=全部配图 sha256 排序拼接后再 sha256 的聚合指纹）；逐图送 SPI、
     * 不逐图另建 biz_type=6 任务（防任务爆炸），仅对文章级任务做一次聚合 CAS 流转。
     *
     * @param event 图片送审事件（bizId=文章ID，contentHash=配图聚合指纹）
     */
    private void processArticleImgReReview(ImageSubmitEvent event) {
        // 步骤1：定位文章级复审任务（biz_type=1 + biz_id=articleId + scene）；任务缺失→error + BizException（ack 丢弃）
        ReviewTask task = reviewTaskMapper.selectOne(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getBizType, ModerationBizTypeEnum.ARTICLE)
                .eq(ReviewTask::getBizId, Long.valueOf(event.getBizId()))
                .eq(ReviewTask::getScene, ReviewSceneEnum.ARTICLE_IMG_RE_REVIEW));
        if (task == null) {
            log.error("文章配图复审任务不存在，确认丢弃，bizId={}，event={}", event.getBizId(), event);
            throw new BizException(ErrorCode.MODERATION_TASK_NOT_FOUND);
        }
        // 步骤2：幂等分流（任务 status≠0 = 已处理后的重投）
        if (task.getStatus() != ReviewTaskStatusEnum.PENDING) {
            if (!Objects.equals(event.getContentHash(), task.getContentHash())) {
                // hash 不一致→旧消息错配新任务，ack 丢弃（重试无意义且可能覆盖新结论）
                log.error("复审事件与任务聚合指纹不一致，疑似旧消息错配，确认丢弃，taskId={}，eventHash={}，taskHash={}",
                        task.getId(), event.getContentHash(), task.getContentHash());
                throw new BizException(ErrorCode.MODERATION_HASH_MISMATCH);
            }
            // hash 一致→「已处理后的重投」：补发 result 事件后返回（ack）；
            // 用 send() 尽力而为而非 sendCritical：首次处理已发过本事件，重投属对账性质
            republishReReviewResult(task);
            return;
        }
        // 步骤3：反查文章配图引用列表（article_image 类型 file_ref，按 fileMetaId 升序保证聚合指纹可重算对账）
        Result<List<ArticleImageRefDTO>> imgsResp = contentClient.listArticleImages(Long.valueOf(event.getBizId()));
        if (imgsResp == null || !imgsResp.isSuccess() || imgsResp.getData() == null) {
            log.error("反查文章配图引用失败，转延迟重试，articleId={}，resp={}", event.getBizId(), imgsResp);
            throw new IllegalStateException("反查文章配图引用失败：articleId=" + event.getBizId());
        }
        List<ArticleImageRefDTO> images = imgsResp.getData();
        if (images.isEmpty()) {
            // 文章已无活跃配图（全部被删/替换）：脏复审请求，ack 丢弃（重试无意义）
            log.error("文章无活跃配图，复审请求确认丢弃，articleId={}", event.getBizId());
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 步骤4：逐图送审 + 聚合判定 + 文章级任务 CAS 流转落库（步骤5 聚合规则见方法内注释）
        moderateAndPersistReReview(event, task, images);
    }

    /**
     * 复审支线逐图送审 + 聚合判定 + 文章级任务落库。
     *
     * 【逐图口径】取图/缓存查重/送 SPI 与单图审同一套（provider+modelVersion 版本化缓存可跨场景复用）；
     * 每张图各落一条 moderation_record（biz_type=6、biz_id=file_meta.id、review_task_id=文章级任务ID、
     * scene=ARTICLE_IMG_RE_REVIEW、content_hash=该图 sha256），异常逐图上抛中断剩余送审（宁疑不放）。
     *
     * 【聚合规则（§4.2.2）】任一图 REJECT→整篇 REJECT；含 SUSPICIOUS→整篇 SUSPICIOUS；全 PASS→PASS；
     * 聚合判定仅对文章级任务做一次 CAS 流转（where id=? and status=0，affected=0→已被并发处理，放弃本次结果）。
     *
     * @param event  复审送审事件
     * @param task   文章级复审任务
     * @param images 反查到的配图引用列表（按 fileMetaId 升序）
     */
    private void moderateAndPersistReReview(ImageSubmitEvent event, ReviewTask task, List<ArticleImageRefDTO> images) {
        // 步骤1：解析版本依赖（与单图审同源：provider=图片通道、modelVersion=模型口径版本）
        ModerationProviderEnum provider = resolveImageProvider();
        String modelVersion = moderationProperties.getImage().getModelVersion();
        LocalDateTime now = LocalDateTime.now();
        // 步骤2：逐图「取图→重算指纹→缓存查重→送 SPI」，收集各图判定（任一图异常上抛中断剩余送审）
        List<ModerationResult> perImageResults = new ArrayList<>(images.size());
        List<String> perImageSha256 = new ArrayList<>(images.size());
        List<ModerationResult> terminalResults = new ArrayList<>();
        for (ArticleImageRefDTO image : images) {
            // 步骤2.1：取图（SSRF 防线唯一入口 FileClient.getContent 内部字节流）+ 本地重算 sha256
            byte[] imageBytes = fetchImageBytes(image.getFileId());
            String sha256 = sha256Hex(imageBytes);
            // 步骤2.2：单图缓存查重（版本化 key 可跨场景复用：同图在单图审/复审间不重复烧模型费）
            String cacheKey = String.format(ModerationConstant.IMG_RESULT_KEY,
                    provider.getCode(), modelVersion, sha256);
            ImageCacheValue cached = readImageCache(cacheKey);
            ModerationResult result;
            String cacheNote = null;
            if (cached != null) {
                result = new ModerationResult(toVerdict(cached.getVerdict()), toCategory(cached.getCategory()),
                        cached.getReason(), null, 0L);
                cacheNote = "命中图片审核缓存";
                // 7-1：moderation_cache_hit{layer=result} 配图复审场景缓存命中计数（跨场景复用不重复烧模型费）
                moderationMetrics.incrementCacheHit(ModerationMetrics.LAYER_RESULT);
            } else {
                // 步骤2.3：SPI 单图送审（异常上抛→Consumer 延迟重试）：
                //          送审前先申请模型直调全站 QPS 配额（3-8.3：超限抛非预期异常走延迟重试，绝不放行）
                moderationModelQpsGuard.tryAcquire();
                result = imageModerationAdapterProvider.resolve().moderateImage(imageBytes);
                // 7-1：moderation_cost Timer 记录配图复审逐张 SPI 真实送审耗时（聚合口径为逐图累加）
                moderationMetrics.recordCost(result.costMs());
            }
            perImageResults.add(result);
            perImageSha256.add(sha256);
            if (result.verdict() == VerdictEnum.PASS || result.verdict() == VerdictEnum.REJECT) {
                terminalResults.add(result);
            }
        }
        // 步骤3：聚合判定——任一 REJECT→REJECT；含 SUSPICIOUS→SUSPICIOUS；全 PASS→PASS
        VerdictEnum aggregate = perImageResults.stream()
                .filter(r -> r.verdict() == VerdictEnum.REJECT)
                .findFirst()
                .or(() -> perImageResults.stream()
                        .filter(r -> r.verdict() == VerdictEnum.SUSPICIOUS)
                        .findFirst())
                .map(ModerationResult::verdict)
                .orElse(VerdictEnum.PASS);
        // 7-1：moderation_total{verdict} 配图复审聚合判定计数（聚合落库同源收口，非逐图重复计数）
        moderationMetrics.incrementTotal(aggregate);
        // 聚合三分态→任务状态流转映射（同文本侧口径）：PASS→1-自动通过 / REJECT→4-驳回 / SUSPICIOUS→2-人工待审
        ReviewTaskStatusEnum targetStatus;
        Layer2RiskEnum layer2Risk;
        if (aggregate == VerdictEnum.PASS) {
            targetStatus = ReviewTaskStatusEnum.AUTO_PASS;
            layer2Risk = Layer2RiskEnum.LOW;
        } else if (aggregate == VerdictEnum.REJECT) {
            targetStatus = ReviewTaskStatusEnum.REJECT;
            layer2Risk = Layer2RiskEnum.HIGH;
        } else {
            targetStatus = ReviewTaskStatusEnum.MANUAL_PENDING;
            layer2Risk = Layer2RiskEnum.MID;
        }
        long totalCostMs = perImageResults.stream().mapToLong(ModerationResult::costMs).sum();
        // 步骤4：编程式事务——逐图留痕 + 文章级任务一次 CAS 流转（process 内部自调用不走代理，@Transactional 失效）
        transactionTemplate.execute(tx -> {
            // 步骤4.1：逐图追加 moderation_record（biz_type=6、biz_id=file_meta.id、review_task_id=文章级任务ID）
            for (int i = 0; i < images.size(); i++) {
                ArticleImageRefDTO image = images.get(i);
                ModerationResult result = perImageResults.get(i);
                moderationRecordMapper.insert(new ModerationRecord()
                        .setModType(ModTypeEnum.LOCAL_IMAGE_MODEL)
                        .setBizType(ModerationBizTypeEnum.FILE)
                        .setBizId(image.getFileMetaId())
                        .setReviewTaskId(task.getId())
                        .setScene(ReviewSceneEnum.ARTICLE_IMG_RE_REVIEW)
                        .setContentHash(perImageSha256.get(i))
                        .setProvider(provider)
                        .setWordlibVer(null)
                        .setModelVersion(modelVersion)
                        .setVerdict(result.verdict())
                        .setCategory(result.category())
                        .setReason(truncate(result.reason(), REASON_MAX_LENGTH))
                        .setProviderTraceId(result.providerTraceId())
                        .setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY))
                        .setOperatorId(0L)
                        .setOperatorName("system")
                        .setCostMs((int) Math.min(result.costMs(), Integer.MAX_VALUE))
                        .setCreateTime(now));
            }
            // 步骤4.2：文章级任务一次 CAS 流转（where id=? and status=0；affected=0→已被并发处理，放弃本次结果）
            int affected = reviewTaskMapper.update(null, new LambdaUpdateWrapper<ReviewTask>()
                    .set(ReviewTask::getStatus, targetStatus)
                    .set(ReviewTask::getModVerdict, aggregate)
                    .set(ReviewTask::getLayer2Risk, layer2Risk)
                    .set(ReviewTask::getUpdateTime, now)
                    .eq(ReviewTask::getId, task.getId())
                    .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.PENDING));
            if (affected == 0) {
                log.info("复审任务状态 CAS 流转落空（已被并发处理），放弃本次结果，taskId={}", task.getId());
                return null;
            }
            // 【板块五 5-1.1 机审 REJECT 联动处罚】聚合判定=REJECT 时在同一事务内按文章归属落
            // punish_log(action=3-内容下架, operator_id=0-系统, score=分值快照, status=1)
            // + violation_score 原子累加；5-1.2 红线：阈值判定只重放 action∈{1,2} 的 status=1 生效行，
            // action=3 仅落流水不计入（不改 last_punish_action/frozen_until，不影响登录态）；
            // 事件发布由闸口 afterCommit 收口（发布失败→DLQ 兑底）
            if (aggregate == VerdictEnum.REJECT) {
                // 步骤4.2.1：回查文章归属（复用 ContentClient 文章契约，与文本侧归属解析口径一致）；
                //           回查失败→业务性失败 ack 丢弃（对齐单图支线归属校验口径：逐图留痕随事务回滚，
                //           DLQ 延迟重试按缓存+任务 CAS 幂等重放，无副作用）
                Result<ContentDTO> ownerResp = contentClient.getContent(
                        ModerationConstant.BIZ_TYPE_ARTICLE, task.getBizId());
                if (ownerResp == null || !ownerResp.isSuccess() || ownerResp.getData() == null
                        || ownerResp.getData().getOwnerId() == null) {
                    log.error("复审聚合 REJECT 回查文章归属失败，确认丢弃，articleId={}，resp={}",
                            event.getBizId(), ownerResp);
                    throw new BizException(ErrorCode.NOT_FOUND);
                }
                if (!MachineRejectPunishGate.recordRejectPunish(
                        new MachineRejectPunishGate.RejectPunishContext(ownerResp.getData().getOwnerId(),
                                ModerationConstant.BIZ_TYPE_ARTICLE, task.getBizId(),
                                ReviewSceneEnum.ARTICLE_IMG_RE_REVIEW.getCode(), task.getId(),
                                event.getContentHash(), MACHINE_REJECT_SCORE,
                                // reason 取聚合决胜的首个 REJECT 图判定理由（与聚合判定规则同源）
                                sanitizeReason(perImageResults.stream()
                                        .filter(r -> r.verdict() == VerdictEnum.REJECT)
                                        .findFirst()
                                        .map(ModerationResult::reason)
                                        .orElse("")), MDC.get(MqSendUtil.TRACE_ID_MDC_KEY)),
                        machineRejectPunishAdapter, punishResultPublisher::publish)) {
                    // 落库失败：处罚记账静默丢失=违规者零分累计，宁严勿松——回滚整个复审事务（含逐图留痕/任务 CAS），延迟重试
                    throw new IllegalStateException("配图复审聚合 REJECT 处罚记账落库失败，回滚复审事务，articleId="
                            + event.getBizId());
                }
            }
            // 步骤4.3：注册事务提交后回调——回写各图终局缓存 + 发文章级聚合 result 事件（防脏缓存/脏事件）
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (int i = 0; i < images.size(); i++) {
                        // 仅回写 PASS/REJECT 终局结论（writeImageCache 内部对 SUSPICIOUS 跳过）
                        writeImageCache(String.format(ModerationConstant.IMG_RESULT_KEY,
                                provider.getCode(), modelVersion, perImageSha256.get(i)), perImageResults.get(i));
                    }
                    // 文章级聚合结论事件（bizType=1、bizId=articleId，content/ai 侧按其联动文章状态）
                    sendImageResultEvent(ModerationBizTypeEnum.ARTICLE.getCode(), event.getBizId(), aggregate, task.getId());
                }
            });
            return null;
        });
        log.info("文章配图复审完成，articleId={}，图片数={}，聚合判定={}，总耗时={}ms",
                event.getBizId(), images.size(), aggregate, totalCostMs);
    }

    /**
     * 已处理复审任务重投场景补发文章级 result 事件（幂等分流专用）。
     *
     * @param task 已定位的非待审文章级复审任务（携带终态 mod_verdict）
     */
    private void republishReReviewResult(ReviewTask task) {
        if (task.getModVerdict() == null || task.getModVerdict() == VerdictEnum.NONE) {
            // 任务已流转但机审结论为空（人审直接裁决等路径），无 result 可补，仅记日志返回
            log.info("复审任务已流转且无机审结论，跳过补发 result 事件，taskId={}，status={}", task.getId(), task.getStatus());
            return;
        }
        // 用 send() 尽力而为而非 sendCritical：首次处理已发过本事件，重投属对账性质，
        // 发丢有下游 eventId 幂等与对账兑底；若 sendCritical 失败会抛 BizException 被 ack，反丢失重投机会
        ModerationResultEvent evt = new ModerationResultEvent();
        evt.setEventId(UUID.randomUUID().toString());
        evt.setEventTime(LocalDateTime.now());
        evt.setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
        evt.setVersion(1);
        evt.setBizType(task.getBizType().getCode());
        evt.setBizId(String.valueOf(task.getBizId()));
        evt.setVerdict(task.getModVerdict().getCode());
        evt.setReviewTaskId(task.getId());
        mqSendUtil.send(MqConstant.EXCHANGE_MODERATION, MqConstant.ROUTING_MODERATION_RESULT, evt);
    }

    /**
     * 图片审核结果缓存 value（JSON 序列化落 Redis，禁 Java 原生反序列化 §2.12）。
     *
     * <p>仅存终局结论所需最小字段集（verdict/category/reason），不含扩展字段——
     * 缓存命中后仅复用三值构造 ModerationResult，凭证/tracing 字段以本次调用为准（本地组件为 null）</p>
     */
    @Data
    private static class ImageCacheValue implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 终局判定（仅 1-PASS/2-REJECT，SUSPICIOUS 非终局不入缓存） */
        private Integer verdict;

        /** 违规类目 code（机审统一映射枚举 code，白名单校验后复用） */
        private String category;

        /** 判定理由（复用透传，作者申诉依据） */
        private String reason;
    }

    /**
     * 解析事件场景为 ReviewSceneEnum（code 与枚举名一致）。
     *
     * @param scene 事件携带的场景码
     * @return 审核场景枚举；非法场景抛 BizException（ack 丢弃脏消息）
     */
    private ReviewSceneEnum parseScene(String scene) {
        try {
            return ReviewSceneEnum.valueOf(scene.trim());
        } catch (IllegalArgumentException e) {
            log.error("图片送审事件 scene 非法，确认丢弃，scene={}", scene);
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
    }

    /**
     * 缓存 verdict code → VerdictEnum（白名单校验，防脏缓存）。
     *
     * @param code 缓存存储的判定码
     * @return 判定枚举；非终局值抛 IllegalStateException（由调用方降级送审处理）
     */
    private VerdictEnum toVerdict(Integer code) {
        if (Objects.equals(code, ModerationConstant.VERDICT_PASS)) {
            return VerdictEnum.PASS;
        }
        if (Objects.equals(code, ModerationConstant.VERDICT_REJECT)) {
            return VerdictEnum.REJECT;
        }
        throw new IllegalStateException("图片缓存结论 verdict 非终局值，降级送审处理：" + code);
    }

    /**
     * 缓存 category code → ModerationCategoryEnum（白名单校验，未知值归 NONE 防脏缓存外溢）。
     *
     * @param code 缓存存储的类目 code
     * @return 类目枚举；未知值返回 NONE
     */
    private ModerationCategoryEnum toCategory(String code) {
        for (ModerationCategoryEnum e : ModerationCategoryEnum.values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return ModerationCategoryEnum.NONE;
    }

    /**
     * reason 落库截断（模型输出为不可信数据，防超长中断审核写入）。
     *
     * @param text      原始理由
     * @param maxLength 列长上限
     * @return 截断后理由
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * reason 卫生处理（处罚 reason 口径，3-4.1 同款纪律）：剥离 \r\n（防 CRLF 伪造日志行）+ 截断 500，
     * 与 ModerationResultPublisher.sanitizeReason 同口径
     *
     * @param reason 原始判定理由（模型不可信输出）
     * @return 处理后的安全理由
     */
    private String sanitizeReason(String reason) {
        if (reason == null || reason.isEmpty()) {
            return reason;
        }
        String cleaned = reason.replace("\r", "").replace("\n", " ");
        return cleaned.length() <= REASON_MAX_LENGTH ? cleaned : cleaned.substring(0, REASON_MAX_LENGTH);
    }
}
