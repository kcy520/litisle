package com.litisle.litislereview.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislereview.enums.WordSceneEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * wordlib_hit_stat 实体（词级命中统计表）
 *
 * <p>定位：词库治理建议清单的「命中观察量」证据分母——异步机审处理任务时按 hitWords 逐词原子累加，
 * 运营在词库列表按命中量/误报证据裁决 MID 转正与 HIGH 降级，标记只是证据、禁触发自动动作</p>
 *
 * <p>口径（已知局限，清单标注）：仅在 review 进程内异步机审处累加（所有送审内容过 review，数据完整）；
 * 同步闸门 HIGH 拦截发生在宿主本地不统计——HIGH 词的误报率分母缺失（比率虚高），降级建议一期以
 * 误报绝对量为主、比率仅作参考</p>
 *
 * <p>去重口径：scene+word 唯一键，同词重复命中原子累加 hit_count 不新增行（照抄 wordlib_misreport
 * upsert 模板：先累加→无行首插→撞唯一键降级累加）</p>
 */
@Data
@Accessors(chain = true)
@TableName("wordlib_hit_stat")
public class WordlibHitStat implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 统计ID（自增主键）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 场景：ARTICLE/COMMENT/USERNAME/MESSAGE/BIO
     */
    @TableField("scene")
    private WordSceneEnum scene;

    /**
     * 命中词（词库规范词，AC 引擎 hitWords 留痕语义）
     */
    @TableField("word")
    private String word;

    /**
     * 累计命中次数（异步机审建任务时原子累加；HIGH/MID 命中均统计）
     */
    @TableField("hit_count")
    private Integer hitCount;

    /**
     * 首次命中时间
     */
    @TableField("first_time")
    private LocalDateTime firstTime;

    /**
     * 最近命中时间（重复命中刷新）
     */
    @TableField("last_hit_time")
    private LocalDateTime lastHitTime;
}
