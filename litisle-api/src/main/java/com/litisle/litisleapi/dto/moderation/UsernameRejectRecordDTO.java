package com.litisle.litisleapi.dto.moderation;


import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 用户名注册拒绝取证 DTO（ReviewClient.insertUsernameRejectRecord 契约载荷，4-7.1）
 *
 * 【使用场景】auth 注册链路 DFA HIGH 命中（DfaPublishGate.decide 判定 blocked=true）时，
 * 经 UsernameRejectGate.recordReject → RecordAdapter → ReviewClient 跨服务传至 review 侧，
 * 落 moderation_record 取证行（biz_type=3-用户名、biz_id=0 占位、不建 review_task、不进 MQ）
 *
 * 【隐私纪律】仅传派生指纹与命中词快照，严禁携带用户名明文/邮箱等 PII
 *
 * @author litisle
 */
@Data
public class UsernameRejectRecordDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 内容指纹 SHA-256(用户名)（SubmitTextAssembler 全站口径派生，64 位小写十六进制） */
    private String contentHash;

    /** DFA 命中词快照（提供方逗号拼接落 moderation_record.hit_words，禁回显用户） */
    private List<String> hitWords;

    /** 判定时词库版本快照（moderation_record.wordlib_ver 留痕） */
    private Long wordlibVer;
}
