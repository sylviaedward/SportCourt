package com.sportcourt.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sportcourt.cache.BookingPassCatalog;
import com.sportcourt.dto.Result;
import com.sportcourt.entity.BookingPass;
import com.sportcourt.entity.GoldenSlotPass;
import com.sportcourt.mapper.BookingPassMapper;
import com.sportcourt.mapper.GoldenSlotPassMapper;
import com.sportcourt.service.IBookingPassService;
import com.sportcourt.service.IBusinessOutboxService;
import com.sportcourt.service.IGoldenSlotPassService;
import com.sportcourt.service.IVenueService;
import com.sportcourt.utils.CacheClient;
import com.sportcourt.utils.UserHolder;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BookingPassServiceImpl extends ServiceImpl<BookingPassMapper, BookingPass>
    implements IBookingPassService {

  @Resource
  private GoldenSlotPassMapper goldenSlotPassMapper;

  @Resource
  private IGoldenSlotPassService goldenSlotPassService;

  @Resource
  private StringRedisTemplate stringRedisTemplate;

  @Resource
  private IBusinessOutboxService outbox;

  @Resource
  private CacheClient cache;

  @Override
  public Result queryBookingPassOfVenue(Long venueId) {
    BookingPassCatalog catalog =
        cache.queryWithMutex(
            "cache:bookingPass-list:",
            venueId,
            BookingPassCatalog.class,
            id -> {
              BookingPassCatalog value = new BookingPassCatalog();
              value.setIds(baseMapper.selectActiveBookingPassIds(id));
              return value;
            },
            10L,
            TimeUnit.MINUTES);
    List<BookingPass> bookingPasses = new ArrayList<>();
    if (catalog == null || catalog.getIds() == null || catalog.getIds().isEmpty()) {
      return Result.ok(bookingPasses);
    }
    // Trading stock and time windows are always read live, in one batch rather than N queries.
    Map<Long, GoldenSlotPass> activities = new HashMap<>();
    for (GoldenSlotPass activity : goldenSlotPassService.listByIds(catalog.getIds())) {
      activities.put(activity.getBookingPassId(), activity);
    }
    LocalDateTime now = LocalDateTime.now();
    for (Long id : catalog.getIds()) {
      BookingPass bookingPass =
          cache.queryWithMutex(
              "cache:bookingPass-meta:",
              id,
              BookingPass.class,
              this::getById,
              10L,
              TimeUnit.MINUTES);
      if (bookingPass == null
          || !Objects.equals(bookingPass.getStatus(), 1)
          || !Objects.equals(bookingPass.getVenueId(), venueId)) {
        continue;
      }
      GoldenSlotPass activity = activities.get(id);
      if (activity != null) {
        bookingPass.setStock(activity.getStock());
        bookingPass.setBeginTime(activity.getBeginTime());
        bookingPass.setEndTime(activity.getEndTime());
      }
      boolean active =
          Objects.equals(bookingPass.getType(), 0)
              || (Objects.equals(bookingPass.getType(), 1)
                  && bookingPass.getStock() != null
                  && bookingPass.getStock() > 0
                  && bookingPass.getBeginTime() != null
                  && bookingPass.getEndTime() != null
                  && !now.isBefore(bookingPass.getBeginTime())
                  && !now.isAfter(bookingPass.getEndTime()));
      bookingPass.setOrderable(active);
      bookingPasses.add(bookingPass);
    }
    // 返回结果
    return Result.ok(bookingPasses);
  }

  @Override
  @Transactional
  public void addGoldenSlotPass(BookingPass bookingPass) {
    // 保存订场券
    save(bookingPass);
    // 保存整点抢黄金时段信息
    GoldenSlotPass goldenSlotPass = new GoldenSlotPass();
    goldenSlotPass.setBookingPassId(bookingPass.getId());
    goldenSlotPass.setStock(bookingPass.getStock());
    goldenSlotPass.setBeginTime(bookingPass.getBeginTime());
    goldenSlotPass.setEndTime(bookingPass.getEndTime());
    goldenSlotPassService.save(goldenSlotPass);
    goldenSlotPassMapper.insertActivity(bookingPass.getId(), bookingPass.getStock());
    outbox.appendBusinessEvent("GOLDEN_SLOT_PUBLISH", bookingPass.getId(), null);
  }

  @Resource
  private IVenueService venueService;

  @Value("${sportcourt.catalog-admin-ids:}")
  private String catalogAdminIds;

  private boolean canManage() {
    return UserHolder.getUser() != null
        && Arrays.stream(catalogAdminIds.split(","))
            .map(String::trim)
            .anyMatch(id -> id.equals(UserHolder.getUser().getId().toString()));
  }

  private String validate(BookingPass v, boolean goldenSlot) {
    if (!canManage()) {
      return "当前账号没有演示商品管理权限";
    }
    if (v.getId() != null
        || v.getVenueId() == null
        || v.getVenueId() <= 0
        || venueService.getById(v.getVenueId()) == null) {
      return "关联场馆不存在或参数无效";
    }
    if (v.getTitle() == null
        || v.getTitle().trim().isEmpty()
        || v.getTitle().length() > 100
        || v.getRules() == null
        || v.getRules().trim().isEmpty()
        || v.getRules().length() > 1000
        || (v.getSubTitle() != null && v.getSubTitle().length() > 200)) {
      return "标题或规则无效";
    }
    if (v.getPayValue() == null
        || v.getActualValue() == null
        || v.getPayValue() < 0
        || v.getActualValue() <= 0
        || v.getActualValue() > 10000000
        || v.getPayValue() > v.getActualValue()) {
      return "券金额无效";
    }
    if (goldenSlot
        && (v.getStock() == null
            || v.getStock() <= 0
            || v.getStock() > 100000
            || v.getBeginTime() == null
            || v.getEndTime() == null
            || !v.getEndTime().isAfter(v.getBeginTime()))) {
      return "整点抢黄金时段库存或时间无效";
    }
    v.setType(goldenSlot ? 1 : 0);
    v.setStatus(1);
    v.setCreateTime(null);
    v.setUpdateTime(null);
    if (!v.getTitle().startsWith("【演示】")) {
      v.setTitle("【演示】" + v.getTitle().trim());
    }
    return v.getTitle().length() > 100 ? "标题过长" : null;
  }

  @Override
  public Result capabilities() {
    Map<String, Object> data = new HashMap<>();
    data.put("can_create_bookingPass", canManage());
    data.put("server_time", LocalDateTime.now().toString());
    data.put("server_zone", ZoneId.systemDefault().toString());
    return Result.ok(data);
  }

  @Override
  @Transactional
  public Result createGoldenSlotPass(BookingPass bookingPass) {
    String error = validate(bookingPass, true);
    if (error != null) {
      return Result.fail(error);
    }
    addGoldenSlotPass(bookingPass);
    return Result.ok(bookingPass.getId());
  }

  @Override
  @Transactional
  public Result createBookingPass(BookingPass bookingPass) {
    String error = validate(bookingPass, false);
    if (error != null) {
      return Result.fail(error);
    }
    if (!save(bookingPass)) {
      return Result.fail("保存失败");
    }
    return Result.ok(bookingPass.getId());
  }
}
