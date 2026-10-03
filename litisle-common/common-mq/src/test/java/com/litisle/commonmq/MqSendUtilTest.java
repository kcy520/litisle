package com.litisle.commonmq;

import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonmq.send.MqSendUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * MqSendUtil 单测：两档发送语义（send 尽力而为 / sendCritical 必达）+ traceId Header 注入。
 */
class MqSendUtilTest {

    private static final String EXCHANGE = "litisle.test.exchange";
    private static final String ROUTING_KEY = "litisle.test.rk";
    private static final String TRACE_ID = "trace-send-456";

    private RabbitTemplate rabbitTemplate;
    private MqSendUtil mqSendUtil;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        mqSendUtil = new MqSendUtil(rabbitTemplate);
        MDC.put(MqSendUtil.TRACE_ID_MDC_KEY, TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("send 成功 → exchange/routingKey/event 正确透传，traceId 注入消息 Header")
    void send_success_shouldInjectTraceIdHeader() {
        Object event = new Object();

        mqSendUtil.send(EXCHANGE, ROUTING_KEY, event);

        ArgumentCaptor<MessagePostProcessor> captor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), eq(event), captor.capture());

        Message message = captor.getValue().postProcessMessage(
                new Message("body".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        assertEquals(TRACE_ID, message.getMessageProperties().getHeader(HeaderConstant.TRACE_ID));
    }

    @Test
    @DisplayName("send 失败 → 吞掉异常不抛（尽力而为语义），主流程不阻断")
    void send_failure_shouldSwallowException() {
        doThrow(new AmqpException("broker 不可达")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class));

        assertDoesNotThrow(() -> mqSendUtil.send(EXCHANGE, ROUTING_KEY, new Object()));
    }

    @Test
    @DisplayName("MDC 无 traceId（定时任务场景）→ Header 不写入脏数据，发送照常")
    void send_withoutMdcTraceId_shouldNotWriteDirtyHeader() {
        MDC.clear();

        mqSendUtil.send(EXCHANGE, ROUTING_KEY, new Object());

        ArgumentCaptor<MessagePostProcessor> captor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), any(), captor.capture());

        Message message = captor.getValue().postProcessMessage(
                new Message("body".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        assertNull(message.getMessageProperties().getHeader(HeaderConstant.TRACE_ID));
    }

    @Test
    @DisplayName("sendCritical 首次成功 → 仅发送 1 次，不抛异常")
    void sendCritical_successFirstAttempt_shouldSendOnce() {
        mqSendUtil.sendCritical(EXCHANGE, ROUTING_KEY, new Object());

        verify(rabbitTemplate, times(1))
                .convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), any(), any(MessagePostProcessor.class));
    }

    @Test
    @DisplayName("sendCritical 首发失败 + 重试 1 次成功 → 共发送 2 次，不抛异常")
    void sendCritical_retryThenSuccess_shouldSendTwice() {
        doThrow(new AmqpException("瞬时抖动"))
                .doNothing()
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class));

        assertDoesNotThrow(() -> mqSendUtil.sendCritical(EXCHANGE, ROUTING_KEY, new Object()));

        verify(rabbitTemplate, times(2))
                .convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), any(), any(MessagePostProcessor.class));
    }

    @Test
    @DisplayName("sendCritical 重试耗尽（共 2 次全失败）→ 抛 BizException(MQ_SEND_FAILED/10015) 强制调用方表态")
    void sendCritical_retryExhausted_shouldThrowBizException() {
        doThrow(new AmqpException("broker 宕机"))
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class));

        BizException ex = assertThrows(BizException.class,
                () -> mqSendUtil.sendCritical(EXCHANGE, ROUTING_KEY, new Object()));

        assertEquals(ErrorCode.MQ_SEND_FAILED.getCode(), ex.getCode());
        verify(rabbitTemplate, times(2))
                .convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), any(), any(MessagePostProcessor.class));
    }
}