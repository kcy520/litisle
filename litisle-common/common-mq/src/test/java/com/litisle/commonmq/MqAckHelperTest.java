package com.litisle.commonmq;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonmq.consume.MqAckHelper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * MqAckHelper 单测：验证 ACK 纪律三分支（成功 ack / 业务性失败 ack 丢弃 / 非预期异常 nack 入 DLQ）。
 * 纯 Mockito 单测，不起 Spring 容器。
 */
class MqAckHelperTest {

    private static final long DELIVERY_TAG = 7L;
    private static final String QUEUE = "litisle.test.queue";

    private Channel channel;

    @BeforeEach
    void setUp() {
        channel = mock(Channel.class);
    }

    private Message newMessage() {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(DELIVERY_TAG);
        return new Message("body".getBytes(StandardCharsets.UTF_8), props);
    }

    @Test
    @DisplayName("分支1：业务成功 → basicAck(tag, false)，不 nack")
    void success_shouldAck() throws IOException {
        MqAckHelper.executeWithAck(QUEUE, newMessage(), channel, () -> { });

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("分支2：BizException 业务性失败 → warn 后 basicAck 丢弃（防毒丸），不 nack")
    void bizException_shouldAckDrop() throws IOException {
        MqAckHelper.executeWithAck(QUEUE, newMessage(), channel,
                () -> { throw new BizException(ErrorCode.PARAM_ERROR, "目标文件不存在"); });

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("分支3：非预期异常 → basicNack(tag, false, false) 经 DLX 入 DLQ，不 ack")
    void unexpectedException_shouldNackToDlq() throws IOException {
        MqAckHelper.executeWithAck(QUEUE, newMessage(), channel,
                () -> { throw new RuntimeException("DB 超时"); });

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("分支3补充：Jackson 反序列化失败的 MessageConversionException（RuntimeException）→ nack 入 DLQ（毒丸防护隐式路径）")
    void messageConversionException_shouldNackToDlq() throws IOException {
        MqAckHelper.executeWithAck(QUEUE, newMessage(), channel,
                () -> { throw new IllegalStateException("模拟 MessageConversionException 同为 RuntimeException"); });

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("边界：ack IO 失败 → 落入 catch(Exception) 兜底转 nack；仅 nack 也 IO 失败才向上抛 IOException")
    void ackIoException_shouldFallbackToNack() throws IOException {
        doThrow(new IOException("channel closed")).when(channel).basicAck(DELIVERY_TAG, false);

        assertDoesNotThrow(() -> MqAckHelper.executeWithAck(QUEUE, newMessage(), channel, () -> { }));
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    @DisplayName("边界：ack 与 nack 均 IO 失败（channel 已死）→ IOException 向上抛出，交由容器重投未确认消息")
    void ackAndNackIoException_shouldPropagate() throws IOException {
        doThrow(new IOException("channel closed")).when(channel).basicAck(DELIVERY_TAG, false);
        doThrow(new IOException("channel closed")).when(channel).basicNack(DELIVERY_TAG, false, false);

        assertThrows(IOException.class, () ->
                MqAckHelper.executeWithAck(QUEUE, newMessage(), channel, () -> { }));
    }

    @Test
    @DisplayName("边界：业务 lambda 内部 catch 吞掉异常（模拟误用反模式）→ 仍按成功 ack，不抛不 nack")
    void businessSwallowException_shouldStillAck() throws IOException {
        assertDoesNotThrow(() -> MqAckHelper.executeWithAck(QUEUE, newMessage(), channel, () -> {
            try {
                throw new RuntimeException("业务内部已自行消化");
            } catch (RuntimeException ignored) {
                // 反模式演示：lambda 内吞异常 = 绕过 DLQ 兜底，此处仅验证 ack 路径不受影响
            }
        }));

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }
}