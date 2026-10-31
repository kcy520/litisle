package com.litisle.litislereview.domain.dto;

import lombok.Data;

/**
 * 词级误报聚合查询出参（wordlib_misreport 按 scene+word 聚合行，词库治理建议清单数据源）
 *
 * <p>聚合口径：uk_reporter_word(reporter_id, scene, word) 唯一键保证一词一上报人至多一行，
 * 故 count(*) 即去重上报人数、sum(report_count) 即累计上报次数（分子），
 * 与 wordlib_hit_stat.hit_count（异步命中分母）配对算参考误报率</p>
 */
@Data
public class WordMisreportStatDTO {

    /** 场景 code（WordSceneEnum 入库值） */
    private String scene;

    /** 误报词 */
    private String word;

    /** 累计上报次数（sum(report_count)，误报率分子） */
    private Integer reportTotal;

    /** 去重上报人数（count(*)，唯一键保证与行数等价） */
    private Integer reporterCount;
}
