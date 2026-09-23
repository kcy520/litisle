package com.litisle.litislefile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislefile.domain.entity.FileRef;
import org.apache.ibatis.annotations.Mapper;

/**
 * file_ref 引用登记表 Mapper（方案C，MyBatis-Plus）
 *
 * 便捷方法全由 BaseMapper<FileRef> 提供，零 XML、零自定义方法，
 * 调用方（FileServiceImpl）配 LambdaQueryWrapper 使用：
 * - 登记/解绑引用：insert(fileRef) / delete(lqw.eq(fileId).eq(bizType).eq(bizId).eq(ownerId))
 *   —— delete 接口 Step1，仅解除当前调用方引用
 * - 活跃引用校验：selectCount(lqw.eq(fileId)) —— delete 接口 Step2
 *   （仍有他人引用则不动文件）及对账任务物理清理前置校验
 *   （仍有引用 → 跳过并告警，兜底不误删）
 * - 物理清理残留行：delete(lqw.eq(fileId)) —— 对账任务 Step2 删元数据时防御性同删
 * - 引用审计：selectList(lqw.eq(fileId)) —— 随时可答"谁在引用该文件"，漏登记可定位
 */
@Mapper
public interface FileRefMapper extends BaseMapper<FileRef> {
}