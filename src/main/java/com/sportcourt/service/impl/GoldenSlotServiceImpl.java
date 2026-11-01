package com.sportcourt.service.impl;

import cn.hutool.json.JSONUtil;
import com.sportcourt.dto.Result;
import com.sportcourt.entity.GoldenSlotPublication;
import com.sportcourt.entity.GoldenSlotReservation;
import com.sportcourt.entity.ReservationOrder;
import com.sportcourt.enums.ReservationState;
import com.sportcourt.mapper.BusinessOutboxMapper;
import com.sportcourt.mapper.GoldenSlotPassMapper;
import com.sportcourt.mapper.GoldenSlotReservationMapper;
import com.sportcourt.mapper.ReservationOrderMapper;
import com.sportcourt.reliability.RedisScripts;
import com.sportcourt.service.IGoldenSlotService;
import com.sportcourt.service.IReservationOrderService;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisZSetCommands.Limit;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
// 整点抢黄金时段初始化会读取活动表，必须等待数据库迁移完成。
@DependsOn("flywayInitializer")
public class GoldenSlotServiceImpl implements IGoldenSlotService {

  public static final String STREAM = "stream.orders";
  public static final String GROUP = "g1";

  private static final DefaultRedisScript<Long> STREAM_LAG = RedisScripts.number("stream-lag.lua");
  private static final DefaultRedisScript<Long> COMPENSATE =
      RedisScripts.number("goldenSlot-compensate.lua");

  @Resource
  private BusinessOutboxMapper businessOutboxMapper;

  @Resource
  private GoldenSlotReservationMapper goldenSlotReservationMapper;

  @Resource
  private GoldenSlotPassMapper goldenSlotPassMapper;

  @Resource
  private ReservationOrderMapper reservationOrderMapper;

  private final String consumer = "worker-" + UUID.randomUUID();
  private final StringRedisTemplate redis;
  private final TransactionTemplate transactions;
  private final RedissonClient redisson;
  private final ObjectProvider<IReservationOrderService> orders;
  private final MeterRegistry metrics;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "goldenSlot-consumer");
            t.setDaemon(true);
            return t;
          });
  private volatile boolean running;
  private String pendingCursor = "-";
  private final AtomicLong streamLag = new AtomicLong();
  private final AtomicLong reviewSize = new AtomicLong();
  private final AtomicLong pendingSize = new AtomicLong();

  @Value("${sportcourt.reliability.max-deliveries:5}")
  private int maxDeliveries;

  @Value("${sportcourt.reliability.claim-idle-ms:30000}")
  private long idleMillis;

  @Value("${sportcourt.reliability.scheduling-enabled:true}")
  private boolean enabled;

  @Value("${sportcourt.mq.enabled:false}")
  private boolean mqEnabled;

  @Value("${sportcourt.mq.goldenSlot-enabled:false}")
  private boolean mqGoldenSlot;

  public GoldenSlotServiceImpl(
      StringRedisTemplate redis,
      TransactionTemplate transactions,
      RedissonClient redisson,
      ObjectProvider<IReservationOrderService> orders,
      MeterRegistry metrics) {
    this.redis = redis;
    this.transactions = transactions;
    this.redisson = redisson;
    this.orders = orders;
    this.metrics = metrics;
    metrics.gauge("sportcourt.goldenSlot.pending", pendingSize);
    metrics.gauge("sportcourt.goldenSlot.lag", streamLag);
    metrics.gauge("sportcourt.goldenSlot.review", reviewSize);
  }

  @PostConstruct
  public void start() {
    redis.execute(
        (RedisCallback<Object>)
            connection -> {
              try {
                connection.execute(
                    "XGROUP",
                    bytes("CREATE"),
                    bytes(STREAM),
                    bytes(GROUP),
                    bytes("0"),
                    bytes("MKSTREAM"));
              } catch (RuntimeException e) {
                if (!hasMessage(e, "BUSYGROUP")) {
                  throw e;
                }
              }
              return null;
            });
    // Auto-publish only never-used activities; missing Redis state for a used activity fails
    // closed.
    for (Map<String, Object> row : goldenSlotPassMapper.selectPublicationStates()) {
      long id = ((Number) row.get("booking_pass_id")).longValue();
      if (row.get("has_published") == null) {
        log.warn("活动 {} 缺少持久化发布基线，保持入口关闭并等待人工核对", id);
        continue;
      }
      if ("ACTIVE".equals(redis.opsForHash().get("goldenSlot:activity:" + id, "state"))) {
        goldenSlotPassMapper.markPublished(id);
        continue;
      }
      if (((Number) row.get("has_published")).intValue() == 0
          && !Boolean.TRUE.equals(redis.hasKey("goldenSlot:activity:" + id))
          && goldenSlotReservationMapper.countByBookingPassId(id) == 0
          && Boolean.TRUE.equals(redis.opsForStream().size(STREAM) == 0)) {
        publishActivity(id);
      }
    }
    if (enabled) {
      running = true;
      executor.submit(this::consumeOrderStreamMessages);
    }
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static boolean hasMessage(Throwable e, String text) {
    while (e != null) {
      if (e.getMessage() != null && e.getMessage().contains(text)) {
        return true;
      }
      e = e.getCause();
    }
    return false;
  }

  @PreDestroy
  public void stop() {
    running = false;
    executor.shutdownNow();
  }

  private void consumeOrderStreamMessages() {
    while (running && !Thread.currentThread().isInterrupted()) {
      try {
        List<MapRecord<String, Object, Object>> records =
            redis
                .opsForStream()
                .read(
                    Consumer.from(GROUP, consumer),
                    StreamReadOptions.empty().count(10).block(Duration.ofSeconds(1)),
                    StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        if (records != null) {
          for (MapRecord<String, Object, Object> record : records) {
            handleOrderStreamMessage(record);
          }
        }
        // XCLAIM transfers idle entries from any dead/slow consumer, not just this worker's
        // own PEL.
        reclaimPending();
        pendingSize.set(redis.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages());
      } catch (RuntimeException e) {
        if (!running) {
          break;
        }
        log.warn("整点抢黄金时段消费暂时失败，将重试", e);
        try {
          Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @Scheduled(fixedDelay = 10000)
  public void refreshMetrics() {
    pendingSize.set(redis.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages());
    Long lag = redis.execute(STREAM_LAG, Collections.singletonList(STREAM), GROUP);
    streamLag.set(lag == null ? -1 : lag);
    reviewSize.set(goldenSlotReservationMapper.countReviewRequests());
  }

  private void reclaimPending() {
    Range<String> range =
        "-".equals(pendingCursor) ? Range.unbounded() : Range.leftOpen(pendingCursor, "+");
    PendingMessages pending = redis.opsForStream().pending(STREAM, GROUP, range, 50);
    // Rotate even past entries which are not idle yet, so a young first page cannot starve the
    // tail.
    pendingCursor = "-";
    for (PendingMessage message : pending) {
      if (pending.size() == 50) {
        pendingCursor = message.getId().getValue();
      }
      long delay =
          idleMillis * Math.min(16, 1L << Math.min(4, (int) message.getTotalDeliveryCount() - 1));
      if (message.getElapsedTimeSinceLastDelivery().toMillis() < delay) {
        continue;
      }
      for (MapRecord<String, Object, Object> record :
          claim(consumer, Duration.ofMillis(delay), message.getId())) {
        handleOrderStreamMessage(record);
      }
    }
  }

  List<MapRecord<String, Object, Object>> claim(String worker, Duration idle, RecordId id) {
    List<ByteRecord> bytes =
        redis.execute(
            (RedisCallback<List<ByteRecord>>)
                connection ->
                    connection.streamCommands().xClaim(bytes(STREAM), GROUP, worker, idle, id));
    List<MapRecord<String, Object, Object>> records = new ArrayList<>();
    if (bytes != null) {
      for (ByteRecord record : bytes) {
        Map<Object, Object> values = new LinkedHashMap<>();
        record
            .getValue()
            .forEach(
                (key, value) ->
                    values.put(
                        new String(key, StandardCharsets.UTF_8),
                        new String(value, StandardCharsets.UTF_8)));
        records.add(MapRecord.create(STREAM, values).withId(record.getId()));
      }
    }
    return records;
  }

  private void handleOrderStreamMessage(MapRecord<String, Object, Object> record) {
    ReservationOrder order = null;
    boolean reservationMatched = false;
    long began = System.nanoTime();
    try {
      Map<Object, Object> value = record.getValue();
      order =
          new ReservationOrder()
              .setId(parsePositiveLongField(value, "id"))
              .setUserId(parsePositiveLongField(value, "userId"))
              .setBookingPassId(parsePositiveLongField(value, "bookingPassId"));
      registerAndValidateReservation(order);
      reservationMatched = true;
      if (ReservationState.PROCESSING.matches(queryReservationState(order.getId()))) {
        if (mqEnabled && mqGoldenSlot) {
          enqueueFulfillmentTask(order);
          // The durable outbox owns delivery now. Preserve the Lua PROCESSING display
          // state.
          redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
          return;
        } else {
          orders.getObject().persistGoldenSlot(order);
        }
      }
      // Database commit precedes cache update and ACK. Duplicate delivery can safely repair
      // either.
      syncReservationStateAndAcknowledgeMessage(record, order.getId());
    } catch (InvalidOrderMessage e) {
      // A conflicting payload is isolated by message ID. It has no authority over the real
      // reservation.
      quarantine(record, order, e);
    } catch (RuntimeException e) {
      metrics.counter("sportcourt.goldenSlot.deliveries", "result", "retry").increment();
      if (reservationMatched) {
        goldenSlotReservationMapper.recordAttempt(summarizeFailureReason(e), order.getId());
        GoldenSlotReservation reservation =
            goldenSlotReservationMapper.selectAttemptState(order.getId());
        if (reservation != null) {
          String state = reservation.getState();
          if (!ReservationState.PROCESSING.matches(state)) {
            syncReservationStateAndAcknowledgeMessage(record, order.getId());
          } else if (reservation.getAttempts() >= maxDeliveries) {
            final long id = order.getId();
            transactions.execute(
                status -> {
                  goldenSlotReservationMapper.selectStateForUpdate(id);
                  if (goldenSlotReservationMapper.markProcessingForReview(
                          summarizeFailureReason(e), id)
                      > 0) {
                    saveDeadLetter(record, id, e);
                  }
                  return null;
                });
            // Re-read after commit: another consumer may already have completed the
            // order.
            syncReservationStateAndAcknowledgeMessage(record, id);
          }
        }
      }
      // Registration/connection errors leave the message pending, without inventing a
      // business failure.
      log.warn("订单消息 {} 执行失败: {}", record.getId(), summarizeFailureReason(e));
    } finally {
      metrics
          .timer("sportcourt.goldenSlot.consume")
          .record(System.nanoTime() - began, TimeUnit.NANOSECONDS);
    }
  }

  /**
   * 把持久预约状态同步到 Redis 展示层，再确认当前 Stream 消息。
   *
   * <p>ACK 表示此消息不再重试，状态可能是 SUCCESS、FAILED 或 REVIEW，并不等同于订单成功。
   *
   * <p>展示状态写入失败时不 ACK，后续投递可以按数据库的当前状态修复展示。
   */
  private void syncReservationStateAndAcknowledgeMessage(
      MapRecord<String, Object, Object> record, long orderId) {
    String state = queryReservationState(orderId);
    redis.opsForHash().put("goldenSlot:request:" + orderId, "state", state);
    redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
    metrics
        .counter("sportcourt.goldenSlot.deliveries", "result", state.toLowerCase(Locale.ROOT))
        .increment();
  }

  private void quarantine(
      MapRecord<String, Object, Object> record, ReservationOrder order, RuntimeException e) {
    transactions.execute(
        status -> {
          saveDeadLetter(record, order == null ? null : order.getId(), e);
          return null;
        });
    redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
    metrics.counter("sportcourt.goldenSlot.deliveries", "result", "invalid").increment();
    log.warn("隔离冲突或无效消息 {}: {}", record.getId(), summarizeFailureReason(e));
  }

  private void saveDeadLetter(
      MapRecord<String, Object, Object> record, Long orderId, RuntimeException e) {
    goldenSlotReservationMapper.insertDeadLetter(
        record.getId().getValue(),
        orderId,
        JSONUtil.toJsonStr(record.getValue()),
        summarizeFailureReason(e));
  }

  private static class InvalidOrderMessage extends RuntimeException {
    InvalidOrderMessage(String message) {
      super(message);
    }
  }

  private static long parsePositiveLongField(Map<Object, Object> fields, String fieldName) {
    Object raw = fields.get(fieldName);
    if (raw == null) {
      throw new InvalidOrderMessage("消息缺少 " + fieldName);
    }
    try {
      long parsed = Long.parseLong(raw.toString());
      if (parsed <= 0) {
        throw new InvalidOrderMessage("消息参数无效");
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new InvalidOrderMessage("消息参数无效: " + fieldName);
    }
  }

  private static String summarizeFailureReason(Throwable e) {
    String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    return text.substring(0, Math.min(500, text.length()));
  }

  /**
   * 在事务中登记持久履约任务，把后续投递交给 Outbox。
   *
   * <p>调用者必须等事务提交成功后再 ACK Stream；否则进程崩溃会丢掉尚未持久化的工作。
   */
  private void enqueueFulfillmentTask(ReservationOrder order) {
    transactions.execute(
        tx -> {
          businessOutboxMapper.insertGoldenSlotFulfillment(
              order.getId(), order.getUserId(), "goldenSlot:" + order.getId());
          // An explicit reservation retry can re-publish the same quarantined event.
          businessOutboxMapper.retryFulfillment("goldenSlot:" + order.getId());
          return null;
        });
    metrics.counter("sportcourt.goldenSlot.handoffs", "result", "durable").increment();
  }

  /**
   * 按持久抢购资格处理履约事件，重复的终态事件不再次扣库存。
   *
   * <p>Redis 展示更新放在事务提交之后；展示故障不能把已经落库的订单回滚或改成失败。
   */
  @Override
  public void fulfillReservationFromMessage(long orderId) {
    GoldenSlotReservation reservation = goldenSlotReservationMapper.selectFulfillment(orderId);
    if (reservation == null) {
      throw new IllegalStateException("缺少持久化抢购资格");
    }
    String state = reservation.getState();
    if (ReservationState.SUCCESS.matches(state) || ReservationState.FAILED.matches(state)) {
      return;
    }
    if (!ReservationState.PROCESSING.matches(state)) {
      throw new IllegalStateException("整点抢黄金时段请求等待人工核对");
    }
    ReservationOrder order =
        new ReservationOrder()
            .setId(orderId)
            .setUserId(reservation.getUserId())
            .setBookingPassId(reservation.getBookingPassId());
    orders.getObject().persistGoldenSlot(order);
    // Database status is authoritative. Redis is a best-effort display projection after commit.
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              redis
                  .opsForHash()
                  .put("goldenSlot:request:" + orderId, "state", queryReservationState(orderId));
            } catch (RuntimeException unavailable) {
              log.warn("请求 {} 的 Redis 展示状态待修复，数据库履约已提交", orderId);
            }
          }
        });
  }

  /**
   * 登记抢购资格，并核对重复订单号的用户、券是否一致。
   *
   * <p>INSERT IGNORE 只避免重复写入，不能证明消息身份正确；冲突消息必须单独隔离。
   */
  private void registerAndValidateReservation(ReservationOrder order) {
    goldenSlotReservationMapper.insertReservation(
        order.getId(), order.getBookingPassId(), order.getUserId());
    GoldenSlotReservation reservation =
        goldenSlotReservationMapper.selectReservation(order.getId());
    if (reservation == null
        || !reservation.getBookingPassId().equals(order.getBookingPassId())
        || !reservation.getUserId().equals(order.getUserId())) {
      throw new InvalidOrderMessage("整点抢黄金时段资格或订单号冲突，需核对");
    }
  }

  /**
   * 锁定预约行并判断是否可以继续履约，由订单事务调用。
   *
   * @return true 表示仍在 PROCESSING；false 表示已经成功或失败，不应再次扣库存。 REVIEW 状态抛异常，等待人工处理；false 不是“没有取得锁”。
   */
  @Override
  public boolean lockReservationForFulfillment(ReservationOrder order) {
    GoldenSlotReservation reservation =
        goldenSlotReservationMapper.selectStateForUpdate(order.getId());
    if (reservation == null) {
      throw new IllegalStateException("缺少持久化抢购资格");
    }
    String state = reservation.getState();
    if (ReservationState.FAILED.matches(state) || ReservationState.SUCCESS.matches(state)) {
      return false;
    }
    if (!ReservationState.PROCESSING.matches(state)) {
      throw new IllegalStateException("订单等待人工核对");
    }
    return true;
  }

  /**
   * 登记持久履约成功，由扣库存、写订单的同一个事务调用。
   *
   * <p>此方法只更新数据库；Redis 展示状态由提交后的流程同步。
   */
  @Override
  public void markReservationSuccessful(long orderId) {
    goldenSlotReservationMapper.markSuccess(orderId);
  }

  @Override
  public String queryReservationState(long orderId) {
    return goldenSlotReservationMapper.selectState(orderId);
  }

  @Override
  public Result queryReservationStatus(long orderId, long userId) {
    List<Map<String, Object>> rows =
        goldenSlotReservationMapper.selectOwnedRequest(orderId, userId);
    if (!rows.isEmpty()) {
      rows.get(0).put("order_id", String.valueOf(orderId));
      return Result.ok(rows.get(0));
    }
    Map<Object, Object> request = redis.opsForHash().entries("goldenSlot:request:" + orderId);
    if (!String.valueOf(userId).equals(request.get("userId"))) {
      return Result.fail("请求不存在");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("order_id", String.valueOf(orderId));
    result.put("booking_pass_id", request.get("bookingPassId"));
    result.put("state", request.get("state"));
    return Result.ok(result);
  }

  /** 首次发布活动；已有抢购记录时拒绝重新初始化执行库存。 */
  @Override
  public Result publishActivity(long bookingPassId) {
    return prepareActivityPublication(bookingPassId, PublicationMode.INITIAL);
  }

  /** 人工核对后的恢复入口；先确认共享 Stream 和持久预约中没有在途工作。 */
  @Override
  public Result recoverActivity(long bookingPassId) {
    return prepareActivityPublication(bookingPassId, PublicationMode.RECOVERY);
  }

  private Result prepareActivityPublication(long bookingPassId, PublicationMode mode) {
    boolean recovering = mode == PublicationMode.RECOVERY;
    RLock lock = redisson.getLock("lock:publish:" + bookingPassId);
    if (!lock.tryLock()) {
      return Result.fail("活动正在准备");
    }
    try {
      GoldenSlotPublication activity = goldenSlotPassMapper.selectPublicationDetails(bookingPassId);
      if (activity == null) {
        return Result.fail("活动不存在");
      }
      String activityKey = "goldenSlot:activity:" + bookingPassId;
      Result admission = checkPublicationAdmission(bookingPassId, recovering, activityKey);
      if (admission != null) {
        return admission;
      }
      // 先暂停入口，再读持久事实和检查在途消息；失败时保持暂停，不能继续售卖。
      redis.opsForHash().put(activityKey, "state", "PREPARING");
      if (recovering && hasUnfinishedActivityWork(bookingPassId)) {
        return Result.fail("仍有在途或待核对请求，活动保持暂停，请先处理");
      }
      if (!recovering && goldenSlotReservationMapper.countByBookingPassId(bookingPassId) > 0) {
        return Result.fail("已有抢购记录，请走核对恢复流程");
      }
      rebuildBuyerIndex(bookingPassId);
      activatePublication(activity, activityKey);
      return Result.ok();
    } finally {
      lock.unlock();
    }
  }

  /** null 表示可继续准备；非 null 表示已完成或必须拒绝发布。 */
  private Result checkPublicationAdmission(
      long bookingPassId, boolean recovering, String activityKey) {
    if (recovering) {
      return null;
    }
    if ("ACTIVE".equals(redis.opsForHash().get(activityKey, "state"))) {
      return Result.ok();
    }
    if (goldenSlotPassMapper.selectPublishedFlag(bookingPassId) == 1) {
      return Result.fail("已发布活动的执行状态缺失，请先核对并恢复");
    }
    long buyers =
        Optional.ofNullable(redis.opsForSet().size("goldenSlot:order:" + bookingPassId)).orElse(0L);
    long requests =
        Optional.ofNullable(redis.opsForHash().size("goldenSlot:requests:" + bookingPassId))
            .orElse(0L);
    if (buyers > 0 || requests > 0) {
      return Result.fail("已有抢购数据，请走核对恢复流程");
    }
    return null;
  }

  private boolean hasUnfinishedActivityWork(long bookingPassId) {
    PendingMessages pending = redis.opsForStream().pending(STREAM, GROUP, Range.unbounded(), 1);
    // Stream 由多活动共享；必须同时排除未读消息和未确认消息，不能只看本活动预约。
    boolean unreadMessages = hasUnreadStreamMessages();
    return !pending.isEmpty()
        || unreadMessages
        || goldenSlotReservationMapper.countUnfinishedRequests(bookingPassId) > 0;
  }

  private boolean hasUnreadStreamMessages() {
    List<StreamInfo.XInfoGroup> groups =
        redis.opsForStream().groups(STREAM).stream().collect(Collectors.toList());
    List<MapRecord<String, Object, Object>> tail =
        redis.opsForStream().reverseRange(STREAM, Range.unbounded(), Limit.limit().count(1));
    if (tail.isEmpty()) {
      return false;
    }
    String tailId = tail.get(0).getId().getValue();
    return groups.stream()
        .anyMatch(
            group -> GROUP.equals(group.groupName()) && !tailId.equals(group.lastDeliveredId()));
  }

  private void rebuildBuyerIndex(long bookingPassId) {
    List<Map<String, Object>> buyers =
        goldenSlotReservationMapper.selectSuccessfulBuyers(bookingPassId);
    redis.delete(
        Arrays.asList("goldenSlot:order:" + bookingPassId, "goldenSlot:requests:" + bookingPassId));
    for (Map<String, Object> buyer : buyers) {
      String userId = buyer.get("user_id").toString();
      redis.opsForSet().add("goldenSlot:order:" + bookingPassId, userId);
      redis
          .opsForHash()
          .put("goldenSlot:requests:" + bookingPassId, userId, buyer.get("order_id").toString());
    }
  }

  private void activatePublication(GoldenSlotPublication activity, String activityKey) {
    redis
        .opsForValue()
        .set("goldenSlot:stock:" + activity.getBookingPassId(), activity.getStock().toString());
    Map<String, String> config = new HashMap<>();
    config.put("begin", String.valueOf(activity.getBeginTime().getTime()));
    config.put("end", String.valueOf(activity.getEndTime().getTime()));
    config.put("state", Integer.valueOf(1).equals(activity.getStatus()) ? "ACTIVE" : "PAUSED");
    goldenSlotPassMapper.markPublished(activity.getBookingPassId());
    redis.opsForHash().putAll(activityKey, config);
  }

  /**
   * 将待复核预约恢复为 PROCESSING，并在同一事务中登记重试任务。
   *
   * <p>后续由投影重新进入 Stream，当前调用不直接履约，也不提前确认原消息。
   */
  @Override
  public Result retryReservation(long orderId) {
    return transactions.execute(
        tx -> {
          GoldenSlotReservation reservation =
              goldenSlotReservationMapper.selectReservationForUpdate(orderId);
          if (reservation == null || !ReservationState.REVIEW.matches(reservation.getState())) {
            return Result.fail("请求不在待核对状态");
          }
          goldenSlotReservationMapper.resetForRetry(orderId);
          businessOutboxMapper.insertGoldenSlotRetry(orderId);
          return Result.ok();
        });
  }

  /**
   * 人工确认失败后，保存 FAILED 状态并尝试补偿 Redis 预扣库存。
   *
   * <p>数据库先提交，Redis 随后执行 Lua；两者不属于同一事务，补偿失败会保留已提交的状态。
   *
   * <p>Lua 只释放与当前订单号匹配的购买资格，重复调用不会重复加库存或释放后续订单资格。
   */
  @Override
  public Result markReservationFailedAndCompensateRedis(long orderId) {
    Result<GoldenSlotReservation> result =
        transactions.execute(
            tx -> {
              GoldenSlotReservation reservation =
                  goldenSlotReservationMapper.selectReservationForUpdate(orderId);
              if (reservation == null) {
                return Result.fail("请求不存在");
              }
              if (!ReservationState.FAILED.matches(reservation.getState())
                  && !ReservationState.REVIEW.matches(reservation.getState())) {
                return Result.fail("仅能处理已隔离的失败请求");
              }
              if (reservationOrderMapper.countByOrderId(orderId) > 0) {
                return Result.fail("订单已经落库，不能回补库存");
              }
              goldenSlotReservationMapper.markFailed(orderId);
              return Result.ok(reservation);
            });
    if (!Boolean.TRUE.equals(result.getSuccess())) {
      return result;
    }
    GoldenSlotReservation reservation = result.getData();
    String bookingPassIdText = reservation.getBookingPassId().toString();
    Long compensationResultCode =
        redis.execute(
            COMPENSATE,
            Arrays.asList(
                "goldenSlot:stock:" + bookingPassIdText,
                "goldenSlot:order:" + bookingPassIdText,
                "goldenSlot:requests:" + bookingPassIdText,
                "goldenSlot:request:" + orderId),
            reservation.getUserId().toString(),
            String.valueOf(orderId));
    if (compensationResultCode == null || compensationResultCode < 0) {
      return Result.fail("失败状态已保存，Redis 尚未补偿，请重试或核对恢复");
    }
    return Result.ok();
  }

  /**
   * 读取数据库库存、已落库订单、持久预约和 Redis 库存，生成差异报告。
   *
   * <p>此方法记录观察指标，不覆盖或修复库存；差异还可能包含未登记的在途 Stream 消息。
   */
  @Override
  public Result queryInventoryReconciliationReport(long bookingPassId) {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("booking_pass_id", bookingPassId);
    List<Map<String, Object>> inventoryRows = goldenSlotPassMapper.selectInventory(bookingPassId);
    if (inventoryRows.isEmpty()) {
      return Result.fail("活动不存在");
    }
    long initialStock = ((Number) inventoryRows.get(0).get("total_stock")).longValue();
    long databaseRemainingStock = ((Number) inventoryRows.get(0).get("stock")).longValue();

    long persistedOrderCount = reservationOrderMapper.countByBookingPassId(bookingPassId);
    long unresolvedReservationCount =
        goldenSlotReservationMapper.countUnfinishedRequests(bookingPassId);
    String redisRemainingStock = redis.opsForValue().get("goldenSlot:stock:" + bookingPassId);
    report.put("initial_stock", initialStock);
    report.put("database_remaining", databaseRemainingStock);
    report.put("persisted_orders", persistedOrderCount);
    report.put("database_consistent", initialStock == databaseRemainingStock + persistedOrderCount);
    report.put("unresolved_reservations", unresolvedReservationCount);
    report.put("redis_remaining", redisRemainingStock);
    report.put(
        "redis_difference",
        redisRemainingStock == null
            ? null
            : databaseRemainingStock
                - Long.parseLong(redisRemainingStock)
                - unresolvedReservationCount);
    report.put("note", "差异可能包含尚未被消费者登记的在途消息；本接口只报告，不覆盖库存");
    metrics
        .counter(
            "sportcourt.goldenSlot.reconciliations",
            "result",
            initialStock == databaseRemainingStock + persistedOrderCount
                ? "consistent"
                : "difference")
        .increment();
    return Result.ok(report);
  }

  @Scheduled(fixedDelay = 60000)
  public void checkActivityInventoryConsistency() {
    for (Long id : goldenSlotPassMapper.selectActivityBookingPassIds()) {
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> report =
            (Map<String, Object>) queryInventoryReconciliationReport(id).getData();
        if (report != null && !Boolean.TRUE.equals(report.get("database_consistent"))) {
          log.warn("整点抢黄金时段库存对账差异: {}", report);
        }
      } catch (RuntimeException e) {
        log.warn("整点抢黄金时段对账暂时不可用: {}", id, e);
      }
    }
  }

  @Override
  public Result queryReservationsForReview() {
    return Result.ok(goldenSlotReservationMapper.selectReviewRequests());
  }

  private enum PublicationMode {
    INITIAL,
    RECOVERY
  }
}
