package com.sportcourt.service.impl;

import com.sportcourt.mapper.BusinessOutboxMapper;
import com.sportcourt.service.IBusinessOutboxService;
import com.sportcourt.service.IOutboxRelayService;
import com.sportcourt.utils.CacheClient;
import java.sql.Timestamp;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
public class BusinessOutboxServiceImpl implements IBusinessOutboxService {

  @Resource
  private BusinessOutboxMapper businessOutboxMapper;

  @Resource
  private ObjectProvider<IOutboxRelayService> relay;

  private final CacheClient cache;

  public BusinessOutboxServiceImpl(CacheClient cache) {
    this.cache = cache;
  }

  @Override
  public void appendBusinessEvent(String kind, long aggregateId, Long userId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Outbox 必须与业务写入同事务");
    }
    businessOutboxMapper.insertEvent(kind, aggregateId, userId);
  }

  @Override
  public void appendMediaDeletionEvent(String path, long userId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("图片删除事件需要业务事务");
    }
    businessOutboxMapper.insertMediaDeletion(userId, userId, path);
  }

  @Override
  public void appendVenueCacheInvalidation(long venueId) {
    appendBusinessEvent("VENUE_INVALIDATE", venueId, null);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              cache.invalidate("cache:venue:" + venueId);
            } catch (RuntimeException e) {
              log.warn("提交后失效失败，Outbox 将重试", e);
            }
          }
        });
  }

  @Override
  public void appendOrderExpirationEvent(long orderId, Long userId, Timestamp deadline) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("订单截止时间事件需要业务事务");
    }
    businessOutboxMapper.insertDeadline(orderId, userId, deadline);
  }

  @Scheduled(fixedDelay = 1000)
  @Override
  public void relayOutboxBatch() {
    relay.getObject().relayOutboxBatch();
  }
}
