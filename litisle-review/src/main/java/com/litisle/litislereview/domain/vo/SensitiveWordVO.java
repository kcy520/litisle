package com.litisle.litislereview.domain.vo;

import com.litisle.litislereview.enums.WordLevelEnum;
import com.litisle.litislereview.enums.WordListTypeEnum;
import com.litisle.litislereview.enums.WordSceneEnum;
import com.litisle.litislereview.enums.WordStatusEnum;
import com.litisle.litislereview.enums.WordSuggestionMarkEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 敏感词库列表出参（GET /api/review/word/list）
 * <p>不回吐 updatedBy（操作人ID）与 version（行级版本快照）等服务端审计字段，
 * 仅保留运营可视信息；枚举字段按 @JsonValue 序列化为中文 desc</p>
 *
 * <p>§5.4 治理建议清单：建议标记+证据字段为异步机审观察量聚合结果（当前页批量查询后内存计算），
 * 标记只是证据、禁自动动作；命中口径不含同步闸门拦截（宿主本地不建任务），HIGH 词比率虚高仅参考</p>
 */
@Data
@Accessors(chain = true)
public class SensitiveWordVO {

    /** 敏感词行 id */
    private Long id;

    /** 敏感词内容 */
    private String word;

    /** 场景（序列化为 desc：文章/评论/用户名/私信） */
    private WordSceneEnum scene;

    /** 级别（序列化为 desc：高危/中危） */
    private WordLevelEnum level;

    /** 名单类型（序列化为 desc：白名单/黑名单） */
    private WordListTypeEnum isBlacklist;

    /** 启停状态（序列化为 desc：禁用/启用） */
    private WordStatusEnum status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /**
     * 治理建议标记（三类口径见 WordSuggestionMarkEnum；null=无建议——白名单/禁用词/证据不足均不标记）；
     * 只是证据展示，转正/降级一律运营手动（复用既有修改词接口）
     */
    private WordSuggestionMarkEnum suggestionMark;

    /** 累计命中次数（wordlib_hit_stat 异步机审口径，HIGH/MID 命中均统计；无统计行=0） */
    private Integer hitCount;

    /** 累计误报上报次数（wordlib_misreport sum(report_count)；无记录=0） */
    private Integer reportCount;

    /** 去重上报人数（wordlib_misreport count(*)，唯一键保证按人去重；无记录=0） */
    private Integer reporterCount;

    /**
     * 参考误报率（reportCount/hitCount，保留 4 位小数）；hitCount=0 或 reportCount=0 时为 null——
     * ⚠️ 分母不含同步闸门拦截量，HIGH 词该比率虚高，降级裁决以 reportCount 绝对量为主
     */
    private Double misreportRate;
}
