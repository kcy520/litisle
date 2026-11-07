package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.UserLike;
import org.apache.ibatis.annotations.Mapper;

/**
 * user_like 点赞表 Mapper（批次十一 11.8）。
 *
 * <p>点赞插入依赖唯一键异常见 {@link org.springframework.dao.DuplicateKeyException} 做幂等；
 * 取消点赞用条件删除并判断影响行数，未额外定义 SQL。</p>
 *
 * <p>必须标注 {@link Mapper}：content 未配置全局 {@code @MapperScan}，漏标时启动即报
 * "required a bean of type UserLikeMapper that could not be found"。</p>
 *
 * @author litisle
 */
@Mapper
public interface UserLikeMapper extends BaseMapper<UserLike> {
}
