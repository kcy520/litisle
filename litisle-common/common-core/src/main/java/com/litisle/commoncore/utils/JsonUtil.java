package com.litisle.commoncore.utils;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON 工具类
 * 
 * 【设计说明】
 * 1. 封装 Jackson 的 ObjectMapper，提供便捷的 JSON 序列化/反序列化方法
 * 2. ObjectMapper 是线程安全的，使用静态常量共享实例
 * 3. 异常统一捕获并记录日志，避免业务代码处理繁琐的异常
 * 
 * 【使用场景】
 * - Redis 缓存：对象序列化为 JSON 存储
 * - MQ 消息：消息体序列化为 JSON
 * - 日志记录：将请求参数序列化为 JSON 便于排查
 * 
 * @author litisle
 */
@Slf4j
public class JsonUtil {

    /**
     * ObjectMapper 实例（线程安全）
     * 【说明】Spring 容器中也有 ObjectMapper，但这里独立创建，避免依赖 Spring 环境
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 对象序列化为 JSON 字符串
     * 
     * @param obj 待序列化对象
     * @return JSON 字符串，序列化失败返回 null
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("JSON序列化失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * JSON 字符串反序列化为对象
     * 
     * 【使用示例】
     * UserVO user = JsonUtil.fromJson(json, UserVO.class);
     * List<ArticleVO> list = JsonUtil.fromJson(json, new TypeReference<List<ArticleVO>>() {});
     * 
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @param <T>   泛型
     * @return 反序列化对象，失败返回 null
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("JSON反序列化失败: {}", e.getMessage(), e);
            return null;
        }
    }
}