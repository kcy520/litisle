package com.litisle.litislereview.moderation.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litislereview.domain.entity.ModerationRecord;
import com.litisle.litislereview.enums.ModerationCategoryEnum;
import com.litisle.litislereview.enums.ModerationProviderEnum;
import com.litisle.litislereview.enums.ReviewSceneEnum;
import com.litisle.litislereview.enums.VerdictEnum;
import com.litisle.litislereview.mapper.ModerationRecordMapper;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 文本审核结果缓存（板块三 3-1 步骤5/6，§2.11 版本化缓存）
 *
 * 【两级查重】Redis 命中 → 直接取 verdict；Redis miss → 查 moderation_record 兜底
 * （idx_hash：where content_hash=? and scene=? order by id desc limit 1）。
 *
 * 【可复用四条件】命中行必须同时满足，任一不符视为未命中：
 * - verdict ∈ {1-PASS, 2-REJECT}（SUSPICIOUS 不参与复用）
 * - provider = 当前值（防 provider 切换复用旧结论）
 * - wordlib_ver = 当前值（防词库热更后旧结论放行）
 * - model_version = 当前 fewShotVer（防 prompt 升级复用旧结论）
 * - create_time 在 cache.ttl-hours(72)±10% 窗口内
 *
 * 【纪律】Redis key 各段均服务端派生（版本号/枚举/SHA256），禁拼用户原串；
 * 缓存 value 一律 JSON；TTL 一律 ±10% 抖动（防同批内容集体过期雪崩）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationResultCache {

    /** Redis（一级缓存读写） */
    private final StringRedisTemplate stringRedisTemplate;

    /** JSON 序列化器（缓存 value 一律 JSON，禁 Java 原生序列化） */
    private final ObjectMapper objectMapper;

    /** 审核记录表（二级兜底查询：Redis miss 后按 idx_hash 四条件过滤） */
    private final ModerationRecordMapper moderationRecordMapper;

    /** 可调参数（cache.ttl-hours / ttl-jitter-ratio） */
    private final ModerationProperties moderationProperties;

    /**
     * 缓存查重：Redis 命中→解析 JSON 返回；miss→moderation_record 兜底查询（四条件过滤）
     * 根据「场景 + 内容指纹 + 当前版本参数」，先查 Redis，再兜底查 DB，判断是否存在可复用的终局审核结论（PASS / REJECT）。
     *
     * @param scene       审核场景（review_task.scene code）
     * @param contentHash 内容指纹（正文 SHA256）
     * @param wordlibVer  当前词库版本（缓存 key + 兜底过滤）
     * @param fewShotVer  当前 few-shot 版本（缓存 key + 兜底过滤，即文本 model_version）     few-shot 提示词版本：变更示例时 +1，消费者侧纳入 model_version 留痕
     * @param provider    当前文本 provider（兜底过滤，防 provider 切换复用）
     * @return 可复用结论；未命中返回 Optional.empty()
     */
    public Optional<CachedVerdict> find(String scene, String contentHash, long wordlibVer,
                                        String fewShotVer, ModerationProviderEnum provider) {
        // 步骤1：拼缓存 key = String.format(ModerationConstant.TEXT_RESULT_KEY,
        //        wordlibVer, fewShotVer, scene, contentHash)（四段均服务端派生）
        String key = buildKey(scene, contentHash, wordlibVer, fewShotVer);
        // 步骤2：stringRedisTemplate.opsForValue().get(key)；非空→ObjectMapper 解析 JSON 为 CachedVerdict 返回
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null) {
                return Optional.of(parseAndValidate(json));
            }
        } catch (Exception e) {
            // 步骤3：value 格式异常/Redis 异常→warn 后按未命中处理（降级送审，不信任脏缓存）
            log.warn("Redis 读取文本审核结果缓存异常或 value 非法，降级走 moderation_record 兑底，key={}", key, e);
        }
        // 步骤4：Redis miss→moderationRecordMapper.selectOne(LambdaQueryWrapper)：
        //        eq(content_hash).eq(scene).in(verdict, PASS, REJECT)
        //        .eq(provider).eq(wordlib_ver).eq(model_version=fewShotVer)
        //        .ge(create_time, now - ttlHours*(1+jitter)).orderByDesc(id).last("limit 1")
        ReviewSceneEnum sceneEnum = toSceneEnum(scene);
        if (sceneEnum == null) {
            return Optional.empty();
        }
        ModerationRecord record = moderationRecordMapper.selectOne(new LambdaQueryWrapper<ModerationRecord>()
                .eq(ModerationRecord::getContentHash, contentHash)
                .eq(ModerationRecord::getScene, sceneEnum)
                .in(ModerationRecord::getVerdict, List.of(VerdictEnum.PASS, VerdictEnum.REJECT))
                .eq(ModerationRecord::getProvider, provider)
                .eq(ModerationRecord::getWordlibVer, wordlibVer)
                .eq(ModerationRecord::getModelVersion, fewShotVer)
                .ge(ModerationRecord::getCreateTime, LocalDateTime.now().minus(jitteredTtl()))
                .orderByDesc(ModerationRecord::getId)
                .last("limit 1"));
        if (record == null) {
            return Optional.empty();
        }
        // 步骤5：命中→组装 CachedVerdict 返回；无行→Optional.empty()
        CachedVerdict cv = new CachedVerdict();
        cv.setVerdict(record.getVerdict().getCode());
        cv.setCategory(record.getCategory() != null
                ? record.getCategory().getCode() : ModerationCategoryEnum.NONE.getCode());
        cv.setReason(record.getReason());
        cv.setRecordId(record.getId());
        return Optional.of(cv);
    }

    /**
     * 写入缓存（仅缓存终局结论 PASS/REJECT；SUSPICIOUS 不缓存）
     *
     * @param scene       审核场景
     * @param contentHash 内容指纹
     * @param wordlibVer  当前词库版本
     * @param fewShotVer  当前 few-shot 版本
     * @param verdict     终局判定（非 PASS/REJECT 直接跳过不写）
     * @param category    违规类目
     * @param reason      判定理由
     * @param recordId    本次留痕行 ID（命中复用时 reason 记录「原记录ID=X」用）
     */
    public void save(String scene, String contentHash, long wordlibVer, String fewShotVer,
                     VerdictEnum verdict, ModerationCategoryEnum category, String reason, Long recordId) {
        // 步骤1：verdict 非 PASS/REJECT→直接返回（SUSPICIOUS 非终局不进缓存）
        if (verdict != VerdictEnum.PASS && verdict != VerdictEnum.REJECT) {
            return;
        }
        String key = buildKey(scene, contentHash, wordlibVer, fewShotVer);
        try {
            CachedVerdict cv = new CachedVerdict();
            cv.setVerdict(verdict.getCode());
            cv.setCategory(category != null ? category.getCode() : ModerationCategoryEnum.NONE.getCode());
            cv.setReason(reason);
            cv.setRecordId(recordId);
            // 步骤2：拼缓存 key（同 find）+ ObjectMapper 序列化 CachedVerdict 为 JSON
            String json = objectMapper.writeValueAsString(cv);
            // 步骤3：stringRedisTemplate.opsForValue().set(key, json, jitteredTtl())（TTL ±10% 抖动）
            stringRedisTemplate.opsForValue().set(key, json, jitteredTtl());
        } catch (Exception e) {
            // 缓存写失败不影响主流程（事务已提交、事件已发），仅告警：下次查重 miss 后走 DB 兑底或重新送审
            log.warn("文本审核结果缓存写入失败（不影响主流程），key={}", key, e);
        }
    }

    /**
     * TTL ±10% 抖动（cache.ttl-hours * (1 ± ttl-jitter-ratio)），防同批内容集体过期雪崩
     */
    private java.time.Duration jitteredTtl() {
        // 步骤1：读 moderationProperties.cache.ttlHours / ttlJitterRatio
        ModerationProperties.Cache cache = moderationProperties.getCache();
        double ratio = cache.getTtlJitterRatio();
        // 步骤2：随机因子落在 [1-ratio, 1+ratio]，乘 ttlHours 得抖动后秒数 → Duration
        double factor = 1 - ratio + ThreadLocalRandom.current().nextDouble(2 * ratio);
        return Duration.ofSeconds((long) (cache.getTtlHours() * 3600 * factor));
    }

    /**
     * 拼缓存 key（四段均服务端派生：版本号/枚举/SHA256，禁拼用户原串）
     */
    private String buildKey(String scene, String contentHash, long wordlibVer, String fewShotVer) {
        return String.format(ModerationConstant.TEXT_RESULT_KEY, wordlibVer, fewShotVer, scene, contentHash);
    }

    /**
     * 解析缓存 JSON 并校验 verdict 为终局值（防脏缓存外溢，非法则抛异常由调用方降级）
     */
    private CachedVerdict parseAndValidate(String json) throws com.fasterxml.jackson.core.JsonProcessingException {
        CachedVerdict cv = objectMapper.readValue(json, CachedVerdict.class);
        if (cv == null || cv.getVerdict() == null
                || (cv.getVerdict() != ModerationConstant.VERDICT_PASS
                && cv.getVerdict() != ModerationConstant.VERDICT_REJECT)) {
            throw new IllegalStateException("缓存 value 结构/verdict 非终局值，按未命中降级处理");
        }
        return cv;
    }

    /**
     * 场景码 → ReviewSceneEnum（缓存兜底 DB 过滤用；非法场景按未命中处理返回 null）
     */
    private ReviewSceneEnum toSceneEnum(String scene) {
        try {
            return ReviewSceneEnum.valueOf(scene);
        } catch (IllegalArgumentException e) {
            log.warn("缓存兑底查询场景非法，按未命中处理，scene={}", scene);
            return null;
        }
    }

    /**
     * 缓存值载体（内部 JSON 结构；verdict/category 存 code 而非枚举，避免 @JsonValue 序列化为中文 desc）
     */
    @Data
    public static class CachedVerdict implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 终局判定 code（1-PASS 2-REJECT） */
        private Integer verdict;

        /** 违规类目 code（politics/porn/abuse/ad/none） */
        private String category;

        /** 判定理由 */
        private String reason;

        /** 原留痕行 ID（复用时写入新留痕 reason=「命中缓存，原记录ID=X」） */
        private Long recordId;
    }
}
