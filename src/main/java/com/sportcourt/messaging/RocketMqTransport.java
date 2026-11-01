package com.sportcourt.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportcourt.service.IBusinessEventService;
import com.sportcourt.service.impl.BusinessEventServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "sportcourt.mq.enabled", havingValue = "true")
public class RocketMqTransport implements EventPublisher {
  private final ObjectMapper json;
  private final IBusinessEventService handler;
  private final MeterRegistry metrics;
  private final AtomicInteger connected = new AtomicInteger();
  private final List<DefaultMQPushConsumer> consumers = new ArrayList<>();
  private DefaultMQProducer producer;

  @Value("${sportcourt.mq.nameserver:127.0.0.1:9876}")
  private String nameserver;

  @Value("${sportcourt.mq.group-prefix:sportcourt}")
  private String prefix;

  @Value("${sportcourt.mq.business-topic:SportCourtBusiness}")
  private String businessTopic;

  @Value("${sportcourt.mq.deadline-topic:SportCourtOrderDeadline}")
  private String deadlineTopic;

  @Value("${sportcourt.mq.consumer-enabled:true}")
  private boolean consumerEnabled;

  @Value("${sportcourt.mq.send-timeout-ms:3000}")
  private int timeout;

  public RocketMqTransport(
      ObjectMapper json, IBusinessEventService handler, MeterRegistry metrics) {
    this.json = json;
    this.handler = handler;
    this.metrics = metrics;
    metrics.gauge("sportcourt.mq.client.started", connected);
  }

  @EventListener(ContextRefreshedEvent.class)
  public synchronized void start() {
    if (producer != null) {
      return;
    }
    try {
      producer = new DefaultMQProducer(prefix + "-relay");
      producer.setNamesrvAddr(nameserver);
      producer.setInstanceName(UUID.randomUUID().toString());
      producer.setSendMsgTimeout(timeout);
      producer.setVipChannelEnabled(false);
      producer.setRetryTimesWhenSendFailed(0);
      producer.start();
      if (consumerEnabled) {
        subscribe("feed", "FEED", businessTopic);
        subscribe("relations", "LIKE || FOLLOW", businessTopic);
        subscribe("media", "MEDIA_DELETE", businessTopic);
        subscribe("cache", "VENUE_INVALIDATE", businessTopic);
        subscribe("activities", "GOLDEN_SLOT_PUBLISH || GOLDEN_SLOT_RETRY", businessTopic);
        subscribe("fulfillment", "GOLDEN_SLOT_FULFILL", businessTopic);
        subscribe("orders", "ORDER_CREATED || ORDER_PAID || ORDER_CLOSED", businessTopic);
        subscribe("deadlines", "ORDER_EXPIRE", deadlineTopic);
      }
      connected.set(1);
    } catch (Exception failure) {
      stop();
      throw new IllegalStateException("RocketMQ 初始化失败，请检查 Nameserver 和 Topic", failure);
    }
  }

  private void subscribe(String group, String tags, String topic) throws Exception {
    DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(prefix + "-" + group);
    consumer.setNamesrvAddr(nameserver);
    consumer.setInstanceName(UUID.randomUUID().toString());
    consumer.setVipChannelEnabled(false);
    consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    consumer.setConsumeMessageBatchMaxSize(1);
    consumer.setConsumeThreadMin(1);
    consumer.setConsumeThreadMax(2);
    consumer.setMaxReconsumeTimes(16);
    consumer.subscribe(topic, tags);
    consumer.registerMessageListener(
        (MessageListenerConcurrently)
            (messages, context) -> {
              for (MessageExt message : messages) {
                BusinessEvent event = null;
                String payload = new String(message.getBody(), StandardCharsets.UTF_8);
                try {
                  event = json.readValue(payload, BusinessEvent.class);
                  handler.consume(event, group);
                } catch (Exception failure) {
                  RuntimeException error =
                      failure instanceof RuntimeException
                          ? (RuntimeException) failure
                          : new IllegalArgumentException("消息格式错误", failure);
                  metrics
                      .counter("sportcourt.mq.consumptions", "group", group, "result", "retry")
                      .increment();
                  try {
                    if (handler.recordConsumptionFailure(
                        event,
                        group,
                        message.getMsgId(),
                        payload,
                        error,
                        message.getReconsumeTimes() + 1)) {
                      continue;
                    }
                  } catch (RuntimeException storageFailure) {
                    log.warn("消息 {} 的失败状态尚未持久化，保持重试", message.getMsgId());
                  }
                  return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
              }
              return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
    consumers.add(consumer);
    consumer.start();
  }

  @Override
  public void publish(BusinessEvent event) {
    if (connected.get() == 0) {
      throw new IllegalStateException("RocketMQ 客户端尚未启动");
    }
    String group = BusinessEventServiceImpl.resolveConsumerGroup(event.getKind());
    try {
      Message message =
          new Message(
              "deadlines".equals(group) ? deadlineTopic : businessTopic,
              event.getKind(),
              "outbox:" + event.getEventId(),
              json.writeValueAsBytes(event));
      message.putUserProperty("eventId", String.valueOf(event.getEventId()));
      message.putUserProperty("aggregateId", String.valueOf(event.getAggregateId()));
      if (event.getDeliverAt() != null && event.getDeliverAt() > System.currentTimeMillis()) {
        if (event.getDeliverAt() - System.currentTimeMillis() > 86400000L) {
          throw new IllegalArgumentException("订单延迟超过本地 Broker 配置的 24 小时范围");
        }
        message.setDeliverTimeMs(event.getDeliverAt());
      }
      if (producer.send(message).getSendStatus() != SendStatus.SEND_OK) {
        throw new IllegalStateException("Broker 未确认消息可靠写入");
      }
    } catch (Exception failure) {
      throw new IllegalStateException("RocketMQ 投递结果未确认", failure);
    }
  }

  @PreDestroy
  public synchronized void stop() {
    connected.set(0);
    for (DefaultMQPushConsumer consumer : consumers) {
      consumer.shutdown();
    }
    consumers.clear();
    if (producer != null) {
      producer.shutdown();
      producer = null;
    }
  }
}
