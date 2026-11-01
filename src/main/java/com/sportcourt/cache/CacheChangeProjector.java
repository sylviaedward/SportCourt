package com.sportcourt.cache;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.google.protobuf.InvalidProtocolBufferException;
import com.sportcourt.entity.BookingPass;
import com.sportcourt.entity.Venue;
import com.sportcourt.service.IBookingPassService;
import com.sportcourt.service.IVenueService;
import com.sportcourt.utils.CacheClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CacheChangeProjector {
  private static final Set<String> WATCHED_TABLES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList("tb_venue", "tb_booking_pass", "tb_golden_slot_booking_pass")));
  private static final long CDC_VENUE_TTL_MINUTES = 20;
  private static final long PASS_METADATA_TTL_MINUTES = 10;

  private final CacheClient cache;
  private final ObjectProvider<IVenueService> venueServiceProvider;
  private final ObjectProvider<IBookingPassService> bookingPassServiceProvider;
  private final MeterRegistry metrics;

  @Value("${sportcourt.cdc.schema:sportcourt}")
  private String schema;

  public CacheChangeProjector(
      CacheClient cache,
      ObjectProvider<IVenueService> venueServiceProvider,
      ObjectProvider<IBookingPassService> bookingPassServiceProvider,
      MeterRegistry metrics) {
    this.cache = cache;
    this.venueServiceProvider = venueServiceProvider;
    this.bookingPassServiceProvider = bookingPassServiceProvider;
    this.metrics = metrics;
  }

  public boolean isRelevantEntry(CanalEntry.Entry entry) {
    return entry.getEntryType() == CanalEntry.EntryType.ROWDATA
        && schema.equals(entry.getHeader().getSchemaName())
        && WATCHED_TABLES.contains(entry.getHeader().getTableName());
  }

  /**
   * 仅处理指定数据库和表的变更，失效相关元数据缓存并按需回源。
   *
   * <p>活动执行库存和购买资格由整点抢黄金时段流程维护，CDC 不用数据库快照覆盖 Redis 执行状态。
   */
  public void projectEntryChanges(CanalEntry.Entry entry) throws InvalidProtocolBufferException {
    log.debug(
        "CDC entry: {} {}.{} type={}",
        entry.getEntryType(),
        entry.getHeader().getSchemaName(),
        entry.getHeader().getTableName(),
        entry.getHeader().getEventType());
    if (!isRelevantEntry(entry)) {
      return;
    }
    String table = entry.getHeader().getTableName();
    CanalEntry.RowChange change = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
    if (change.getIsDdl()) {
      invalidateTableMetadata(table);
      metrics.counter("sportcourt.cdc.ddl", "table", table).increment();
      return;
    }
    log.debug("CDC row count={} event={}", change.getRowDatasCount(), change.getEventType());
    for (CanalEntry.RowData row : change.getRowDatasList()) {
      projectRow(table, change.getEventType(), row);
      metrics.counter("sportcourt.cdc.rows", "table", table).increment();
    }
  }

  private void invalidateTableMetadata(String table) {
    if ("tb_venue".equals(table)) {
      cache.invalidatePrefix("cache:venue:");
    }
    if ("tb_booking_pass".equals(table)) {
      cache.invalidatePrefix("cache:bookingPass-meta:");
      cache.invalidatePrefix("cache:bookingPass-list:");
    }
  }

  private void projectRow(String table, CanalEntry.EventType eventType, CanalEntry.RowData row) {
    Map<String, String> before = mapColumnValues(row.getBeforeColumnsList());
    Map<String, String> after = mapColumnValues(row.getAfterColumnsList());
    Map<String, String> identity = after.isEmpty() ? before : after;
    if ("tb_venue".equals(table)) {
      projectVenueChange(parseRowId(identity, "id"), eventType);
    } else if ("tb_booking_pass".equals(table)) {
      projectBookingPassChange(parseRowId(identity, "id"), eventType, before, after);
    }
    // 活动库存与窗口实时查库；CDC 不覆盖 Redis 的执行库存或购买资格。
  }

  private void projectVenueChange(long venueId, CanalEntry.EventType eventType) {
    cache.invalidate("cache:venue:" + venueId);
    if (eventType != CanalEntry.EventType.DELETE) {
      cache.queryWithMutex(
          "cache:venue:",
          venueId,
          Venue.class,
          id -> venueServiceProvider.getObject().getById(id),
          CDC_VENUE_TTL_MINUTES,
          TimeUnit.MINUTES);
    }
  }

  private void projectBookingPassChange(
      long bookingPassId,
      CanalEntry.EventType eventType,
      Map<String, String> before,
      Map<String, String> after) {
    cache.invalidate("cache:bookingPass-meta:" + bookingPassId);
    // 券换店后旧、新场馆都需要失效；这里只对当前行的关联场馆去重。
    Set<String> venueIds = new HashSet<>();
    venueIds.add(before.get("venue_id"));
    venueIds.add(after.get("venue_id"));
    venueIds.remove(null);
    for (String venueId : venueIds) {
      cache.invalidate("cache:bookingPass-list:" + Long.parseLong(venueId));
    }
    if (eventType != CanalEntry.EventType.DELETE) {
      cache.queryWithMutex(
          "cache:bookingPass-meta:",
          bookingPassId,
          BookingPass.class,
          id -> bookingPassServiceProvider.getObject().getById(id),
          PASS_METADATA_TTL_MINUTES,
          TimeUnit.MINUTES);
    }
  }

  private static Map<String, String> mapColumnValues(List<CanalEntry.Column> columns) {
    Map<String, String> result = new HashMap<>();
    for (CanalEntry.Column column : columns) {
      if (!column.getIsNull()) {
        result.put(column.getName(), column.getValue());
      }
    }
    return result;
  }

  private static long parseRowId(Map<String, String> row, String key) {
    String value = row.get(key);
    if (value == null) {
      throw new IllegalStateException("binlog 缺少完整主键: " + key);
    }
    return Long.parseLong(value);
  }
}
