package com.litisle.commoncore.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置类
 * 
 * 【设计说明】
 * 1. 配置 MyBatis-Plus 插件，如分页、乐观锁等
 * 2. 使用 @ConditionalOnClass 确保仅在引入 MyBatis-Plus 时生效
 * 3. 所有服务共享同一配置，避免重复配置
 * 
 * 【插件说明】
 * - PaginationInnerInterceptor：分页插件，自动拼接分页 SQL
 * - OptimisticLockerInnerInterceptor：乐观锁插件，自动处理 version 字段
 * 
 * 【学习要点】
 * - MybatisPlusInterceptor：插件拦截器，按顺序执行
 * - 插件顺序很重要：分页插件应放在最后
 * 
 * @author litisle
 */
@Configuration
@ConditionalOnClass(MybatisPlusInterceptor.class)
public class MyBatisPlusConfig {

    /**
     * 配置 MyBatis-Plus 插件
     * 
     * @return MybatisPlusInterceptor 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        
        // 乐观锁插件（自动处理 version 字段）
        // 【说明】更新时自动 +1，并在 WHERE 条件中添加 version = oldVersion
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        
        // 分页插件（自动拼接 LIMIT 语句）
        // 【说明】DbType.MYSQL 指定数据库类型，支持 MySQL、PostgreSQL 等
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        // 设置最大单页限制数量，默认 500 条，-1 不受限制
        paginationInterceptor.setMaxLimit(500L);
        interceptor.addInnerInterceptor(paginationInterceptor);
        
        return interceptor;
    }
}