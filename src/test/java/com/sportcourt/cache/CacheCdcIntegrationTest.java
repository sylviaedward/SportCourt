package com.sportcourt.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.alibaba.otter.canal.client.CanalConnector;
import com.sportcourt.entity.Venue;
import com.sportcourt.service.impl.GoldenSlotServiceImpl;
import com.sportcourt.utils.CacheClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"sportcourt.reliability.scheduling-enabled=false", "sportcourt.cdc.enabled=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CacheCdcIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired CacheClient cache;
  @Autowired MemcacheStore shared;
  @Autowired MeterRegistry metrics;
  @Autowired GoldenSlotServiceImpl goldenSlot;
  @Autowired ApplicationContext context;

  @BeforeEach
  void guard() {
    assertEquals(
        "sportcourt_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    cache.invalidate("cache:venue:900201");
    cache.invalidate("cache:bookingPass-meta:900202");
    jdbc.update("DELETE FROM tb_booking_pass WHERE id=900202");
  }

  @Test
  void externalSqlWarmsCurrentValueAndMakesOldMemcacheNamespaceUnreachable() throws Exception {
    await(() -> metrics.get("sportcourt.cdc.connected").gauge().value() == 1);
    long id = 900201;
    String old = "cdc-old";
    String fresh = "cdc-new-" + System.nanoTime();

    jdbc.update(
        "INSERT INTO "
            + "tb_venue(id,name,type_id,images,area,address,x,y,"
            + "avg_price,sold,comments,score) VALUES(?, ?,1,'img','test','test',"
            + "1,1,1,1,1,50) ON DUPLICATE KEY UPDATE name=VALUES(name)",
        id,
        old);
    await(() -> old.equals(read(id).getName()));
    String before = version(id);
    jdbc.update(
        "UPDATE tb_venue SET name=? WHERE id=?",
        fresh,
        id); // Direct SQL bypasses service and Outbox.
    await(
        () ->
            !before.equals(version(id))
                && fresh.equals(read(id).getName())
                && shared.get("cache:venue:" + id, version(id)) != null);
    // Warming happens before the batch audit commits; wait for the independent durable
    // checkpoint.
    await(
        () ->
            jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tb_cache_cdc_checkpoint WHERE "
                        + "destination='example' AND binlog_offset>0",
                    Long.class)
                > 0);
    CacheClient peer = new CacheClient(redis, shared);
    try {
      Venue value =
          peer.queryWithMutex(
              "cache:venue:",
              id,
              Venue.class,
              key -> {
                fail("A new instance must hit shared warm data");
                return null;
              },
              20L,
              TimeUnit.MINUTES);
      assertEquals(fresh, value.getName());
    } finally {
      peer.close();
    }
    jdbc.update("DELETE FROM tb_venue WHERE id=?", id);
    await(() -> read(id) == null);
    goldenSlot.refreshMetrics();
    assertTrue(metrics.get("sportcourt.goldenSlot.lag").gauge().value() >= 0);
  }

  @Test
  void bookingPassMoveInvalidatesBothVenueAggregates() throws Exception {
    await(() -> metrics.get("sportcourt.cdc.connected").gauge().value() == 1);
    long id = 900202;
    jdbc.update(
        "INSERT INTO "
            + "tb_booking_pass(id,venue_id,title,rules,pay_value,actual_value,type,status) "
            + "VALUES(?,1,'cdc-bookingPass','rules',0,100,0,1) ON DUPLICATE KEY UPDATE "
            + "venue_id=1",
        id);
    await(() -> redis.opsForValue().get("cache:bookingPass-meta:" + id) != null);
    String oldOwner = revision("cache:bookingPass-list:1");
    String newOwner = revision("cache:bookingPass-list:2");

    jdbc.update("UPDATE tb_booking_pass SET venue_id=2,title='moved' WHERE id=?", id);
    await(
        () ->
            !oldOwner.equals(revision("cache:bookingPass-list:1"))
                && !newOwner.equals(revision("cache:bookingPass-list:2")));
    await(
        () -> {
          String raw = redis.opsForValue().get("cache:bookingPass-meta:" + id);
          return raw != null && raw.contains("moved");
        });
  }

  @Test
  void clientReconnectResumesAndProcessesSubsequentChange() throws Exception {
    await(() -> metrics.get("sportcourt.cdc.connected").gauge().value() == 1);
    CanalCacheConsumer consumer = context.getBean(CanalCacheConsumer.class);
    CanalConnector previous = (CanalConnector) ReflectionTestUtils.getField(consumer, "active");
    assertNotNull(previous);
    previous.disconnect();
    await(
        () -> {
          Object active = ReflectionTestUtils.getField(consumer, "active");
          return active != null
              && active != previous
              && metrics.get("sportcourt.cdc.connected").gauge().value() == 1;
        });
    long id = 900203;
    String name = "after-reconnect-" + System.nanoTime();
    jdbc.update(
        "INSERT INTO "
            + "tb_venue(id,name,type_id,images,area,address,x,y,"
            + "avg_price,sold,comments,score) VALUES(?, ?,1,'img','test','test',"
            + "1,1,1,1,1,50) ON DUPLICATE KEY UPDATE name=VALUES(name)",
        id,
        name);
    await(
        () -> {
          CacheSnapshot value = shared.get("cache:venue:" + id, version(id));
          return value != null && value.json.contains(name);
        });
    jdbc.update("DELETE FROM tb_venue WHERE id=?", id);
  }

  private Venue read(long id) {
    return cache.queryWithMutex(
        "cache:venue:",
        id,
        Venue.class,
        key ->
            jdbc
                .query(
                    "SELECT id,name FROM tb_venue WHERE id=?",
                    (rs, row) -> new Venue().setId(rs.getLong(1)).setName(rs.getString(2)),
                    key)
                .stream()
                .findFirst()
                .orElse(null),
        20L,
        TimeUnit.MINUTES);
  }

  private String version(long id) {
    return revision("cache:venue:" + id);
  }

  private String revision(String key) {
    String raw = redis.opsForValue().get(key + ":version");
    return raw == null ? "0" : raw;
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(100);
    }
    assertTrue(condition.getAsBoolean(), "Real Canal CDC did not converge within 30 seconds");
  }
}
