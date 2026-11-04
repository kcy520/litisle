package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.EventOutbox;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Mapper + extends BaseMapper<EventOutbox>（MyBatis-Plus）
 *
 * 关键查询：
 * - 重发扫描：selectList(status in (0-待发送, 2-发送失败) && retry_count < 上限，order by create_time asc limit 批量上限)
 *   走 idx_status_create；EventOutboxResendJob 每轮消费
 * - 已发送行清理：delete(status=1 && update_time < 保留期)，走 idx_sent_update；同 job 内顺带执行
 * - 生产者写入：insert（status=0）+ 发送结果回写 update（status=1/2），均由 ContentEventProducer 调用
 */
@Mapper
public interface EventOutboxMapper extends BaseMapper<EventOutbox> {

}
