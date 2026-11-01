package com.sportcourt.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sportcourt.dto.Result;
import com.sportcourt.entity.BookingPass;
import com.sportcourt.entity.Venue;
import com.sportcourt.service.IBookingPassService;
import com.sportcourt.utils.CacheClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "sportcourt.reliability.scheduling-enabled=false",
      "sportcourt.cdc.enabled=false"
    })
class CacheLayersIntegrationTest {
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcTemplate jdbc;
  @Autowired MemcacheStore shared;
  @Autowired CacheClient serviceCache;
  @Autowired TestRestTemplate http;
  @Autowired IBookingPassService bookingPasses;
  private CacheClient cache;
  private SimpleMeterRegistry registry;
  private String prefix;

  @BeforeEach
  void init() throws InterruptedException {
    assertEquals(
        "sportcourt_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    prefix = "test:layers:" + UUID.randomUUID() + ":";
    cache = new CacheClient(redis, shared);
    registry = new SimpleMeterRegistry();
    cache.bindMetrics(registry);
    // A previous context may have just closed its client. Wait for this client's
    // real connection before asserting short TTLs or cross-client cache hits.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!shared.put(
        prefix + "ready", "0", new CacheSnapshot("{}", System.currentTimeMillis() + 10000, true))) {
      assertTrue(System.nanoTime() < deadline, "隔离 Memcache 未在 5 秒内就绪");
      Thread.sleep(100);
    }
  }

  @AfterEach
  void close() {
    cache.close();
    registry.close();
  }

  private Venue venue(String name) {
    Venue s = new Venue();
    s.setId(1L);
    s.setName(name);
    return s;
  }

  private Venue read(CacheClient client, AtomicInteger loads) {
    return client.queryWithMutex(
        prefix,
        1L,
        Venue.class,
        id -> {
          loads.incrementAndGet();
          return venue("database");
        },
        1L,
        TimeUnit.MINUTES);
  }

  @Test
  void scopeLocalSharedAndRedisServeImmutableCopies() {
    AtomicInteger loads = new AtomicInteger();
    try (RequestCacheScope scope = RequestCacheScope.open()) {
      read(cache, loads).setName("mutated");
      assertEquals("database", read(cache, loads).getName());
      assertEquals(
          1,
          registry
              .get("sportcourt.cache.requests")
              .tags("layer", "scope", "result", "hit")
              .counter()
              .count());
    }
    assertEquals("database", read(cache, loads).getName());
    assertEquals(
        1,
        registry
            .get("sportcourt.cache.requests")
            .tags("layer", "jvm", "result", "hit")
            .counter()
            .count());
    CacheClient peer = new CacheClient(redis, shared);
    SimpleMeterRegistry peerMetrics = new SimpleMeterRegistry();
    peer.bindMetrics(peerMetrics);
    try {
      assertEquals("database", read(peer, loads).getName());
      assertEquals(
          1,
          peerMetrics
              .get("sportcourt.cache.requests")
              .tags("layer", "memcache", "result", "hit")
              .counter()
              .count());
    } finally {
      peer.close();
      peerMetrics.close();
    }
    CacheClient redisOnly = new CacheClient(redis);
    try {
      assertEquals("database", read(redisOnly, loads).getName());
      assertEquals(1, loads.get());
    } finally {
      redisOnly.close();
    }
  }

  @Test
  void concurrentLoadingCoalescesAndInvalidatedFlightCannotPoisonNewGeneration() throws Exception {
    AtomicInteger loads = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch began = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);

    try {
      List<Future<Venue>> reads = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        reads.add(
            pool.submit(
                () ->
                    cache.queryWithMutex(
                        prefix,
                        1L,
                        Venue.class,
                        id -> {
                          loads.incrementAndGet();
                          try {
                            Thread.sleep(30);
                          } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                          }
                          return venue("old");
                        },
                        1L,
                        TimeUnit.MINUTES)));
      }
      for (Future<Venue> f : reads) {
        assertEquals("old", f.get(5, TimeUnit.SECONDS).getName());
      }
      assertEquals(1, loads.get());
      cache.invalidate(prefix + 1);
      Future<Venue> old =
          pool.submit(
              () ->
                  cache.queryWithMutex(
                      prefix,
                      1L,
                      Venue.class,
                      id -> {
                        began.countDown();
                        try {
                          finish.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          throw new IllegalStateException(e);
                        }
                        return venue("old");
                      },
                      1L,
                      TimeUnit.MINUTES));
      assertTrue(began.await(3, TimeUnit.SECONDS));
      cache.invalidate(prefix + 1);
      finish.countDown();
      old.get(5, TimeUnit.SECONDS);
      assertNull(redis.opsForValue().get(prefix + 1));
      assertNull(shared.get(prefix + 1, redis.opsForValue().get(prefix + "1:version")));
      assertEquals("database", read(cache, loads).getName());
    } finally {
      finish.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void versionNamespaceRejectsOldSharedValueAndTtlDoesNotExtendSourceLifetime() throws Exception {
    String key = prefix + 1;
    String revision = "0";
    assertTrue(
        shared.put(
            key,
            revision,
            new CacheSnapshot(
                "{\"id\":1,\"name\":\"stale\"}", System.currentTimeMillis() + 200, true)));
    Thread.sleep(260);
    assertNull(shared.get(key, revision));
    assertTrue(
        shared.put(
            key,
            revision,
            new CacheSnapshot("{\"id\":1,\"name\":\"stale\"}", Long.MAX_VALUE, true)));
    cache.invalidate(key);
    AtomicInteger loads = new AtomicInteger();
    assertEquals("database", read(cache, loads).getName());
    assertEquals(1, loads.get());
  }

  @Test
  void ddlNamespaceScanFencesSharedEntriesAndLocalSnapshots() {
    AtomicInteger loads = new AtomicInteger();
    read(cache, loads);
    String before = redis.opsForValue().get(prefix + "1:version");
    cache.invalidatePrefix(prefix);
    assertNotEquals(before, redis.opsForValue().get(prefix + "1:version"));
    assertNull(redis.opsForValue().get(prefix + 1));
    assertEquals("database", read(cache, loads).getName());
    assertEquals(2, loads.get());
  }

  @Test
  void unavailableMemcacheFallsBackWithinBoundAndKeepsRedisData() {
    MemcacheStore broken = new MemcacheStore();
    ReflectionTestUtils.setField(broken, "enabled", true);
    ReflectionTestUtils.setField(broken, "host", "127.0.0.1");
    ReflectionTestUtils.setField(broken, "port", 1);
    ReflectionTestUtils.setField(broken, "timeoutMillis", 40L);
    ReflectionTestUtils.setField(broken, "metrics", registry);
    CacheClient fallback = new CacheClient(redis, broken);
    redis.opsForValue().set(prefix + 1, "{\"id\":1,\"name\":\"redis\"}", 1, TimeUnit.MINUTES);
    try {
      long began = System.nanoTime();
      assertEquals("redis", read(fallback, new AtomicInteger()).getName());
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 2000);
      assertEquals(
          1,
          registry
              .get("sportcourt.memcache.operations")
              .tags("operation", "get", "result", "error")
              .counter()
              .count());
      assertNotNull(redis.opsForValue().get(prefix + 1));
    } finally {
      fallback.close();
      broken.close();
    }
  }

  @Test
  void realActuatorHandlerRequiresDedicatedCredentialOrAdminLogin() {
    assertEquals(401, http.getForEntity("/actuator/prometheus", String.class).getStatusCodeValue());
    HttpHeaders headers = new HttpHeaders();
    headers.set("Authorization", "Bearer wrong");
    assertEquals(
        401,
        http.exchange(
                "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(headers), String.class)
            .getStatusCodeValue());
    headers.set("Authorization", "Bearer sportcourt_dev_metrics");
    assertEquals(
        200,
        http.exchange(
                "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(headers), String.class)
            .getStatusCodeValue());
    assertEquals(
        401,
        http.exchange(
                "/ops/goldenSlot/tasks", HttpMethod.GET, new HttpEntity<>(headers), String.class)
            .getStatusCodeValue());
    redis.opsForHash().put("login:token:test-monitor-admin", "id", "1010");
    redis.expire("login:token:test-monitor-admin", 1, TimeUnit.MINUTES);
    try {
      headers.set("Authorization", "test-monitor-admin");
      assertEquals(
          200,
          http.exchange(
                  "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(headers), String.class)
              .getStatusCodeValue());
    } finally {
      redis.delete("login:token:test-monitor-admin");
    }
    assertEquals(200, http.getForEntity("/actuator/health", String.class).getStatusCodeValue());
  }

  @Test
  void bookingPassMetadataCachesButStockAndWindowRemainLive() {
    long id = 900111;
    jdbc.update(
        "INSERT INTO "
            + "tb_booking_pass(id,venue_id,title,rules,pay_value,actual_value,type,status) "
            + "VALUES(?,1,'metadata','rules',0,100,1,1) ON DUPLICATE KEY UPDATE "
            + "title='metadata',type=1,status=1",
        id);
    jdbc.update(
        "INSERT INTO tb_golden_slot_booking_pass(booking_pass_id,stock,begin_time,end_time) "
            + "VALUES(?,1,TIMESTAMPADD(HOUR,-1,NOW()),TIMESTAMPADD(DAY,1,NOW())) ON "
            + "DUPLICATE KEY UPDATE "
            + "stock=1,begin_time=VALUES(begin_time),end_time=VALUES(end_time)",
        id);
    serviceCache.invalidate("cache:bookingPass-list:1");
    serviceCache.invalidate("cache:bookingPass-meta:" + id);
    assertTrue(find(bookingPasses.queryBookingPassOfVenue(1L), id).getOrderable());
    jdbc.update("UPDATE tb_golden_slot_booking_pass SET stock=0 WHERE booking_pass_id=?", id);
    BookingPass response = find(bookingPasses.queryBookingPassOfVenue(1L), id);
    assertFalse(response.getOrderable());
    assertEquals(0, response.getStock());
    jdbc.update(
        "UPDATE tb_golden_slot_booking_pass SET "
            + "stock=5,begin_time=TIMESTAMPADD(DAY,1,NOW()) WHERE booking_pass_id=?",
        id);
    assertFalse(find(bookingPasses.queryBookingPassOfVenue(1L), id).getOrderable());
  }

  @SuppressWarnings("unchecked")
  private BookingPass find(Result response, long id) {
    return ((List<BookingPass>) response.getData())
        .stream().filter(v -> v.getId() == id).findFirst().get();
  }
}
