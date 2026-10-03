package com.litisle.commonmq;

import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commonmq.consume.MqTraceSupport;
import com.litisle.commonmq.send.MqSendUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MqTraceSupport 单测：消费侧 traceId 恢复三分支（Header 恢复 / 缺失静默跳过 / clear 清理）。
 */
class MqTraceSupportTest {

    private static final String TRACE_ID = "trace-abc-123";

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private Message newMessageWithHeader(Object traceId) {
        MessageProperties props = new MessageProperties();
        if (traceId != null) {
            props.setHeader(HeaderConstant.TRACE_ID, traceId);
        }
        return new Message("body".getBytes(StandardCharsets.UTF_8), props);
    }

    @Test
    @DisplayName("Header 有 traceId → 写入当前线程 MDC（key 与发送侧 SSOT 一致）")
    void restore_withHeader_shouldPutMdc() {
        MqTraceSupport.restore(newMessageWithHeader(TRACE_ID));

        assertEquals(TRACE_ID, MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
    }

    @Test
    @DisplayName("Header 缺失 → 静默跳过，MDC 不写入（旧消息/定时任务直投场景）")
    void restore_withoutHeader_shouldSkipSilently() {
        MqTraceSupport.restore(newMessageWithHeader(null));

        assertNull(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
    }

    @Test
    @DisplayName("Header 为空白串 → 静默跳过，MDC 不写入脏数据")
    void restore_withBlankHeader_shouldSkipSilently() {
        MqTraceSupport.restore(newMessageWithHeader("   "));

        assertNull(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
    }

    @Test
    @DisplayName("message 为 null → 防御性判空，不抛 NPE")
    void restore_withNullMessage_shouldNotThrow() {
        MqTraceSupport.restore(null);

        assertNull(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
    }

    @Test
    @DisplayName("clear → MDC 移除，防线程池复用串扰")
    void clear_shouldRemoveMdc() {
        MDC.put(MqSendUtil.TRACE_ID_MDC_KEY, TRACE_ID);

        MqTraceSupport.clear();

        assertNull(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
    }

    @Test
    @DisplayName("完整链路：restore 后业务日志可读、clear 后残留不串扰下一条消息")
    void restoreThenClear_fullLifecycle() {
        MqTraceSupport.restore(newMessageWithHeader(TRACE_ID));
        assertEquals(TRACE_ID, MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));

        MqTraceSupport.clear();
        assertNull(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
    }
}