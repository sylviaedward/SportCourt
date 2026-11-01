package com.sportcourt.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.hutool.json.JSONUtil;
import com.sportcourt.controller.UploadController;
import com.sportcourt.dto.Result;
import com.sportcourt.dto.UserDTO;
import com.sportcourt.dto.VenueSearchDTO;
import com.sportcourt.entity.Post;
import com.sportcourt.entity.ReservationOrder;
import com.sportcourt.entity.Venue;
import com.sportcourt.service.IFollowService;
import com.sportcourt.service.IPostService;
import com.sportcourt.service.IReservationOrderService;
import com.sportcourt.service.IVenueService;
import com.sportcourt.service.impl.BusinessOutboxServiceImpl;
import com.sportcourt.service.impl.GoldenSlotServiceImpl;
import com.sportcourt.service.impl.MediaServiceImpl;
import com.sportcourt.service.impl.ReservationOrderServiceImpl;
import com.sportcourt.utils.CacheClient;
import com.sportcourt.utils.RedisConstants;
import com.sportcourt.utils.SystemConstants;
import com.sportcourt.utils.UserHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"sportcourt.reliability.scheduling-enabled=false"})
class ReliabilityIntegrationTest {
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired ReservationOrderServiceImpl orders;
  @Autowired GoldenSlotServiceImpl coordinator;
  @Autowired IPostService posts;
  @Autowired IFollowService follows;
  @Autowired BusinessOutboxServiceImpl outbox;
  @Autowired IVenueService venues;
  @Autowired ApplicationContext context;
  @Autowired TransactionTemplate transactions;
  @Autowired RedissonClient redisson;
  @Autowired UploadController uploads;
  @Autowired MediaStorage mediaStorage;
  @Autowired MediaServiceImpl media;

  @Autowired DataSourceProperties datasourceProperties;

  private static final long SKU = 900001;
  private static final long OTHER = 900002;

  @BeforeEach
  void isolate() {
    // Hard guard: never run these destructive fixture resets against a user's business
    // database.
    assertEquals(
        "sportcourt_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    ReflectionTestUtils.setField(coordinator, "pendingCursor", "-");
    jdbc.update(
        "INSERT IGNORE INTO tb_user(id,phone,nick_name) "
            + "VALUES(1010,'13809991010','admin'),(1012,'13809991012','author')");
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setOutputStreaming(false);
    http.getRestTemplate().setRequestFactory(factory);
    jdbc.update("DELETE FROM tb_business_outbox");
    jdbc.update("DELETE FROM tb_golden_slot_dead_letter");
    jdbc.update(
        "DELETE FROM tb_golden_slot_reservation WHERE booking_pass_id IN (?,?)", SKU, OTHER);
    jdbc.update("DELETE FROM tb_booking_pass_order WHERE booking_pass_id IN (?,?)", SKU, OTHER);
    jdbc.update("DELETE FROM tb_golden_slot_activity WHERE booking_pass_id IN (?,?)", SKU, OTHER);
    jdbc.update(
        "DELETE FROM tb_golden_slot_booking_pass WHERE booking_pass_id IN (?,?)", SKU, OTHER);
    jdbc.update("DELETE FROM tb_booking_pass WHERE id IN (?,?)", SKU, OTHER);
    redis.delete("stream.orders");
    redis.execute(
        (RedisCallback<Object>)
            connection ->
                connection.execute(
                    "XGROUP",
                    "CREATE".getBytes(),
                    "stream.orders".getBytes(),
                    "g1".getBytes(),
                    "0".getBytes(),
                    "MKSTREAM".getBytes()));
    for (long id : new long[] {SKU, OTHER}) {
      redis.delete(
          Arrays.asList(
              "goldenSlot:stock:" + id,
              "goldenSlot:order:" + id,
              "goldenSlot:requests:" + id,
              "goldenSlot:activity:" + id));
      jdbc.update(
          "INSERT INTO "
              + "tb_booking_pass(id,venue_id,title,rules,pay_value,actual_value,type,status) "
              + "VALUES(?,1,'test','rules',0,100,1,1)",
          id);
      jdbc.update(
          "INSERT INTO tb_golden_slot_booking_pass(booking_pass_id,stock,begin_time,end_time) "
              + "VALUES(?,1,TIMESTAMPADD(HOUR,-1,NOW()),TIMESTAMPADD(DAY,1,NOW()))",
          id);
      jdbc.update(
          "INSERT INTO tb_golden_slot_activity(booking_pass_id,total_stock) VALUES(?,1)", id);
      assertTrue(coordinator.publishActivity(id).getSuccess());
    }
  }

  @AfterEach
  void clearUser() {
    UserHolder.removeUser();
  }

  private static void user(long id) {
    UserDTO user = new UserDTO();
    user.setId(id);
    UserHolder.saveUser(user);
  }

  private List<MapRecord<String, Object, Object>> read() {
    List<MapRecord<String, Object, Object>> list =
        redis
            .opsForStream()
            .read(
                Consumer.from("g1", "test-worker"),
                StreamReadOptions.empty().count(100),
                StreamOffset.create("stream.orders", ReadOffset.lastConsumed()));
    return list == null ? Collections.emptyList() : list;
  }

  private void handle(MapRecord<String, Object, Object> message) {
    ReflectionTestUtils.invokeMethod(coordinator, "handleOrderStreamMessage", message);
  }

  @Test
  void concurrentLastStockAndDuplicateDeliveryRemainConsistent() throws Exception {
    ExecutorService workers = Executors.newFixedThreadPool(12);
    List<Future<Result>> responses = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      final long id = 20000 + i;
      responses.add(
          workers.submit(
              () -> {
                user(id);
                try {
                  return orders.goldenSlotPass(SKU);
                } finally {
                  UserHolder.removeUser();
                }
              }));
    }
    long winner = 0;
    Result accepted = null;
    int successes = 0;
    for (int i = 0; i < responses.size(); i++) {
      Result result = responses.get(i).get(15, TimeUnit.SECONDS);
      if (Boolean.TRUE.equals(result.getSuccess())) {
        successes++;
        winner = 20000 + i;
        accepted = result;
      }
    }
    workers.shutdownNow();
    assertEquals(1, successes);
    user(winner);
    assertEquals(accepted.getData(), orders.goldenSlotPass(SKU).getData());
    List<MapRecord<String, Object, Object>> messages = read();
    assertEquals(1, messages.size());
    handle(messages.get(0));
    // At-least-once replay after transaction commit must not deduct stock twice.
    redis.opsForStream().add("stream.orders", messages.get(0).getValue());
    handle(read().get(0));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tb_booking_pass_order WHERE booking_pass_id=?", Long.class, SKU));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT stock FROM tb_golden_slot_booking_pass WHERE booking_pass_id=?",
            Long.class,
            SKU));
    assertEquals(
        "SUCCESS", coordinator.queryReservationState(((Number) accepted.getData()).longValue()));
    assertEquals("0", redis.opsForValue().get("goldenSlot:stock:" + SKU));
  }

  @Test
  void poisonMessageIsQuarantinedAndCompensationIsIdempotent() {
    user(21001);
    Result first = orders.goldenSlotPass(SKU);
    assertTrue(first.getSuccess());
    jdbc.update("UPDATE tb_golden_slot_booking_pass SET stock=0 WHERE booking_pass_id=?", SKU);
    MapRecord<String, Object, Object> bad = read().get(0);
    for (int i = 0; i < 5; i++) {
      handle(bad);
    }
    long id = ((Number) first.getData()).longValue();
    assertEquals("REVIEW", coordinator.queryReservationState(id));
    user(21002);
    assertTrue(orders.goldenSlotPass(OTHER).getSuccess());
    handle(read().get(0));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tb_booking_pass_order WHERE booking_pass_id=?",
            Long.class,
            OTHER));
    assertTrue(coordinator.markReservationFailedAndCompensateRedis(id).getSuccess());
    assertTrue(coordinator.markReservationFailedAndCompensateRedis(id).getSuccess());
    assertEquals("1", redis.opsForValue().get("goldenSlot:stock:" + SKU));
    // A replay after compensation cannot resurrect the failed reservation.
    handle(bad);
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tb_booking_pass_order WHERE booking_pass_id=?", Long.class, SKU));
  }

  @Test
  void restartClaimsAnotherConsumersPendingMessage() {
    user(22001);
    assertTrue(orders.goldenSlotPass(SKU).getSuccess());
    List<MapRecord<String, Object, Object>> abandoned = read();
    assertEquals(1, abandoned.size());
    List<MapRecord<String, Object, Object>> claimed =
        ReflectionTestUtils.invokeMethod(
            coordinator, "claim", "replacement", Duration.ZERO, abandoned.get(0).getId());
    assertEquals(1, claimed.size());
    handle(claimed.get(0));
    assertEquals(0L, redis.opsForStream().pending("stream.orders", "g1").getTotalPendingMessages());
  }

  @Test
  void missingRedisStockFailsClosedAndRecoveryRestoresPurchaseQualifications() {
    user(23001);
    Result result = orders.goldenSlotPass(SKU);
    handle(read().get(0));
    redis.delete(
        Arrays.asList(
            "goldenSlot:stock:" + SKU,
            "goldenSlot:order:" + SKU,
            "goldenSlot:requests:" + SKU,
            "goldenSlot:activity:" + SKU));
    user(23002);
    assertFalse(orders.goldenSlotPass(SKU).getSuccess());
    assertTrue(coordinator.recoverActivity(SKU).getSuccess());
    user(23001);
    assertEquals(result.getData(), orders.goldenSlotPass(SKU).getData());
    user(23002);
    assertFalse(orders.goldenSlotPass(SKU).getSuccess());
    @SuppressWarnings("unchecked")
    Map<String, Object> report =
        (Map<String, Object>) coordinator.queryInventoryReconciliationReport(SKU).getData();
    assertEquals(true, report.get("database_consistent"));
  }

  @Test
  void ordinaryCancellationAllowsReorderAndSnapshotSurvivesPriceChanges() {
    jdbc.update("UPDATE tb_booking_pass SET type=0,pay_value=100 WHERE id=?", SKU);
    user(24001);
    Result created = orders.orderOrdinaryBookingPass(SKU);
    assertTrue(created.getSuccess());
    long id = ((Number) created.getData()).longValue();
    assertEquals(created.getData(), orders.orderOrdinaryBookingPass(SKU).getData());
    jdbc.update("UPDATE tb_booking_pass SET pay_value=200,title='changed' WHERE id=?", SKU);
    assertFalse(orders.payMockOrder(id, 200L).getSuccess());
    assertTrue(orders.payMockOrder(id, 100L).getSuccess());
    assertEquals(100L, orders.getById(id).getPayValueSnapshot());
    jdbc.update("UPDATE tb_booking_pass_order SET status=1 WHERE id=?", id);
    assertTrue(orders.cancelUnpaidOrdinaryOrder(id).getSuccess());
    assertNotEquals(created.getData(), orders.orderOrdinaryBookingPass(SKU).getData());
  }

  @Test
  void explicitLikesAndFollowsAreIdempotentAndFeedIsRecoverable() {
    jdbc.update("DELETE FROM tb_follow_edge WHERE user_id=1010 AND follow_user_id=1012");
    user(1010);
    assertTrue(follows.follow(1012L, true).getSuccess());
    assertTrue(follows.follow(1012L, true).getSuccess());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tb_follow_edge WHERE user_id=1010 AND follow_user_id=1012",
            Long.class));
    Post post = new Post().setVenueId(1L).setTitle("isolation test").setContent("test");
    user(1012);
    Result saved = posts.savePost(post);
    assertTrue(saved.getSuccess());
    long id = ((Number) saved.getData()).longValue();
    user(1010);
    assertTrue(posts.setPostLike(id, true).getSuccess());
    assertTrue(posts.setPostLike(id, true).getSuccess());
    assertEquals(1L, jdbc.queryForObject("SELECT liked FROM tb_post WHERE id=?", Long.class, id));
    outbox.relayOutboxBatch();
    assertNotNull(redis.opsForZSet().score("feed:1010", String.valueOf(id)));
    assertTrue(posts.setPostLike(id, false).getSuccess());
    assertTrue(posts.setPostLike(id, false).getSuccess());
    assertEquals(0L, jdbc.queryForObject("SELECT liked FROM tb_post WHERE id=?", Long.class, id));
  }

  @Test
  void httpAuthorizationAndPendingRequestOwnershipAreEnforced() {
    assertEquals(200, http.getForEntity("/venue/1", String.class).getStatusCodeValue());
    assertEquals(401, http.postForEntity("/venue", new Venue(), String.class).getStatusCodeValue());
    user(25001);
    Result result = orders.goldenSlotPass(SKU);
    long id = ((Number) result.getData()).longValue();
    assertTrue(coordinator.queryReservationStatus(id, 25001).getSuccess());
    assertFalse(coordinator.queryReservationStatus(id, 25002).getSuccess());
    Map<String, String> identity = new HashMap<>();
    identity.put("id", "1012");
    identity.put("nickName", "test");
    redis.opsForHash().putAll("login:token:isolation-non-admin", identity);
    HttpHeaders headers = new HttpHeaders();
    headers.set("authorization", "isolation-non-admin");
    ResponseEntity<String> forbidden =
        http.exchange(
            "/venue", HttpMethod.PUT, new HttpEntity<>(new Venue(), headers), String.class);
    assertEquals(403, forbidden.getStatusCodeValue());
  }

  @Test
  void wrongStreamTypeDoesNotPreDeductAndCodeIsConsumedOnlyOnce() {
    redis.delete("stream.orders");
    redis.opsForValue().set("stream.orders", "wrong-type");
    user(26001);
    assertFalse(orders.goldenSlotPass(SKU).getSuccess());
    assertEquals("1", redis.opsForValue().get("goldenSlot:stock:" + SKU));
    assertFalse(
        Boolean.TRUE.equals(redis.opsForSet().isMember("goldenSlot:order:" + SKU, "26001")));
    redis.opsForValue().set("test:code", "123456");
    assertEquals(
        1L,
        redis.execute(
            RedisScripts.number("consume-code.lua"),
            Collections.singletonList("test:code"),
            "123456"));
    assertEquals(
        0L,
        redis.execute(
            RedisScripts.number("consume-code.lua"),
            Collections.singletonList("test:code"),
            "123456"));
  }

  @Test
  void coldCacheCoalescesConcurrentLoadsAndFencesStaleFill() throws Exception {
    CacheClient cache = new CacheClient(redis);
    ExecutorService workers = Executors.newFixedThreadPool(8);
    String prefix = "isolation:cache:";
    redis.delete(Arrays.asList(prefix + "1", prefix + "1:version"));
    AtomicInteger loads = new AtomicInteger();
    List<Future<Venue>> reads = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      reads.add(
          workers.submit(
              () ->
                  cache.queryWithMutex(
                      prefix,
                      1L,
                      Venue.class,
                      id -> {
                        loads.incrementAndGet();
                        try {
                          Thread.sleep(40);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return new Venue().setId(1L).setName("fresh");
                      },
                      1L,
                      TimeUnit.MINUTES)));
    }
    for (Future<Venue> read : reads) {
      assertEquals("fresh", read.get(5, TimeUnit.SECONDS).getName());
    }
    assertEquals(1, loads.get());
    cache.invalidate(prefix + "1");
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);

    Future<Venue> old =
        workers.submit(
            () ->
                cache.queryWithMutex(
                    prefix,
                    1L,
                    Venue.class,
                    id -> {
                      started.countDown();
                      try {
                        finish.await(5, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return new Venue().setId(1L).setName("old");
                    },
                    1L,
                    TimeUnit.MINUTES));
    assertTrue(started.await(3, TimeUnit.SECONDS));
    cache.invalidate(prefix + "1");
    finish.countDown();
    old.get(3, TimeUnit.SECONDS);
    assertNull(redis.opsForValue().get(prefix + "1"));
    cache.close();
    workers.shutdownNow();
  }

  @Test
  void twoLiveConsumersFulfilEachReservationOnce() throws Exception {
    jdbc.update("UPDATE tb_golden_slot_booking_pass SET stock=10 WHERE booking_pass_id=?", SKU);
    jdbc.update("UPDATE tb_golden_slot_activity SET total_stock=10 WHERE booking_pass_id=?", SKU);
    redis.opsForValue().set("goldenSlot:stock:" + SKU, "10");
    GoldenSlotServiceImpl first =
        new GoldenSlotServiceImpl(
            redis,
            transactions,
            redisson,
            context.getBeanProvider(IReservationOrderService.class),
            new SimpleMeterRegistry());
    GoldenSlotServiceImpl second =
        new GoldenSlotServiceImpl(
            redis,
            transactions,
            redisson,
            context.getBeanProvider(IReservationOrderService.class),
            new SimpleMeterRegistry());
    for (GoldenSlotServiceImpl worker : new GoldenSlotServiceImpl[] {first, second}) {
      context.getAutowireCapableBeanFactory().autowireBean(worker);
      ReflectionTestUtils.setField(worker, "enabled", true);
      ReflectionTestUtils.setField(worker, "maxDeliveries", 5);
      ReflectionTestUtils.setField(worker, "idleMillis", 100L);
      worker.start();
    }
    try {
      for (int i = 0; i < 10; i++) {
        user(27000 + i);
        assertTrue(orders.goldenSlotPass(SKU).getSuccess());
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
      long count = 0;

      while (System.nanoTime() < deadline) {
        count =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_booking_pass_order WHERE booking_pass_id=?",
                Long.class,
                SKU);
        if (count == 10) {
          break;
        }
        Thread.sleep(50);
      }
      assertEquals(10, count);
      assertEquals(
          0L,
          jdbc.queryForObject(
              "SELECT stock FROM tb_golden_slot_booking_pass WHERE booking_pass_id=?",
              Long.class,
              SKU));
      assertEquals(
          10L,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM tb_golden_slot_reservation "
                  + "WHERE booking_pass_id=? AND state='SUCCESS'",
              Long.class,
              SKU));
    } finally {
      first.stop();
      second.stop();
    }
  }

  @Test
  void publishedActivityNeverAutoReopensAfterUnconsumedRedisDataLoss() {
    user(28001);
    Result accepted = orders.goldenSlotPass(SKU);
    assertTrue(accepted.getSuccess());
    redis.delete(
        Arrays.asList(
            "goldenSlot:stock:" + SKU,
            "goldenSlot:order:" + SKU,
            "goldenSlot:requests:" + SKU,
            "goldenSlot:activity:" + SKU,
            "goldenSlot:request:" + accepted.getData(),
            "stream.orders"));
    coordinator.start(); // Simulate a restart before the request ever reaches MySQL.
    assertFalse(Boolean.TRUE.equals(redis.hasKey("goldenSlot:stock:" + SKU)));
    assertFalse(coordinator.publishActivity(SKU).getSuccess());
    user(28002);
    assertFalse(orders.goldenSlotPass(SKU).getSuccess());
  }

  @Test
  void nearbySearchUsesDatabasePaginationAndStableOrdering() {
    VenueSearchDTO query = new VenueSearchDTO();
    query.setTypeId(1L);
    query.setX(121.4632);
    query.setY(31.1746);
    query.setSort("distance");
    query.setPage(1);
    query.setSize(2);
    Result result = venues.searchVenues(query);
    assertTrue(result.getSuccess());
    @SuppressWarnings("unchecked")
    List<Venue> page = (List<Venue>) result.getData();
    assertTrue(page.size() <= 2);
    assertTrue(result.getTotal() > 0);
    if (page.size() == 2) {
      assertTrue(page.get(0).getDistance() <= page.get(1).getDistance());
    }
    redis.delete(RedisConstants.VENUE_GEO_KEY + "1");
    Result defaultPage = venues.queryVenueByType(1, 1, 121.4632, 31.1746);
    assertTrue(defaultPage.getSuccess());
    @SuppressWarnings("unchecked")
    List<Venue> defaults = (List<Venue>) defaultPage.getData();
    assertFalse(defaults.isEmpty());
    assertTrue(defaults.size() <= SystemConstants.DEFAULT_PAGE_SIZE);
  }

  @Test
  void defaultFrontendImagePathsAndLegacyDeleteKeepOwnershipChecks() throws Exception {
    Path directory = Files.createTempDirectory("sportcourt-images-");
    Object original = ReflectionTestUtils.getField(media, "uploadDir");
    Object storageOriginal = ReflectionTestUtils.getField(mediaStorage, "uploadDir");
    ReflectionTestUtils.setField(media, "uploadDir", directory.toString());
    ReflectionTestUtils.setField(mediaStorage, "uploadDir", directory.toString());
    try {
      user(1010);
      byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0};
      String path =
          (String)
              uploads
                  .uploadImage(new MockMultipartFile("file", "default.png", "image/png", png))
                  .getData();
      String frontendPath = "/imgs" + path;
      user(1012);
      assertFalse(
          posts
              .savePost(
                  new Post()
                      .setVenueId(1L)
                      .setTitle("other owner")
                      .setContent("test")
                      .setImages(frontendPath))
              .getSuccess());
      user(1010);
      assertTrue(
          posts
              .savePost(
                  new Post()
                      .setVenueId(1L)
                      .setTitle("default frontend")
                      .setContent("test")
                      .setImages(frontendPath))
              .getSuccess());
      assertFalse(uploads.deletePostImgLegacy(frontendPath).getSuccess());
      String unused =
          (String)
              uploads
                  .uploadImage(new MockMultipartFile("file", "unused.png", "image/png", png))
                  .getData();
      HttpHeaders headers = new HttpHeaders();
      headers.set("authorization", "default-ui-owner");
      redis.opsForHash().put("login:token:default-ui-owner", "id", "1010");
      String response =
          http.exchange(
                  "/upload/post/delete?name=/imgs" + unused,
                  HttpMethod.GET,
                  new HttpEntity<>(headers),
                  String.class)
              .getBody();
      assertTrue(JSONUtil.parseObj(response).getBool("success"));
      outbox.relayOutboxBatch();
      assertFalse(Files.exists(directory.resolve(unused.substring(1))));
    } finally {
      ReflectionTestUtils.setField(media, "uploadDir", original);
      ReflectionTestUtils.setField(mediaStorage, "uploadDir", storageOriginal);
      try (Stream<Path> paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  @Test
  void conflictingMessageCannotPoisonTheRealReservation() {
    user(29001);
    Result accepted = orders.goldenSlotPass(SKU);
    long id = ((Number) accepted.getData()).longValue();
    MapRecord<String, Object, Object> original = read().get(0);
    ReflectionTestUtils.invokeMethod(
        coordinator,
        "registerAndValidateReservation",
        new ReservationOrder().setId(id).setUserId(29001L).setBookingPassId(SKU));
    Map<Object, Object> wrong = new HashMap<>(original.getValue());
    wrong.put("userId", "29002");
    redis.opsForStream().add(GoldenSlotServiceImpl.STREAM, wrong);
    MapRecord<String, Object, Object> conflict = read().get(0);
    for (int attempt = 0; attempt < 6; attempt++) {
      handle(conflict);
    }
    assertEquals("PROCESSING", coordinator.queryReservationState(id));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT attempts FROM tb_golden_slot_reservation WHERE order_id=?", Integer.class, id));
    assertEquals("PROCESSING", redis.opsForHash().get("goldenSlot:request:" + id, "state"));
    assertEquals(
        1L, jdbc.queryForObject("SELECT COUNT(*) FROM tb_golden_slot_dead_letter", Long.class));
    handle(original);
    assertEquals("SUCCESS", coordinator.queryReservationState(id));
    assertEquals(29001L, orders.getById(id).getUserId());
  }

  @Test
  void statusCacheFailureAfterCommitCannotDowngradeSuccess() {
    user(30001);
    Result accepted = orders.goldenSlotPass(SKU);
    long id = ((Number) accepted.getData()).longValue();
    MapRecord<String, Object, Object> original = read().get(0);
    ReflectionTestUtils.invokeMethod(
        coordinator,
        "registerAndValidateReservation",
        new ReservationOrder().setId(id).setUserId(30001L).setBookingPassId(SKU));
    jdbc.update("UPDATE tb_golden_slot_reservation SET attempts=5 WHERE order_id=?", id);
    String key = "goldenSlot:request:" + id;
    redis.delete(key);
    redis.opsForValue().set(key, "simulate status cache failure");
    assertThrows(RuntimeException.class, () -> handle(original));
    assertEquals("SUCCESS", coordinator.queryReservationState(id));
    assertEquals(
        0L, jdbc.queryForObject("SELECT COUNT(*) FROM tb_golden_slot_dead_letter", Long.class));
    assertEquals(
        1L,
        redis
            .opsForStream()
            .pending(GoldenSlotServiceImpl.STREAM, GoldenSlotServiceImpl.GROUP)
            .getTotalPendingMessages());
    redis.delete(key);
    handle(original);
    assertEquals("SUCCESS", redis.opsForHash().get(key, "state"));
    assertEquals(
        0L,
        redis
            .opsForStream()
            .pending(GoldenSlotServiceImpl.STREAM, GoldenSlotServiceImpl.GROUP)
            .getTotalPendingMessages());
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT stock FROM tb_golden_slot_booking_pass WHERE booking_pass_id=?",
            Long.class,
            SKU));
  }

  @Test
  void pendingCursorReachesOldTailBehindFiftyYoungEntries() {
    for (int i = 0; i < 50; i++) {
      redis
          .opsForStream()
          .add(GoldenSlotServiceImpl.STREAM, Collections.singletonMap("sentinel", "young-" + i));
    }
    user(31001);
    Result accepted = orders.goldenSlotPass(SKU);
    List<MapRecord<String, Object, Object>> abandoned = read();
    assertEquals(51, abandoned.size());
    String tail = abandoned.get(50).getId().getValue();
    redis.execute(
        (RedisCallback<Object>)
            connection ->
                connection.execute(
                    "XCLAIM",
                    GoldenSlotServiceImpl.STREAM.getBytes(),
                    GoldenSlotServiceImpl.GROUP.getBytes(),
                    "abandoned".getBytes(),
                    "0".getBytes(),
                    tail.getBytes(),
                    "IDLE".getBytes(),
                    "120000".getBytes()));
    ReflectionTestUtils.invokeMethod(coordinator, "reclaimPending");
    assertEquals(
        51L,
        redis
            .opsForStream()
            .pending(GoldenSlotServiceImpl.STREAM, GoldenSlotServiceImpl.GROUP)
            .getTotalPendingMessages());
    ReflectionTestUtils.invokeMethod(coordinator, "reclaimPending");
    assertEquals(
        "SUCCESS", coordinator.queryReservationState(((Number) accepted.getData()).longValue()));
    assertEquals(
        50L,
        redis
            .opsForStream()
            .pending(GoldenSlotServiceImpl.STREAM, GoldenSlotServiceImpl.GROUP)
            .getTotalPendingMessages());
  }

  @Test
  void imageDeletionWaitsForPublicationAndCannotDeleteTheCommittedReference() throws Exception {
    String path = "/posts/0/0/" + UUID.randomUUID() + ".png";
    jdbc.update("INSERT INTO tb_media_owner(path,user_id) VALUES(?,1010)", path);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch saved = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    CountDownLatch deleting = new CountDownLatch(1);

    try {
      Future<Result> publication =
          workers.submit(
              () -> {
                user(1010);
                try {
                  return transactions.execute(
                      tx -> {
                        Result result =
                            posts.savePost(
                                new Post()
                                    .setVenueId(1L)
                                    .setTitle("media race")
                                    .setContent("test")
                                    .setImages("/imgs" + path));
                        saved.countDown();
                        try {
                          if (!commit.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("commit timeout");
                          }
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                        return result;
                      });
                } finally {
                  UserHolder.removeUser();
                }
              });
      assertTrue(saved.await(3, TimeUnit.SECONDS));
      Future<Result> deletion =
          workers.submit(
              () -> {
                deleting.countDown();
                return media.delete(path, 1010);
              });
      assertTrue(deleting.await(1, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> deletion.get(200, TimeUnit.MILLISECONDS));
      commit.countDown();
      assertTrue(publication.get(3, TimeUnit.SECONDS).getSuccess());
      assertFalse(deletion.get(3, TimeUnit.SECONDS).getSuccess());
      assertEquals(
          "ACTIVE",
          jdbc.queryForObject("SELECT state FROM tb_media_owner WHERE path=?", String.class, path));
      assertEquals(
          1L,
          jdbc.queryForObject("SELECT COUNT(*) FROM tb_post_media WHERE path=?", Long.class, path));
    } finally {
      commit.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void failedFileDeletionIsDurableAndBlocksReuseUntilIdempotentRetryCompletes() throws Exception {
    Path directory = Files.createTempDirectory("sportcourt-media-retry-");
    Object original = ReflectionTestUtils.getField(mediaStorage, "uploadDir");
    ReflectionTestUtils.setField(mediaStorage, "uploadDir", directory.toString());
    String path = "/posts/0/0/" + UUID.randomUUID() + ".png";
    Path file = directory.resolve(path.substring(1));
    try {
      Files.createDirectories(
          file); // Simulate an invalid storage entry which cannot be removed as a file.
      jdbc.update("INSERT INTO tb_media_owner(path,user_id) VALUES(?,1010)", path);
      assertTrue(media.delete("/imgs" + path, 1010).getSuccess());
      assertTrue(media.delete(path, 1010).getSuccess());
      assertFalse(media.delete(path, 1012).getSuccess());
      outbox.relayOutboxBatch();
      assertEquals(
          "DELETING",
          jdbc.queryForObject("SELECT state FROM tb_media_owner WHERE path=?", String.class, path));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT attempts FROM tb_business_outbox WHERE resource_path=?",
              Integer.class,
              path));
      user(1010);
      assertFalse(
          posts
              .savePost(
                  new Post()
                      .setVenueId(1L)
                      .setTitle("deleted reference")
                      .setContent("test")
                      .setImages("/imgs" + path))
              .getSuccess());
      Files.delete(file);
      jdbc.update(
          "UPDATE tb_business_outbox SET next_attempt_at=NOW() WHERE resource_path=?", path);
      outbox.relayOutboxBatch();
      assertEquals(
          "DELETED",
          jdbc.queryForObject("SELECT state FROM tb_media_owner WHERE path=?", String.class, path));
      jdbc.update(
          "UPDATE tb_business_outbox SET state='PENDING',next_attempt_at=NOW() "
              + "WHERE resource_path=?",
          path);
      outbox.relayOutboxBatch();
      assertEquals(
          "DONE",
          jdbc.queryForObject(
              "SELECT state FROM tb_business_outbox WHERE resource_path=?", String.class, path));
      assertTrue(media.delete(path, 1010).getSuccess());
      assertEquals(
          1L,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM tb_business_outbox WHERE resource_path=?", Long.class, path));
    } finally {
      ReflectionTestUtils.setField(mediaStorage, "uploadDir", original);
      try (Stream<Path> paths = Files.walk(directory)) {
        for (Path entry : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
          Files.deleteIfExists(entry);
        }
      }
    }
  }

  @Test
  void mediaMigrationPreservesPreviouslyPublishedOwnedImages() throws Exception {
    assertEquals(
        "sportcourt_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    String schema = "sportcourt_media_upgrade_test_" + Long.toHexString(System.nanoTime());
    jdbc.execute("CREATE DATABASE " + schema);
    try {
      DriverManagerDataSource source =
          new DriverManagerDataSource(
              datasourceProperties.getUrl().replace("sportcourt_optimization_test", schema),
              datasourceProperties.getUsername(),
              datasourceProperties.getPassword());
      try (Connection connection = source.getConnection()) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/sportcourt.sql"));
      }
      Flyway.configure()
          .dataSource(source)
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .target("3")
          .load()
          .migrate();
      JdbcTemplate before = new JdbcTemplate(source);
      String first = "/posts/0/0/" + UUID.randomUUID() + ".png";
      String second = "/posts/1/1/" + UUID.randomUUID() + ".jpg";

      before.update(
          "INSERT INTO tb_media_owner(path,user_id) VALUES(?,1010),(?,1010)", first, second);
      before.update(
          "INSERT INTO tb_post(id,venue_id,user_id,title,content,images) "
              + "VALUES(990001,1,1010,'upgrade','test',?)",
          "/imgs" + first + "," + second + ",/imgs" + first + ",/imgs/posts/legacy.jpg");
      Flyway.configure()
          .dataSource(source)
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .load()
          .migrate();
      assertEquals(
          2L,
          before.queryForObject(
              "SELECT COUNT(*) FROM tb_post_media WHERE post_id=990001", Long.class));
      assertEquals(
          "ACTIVE",
          before.queryForObject(
              "SELECT state FROM tb_media_owner WHERE path=?", String.class, first));
      assertEquals(
          1L,
          before.queryForObject(
              "SELECT COUNT(*) FROM tb_post_media WHERE post_id=990001 AND path=?",
              Long.class,
              first));
    } finally {
      jdbc.execute("DROP DATABASE " + schema);
    }
  }
}
