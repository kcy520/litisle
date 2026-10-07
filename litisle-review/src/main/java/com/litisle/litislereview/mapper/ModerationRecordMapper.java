package com.litisle.litislereview.mapper;



import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.entity.ModerationRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * moderation_record 审核记录表 Mapper（MyBatis-Plus，append-only 不可改不可删）
 *
 * 使用约束：
 * - 只允许 insert 与 select，禁止封装 update/delete（合规留痕 ≥6 个月的硬约束）
 * - 缓存兜底查询：按 biz_type+biz_id+content_hash 定位，过滤 provider + model_version + wordlib_ver
 *   （防 onnx→aliyun 切换或词库热更后复用旧结论）
 * - 历史链路：selectList(biz_type+biz_id) 供申诉/复审/审计回溯
 */
@Mapper
public interface ModerationRecordMapper extends BaseMapper<ModerationRecord> {
}
