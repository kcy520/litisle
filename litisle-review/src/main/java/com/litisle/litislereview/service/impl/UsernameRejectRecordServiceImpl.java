package com.litisle.litislereview.service.impl;


import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.dto.moderation.UsernameRejectRecordDTO;
import com.litisle.litislereview.domain.entity.ModerationRecord;
import com.litisle.litislereview.enums.ModTypeEnum;
import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import com.litisle.litislereview.enums.ReviewSceneEnum;
import com.litisle.litislereview.enums.VerdictEnum;
import com.litisle.litislereview.mapper.ModerationRecordMapper;
import com.litisle.litislereview.service.UsernameRejectRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 用户名拒绝取证落库服务实现（4-7.1 USERNAME 同步拒绝唯一例外：只落 record 不建 task）
 *拒绝取证落库 = 拒绝 + 取证 + 落库，三个动作拼在一起：
 *
 * <p>【append-only 纪律】moderation_record 仅允许 insert/select，禁止 update/delete，
 * 满足《网络安全法》留痕硬凭证留存≥6 个月</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsernameRejectRecordServiceImpl implements UsernameRejectRecordService {

    /** moderation_record.reason 列长度（落库前截断，防超长中断写入） */
    private static final int REASON_MAX_LENGTH = 500;

    /** 命中词快照截断保护（text 列大容量，宁存不丢，但应用层仍兜底截断） */
    private static final int HIT_WORDS_MAX_LENGTH = 2000;

    /** 审核记录 Mapper（append-only） */
    private final ModerationRecordMapper moderationRecordMapper;

    /**
     * 用户名注册 DFA HIGH 拒绝取证落库：口径对齐 UsernameRejectGate.RecordAdapter 契约声明
     * <p>整体写入同一本地事务；载荷非法拒收返回 fail，不产生任何 DB 写入</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> insertRejectRecord(UsernameRejectRecordDTO dto) {
        // 步骤1：载荷闸门 fail-fast（缺指纹/命中词/版本属调用方误用，拒收防脏取证行）
        if (dto == null || !StringUtils.hasText(dto.getContentHash())
                || dto.getHitWords() == null || dto.getHitWords().isEmpty()
                || dto.getWordlibVer() == null) {
            log.warn("用户名拒绝取证载荷非法，拒收: contentHashEmpty={}, hitWordsEmpty={}, wordlibVerNull={}",
                    dto == null || !StringUtils.hasText(dto.getContentHash()),
                    dto == null || dto.getHitWords() == null || dto.getHitWords().isEmpty(),
                    dto == null || dto.getWordlibVer() == null);
            return Result.error(ErrorCode.PARAM_ERROR);
        }

        // 步骤2：组装取证行（列口径：mod_type=1-DFA、biz_type=3-用户名、biz_id=0 注册被拒无实体占位、
        //        scene=USERNAME、verdict=2-REJECT、provider/category/model_version 为空——DFA 无模型提供方）
        // reason 卫生处理：剥离换行符防 CRLF 伪造日志行（固定文案亦统一走该纪律）
        String reason = "用户名注册DFA同步拒绝（HIGH命中，不建任务仅取证，4-7.1）".replaceAll("[\\r\\n]", " ");
        ModerationRecord record = new ModerationRecord()
                .setModType(ModTypeEnum.DFA)
                .setBizType(ModerationBizTypeEnum.USERNAME)
                .setBizId(0L)
                .setScene(ReviewSceneEnum.USERNAME)
                .setContentHash(dto.getContentHash())
                .setWordlibVer(dto.getWordlibVer())
                .setVerdict(VerdictEnum.REJECT)
                .setHitWords(truncate(String.join(",", dto.getHitWords()), HIT_WORDS_MAX_LENGTH))
                .setReason(truncate(reason, REASON_MAX_LENGTH))
                .setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY))
                .setOperatorId(0L)
                .setOperatorName("system")
                .setCreateTime(LocalDateTime.now());

        // 步骤3：append-only 追加取证行（唯一 DB 写入，失败由事务回滚、调用方 error 留痕）
        moderationRecordMapper.insert(record);
        log.info("用户名拒绝取证落库成功: recordId={}, contentHash={}, wordlibVer={}, hitWords={}",
                record.getId(), dto.getContentHash(), dto.getWordlibVer(), record.getHitWords());
        return Result.success();
    }

    /**
     * 落库前截断保护（reason/hit_words 超长在 strict mode 会中断写入，证据宁存不丢）
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}