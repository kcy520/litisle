package com.litisle.litislereview.moderation.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litislereview.domain.entity.ModerationRecord;
import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import com.litisle.litislereview.mapper.ModerationRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 图片申诉平反缓存失效器（板块五 5-5.2，review 侧对称恢复半边）
 *
 * 当一张图片的申诉被判定为“平反（PASS / REVOKED）”时，精确清除该图片在 Redis 中的旧审核结论缓存，
 * 避免用户虽然申诉成功，但系统仍然因为缓存命中旧结果而继续拒绝该图片。
 *
 * 【职责】bizType=6（图片）申诉改判 PASS（平反）时，按该图机审留痕（moderation_record）
 * 精确重建并删除 review:img-result:* 键 + review:img-blacklist:{sha256} 键——
 * 否则平反图在 TTL 窗口内仍命中旧 REJECT 结论被拒传（黑名单）/仍判驳回（结果缓存），申诉闭环断裂。
 *
 * 【为什么按留痕行重建键（而非 review_task.contentHash 拼键）】
 * - 配图复审（ARTICLE_IMG_RE_REVIEW）：任务 content_hash 为全图聚合指纹（全部配图 sha256
 *   排序拼接后再 sha256），而 img-result 键按单图 sha256 存储——按任务拼键必错位漏删；
 * - 单图审（IMAGE）：不建 review_task，按任务拼键无从谈起；
 * - 留痕行 provider/model_version 列已随判定固化（§2.11 版本化纪律），逐行重建的键与
 *   机审写入键完全同源，零版本漂移；且为 review 本地 DB 回查，零跨服务调用。
 *
 * 【调用方】AppealServiceImpl.invalidateModerationCache 的 bizType=6 分支委派本组件
 * （替换既有按 reviewTask.contentHash 的粗粒度删除）；仅改判 REVOKED（平反）时调用，
 * 维持驳回不清理（旧 REJECT 结论仍有效，隔离保持）。
 *
 * 【失败语义】删除失败仅 error 留痕不抛异常——裁决事务已提交，不可因删缓存失败回滚裁决；
 * 残留键由 TTL 过期兜底（对齐 5-4.5 缓存失效失败语义，宁严勿松）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageAppealCacheInvalidator {

    /** 机审留痕 Mapper：按 biz_type=6 + biz_id 回查该图全部留痕行（provider/modelVersion/content_hash 版本段来源） */
    private final ModerationRecordMapper moderationRecordMapper;

    /** Redis 客户端：img-result/img-blacklist 键批量删除 */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 图片申诉平反缓存失效：按留痕行精确重建缓存键并批量删除
     *
     * <p>append-only 多行留痕（多轮机审/复审/缓存命中各一行）逐行取用，
     * 覆盖该图全部历史版本键，防「换模型/换 provider 后旧键残留」。</p>
     *
     * @param fileId 图片文件 ID（appeal.bizId，biz_type=6 的业务 ID）
     */
    public void invalidate(Long fileId) {
        // ===== 步骤0：入参闸门 =====
        // fileId 判空 fail-fast（静默返回会留下平反图仍被拒传的闭环断点）
        if (fileId == null) {
            log.warn("图片申诉平反缓存失效跳过：fileId 为空，无缓存键可重建");
            return;
        }
        try {
            // ===== 步骤1：回查该图全部机审留痕行 =====
            // select * from moderation_record where biz_type=6 and biz_id=fileId（append-only 全量取）
            List<ModerationRecord> records = moderationRecordMapper.selectList(
                    new LambdaQueryWrapper<ModerationRecord>()
                            .eq(ModerationRecord::getBizType, ModerationBizTypeEnum.FILE)
                            .eq(ModerationRecord::getBizId, fileId));
            if (records == null || records.isEmpty()) {
                // 无留痕行：该图未经机审（历史数据），无键可删，直接返回
                log.info("图片申诉平反缓存失效：无机审留痕行，fileId={}", fileId);
                return;
            }
            // ===== 步骤2：逐行重建缓存键（版本段取留痕行固化值，禁取当前配置防漂移） =====
            Set<String> keys = new HashSet<>();
            for (ModerationRecord record : records) {
                String contentHash = record.getContentHash();
                if (contentHash == null || contentHash.isEmpty()) {
                    // 无 sha256 的留痕行无法重建任何键，跳过（TTL 兜底）
                    continue;
                }
                // 2a. img-result：键口径与 ModerationImageService 写键处完全同源
                //     （provider/modelVersion 为空的历史行跳过该键——无版本段无法命中原键，TTL 兜底）
                if (record.getProvider() != null && record.getModelVersion() != null
                        && !record.getModelVersion().isEmpty()) {
                    keys.add(String.format(ModerationConstant.IMG_RESULT_KEY,
                            record.getProvider().getCode(), record.getModelVersion(), contentHash));
                }
                // 2b. img-blacklist：Set 去重，同一 sha256 多行留痕只删一次
                keys.add(String.format(ModerationConstant.IMG_REJECT_BLACKLIST_KEY, contentHash));
            }
            // ===== 步骤3：批量删除 + 失败语义 =====
            // keys 非空才执行批量删除；异常仅 error 留痕不抛（裁决事务已提交不可回滚，残留键 TTL 过期兜底）
            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.error("图片申诉平反缓存失效失败（不阻断裁决主流程，TTL 过期兜底），fileId={}", fileId, e);
        }
    }
}
