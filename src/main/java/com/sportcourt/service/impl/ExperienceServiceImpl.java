package com.sportcourt.service.impl;

import com.sportcourt.cache.CacheReadTrace;
import com.sportcourt.cache.RequestCacheScope;
import com.sportcourt.dto.Result;
import com.sportcourt.entity.Venue;
import com.sportcourt.mapper.BehaviorMapper;
import com.sportcourt.mapper.MessageMapper;
import com.sportcourt.service.IBehaviorJournalService;
import com.sportcourt.service.IExperienceService;
import com.sportcourt.service.IReservationOrderService;
import com.sportcourt.service.IVenueService;
import com.sportcourt.utils.UserHolder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ExperienceServiceImpl implements IExperienceService {
  @Resource
  private IVenueService venueService;

  @Resource
  private IBehaviorJournalService behaviorJournalService;

  @Resource
  private IReservationOrderService reservationOrderService;

  @Resource
  private BehaviorMapper behaviorMapper;

  @Resource
  private MessageMapper messageMapper;

  @Resource
  private MeterRegistry metrics;

  @Value("${sportcourt.experience.enabled:false}")
  private boolean enabled;

  @Value("${sportcourt.cache.enabled:true}")
  private boolean cacheEnabled;

  @Value("${sportcourt.analytics.enabled:false}")
  private boolean analyticsEnabled;

  @Value("${sportcourt.mq.enabled:false}")
  private boolean mqEnabled;

  @Value("${sportcourt.cdc.enabled:false}")
  private boolean cdcEnabled;

  @Override
  public Result queryExperienceCapabilities() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("enabled", enabled);
    if (enabled) {
      result.put("cacheEnabled", cacheEnabled);
      result.put("analyticsEnabled", analyticsEnabled);
      result.put("mqEnabled", mqEnabled);
      result.put("cdcEnabled", cdcEnabled);
      result.put("cdcConnected", readBooleanGauge("sportcourt.cdc.connected"));
      result.put("mqClientStarted", readBooleanGauge("sportcourt.mq.client.started"));
      result.put("kafkaConsumerRunning", readBooleanGauge("sportcourt.kafka.consumer.running"));
      result.put("sampledAt", System.currentTimeMillis());
    }
    return Result.ok(result);
  }

  private Boolean readBooleanGauge(String name) {
    Gauge gauge = metrics.find(name).gauge();
    return gauge == null ? null : gauge.value() == 1;
  }

  /**
   * 在同一个请求缓存作用域中读取同一场馆两次，记录每次经过的缓存层与耗时。
   *
   * <p>用于观察请求级复用；不会主动清空缓存制造命中结果。
   */
  @Override
  public Result traceVenueCacheReads(Long venueId) {
    if (!enabled) {
      return Result.fail("体验观察接口未启用");
    }
    if (venueId == null || venueId <= 0) {
      return Result.fail("请选择有效场馆");
    }
    List<Map<String, Object>> reads = new ArrayList<>();
    String venueName = null;
    try (RequestCacheScope scope = RequestCacheScope.open()) {
      for (int index = 0; index < 2; index++) {
        try (CacheReadTrace trace = CacheReadTrace.open()) {
          long started = System.nanoTime();
          Result loaded = venueService.queryById(venueId);
          if (!Boolean.TRUE.equals(loaded.getSuccess()) || loaded.getData() == null) {
            return loaded;
          }
          venueName = ((Venue) loaded.getData()).getName();
          Map<String, Object> read = new LinkedHashMap<>();
          read.put("sequence", index + 1);
          read.put("durationMicros", TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started));
          read.put("events", trace.snapshot());
          reads.add(read);
        }
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("requestId", UUID.randomUUID().toString());
    result.put("venueId", venueId);
    result.put("venueName", venueName);
    result.put("reads", reads);
    return Result.ok(result);
  }

  /**
   * 提交三条体验访问事件并返回事件号，浏览器可继续轮询各事件的落库结果。
   *
   * <p>submitted 是提交采集的数量，不是 Kafka 确认数或成功写入数据库的数量。
   */
  @Override
  public Result submitDemoVisitEvents(Long venueId) {
    if (!enabled || !analyticsEnabled) {
      return Result.fail("Kafka 访问体验未启用");
    }
    if (venueId == null || venueId <= 0) {
      return Result.fail("请选择有效场馆");
    }
    Result venue = venueService.queryById(venueId);
    if (!Boolean.TRUE.equals(venue.getSuccess())) {
      return venue;
    }
    List<String> eventIds = new ArrayList<>();
    Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
    for (int index = 0; index < 3; index++) {
      String id = behaviorJournalService.submitVenueVisitEvent(venueId, userId);
      if (id == null) {
        return Result.fail("访问采集未启用");
      }
      eventIds.add(id);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("eventIds", eventIds);
    result.put("submitted", eventIds.size());
    result.put("venueId", venueId);
    return Result.ok(result);
  }

  @Override
  public Result queryVisitEventReceipts(List<String> eventIds) {
    if (!enabled || !analyticsEnabled) {
      return Result.fail("Kafka 访问体验未启用");
    }
    if (eventIds == null || eventIds.isEmpty() || eventIds.size() > 3) {
      return Result.fail("一次仅查询 1～3 个体验事件");
    }
    for (String id : eventIds) {
      if (id == null || !id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) {
        return Result.fail("体验事件号格式无效");
      }
    }
    List<Map<String, Object>> receipts = new ArrayList<>();
    for (String id : eventIds) {
      Map<String, Object> stored = behaviorMapper.selectEvent(id);
      Map<String, Object> receipt = new LinkedHashMap<>();
      receipt.put("eventId", id);
      receipt.put("state", stored == null ? "PENDING" : "PERSISTED");
      if (stored != null) {
        receipt.put("venueId", stored.get("venue_id"));
      }
      receipts.add(receipt);
    }
    return Result.ok(receipts);
  }

  @Override
  public Result queryMyOrderProgress(Long orderId) {
    if (!enabled) {
      return Result.fail("体验观察接口未启用");
    }
    if (orderId == null || orderId <= 0 || UserHolder.getUser() == null) {
      return Result.fail("请登录后查询自己的订单");
    }
    Result owned = reservationOrderService.queryMyOrder(orderId);
    if (!Boolean.TRUE.equals(owned.getSuccess())) {
      return owned;
    }
    return Result.ok(messageMapper.selectOrderProgress(orderId, UserHolder.getUser().getId()));
  }
}
