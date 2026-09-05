package com.litisle.litisleuser.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充器
 *
 * 【生效前提】实体字段须标注 fill：
 *   create_time  -> @TableField(value = "create_time", fill = FieldFill.INSERT)
 *   update_time  -> @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
 *
 * 【语义】strictInsertFill / strictUpdateFill 只在字段值为 null 时填充，
 *         不会覆盖业务代码显式设置的时间值
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }


}