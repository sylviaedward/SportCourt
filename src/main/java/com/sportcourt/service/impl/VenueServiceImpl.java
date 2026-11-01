package com.sportcourt.service.impl;

import static com.sportcourt.utils.RedisConstants.CACHE_VENUE_KEY;
import static com.sportcourt.utils.RedisConstants.CACHE_VENUE_TTL;
import static com.sportcourt.utils.RedisConstants.VENUE_GEO_KEY;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sportcourt.dto.Result;
import com.sportcourt.dto.VenueSearchDTO;
import com.sportcourt.entity.Venue;
import com.sportcourt.mapper.VenueMapper;
import com.sportcourt.service.IBehaviorJournalService;
import com.sportcourt.service.IBusinessOutboxService;
import com.sportcourt.service.IVenueService;
import com.sportcourt.utils.CacheClient;
import com.sportcourt.utils.SystemConstants;
import com.sportcourt.utils.UserHolder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VenueServiceImpl extends ServiceImpl<VenueMapper, Venue> implements IVenueService {
  @Resource
  private IBehaviorJournalService behavior;

  @Resource
  private StringRedisTemplate stringRedisTemplate;

  @Resource
  private CacheClient cacheClient;

  @Resource
  private IBusinessOutboxService outbox;

  @Override
  public Result queryById(Long id) {
    // 解决缓存穿透
    Venue venue =
        cacheClient.queryWithPassThrough(
            CACHE_VENUE_KEY, id, Venue.class, this::getById, CACHE_VENUE_TTL, TimeUnit.MINUTES);

    // 互斥锁解决缓存击穿
    // Venue venue = cacheClient
    //         .queryWithMutex(CACHE_VENUE_KEY, id, Venue.class, this::getById, CACHE_VENUE_TTL,
    // TimeUnit.MINUTES);

    // 逻辑过期解决缓存击穿
    // Venue venue = cacheClient
    //         .queryWithLogicalExpire(CACHE_VENUE_KEY, id, Venue.class, this::getById, 20L,
    // TimeUnit.SECONDS);

    // Course cache strategies share the same key. A logical-expiry wrapper cannot
    // be deserialized as a Venue by pass-through; repair malformed/mismatched entries.
    if (venue != null && !id.equals(venue.getId())) {
      cacheClient.invalidate(CACHE_VENUE_KEY + id);
      venue =
          cacheClient.queryWithMutex(
              CACHE_VENUE_KEY, id, Venue.class, this::getById, CACHE_VENUE_TTL, TimeUnit.MINUTES);
    }
    if (venue == null) {
      return Result.fail("场馆不存在！");
    }
    // 7.返回
    return Result.ok(venue);
  }

  @Override
  @Transactional
  public Result update(Venue venue) {
    Long id = venue.getId();
    if (id == null) {
      return Result.fail("场馆id不能为空");
    }
    // 1.更新数据库
    if (!updateById(venue)) {
      return Result.fail("场馆不存在或未修改");
    }
    // Durable invalidation event commits with the update; immediate deletion runs after commit.
    outbox.appendVenueCacheInvalidation(id);
    return Result.ok();
  }

  @Override
  public Result queryVenueByType(Integer typeId, Integer current, Double x, Double y) {
    // 1.判断是否需要根据坐标查询
    if (x == null || y == null) {
      // 不需要坐标查询，按数据库查询
      Page<Venue> page =
          query()
              .eq("type_id", typeId)
              .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
      // 返回数据
      return Result.ok(page.getRecords());
    }

    // 2.计算分页参数
    int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
    int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

    // 3.查询redis、按照距离排序、分页。结果：venueId、distance
    String key = VENUE_GEO_KEY + typeId;
    // A fresh Redis has no course GEO fixtures; the default page must still show nearby venues.
    if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
      VenueSearchDTO criteria = new VenueSearchDTO();
      criteria.setTypeId(typeId.longValue());
      criteria.setX(x);
      criteria.setY(y);
      criteria.setPage(current);
      criteria.setSize(SystemConstants.DEFAULT_PAGE_SIZE);
      criteria.setSort("distance");
      return searchVenues(criteria);
    }
    GeoResults<RedisGeoCommands.GeoLocation<String>> results =
        stringRedisTemplate
            .opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
            .search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                    .includeDistance()
                    .sortAscending()
                    .limit(end));
    // 4.解析出id
    if (results == null) {
      return Result.ok(Collections.emptyList());
    }
    List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
    if (list.size() <= from) {
      // 没有下一页了，结束
      return Result.ok(Collections.emptyList());
    }
    // 4.1.截取 from ~ end的部分
    List<Long> ids = new ArrayList<>(list.size());
    Map<String, Distance> distanceMap = new HashMap<>(list.size());
    list.stream()
        .skip(from)
        .forEach(
            result -> {
              // 4.2.获取场馆id
              String venueIdStr = result.getContent().getName();
              ids.add(Long.valueOf(venueIdStr));
              // 4.3.获取距离
              Distance distance = result.getDistance();
              distanceMap.put(venueIdStr, distance);
            });
    // 5.根据id查询Venue
    String idStr = StrUtil.join(",", ids);
    List<Venue> venues = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
    for (Venue venue : venues) {
      venue.setDistance(distanceMap.get(venue.getId().toString()).getValue());
    }
    // 6.返回
    return Result.ok(venues);
  }

  @Override
  public Result searchVenues(VenueSearchDTO criteria) {
    if (criteria.getTypeId() == null || criteria.getTypeId() <= 0) {
      return Result.fail("typeId 必须是正整数");
    }
    Integer page = criteria.getPage();
    Integer size = criteria.getSize();
    if (page == null || page < 1 || page > 100000 || size == null || size < 1 || size > 20) {
      return Result.fail("page 必须为 1~100000，size 必须为 1~20");
    }
    if (criteria.getMaxPrice() != null && criteria.getMaxPrice() < 0) {
      return Result.fail("maxPrice 不能为负数");
    }
    if (criteria.getMinScore() != null
        && (criteria.getMinScore() < 0 || criteria.getMinScore() > 50)) {
      return Result.fail("minScore 必须为 0~50");
    }
    Double x = criteria.getX();
    Double y = criteria.getY();
    if ((x == null) != (y == null)) {
      return Result.fail("x 和 y 必须一起提供");
    }
    if (x != null
        && (!Double.isFinite(x)
            || !Double.isFinite(y)
            || x < -180
            || x > 180
            || y < -90
            || y > 90)) {
      return Result.fail("坐标超出范围");
    }
    String sort = StrUtil.blankToDefault(criteria.getSort(), "score");
    if (!"score".equals(sort) && !"price".equals(sort) && !"distance".equals(sort)) {
      return Result.fail("sort 只支持 score、price、distance");
    }
    if ("distance".equals(sort) && x == null) {
      return Result.fail("按距离排序需要 x 和 y");
    }

    QueryWrapper<Venue> query = new QueryWrapper<>();
    query.eq("type_id", criteria.getTypeId());
    if (StrUtil.isNotBlank(criteria.getKeyword())) {
      query.like("name", criteria.getKeyword().trim());
    }
    if (StrUtil.isNotBlank(criteria.getArea())) {
      String area = criteria.getArea().trim();
      query.and(q -> q.like("area", area).or().like("address", area));
    }
    if (criteria.getMaxPrice() != null) {
      query.le("avg_price", criteria.getMaxPrice());
    }
    if (criteria.getMinScore() != null) {
      query.ge("score", criteria.getMinScore());
    }
    if (Boolean.TRUE.equals(criteria.getHasBookingPass())) {
      query.apply(
          "EXISTS (SELECT 1 FROM tb_booking_pass v "
              + "LEFT JOIN tb_golden_slot_booking_pass sv ON sv.booking_pass_id = v.id "
              + "WHERE v.venue_id = tb_venue.id AND v.status = 1 "
              + "AND (v.type = 0 OR (v.type = 1 AND sv.stock > 0 "
              + "AND sv.begin_time <= NOW() AND sv.end_time >= NOW())))");
    }

    if (x == null) {
      if ("price".equals(sort)) {
        query.orderByAsc("avg_price").orderByDesc("score").orderByAsc("id");
      } else {
        query.orderByDesc("score").orderByAsc("avg_price").orderByAsc("id");
      }
      Page<Venue> resultPage = page(new Page<>(page, size), query);
      return Result.ok(resultPage.getRecords(), resultPage.getTotal());
    }

    // 先用经纬度范围缩小 MySQL 候选集，再用球面距离精确筛选 5 公里范围。
    final double radiusMeters = 5000.0;
    double latDelta = radiusMeters / 111320.0;
    double lonDelta = radiusMeters / (111320.0 * Math.max(0.01, Math.cos(Math.toRadians(y))));
    query.between("x", x - lonDelta, x + lonDelta).between("y", y - latDelta, y + latDelta);
    // Bound heap usage by doing distance filtering, ordering and pagination in MySQL.
    String distance =
        "(6371000.0 * 2 * ASIN(SQRT(LEAST(1.0, POW(SIN(RADIANS(y - "
            + y
            + ")/2),2) + COS(RADIANS("
            + y
            + ")) * COS(RADIANS(y)) * POW(SIN(RADIANS(x - "
            + x
            + ")/2),2)))))";
    query.apply(distance + " <= {0}", radiusMeters);
    if ("distance".equals(sort)) {
      query.orderByAsc(distance);
    } else if ("price".equals(sort)) {
      query.orderByAsc("avg_price");
    } else {
      query.orderByDesc("score");
    }
    query.orderByAsc("id");
    Page<Venue> nearbyPage = page(new Page<>(page, size), query);
    for (Venue venue : nearbyPage.getRecords()) {
      venue.setDistance(distanceMeters(x, y, venue.getX(), venue.getY()));
    }
    return Result.ok(nearbyPage.getRecords(), nearbyPage.getTotal());
  }

  private static double distanceMeters(double x1, double y1, double x2, double y2) {
    double lat1 = Math.toRadians(y1);
    double lat2 = Math.toRadians(y2);
    double deltaLat = lat2 - lat1;
    double deltaLon = Math.toRadians(x2 - x1);
    double a =
        Math.pow(Math.sin(deltaLat / 2), 2)
            + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLon / 2), 2);
    return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  @Override
  public Result catalog(String keyword, Long typeId, Integer page, Integer size, String sort) {
    if (page < 1
        || page > 10000
        || size < 1
        || size > 20
        || keyword.length() > 50
        || (typeId != null && typeId <= 0)
        || !("score".equals(sort) || "price".equals(sort))) {
      return Result.fail("场馆查询参数无效");
    }
    QueryWrapper<Venue> query = new QueryWrapper<>();
    query
        .eq(typeId != null, "type_id", typeId)
        .like(StrUtil.isNotBlank(keyword), "name", keyword.trim());
    if ("price".equals(sort)) {
      query.orderByAsc("avg_price");
    } else {
      query.orderByDesc("score");
    }
    query.orderByAsc("id");
    Page<Venue> result = page(new Page<>(page, size), query);
    return Result.ok(result.getRecords(), result.getTotal());
  }

  @Override
  public Result queryVenuesByIds(List<Long> ids) {
    if (ids == null
        || ids.isEmpty()
        || ids.size() > 40
        || ids.stream().anyMatch(id -> id == null || id <= 0)) {
      return Result.fail("ids 必须包含 1~40 个正整数");
    }
    List<Long> uniqueIds = ids.stream().distinct().collect(Collectors.toList());
    return Result.ok(listByIds(uniqueIds));
  }

  @Override
  public Result listAreas() {
    List<String> areas =
        list(new QueryWrapper<Venue>().select("DISTINCT area").isNotNull("area").ne("area", ""))
            .stream()
            .map(Venue::getArea)
            .sorted()
            .collect(Collectors.toList());
    return Result.ok(areas);
  }

  @Override
  public Result saveVenue(Venue venue) {
    // 写入数据库
    save(venue);
    // 返回场馆id
    return Result.ok(venue.getId());
  }

  @Override
  public Result queryVenueByName(String name, Integer current) {
    // 根据类型分页查询
    Page<Venue> page =
        query()
            .like(StrUtil.isNotBlank(name), "name", name)
            .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
    // 返回数据
    return Result.ok(page.getRecords());
  }

  @Override
  public Result queryVenueDetail(Long id) {
    Result result = queryById(id);
    if (Boolean.TRUE.equals(result.getSuccess()) && result.getData() != null) {
      behavior.recordVenueVisit(
          id, UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
    }
    return result;
  }
}
