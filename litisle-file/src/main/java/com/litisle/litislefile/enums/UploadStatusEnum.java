package com.litisle.litislefile.enums;


import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 上传状态枚举（两阶段写入：锁内占位 → 锁外 put）
 * 0-上传中（锁内占位写库）1-上传完成（锁外 put 成功后更新）
 *
 * put 失败保留 UPLOADING，由对账任务清理（恢复或废弃），见 seaweedfs笔记.md §3.4
 */
@Getter
public enum UploadStatusEnum {

    /** 上传中（锁内占位写库，未完成 put 前 URL 不可用） */
    UPLOADING(0, "上传中"),

    /** 上传完成（锁外 put 成功后更新，可正常访问） */
    COMPLETED(1, "上传完成");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    UploadStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}