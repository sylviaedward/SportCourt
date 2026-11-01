package com.sportcourt.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sportcourt.dto.Result;
import com.sportcourt.entity.BookingPass;
import com.sportcourt.entity.ReservationOrder;
import com.sportcourt.enums.BookingPassType;
import com.sportcourt.enums.OrderStatus;
import com.sportcourt.mapper.ReservationOrderMapper;
import com.sportcourt.service.IBookingPassService;
import com.sportcourt.service.IGoldenSlotPassService;
import com.sportcourt.service.IGoldenSlotService;
import com.sportcourt.service.IOrderLifecycleService;
import com.sportcourt.service.IReservationOrderService;
import com.sportcourt.utils.RedisIdWorker;
import com.sportcourt.utils.UserHolder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ReservationOrderServiceImpl
    extends ServiceImpl<ReservationOrderMapper, ReservationOrder>
    implements IReservationOrderService {

  @Value("${sportcourt.payment.mock-enabled:false}")
  private boolean mockPaymentEnabled;

  @Resource
  private IGoldenSlotPassService goldenSlotPassService;

  @Resource
  private IBookingPassService bookingPassService;

  @Resource
  private RedisIdWorker redisIdWorker;

  @Resource
  private RedissonClient redissonClient;

  @Resource
  private StringRedisTemplate stringRedisTemplate;

  @Resource
  private TransactionTemplate transactionTemplate;

  @Resource
  private IOrderLifecycleService lifecycle;

  private static final DefaultRedisScript<Long> GOLDEN_SLOT_SCRIPT;

  static {
    GOLDEN_SLOT_SCRIPT = new DefaultRedisScript<>();
    GOLDEN_SLOT_SCRIPT.setLocation(new ClassPathResource("goldenSlot.lua"));
    GOLDEN_SLOT_SCRIPT.setResultType(Long.class);
  }

  @Resource
  private IGoldenSlotService coordinator;

  @Override
  public void persistGoldenSlot(ReservationOrder order) {
    createReservationOrder(order);
  }

  protected void createReservationOrder(ReservationOrder reservationOrder) {
    Long userId = reservationOrder.getUserId();
    Long bookingPassId = reservationOrder.getBookingPassId();
    RLock lock = redissonClient.getLock("lock:order:" + userId);
    if (!lock.tryLock()) {
      throw new IllegalStateException("订单锁忙，保留消息重试");
    }
    try {
      transactionTemplate.execute(
          status -> {
            if (coordinator != null
                && !coordinator.lockReservationForFulfillment(reservationOrder)) {
              return null;
            }
            ReservationOrder existing = getById(reservationOrder.getId());
            if (existing != null) {
              if (!userId.equals(existing.getUserId())
                  || !bookingPassId.equals(existing.getBookingPassId())) {
                throw new IllegalStateException("订单号冲突");
              }
              if (coordinator != null) {
                coordinator.markReservationSuccessful(reservationOrder.getId());
              }
              return null;
            }
            if (query().eq("user_id", userId).eq("booking_pass_id", bookingPassId).count() > 0) {
              throw new IllegalStateException("资格对应的订单号不一致，保留消息核对");
            }
            boolean deducted =
                goldenSlotPassService
                    .update()
                    .setSql("stock = stock - 1")
                    .eq("booking_pass_id", bookingPassId)
                    .gt("stock", 0)
                    .update();
            if (!deducted) {
              throw new IllegalStateException("库存不足，保留消息核对");
            }
            BookingPass bookingPass = bookingPassService.getById(bookingPassId);
            if (bookingPass == null) {
              throw new IllegalStateException("商品不存在，保留消息核对");
            }
            snapshot(reservationOrder, bookingPass);
            reservationOrder.setStatus(initialOrderStatus(bookingPass));
            if (!save(reservationOrder)) {
              throw new IllegalStateException("订单写入失败，回滚库存");
            }
            if (coordinator != null) {
              coordinator.markReservationSuccessful(reservationOrder.getId());
            }
            return null;
          });
    } finally {
      lock.unlock();
    }
  }

  @Autowired(required = false)
  private MeterRegistry metrics;

  @Override
  public Result goldenSlotPass(Long bookingPassId) {
    if (bookingPassId == null || bookingPassId <= 0) {
      return Result.fail("整点抢黄金时段券参数无效");
    }
    Long userId = UserHolder.getUser().getId();
    long orderId = redisIdWorker.nextId("order");
    // 1.执行lua脚本
    long started = System.nanoTime();
    Long result;
    try {
      result =
          stringRedisTemplate.execute(
              GOLDEN_SLOT_SCRIPT,
              Arrays.asList(
                  "goldenSlot:stock:" + bookingPassId,
                  "goldenSlot:order:" + bookingPassId,
                  "stream.orders",
                  "goldenSlot:activity:" + bookingPassId,
                  "goldenSlot:requests:" + bookingPassId,
                  "goldenSlot:request:" + orderId),
              bookingPassId.toString(),
              userId.toString(),
              String.valueOf(orderId));
    } catch (RuntimeException unavailable) {
      if (metrics != null) {
        metrics
            .counter("sportcourt.goldenSlot.admissions", "result", "transport_error")
            .increment();
      }
      return Result.fail("订单服务暂时不可用，请稍后查询订单状态");
    } finally {
      if (metrics != null) {
        metrics
            .timer("sportcourt.goldenSlot.admission")
            .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
      }
    }
    if (metrics != null) {
      metrics
          .counter("sportcourt.goldenSlot.admissions", "result", admissionMetricLabel(result))
          .increment();
    }
    if (result == null) {
      return Result.fail("订单服务暂时不可用");
    }
    int admissionCode = result.intValue();
    // 2.判断结果是否为0
    if (admissionCode != 0) {
      // 2.1.不为0 ，代表没有购买资格
      if (admissionCode == 2) {
        Object original =
            stringRedisTemplate
                .opsForHash()
                .get("goldenSlot:requests:" + bookingPassId, userId.toString());
        if (original != null) {
          return Result.ok(Long.parseLong(original.toString()));
        }
        ReservationOrder existing =
            query()
                .eq("user_id", userId)
                .eq("booking_pass_id", bookingPassId)
                .orderByDesc("create_time")
                .last("LIMIT 1")
                .one();
        if (existing != null) {
          return Result.ok(existing.getId());
        }
        return Result.fail("已有抢购资格，请查询处理状态");
      }
      return Result.fail(
          admissionCode == 1 ? "库存不足" : admissionCode == 3 ? "活动尚未就绪、已暂停或不在购买时段" : "活动数据异常，请稍后重试");
    }
    // 3.返回订单id
    return Result.ok(orderId);
  }

  @Override
  public Result orderOrdinaryBookingPass(Long bookingPassId) {
    Long userId = UserHolder.getUser().getId();
    RLock lock = redissonClient.getLock("lock:order:ordinary:" + userId);
    if (!lock.tryLock()) {
      return Result.fail("订单正在处理，请稍后查询");
    }
    try {
      return transactionTemplate.execute(
          tx -> {
            baseMapper.insertOrdinarySlot(userId, bookingPassId);
            baseMapper.lockOrdinarySlot(userId, bookingPassId);
            BookingPass bookingPass = bookingPassService.getById(bookingPassId);
            if (bookingPass == null
                || bookingPass.getStatus() == null
                || bookingPass.getStatus() != 1
                || bookingPass.getType() == null
                || !BookingPassType.ORDINARY.matches(bookingPass.getType())) {
              return Result.fail("普通券不存在或已下架");
            }
            ReservationOrder existing =
                query()
                    .eq("user_id", userId)
                    .eq("booking_pass_id", bookingPassId)
                    .ne("status", OrderStatus.CANCELLED.getCode())
                    .orderByDesc("create_time")
                    .last("LIMIT 1")
                    .one();
            if (existing != null) {
              return Result.ok(existing.getId());
            }
            ReservationOrder order = new ReservationOrder();
            order.setId(redisIdWorker.nextId("order"));
            order.setUserId(userId);
            order.setBookingPassId(bookingPassId);
            snapshot(order, bookingPass);
            // 免费券直接领取；付费券只创建待支付订单，不能冒充支付成功。
            order.setStatus(initialOrderStatus(bookingPass));
            if (!save(order)) {
              return Result.fail("创建订单失败");
            }
            lifecycle.registerOrderCreatedEventAndDeadline(order.getId());
            return Result.ok(order.getId());
          });
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Result queryMyOrder(Long orderId) {
    ReservationOrder order = getById(orderId);
    if (order == null || !UserHolder.getUser().getId().equals(order.getUserId())) {
      return Result.fail("订单不存在");
    }
    Map<String, Object> detail = BeanUtil.beanToMap(order);
    BookingPass bookingPass = bookingPassService.getById(order.getBookingPassId());
    if (bookingPass != null) {
      detail.put(
          "bookingPassType",
          order.getBookingPassTypeSnapshot() == null
              ? bookingPass.getType()
              : order.getBookingPassTypeSnapshot());
      detail.put(
          "payValue",
          order.getPayValueSnapshot() == null
              ? bookingPass.getPayValue()
              : order.getPayValueSnapshot());
      detail.put("venueId", bookingPass.getVenueId());
      detail.put(
          "bookingPassTitle",
          order.getTitleSnapshot() == null ? bookingPass.getTitle() : order.getTitleSnapshot());
    }
    return Result.ok(detail);
  }

  @Override
  public Result queryMyReservationOrder(Long bookingPassId) {
    ReservationOrder order =
        query()
            .eq("user_id", UserHolder.getUser().getId())
            .eq("booking_pass_id", bookingPassId)
            .orderByDesc("create_time")
            .last("LIMIT 1")
            .one();
    return Result.ok(order);
  }

  @Override
  public Result queryMyOrders(Integer page, boolean heldOnly) {
    if (page == null || page < 1 || page > 10000) {
      return Result.fail("分页参数无效");
    }
    LambdaQueryChainWrapper<ReservationOrder> query =
        lambdaQuery().eq(ReservationOrder::getUserId, UserHolder.getUser().getId());
    if (heldOnly) {
      query.eq(ReservationOrder::getStatus, OrderStatus.PAID.getCode());
    }
    Page<ReservationOrder> result =
        query
            .orderByDesc(ReservationOrder::getCreateTime)
            .orderByDesc(ReservationOrder::getId)
            .page(new Page<>(page, 20));
    Map<Long, BookingPass> bookingPasses =
        result.getRecords().isEmpty()
            ? Collections.emptyMap()
            : bookingPassService
                .listByIds(
                    result.getRecords().stream()
                        .map(ReservationOrder::getBookingPassId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(BookingPass::getId, v -> v));
    List<Map<String, Object>> items = new ArrayList<>();
    for (ReservationOrder order : result.getRecords()) {
      BookingPass bookingPass = bookingPasses.get(order.getBookingPassId());
      Map<String, Object> item = new HashMap<>();
      item.put("order_id", order.getId().toString());
      item.put("booking_pass_id", order.getBookingPassId());
      item.put("status", order.getStatus());
      item.put("created_at", order.getCreateTime());
      if (bookingPass != null) {
        item.put("venue_id", bookingPass.getVenueId());
        item.put(
            "title",
            order.getTitleSnapshot() == null ? bookingPass.getTitle() : order.getTitleSnapshot());
        item.put(
            "pay_cents",
            order.getPayValueSnapshot() == null
                ? bookingPass.getPayValue()
                : order.getPayValueSnapshot());
        item.put(
            "booking_pass_type",
            order.getBookingPassTypeSnapshot() == null
                ? bookingPass.getType()
                : order.getBookingPassTypeSnapshot());
        item.put(
            "rules",
            order.getRulesSnapshot() == null ? bookingPass.getRules() : order.getRulesSnapshot());
      }
      items.add(item);
    }
    return Result.ok(items, result.getTotal());
  }

  @Override
  public Result payMockOrder(Long orderId, Long expectedPayCents) {
    if (!mockPaymentEnabled) {
      return Result.fail("本地模拟支付未启用");
    }
    return transactionTemplate.execute(
        tx -> {
          Long userId = UserHolder.getUser().getId();
          ReservationOrder order = getById(orderId);
          if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("订单不存在");
          }
          BookingPass bookingPass = bookingPassService.getById(order.getBookingPassId());
          Long payable =
              order.getPayValueSnapshot() == null
                  ? (bookingPass == null ? null : bookingPass.getPayValue())
                  : order.getPayValueSnapshot();
          if (payable == null || payable <= 0 || !payable.equals(expectedPayCents)) {
            return Result.fail("支付金额不匹配，请重新查询");
          }
          if (OrderStatus.PAID.matches(order.getStatus())) {
            return Result.ok(orderId.toString());
          }
          if (!OrderStatus.UNPAID.matches(order.getStatus())) {
            return Result.fail("订单不是待支付状态");
          }
          boolean changed =
              update()
                  .set("status", OrderStatus.PAID.getCode())
                  .set("pay_time", LocalDateTime.now())
                  .eq("id", orderId)
                  .eq("user_id", userId)
                  .eq("status", OrderStatus.UNPAID.getCode())
                  .apply("(expire_at IS NULL OR expire_at > NOW(3))")
                  .update();
          if (!changed) {
            ReservationOrder latest = getById(orderId);
            if (latest == null
                || !userId.equals(latest.getUserId())
                || !OrderStatus.PAID.matches(latest.getStatus())) {
              return Result.fail("订单状态已变化，请刷新后重试");
            }
          }
          if (changed) {
            lifecycle.registerOrderPaidEvent(orderId, userId);
          }
          return Result.ok(orderId.toString());
        });
  }

  @Override
  public Result cancelUnpaidOrdinaryOrder(Long orderId) {
    return transactionTemplate.execute(
        tx -> {
          Long userId = UserHolder.getUser().getId();
          ReservationOrder order = getById(orderId);
          if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("订单不存在");
          }
          BookingPass bookingPass = bookingPassService.getById(order.getBookingPassId());
          if (bookingPass == null
              || bookingPass.getType() == null
              || !BookingPassType.ORDINARY.matches(bookingPass.getType())
              || bookingPass.getPayValue() == null
              || bookingPass.getPayValue() <= 0) {
            return Result.fail("仅支持取消未支付的普通券订单");
          }
          if (OrderStatus.CANCELLED.matches(order.getStatus())) {
            return Result.ok(orderId);
          }
          if (!OrderStatus.UNPAID.matches(order.getStatus())) {
            return Result.fail("订单不是待支付状态");
          }
          boolean changed =
              update()
                  .set("status", OrderStatus.CANCELLED.getCode())
                  .eq("id", orderId)
                  .eq("user_id", userId)
                  .eq("status", OrderStatus.UNPAID.getCode())
                  .update();
          if (changed) {
            lifecycle.registerOrderClosedEvent(orderId, userId);
          }
          return changed ? Result.ok(orderId) : Result.fail("订单状态已变化，请刷新后重试");
        });
  }

  private static int initialOrderStatus(BookingPass bookingPass) {
    boolean freeBookingPass = bookingPass.getPayValue() != null && bookingPass.getPayValue() == 0L;
    return freeBookingPass ? OrderStatus.PAID.getCode() : OrderStatus.UNPAID.getCode();
  }

  private static String admissionMetricLabel(Long result) {
    if (result == null) {
      return "unavailable";
    }
    if (result == 0) {
      return "accepted";
    }
    if (result == 1) {
      return "sold_out";
    }
    if (result == 2) {
      return "duplicate";
    }
    return result == 3 ? "inactive" : "invalid";
  }

  private static void snapshot(ReservationOrder order, BookingPass bookingPass) {
    order.setVenueIdSnapshot(bookingPass.getVenueId());
    order.setPayValueSnapshot(bookingPass.getPayValue());
    order.setTitleSnapshot(bookingPass.getTitle());
    order.setRulesSnapshot(bookingPass.getRules());
    order.setBookingPassTypeSnapshot(bookingPass.getType());
  }
}
