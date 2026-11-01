package com.sportcourt.service.impl;

import com.sportcourt.analytics.BehaviorEvent;
import com.sportcourt.mapper.BehaviorMapper;
import com.sportcourt.service.IBehaviorAggregationService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BehaviorAggregationServiceImpl implements IBehaviorAggregationService {

  @Resource
  private BehaviorMapper behaviorMapper;

  private final TransactionTemplate transactions;

  public BehaviorAggregationServiceImpl(TransactionTemplate transactions) {
    this.transactions = transactions;
  }

  @Override
  public void accept(BehaviorEvent event) {
    event.validate();
    transactions.execute(
        tx -> {
          long bucket = event.getOccurredAt() / 60000 * 60000;
          if (behaviorMapper.insertEvent(
                  event.getEventId(),
                  event.getVenueId(),
                  event.getUserId(),
                  event.getAction(),
                  event.getOccurredAt(),
                  bucket)
              == 0) {
            Map<String, Object> old = behaviorMapper.selectEvent(event.getEventId());
            if (((Number) old.get("venue_id")).longValue() != event.getVenueId()
                || !Objects.equals(old.get("action"), event.getAction())
                || !Objects.equals(old.get("user_id"), event.getUserId())
                || ((Number) old.get("occurred_at_ms")).longValue() != event.getOccurredAt()) {
              throw new IllegalArgumentException("同一行为事件号对应不同内容");
            }
            return null;
          }
          behaviorMapper.accumulateBucket(
              event.getVenueId(),
              bucket,
              "VISIT".equals(event.getAction()) ? 1 : 0,
              "ORDER".equals(event.getAction()) ? 1 : 0,
              "PAID".equals(event.getAction()) ? 1 : 0);
          if ("VISIT".equals(event.getAction()) && event.getUserId() != null) {
            behaviorMapper.insertVisitUser(event.getVenueId(), event.getUserId(), bucket);
          }
          return null;
        });
  }

  @Override
  public List<Map<String, Object>> ranking(int minutes, int limit) {
    if ((minutes != 5 && minutes != 60) || limit < 1 || limit > 100) {
      throw new IllegalArgumentException("窗口仅支持 5 或 60 分钟，条数 1～100");
    }
    long now = System.currentTimeMillis() / 60000 * 60000;
    long from = now - (minutes - 1L) * 60000;
    // COUNT DISTINCT over the entire window avoids adding per-minute UVs and over-counting
    // repeat visitors.
    return behaviorMapper.selectRanking(from, now, from, now, limit);
  }
}
