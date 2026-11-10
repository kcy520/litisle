package com.litisle.litislereview.moderation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dfa.WordLibraryLoader;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.dto.social.MessageDTO;
import com.litisle.litisleapi.event.moderation.ModerationResultEvent;
import com.litisle.litisleapi.event.moderation.TextSubmitEvent;
import com.litisle.litisleapi.feign.ContentClient;
import com.litisle.litisleapi.feign.SocialClient;
import com.litisle.litisleapi.moderation.MachineRejectPunishGate;
import com.litisle.litislereview.constant.ReviewRedisKeyConstant;
import com.litisle.litislereview.dfa.WordlibHitStatRecorder;
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
import com.litisle.litislereview.mq.publisher.ModerationResultPublisher;
import com.litisle.litislereview.mq.publisher.PunishResultPublisher;
import com.litisle.litislereview.moderation.cache.ModerationResultCache;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import com.litisle.litislereview.moderation.config.TextModerationAdapterProvider;
import com.litisle.litislereview.moderation.spi.ContentModerationService;
import com.litisle.litislereview.moderation.spi.ModerationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 文本审核编排服务（板块三 3-1，§4.2.1 ④⑤⑥）
 *
 * 【职责】由 {@code ModerationTextConsumer} 调用：定位任务 → 幂等分流 → 缓存查重 →
 * SPI 送审（分片聚合）→ 三分态落库（同一本地事务）→ 事务提交后发 result 事件。
 *
 * 【分层】Consumer 只做消费编排（ACK/traceId）；本类承载全部审核业务逻辑；
 * SPI 门面 {@link ContentModerationService} 只做单片刻度送审（分片/聚合由本类负责）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationTextService {

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
    /** 内容服务回查契约（bizType∈{1,2} 文章/评论正文） */
    private final ContentClient contentClient;
    /** 私信服务回查契约（bizType=5 私信正文） */
    private final SocialClient socialClient;
    /** 机审 SPI 门面解析器（解析逻辑收敛单点：ObjectProvider/类型筛选/none 延迟重试语义全在 Provider 组件内，本类只管调 resolve()；当前所有环境 text-provider=llm，本注入属与图片侧对称的预防性改造） */
    private final TextModerationAdapterProvider textModerationAdapterProvider;
    /** 模型直调全站 QPS 护栏（3-8.3：Redis 降级直调模型仍受全站 QPS+本地兑底计数约束，超限延迟重试绝不放行） */
    private final ModerationModelQpsGuard moderationModelQpsGuard;
    /** 审核结果缓存查重（Redis→moderation_record 兜底） */
    private final ModerationResultCache moderationResultCache;
    /** 词库装载 SPI（取当前词库版本，供缓存 key/兜底过滤） */
    private final WordLibraryLoader wordLibraryLoader;
    /** 机审可调参数（llm.max-text-chars / dedup.lock-seconds / cache.* 等） */
    private final ModerationProperties moderationProperties;
    /** Redis（在途去重短锁 SETNX） */
    private final StringRedisTemplate stringRedisTemplate;
    /** 机审结果事件发布器（按 bizType 统一解析路由键后发 result 事件；发布失败内部转 IllegalStateException 走 DLQ 兑底） */
    private final ModerationResultPublisher moderationResultPublisher;
    /** 编程式事务模板（persistAndPublish 由 process 内部调用，自调用不走代理，@Transactional 会失效，故用编程式事务） */
    private final TransactionTemplate transactionTemplate;
    /** 机审 REJECT 处罚落库适配器（5-1.1：punish_log 落行 + violation_score 原子累加，契约见 MachineRejectPunishGate.RejectPunishAdapter） */
    private final MachineRejectPunishGate.RejectPunishAdapter machineRejectPunishAdapter;
    /** 处罚结果事件发布器（5-2.1：punish.result 出口收口，闸口 eventSender 注入本组件方法引用） */
    private final PunishResultPublisher punishResultPublisher;
    /** 机审观测埋点组件（7-1：moderation_total/cost/cache_hit 计数计时，ObjectProvider optional 模式静默跳过） */
    private final ModerationMetrics moderationMetrics;
    /** 词级命中统计记录器（§5.4：异步机审处理成功后按任务快照 hitWords 逐词累加，词库治理建议清单数据源；内部整体不抛异常不阻断审核） */
    private final WordlibHitStatRecorder wordlibHitStatRecorder;

    /**
     * 文本审核编排主入口（Consumer 委托）
     *
     * @param event 文本送审事件（含 bizType/bizId/scene/contentHash）
     */
    public void process(TextSubmitEvent event) {
        // 步骤1：参数与场景校验——event/scene 必带；缺 scene→error 日志 + 抛 BizException
        //        （MqAckHelper 按业务性失败 ack 丢弃脏消息，防毒丸重投）
        if (event == null || !StringUtils.hasText(event.getScene())) {
            log.error("文本送审事件缺少 scene，确认丢弃，event={}", event);
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        ReviewSceneEnum scene = parseScene(event.getScene());

        // 步骤2：按 (biz_type, biz_id, scene) 三元组定位 review_task；任务缺失→error 日志 + 抛 BizException（ack 丢弃）
        ReviewTask task = reviewTaskMapper.selectOne(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getBizType, toBizType(event.getBizType()))
                .eq(ReviewTask::getBizId, event.getBizId())
                .eq(ReviewTask::getScene, scene));
        if (task == null) {
            log.error("文本送审任务不存在，确认丢弃，bizType={}，bizId={}，scene={}",
                    event.getBizType(), event.getBizId(), event.getScene());
            throw new BizException(ErrorCode.MODERATION_TASK_NOT_FOUND);
        }

        // 步骤3：幂等分流（任务 status≠0 = 已处理后的重投）
        if (task.getStatus() != ReviewTaskStatusEnum.PENDING) {
            if (!Objects.equals(event.getContentHash(), task.getContentHash())) {
                // hash 不一致→旧消息错配新任务，ack 丢弃（重试无意义且可能覆盖新结论）
                log.error("送审事件与任务内容指纹不一致，疑似旧消息错配，确认丢弃，taskId={}，eventHash={}，taskHash={}",
                        task.getId(), event.getContentHash(), task.getContentHash());
                throw new BizException(ErrorCode.MODERATION_HASH_MISMATCH);
            }
            // hash 一致→「已处理后的重投」：补发 result 事件后返回（ack）。
            // 用 send() 尽力而为而非 sendCritical：首次处理已发过本事件，重投属对账性质，
            // 发丢有下游 eventId 幂等与对账兜底；若 sendCritical 失败会纳 BizException 被 ack，反丢失重投机会
            republishResult(task);
            return;
        }

        // 步骤4：回查正文（标题+正文拼接；标题违规语义须一并送审；同时取回归属人供 5-1 处罚上下文）
        FetchedText fetched = fetchText(event);

        // 步骤5：解析版本依赖（词库版本按 DFA 场景归一映射取；few-shot 版本即文本 model_version）
        String sceneCode = task.getScene().getCode();
        long wordlibVer = wordLibraryLoader.loadVersion(requireWordScene(task.getScene()));
        String fewShotVer = moderationProperties.getLlm().getFewShotVersion();
        ModerationProviderEnum provider = ModerationProviderEnum.LLM;

        // 步骤6：缓存查重（Redis→moderation_record 兜底，四条件版本化过滤）
        Optional<ModerationResultCache.CachedVerdict> cached =
                moderationResultCache.find(sceneCode, event.getContentHash(), wordlibVer, fewShotVer, provider);
        String cacheNote = null;
        ModerationResult result;
        if (cached.isPresent()) {
            // 步骤7：命中缓存→跳过模型调用，仅复用 verdict/category/reason（留痕行 reason 由 persistAndPublish 补记 cacheNote）
            ModerationResultCache.CachedVerdict cv = cached.get();
            result = new ModerationResult(toVerdict(cv.getVerdict()), toCategory(cv.getCategory()),
                    cv.getReason(), null, 0L);
            cacheNote = "命中缓存，原记录ID=" + cv.getRecordId();
            // 7-1：moderation_cache_hit{layer=result} 审核结果缓存命中计数（缓存复用成功即计，跳过模型费）
            moderationMetrics.incrementCacheHit(ModerationMetrics.LAYER_RESULT);
            log.info("命中审核结果缓存，跳过模型调用，taskId={}，verdict={}，原记录ID={}",
                    task.getId(), cv.getVerdict(), cv.getRecordId());
        } else {
            // 步骤8：抢 SETNX 在途短锁（TTL=dedup.lock-seconds）；抢不到→抛异常不 ack
            //        （原请求在途，经 DLQ 延迟重试，防并发穿透烧 N 倍模型费）
            tryLockInFlight(provider, event.getContentHash());
            // 步骤9：SPI 送审（分片+聚合，禁静默截断）
            result = moderateWithChunking(fetched.text());
            // 7-1：moderation_cost Timer 记录 SPI 真实送审耗时（缓存命中/重投补发不进入此分支）
            moderationMetrics.recordCost(result.costMs());
        }

        // 步骤10：三分态落库（同一本地事务）+ 事务提交后写缓存、发 result 事件
        persistAndPublish(event, task, result, wordlibVer, cacheNote, fetched.ownerId());
    }

    /**
     * 已处理任务重投场景补发 result 事件（幂等分流专用）
     *
     * @param task 已定位的非待审任务（携带终态 mod_verdict）
     */
    private void republishResult(ReviewTask task) {
        if (task.getModVerdict() == null || task.getModVerdict() == VerdictEnum.NONE) {
            // 任务已流转但无机审结论（人审直接裁决等路径），无 result 可补，仅记日志返回
            log.info("任务已流转且无机审结论，跳过补发 result 事件，taskId={}，status={}", task.getId(), task.getStatus());
            return;
        }
        ModerationResultEvent evt = new ModerationResultEvent();
        evt.setEventId(UUID.randomUUID().toString());
        evt.setEventTime(LocalDateTime.now());
        evt.setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
        evt.setVersion(1);
        evt.setBizType(task.getBizType().getCode());
        evt.setBizId(String.valueOf(task.getBizId()));
        evt.setVerdict(task.getModVerdict().getCode());
        evt.setReviewTaskId(task.getId());
        // 复用发布器的尽力而为补发通道（send 语义，不保证必达）；路由键由发布器按 bizType 统一解析
        // （Q87 修复：原直发 moderation.result 与 content 侧 text.result 绑定不匹配，消息到不了消费队列）
        moderationResultPublisher.republish(evt);
    }

    /**
     * 抢在途去重短锁（SETNX + TTL，成本护栏）
     *
     * @param provider    当前文本 provider（key 段之一，服务端枚举派生）
     * @param contentHash 内容指纹（key 段之一，SHA256 派生）
     */
    private void tryLockInFlight(ModerationProviderEnum provider, String contentHash) {
        String dedupKey = String.format(ReviewRedisKeyConstant.REVIEW_DEDUP_KEY_FORMAT, provider.getCode(), contentHash);
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", Duration.ofSeconds(moderationProperties.getDedup().getLockSeconds()));
        if (!Boolean.TRUE.equals(locked)) {
            // 抢锁失败=同内容送审在途：抛非 BizException 异常→MqAckHelper nack 入 DLQ 延迟重试
            // （若抛 BizException 会被按业务性失败 ack 丢弃，原请求结果将丢失，语义错误）
            log.warn("在途去重短锁竞争失败，原请求在途，转延迟重试，key={}", dedupKey);
            throw new IllegalStateException("同内容送审在途，稍后重试：" + dedupKey);
        }
    }

    /**
     * 回查正文（标题+正文拼接送审；标题违规语义须一并送审）
     * <p>口径 SSOT（4-1.2）：拼接口径须与契约层 SubmitTextAssembler / ModerationConstant.TEXT_SUBMIT_SEPARATOR
     * 保持逐字一致；该骨架落地实现后，本方法拼装逻辑应切换复用常量，禁止两侧各自演化
     * （hash 错配会被消费端 ACK 丢弃，见 3-1.4）。
     *
     * @param event 送审事件（bizType/bizId 决定回查契约）
     * @return 待审文本（标题+正文）+ 归属人（5-1 机审 REJECT 处罚上下文 userId 来源）
     */
    private FetchedText fetchText(TextSubmitEvent event) {
        Integer bizType = event.getBizType();
        // 步骤1：bizType∈{1,2}→ContentClient 回查，标题+正文拼接送审（标题违规语义须一并送审）
        if (Objects.equals(bizType, ModerationBizTypeEnum.ARTICLE.getCode())
                || Objects.equals(bizType, ModerationBizTypeEnum.COMMENT.getCode())) {
            Result<ContentDTO> resp = contentClient.getContent(bizType, event.getBizId());
            // 步骤3：判空 + 判码（内容不存在→业务性失败 BizException，ack 丢弃；
            //        fallback fail-fast 抛的非 BizException 与调用异常直接上抛，Consumer nack 延迟重试）
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                log.error("回查内容不存在，确认丢弃，bizType={}，bizId={}，resp={}", bizType, event.getBizId(), resp);
                throw new BizException(Objects.equals(bizType, ModerationBizTypeEnum.ARTICLE.getCode())
                        ? ErrorCode.ARTICLE_NOT_FOUND : ErrorCode.COMMENT_NOT_FOUND);
            }
            ContentDTO dto = resp.getData();
            String title = StringUtils.hasText(dto.getTitle()) ? dto.getTitle() : "";
            String body = StringUtils.hasText(dto.getBody()) ? dto.getBody() : "";
            // 拼接分隔符收口 ModerationConstant.TEXT_SUBMIT_SEPARATOR（4-1.2 SSOT，与发布侧 SubmitTextAssembler 同口径）
            String text = title.isEmpty() ? body : title + ModerationConstant.TEXT_SUBMIT_SEPARATOR + body;
            return new FetchedText(text, dto.getOwnerId());
        }
        // 步骤2：bizType=5→MessageClient 回查私信正文
        if (Objects.equals(bizType, ModerationBizTypeEnum.MESSAGE.getCode())) {
            Result<MessageDTO> resp = socialClient.getContent(event.getBizId());
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                log.error("回查私信不存在，确认丢弃，bizId={}，resp={}", event.getBizId(), resp);
                throw new BizException(ErrorCode.NOT_FOUND);
            }
            return new FetchedText(StringUtils.hasText(resp.getData().getContent())
                    ? resp.getData().getContent() : "", resp.getData().getSenderId());
        }
        // 其余 bizType（用户名不建任务、作家申请另链路）不在文本送审消费范围，脏消息 ack 丢弃
        log.error("文本送审事件 bizType 超出消费范围，确认丢弃，bizType={}，bizId={}", bizType, event.getBizId());
        throw new BizException(ErrorCode.PARAM_ERROR);
    }

    /**
     * 分片送审 + 三分态聚合（禁静默截断）
     *
     * @param text 待审全文
     * @return 聚合后的整文判定
     */
    private ModerationResult moderateWithChunking(String text) {
        // 步骤1/2：超限先按空行段落聚合分片，单片仍超限再字符窗口硬切（步长/重叠外置）
        List<String> chunks = splitChunks(text);
        // 步骤3：逐片送审（单片口径，LLM 适配器负责防注入与解析失败宁疑不放）
        List<ModerationResult> results = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            // 每片都是一次真实模型调用：送审前申请模型直调全站 QPS 配额
            //（3-8.3：超限抛非预期异常走延迟重试，绝不放行）
            moderationModelQpsGuard.tryAcquire();
            results.add(textModerationAdapterProvider.resolve().moderateText(chunk));
        }
        if (results.size() == 1) {
            return results.get(0);
        }
        // 步骤4：聚合规则——任一片 REJECT→整文 REJECT；含 SUSPICIOUS 片→整文 SUSPICIOUS；全 PASS→PASS
        //        决胜片携带其 category/reason/providerTraceId，costMs 取全部片耗时之和（观测口径完整）
        ModerationResult decisive = results.stream()
                .filter(r -> r.verdict() == VerdictEnum.REJECT)
                .findFirst()
                .or(() -> results.stream()
                        .filter(r -> r.verdict() == VerdictEnum.SUSPICIOUS)
                        .findFirst())
                .orElse(results.get(0));
        long totalCostMs = results.stream().mapToLong(ModerationResult::costMs).sum();
        log.info("超长文本分片送审完成，chunks={}，聚合判定={}，总耗时={}ms",
                chunks.size(), decisive.verdict(), totalCostMs);
        return new ModerationResult(decisive.verdict(), decisive.category(),
                decisive.reason(), decisive.providerTraceId(), totalCostMs);
    }

    /**
     * 超长文本分片：优先按空行段落聚合（不拆散语义段落），单段落自身超限再字符窗口硬切
     *
     * @param text 待审全文
     * @return 分片列表（每片长度均 ≤ llm.max-text-chars，禁静默截断）
     */
    private List<String> splitChunks(String text) {
        int maxChars = moderationProperties.getLlm().getMaxTextChars();
        int stepChars = moderationProperties.getLlm().getChunkStepChars();
        int overlapChars = moderationProperties.getLlm().getChunkOverlapChars();
        List<String> chunks = new ArrayList<>();
        if (text.length() <= maxChars) {
            chunks.add(text);
            return chunks;
        }
        // 按空行段落切分后逐段聚合进分片缓冲（窗口硬切段不参与聚合，避免与段落混合后语义断裂）
        StringBuilder buf = new StringBuilder();
        for (String paragraph : text.split("\\r?\\n\\s*\\r?\\n")) {
            if (paragraph.length() > maxChars) {
                // 步骤2：无段落分隔/单段仍超 max-text-chars→字符窗口硬切：
                // 窗口长度 = 步长 + 重叠（步长 5500 + 重叠 500 = 6000 ≤ maxChars），
                // 相邻窗口重叠防违规词跨切点被拆开漏检，禁静默截断
                flushChunk(buf, chunks);
                for (int start = 0; start < paragraph.length(); start += stepChars) {
                    int end = Math.min(paragraph.length(), start + stepChars + overlapChars);
                    chunks.add(paragraph.substring(start, end));
                    if (end == paragraph.length()) {
                        break;
                    }
                }
                continue;
            }
            if (buf.length() + paragraph.length() + 2 > maxChars) {
                flushChunk(buf, chunks);
            }
            if (buf.length() > 0) {
                buf.append("\n\n");
            }
            buf.append(paragraph);
        }
        flushChunk(buf, chunks);
        return chunks;
    }

    /**
     * 将段落聚合缓冲刷入分片列表并重置缓冲
     */
    private void flushChunk(StringBuilder buf, List<String> chunks) {
        if (buf.length() > 0) {
            chunks.add(buf.toString());
            buf.setLength(0);
        }
    }

    /**
     * 三分态落库（同一本地事务）+ 事务提交后发 result 事件
     *
     * @param event     送审事件
     * @param task      已定位任务
     * @param result    聚合后判定
     * @param wordlibVer 当前词库版本（留痕 + 缓存 key）
     * @param ownerId   归属人（回查 DTO 解析，5-1 机审 REJECT 处罚上下文 userId 来源）
     */
    private void persistAndPublish(TextSubmitEvent event, ReviewTask task, ModerationResult result,
                                   long wordlibVer, String cacheNote, Long ownerId) {
        LocalDateTime now = LocalDateTime.now();
        String fewShotVer = moderationProperties.getLlm().getFewShotVersion();
        // 三分态→任务状态流转映射：PASS→1-自动通过 / REJECT→4-驳回 / SUSPICIOUS→2-人工待审
        ReviewTaskStatusEnum targetStatus;
        Layer2RiskEnum layer2Risk;
        if (result.verdict() == VerdictEnum.PASS) {
            targetStatus = ReviewTaskStatusEnum.AUTO_PASS;
            layer2Risk = Layer2RiskEnum.LOW;
        } else if (result.verdict() == VerdictEnum.REJECT) {
            targetStatus = ReviewTaskStatusEnum.REJECT;
            layer2Risk = Layer2RiskEnum.HIGH;
        } else {
            targetStatus = ReviewTaskStatusEnum.MANUAL_PENDING;
            layer2Risk = Layer2RiskEnum.MID;
        }
        // 7-1：moderation_total{verdict} 机审三分态计数（判定落库同源收口：缓存命中/真实送审两路均经此落库）
        moderationMetrics.incrementTotal(result.verdict());

        // 步骤1：编程式事务（process 内部自调用不走代理，@Transactional 会失效，故用 TransactionTemplate）
        Long recordId = transactionTemplate.execute(tx -> {
            // CAS 流转 review_task：where id=? and status=0，affected=0→已被并发消费处理，放弃本次结果
            int affected = reviewTaskMapper.update(null, new LambdaUpdateWrapper<ReviewTask>()
                    .set(ReviewTask::getStatus, targetStatus)
                    .set(ReviewTask::getModVerdict, result.verdict())
                    .set(ReviewTask::getLayer2Risk, layer2Risk)
                    .set(ReviewTask::getUpdateTime, now)
                    .eq(ReviewTask::getId, task.getId())
                    .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.PENDING));
            if (affected == 0) {
                log.info("任务状态 CAS 流转落空（已被并发消费处理），放弃本次结果，taskId={}", task.getId());
                return null;
            }
            // 步骤2：追加 moderation_record（append-only 留痕；命中缓存也必追加，缓存命中备注置于 reason 首部）
            String reason = (cacheNote != null ? cacheNote + "；" : "") + result.reason();
            ModerationRecord record = new ModerationRecord()
                    .setModType(ModTypeEnum.LLM)
                    .setBizType(task.getBizType())
                    .setBizId(task.getBizId())
                    .setReviewTaskId(task.getId())
                    .setScene(task.getScene())
                    .setContentHash(event.getContentHash())
                    .setProvider(ModerationProviderEnum.LLM)
                    .setWordlibVer(wordlibVer)
                    .setModelVersion(fewShotVer)
                    .setVerdict(result.verdict())
                    .setCategory(result.category())
                    .setHitWords(task.getHitWords())
                    .setReason(truncate(reason, REASON_MAX_LENGTH))
                    .setProviderTraceId(result.providerTraceId())
                    .setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY))
                    .setOperatorId(0L)
                    .setOperatorName("system")
                    .setCostMs((int) Math.min(result.costMs(), Integer.MAX_VALUE))
                    .setCreateTime(now);
            moderationRecordMapper.insert(record);
            // 【板块五 5-1.1 机审 REJECT 联动处罚】verdict=REJECT 时在同一本地事务内落
            // punish_log(action=3-内容下架, operator_id=0-系统, score=分值快照, status=1)
            // + violation_score 原子累加（userId 按回查 DTO 归属解析）；5-1.2 红线：阈值判定只重放
            // action∈{1,2} 的 status=1 生效行，action=3 仅落流水不计入（不改 last_punish_action/
            // frozen_until，不影响登录态）——误计入=机审误判在申诉裁决前触发真实封禁；
            // 不落流水=零分累计重复违规。事件发布由闸口 afterCommit 收口（发布失败→DLQ 兑底）
            if (result.verdict() == VerdictEnum.REJECT
                    && !MachineRejectPunishGate.recordRejectPunish(
                    new MachineRejectPunishGate.RejectPunishContext(ownerId, event.getBizType(),
                            task.getBizId(), task.getScene().getCode(), task.getId(),
                            event.getContentHash(), MACHINE_REJECT_SCORE,
                            sanitizeReason(result.reason()), MDC.get(MqSendUtil.TRACE_ID_MDC_KEY)),
                    machineRejectPunishAdapter, punishResultPublisher::publish)) {
                // 落库失败：处罚记账静默丢失=违规者零分累计，宁严勿松——抛非预期异常回滚整个审核事务
                // （含留痕/任务 CAS 同生共死），Consumer nack 入 DLQ 延迟重试
                throw new IllegalStateException("机审 REJECT 处罚记账落库失败，回滚审核事务，taskId=" + task.getId());
            }
            // 步骤3：注册事务提交后回调——写缓存 + 发 result 事件（均在事务成功提交后执行，防脏缓存/脏事件）。
            // 注册必须放在事务模板 lambda 内（此刻事务同步处于活动状态）：若在 execute 返回后才注册，
            // 独立事务场景下事务同步已随提交清理，必抛 Transaction synchronization is not active（Q88 修复）
            String sceneCode = task.getScene().getCode();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 步骤4：落库成功后写审核结果缓存（仅 PASS/REJECT 终局结论；SUSPICIOUS 内部跳过）
                    moderationResultCache.save(sceneCode, event.getContentHash(), wordlibVer, fewShotVer,
                            result.verdict(), result.category(), result.reason(), record.getId());
                    ModerationResultEvent evt = new ModerationResultEvent();
                    evt.setEventId(UUID.randomUUID().toString());
                    evt.setEventTime(LocalDateTime.now());
                    evt.setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
                    evt.setVersion(1);
                    evt.setBizType(task.getBizType().getCode());
                    evt.setBizId(String.valueOf(task.getBizId()));
                    evt.setVerdict(result.verdict().getCode());
                    evt.setReviewTaskId(task.getId());
                    // 复用结果发布器统一发送（Q87 修复）：按 bizType 解析路由键（1,2,4→text.result / 5→message.result / 6→image.result），
                    // 原直发 moderation.result 与 content 侧 text.result 绑定不匹配，消息无法路由到消费队列；
                    // sendCritical 必达语义，发布失败发布器内部转 IllegalStateException → 消费端 nack 入 DLQ 兑底
                    moderationResultPublisher.publish(evt);
                }
            });
            return record.getId();
        });
        // 并发已处理→直接返回（本次消息 ack，不重复发事件）
        if (recordId == null) {
            return;
        }

        // 词级命中统计（§5.4 词库治理建议清单数据源）：任务 CAS 流转成功后按快照 hitWords 逐词累加，
        // 仅异步机审口径（HIGH/MID 命中均统计；同步闸门拦截发生在宿主本地不建任务故不在此口径内）；
        // 复用 requireWordScene 归一映射场景（ARTICLE_FEATURED 等审核场景 → ARTICLE 词库场景）；
        // recorder 内部单词失败仅 error 继续、整体不抛——统计属运营证据，绝不影响审核主链路
        wordlibHitStatRecorder.recordFromSnapshot(requireWordScene(task.getScene()), task.getHitWords());
    }

    /**
     * 解析事件场景为 ReviewSceneEnum（code 与枚举名一致）
     *
     * @param scene 事件携带的场景码
     * @return 审核场景枚举；非法场景抛 BizException（ack 丢弃脏消息）
     */
    private ReviewSceneEnum parseScene(String scene) {
        try {
            return ReviewSceneEnum.valueOf(scene.trim());
        } catch (IllegalArgumentException e) {
            log.error("文本送审事件 scene 非法，确认丢弃，scene={}", scene);
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
    }

    /**
     * 事件 bizType（Integer）→ ModerationBizTypeEnum 映射
     *
     * @param bizType 事件业务类型码
     * @return 枚举；未知码抛 BizException（ack 丢弃脏消息）
     */
    private ModerationBizTypeEnum toBizType(Integer bizType) {
        for (ModerationBizTypeEnum e : ModerationBizTypeEnum.values()) {
            if (e.getCode() == bizType) {
                return e;
            }
        }
        log.error("文本送审事件 bizType 非法，确认丢弃，bizType={}", bizType);
        throw new BizException(ErrorCode.PARAM_ERROR);
    }

    /**
     * DFA 词库场景归一映射（review_task.scene → WordLibraryLoader 场景码）：
     * ARTICLE_FEATURED/ARTICLE_COMMUNITY/ARTICLE_RE_REVIEW→ARTICLE；COMMENT/COMMENT_RE_REVIEW→COMMENT；
     * MESSAGE/MESSAGE_RE_REVIEW→MESSAGE；USERNAME 同名直映
     *
     * @param scene 审核场景
     * @return 词库场景码；图片场景不走 DFA，出现即脏消息抛 BizException（ack 丢弃）
     */
    private String requireWordScene(ReviewSceneEnum scene) {
        return switch (scene) {
            case ARTICLE_FEATURED, ARTICLE_COMMUNITY, ARTICLE_RE_REVIEW -> "ARTICLE";
            case COMMENT, COMMENT_RE_REVIEW -> "COMMENT";
            case MESSAGE, MESSAGE_RE_REVIEW -> "MESSAGE";
            case USERNAME -> "USERNAME";
            default -> {
                log.error("文本送审消费者收到非文本场景，确认丢弃，scene={}", scene.getCode());
                throw new BizException(ErrorCode.PARAM_ERROR);
            }
        };
    }

    /**
     * 缓存 verdict code → VerdictEnum（白名单校验，防脏缓存）
     */
    private VerdictEnum toVerdict(Integer code) {
        if (Objects.equals(code, ModerationConstant.VERDICT_PASS)) {
            return VerdictEnum.PASS;
        }
        if (Objects.equals(code, ModerationConstant.VERDICT_REJECT)) {
            return VerdictEnum.REJECT;
        }
        throw new IllegalStateException("缓存结论 verdict 非终局值，降级送审处理：" + code);
    }

    /**
     * 缓存 category code → ModerationCategoryEnum（白名单校验，未知值归 NONE 防脏缓存外溢）
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
     * reason 落库截断（LLM 输出为不可信数据，防超长中断审核写入）
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
     * @param reason 原始判定理由（LLM 不可信输出）
     * @return 处理后的安全理由
     */
    private String sanitizeReason(String reason) {
        if (reason == null || reason.isEmpty()) {
            return reason;
        }
        String cleaned = reason.replace("\r", "").replace("\n", " ");
        return cleaned.length() <= REASON_MAX_LENGTH ? cleaned : cleaned.substring(0, REASON_MAX_LENGTH);
    }

    /**
     * 文本回查结果（待审文本 + 归属人）：归属人供 5-1 机审 REJECT 处罚上下文组装
     * （文章/评论取 ContentDTO.ownerId、私信取 MessageDTO.senderId）
     */
    private record FetchedText(String text, Long ownerId) {
    }
}