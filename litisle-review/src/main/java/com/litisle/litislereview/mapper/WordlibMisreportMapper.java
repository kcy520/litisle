package com.litisle.litislereview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.dto.WordMisreportStatDTO;
import com.litisle.litislereview.domain.entity.WordlibMisreport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * wordlib_misreport 词库误报上报记录表 Mapper（MyBatis-Plus）
 *
 * <p>写入收口：仅 WordlibServiceImpl.reportMisreport（误报上报链路）；
 * 查询供灰度转正裁决（按 scene+word 聚合 report_count 算单词误报率）与 sweeper TTL 清理扫描</p>
 */
@Mapper
public interface WordlibMisreportMapper extends BaseMapper<WordlibMisreport> {

    /**
     * 同上报人同词累计次数原子累加 + 刷新最近上报时间（SQL 侧自增，避免读-改-写竞态）
     *
     * @param reporterId 上报人ID
     * @param scene      场景 code（WordSceneEnum 入库值）
     * @param word       误报词
     * @return 影响行数：0=无既有行（调用方需走首次插入路径）
     */
    @Update("update wordlib_misreport set report_count = report_count + 1, last_report_time = now() "
            + "where reporter_id = #{reporterId} and scene = #{scene} and word = #{word}")
    int incrementReportCount(@Param("reporterId") Long reporterId,
                             @Param("scene") String scene,
                             @Param("word") String word);

    /**
     * 按词批量聚合误报数据（词库治理建议清单数据源，§5.4 配套）
     *
     * <p>出参两列口径：report_total = sum(report_count)（累计误报次数）；
     * reporter_count = count(*)（去重上报人数——uk_reporter_word 唯一键保证一行=一个上报人，count(*) 即去重人数）</p>
     *
     * @param scene 场景 code（WordSceneEnum 入库值），null=不限场景（跨场景列表页按 scene+word 匹配）
     * @param words 当前页敏感词内容集合（批量 IN 查询，禁全表扫描）
     * @return 按 (scene, word) 分组的误报聚合行；无误报记录的词不出现在结果中
     */
    @Select("<script>select scene, word, sum(report_count) as report_total, count(*) as reporter_count "
            + "from wordlib_misreport where word in "
            + "<foreach collection='words' item='w' open='(' separator=',' close=')'>#{w}</foreach> "
            + "<if test='scene != null'>and scene = #{scene}</if> "
            + "group by scene, word</script>")
    List<WordMisreportStatDTO> aggregateByWords(@Param("scene") String scene, @Param("words") List<String> words);
}
