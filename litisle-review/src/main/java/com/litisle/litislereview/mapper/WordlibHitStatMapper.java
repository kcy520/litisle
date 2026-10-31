package com.litisle.litislereview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.entity.WordlibHitStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * wordlib_hit_stat 词级命中统计表 Mapper（MyBatis-Plus）
 *
 * <p>写入收口：仅 WordlibHitStatRecorder（异步机审命中累加链路）；
 * 查询供词库治理建议清单（listWords 按 scene+word 批量取命中观察量算建议标记）</p>
 */
@Mapper
public interface WordlibHitStatMapper extends BaseMapper<WordlibHitStat> {

    /**
     * 同场景同词命中次数原子累加 + 刷新最近命中时间（SQL 侧自增，避免读-改-写竞态）
     *
     * @param scene 场景 code（WordSceneEnum 入库值）
     * @param word  命中词（词库规范词）
     * @return 影响行数：0=无既有行（调用方需走首次插入路径）
     */
    @Update("update wordlib_hit_stat set hit_count = hit_count + 1, last_hit_time = now() "
            + "where scene = #{scene} and word = #{word}")
    int incrementHitCount(@Param("scene") String scene,
                          @Param("word") String word);
}
