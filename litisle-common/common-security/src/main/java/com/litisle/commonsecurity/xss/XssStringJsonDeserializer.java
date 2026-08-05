package com.litisle.commonsecurity.xss;

e:\NewJavaPro\LitIsle\litisle-common\common-security\src\main\java\com\litisle\commonsecurity\xss\XssStringJsonDeserializer.java
package com.litisle.commonsecurity.xss;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * XSS JSON 字符串反序列化器
 *
 * 【设计说明】
 * 1. 自定义 Jackson 反序列化器，对 JSON 请求体中的 String 字段进行 XSS 清洗
 * 2. 配合 @JsonDeserialize 使用，或通过 ObjectMapper 全局配置
 * 3. 补充 XssHttpServletRequestWrapper 的盲区：JSON 请求体（@RequestBody）
 *
 * 【为什么需要两个 XSS 防护】
 * - XssHttpServletRequestWrapper 只能处理表单参数（getParameter）
 * - @RequestBody 的 JSON 数据通过 Jackson 反序列化，不走 getParameter
 * - 因此需要 Jackson 反序列化器来清洗 JSON 中的 String 字段
 *
 * 【StdScalarDeserializer 说明】
 * - Jackson 提供的标量反序列化基类，适用于 String/Number/Boolean
 * - 继承它可简化实现，只需重写 deserialize 方法
 *
 * 【全局配置方式】（在 JacksonConfig 中）
 * SimpleModule module = new SimpleModule();
 * module.addDeserializer(String.class, new XssStringJsonDeserializer());
 * objectMapper.registerModule(module);
 *
 * @author litisle
 */
@Slf4j
public class XssStringJsonDeserializer extends StdScalarDeserializer<String> {

    /**
     * 无参构造（Jackson 反射创建）
     */
    public XssStringJsonDeserializer() {
        super(String.class);
    }

    /**
     * 反序列化方法：对 JSON 字符串值进行 XSS 清洗
     *
     * @param p     JSON 解析器
     * @param ctxt  反序列化上下文
     * @return 清洗后的字符串
     */
    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String value = p.getValueAsString();
        if (value == null) {
            return null;
        }
        // 复用 XssHttpServletRequestWrapper 的清洗逻辑
        return XssHttpServletRequestWrapper.cleanHtmlTag(value);
    }
}