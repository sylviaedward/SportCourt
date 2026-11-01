package com.sportcourt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportcourt.dto.UserDTO;
import com.sportcourt.entity.BookingPass;
import com.sportcourt.entity.GoldenSlotPass;
import com.sportcourt.entity.ReservationOrder;
import com.sportcourt.service.IBookingPassService;
import com.sportcourt.service.IGoldenSlotPassService;
import com.sportcourt.service.impl.ReservationOrderServiceImpl;
import com.sportcourt.utils.UserHolder;
import java.io.Serializable;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ReservationOrderReliabilityTest {
  static class Service extends ReservationOrderServiceImpl {
    ReservationOrder existing;
    boolean saveResult = true;

    @SuppressWarnings("unchecked")
    QueryChainWrapper<ReservationOrder> candidates = mock(QueryChainWrapper.class, RETURNS_SELF);

    @SuppressWarnings("unchecked")
    LambdaQueryChainWrapper<ReservationOrder> personal =
        mock(LambdaQueryChainWrapper.class, RETURNS_SELF);

    @Override
    public ReservationOrder getById(Serializable id) {
      return existing;
    }

    @Override
    public QueryChainWrapper<ReservationOrder> query() {
      return candidates;
    }

    @Override
    public LambdaQueryChainWrapper<ReservationOrder> lambdaQuery() {
      return personal;
    }

    @Override
    public boolean save(ReservationOrder order) {
      return saveResult;
    }

    void consume(ReservationOrder order) {
      createReservationOrder(order);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void persistenceFailureRollsBackAndDuplicateDeliveryDoesNotDeductAgain() {
    Service service = new Service();
    RLock lock = mock(RLock.class);
    when(lock.tryLock()).thenReturn(true);
    RedissonClient redis = mock(RedissonClient.class);
    when(redis.getLock(anyString())).thenReturn(lock);
    IGoldenSlotPassService stock = mock(IGoldenSlotPassService.class);
    UpdateChainWrapper<GoldenSlotPass> update = mock(UpdateChainWrapper.class, RETURNS_SELF);
    when(stock.update()).thenReturn(update);
    when(update.update()).thenReturn(true);
    IBookingPassService bookingPasses = mock(IBookingPassService.class);
    when(bookingPasses.getById(9L)).thenReturn(new BookingPass().setId(9L).setPayValue(0L));
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    TransactionStatus status = mock(TransactionStatus.class);
    when(manager.getTransaction(any())).thenReturn(status);
    ReflectionTestUtils.setField(service, "redissonClient", redis);
    ReflectionTestUtils.setField(service, "goldenSlotPassService", stock);
    ReflectionTestUtils.setField(service, "bookingPassService", bookingPasses);
    ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(manager));
    ReservationOrder order = new ReservationOrder().setId(88L).setUserId(7L).setBookingPassId(9L);
    service.saveResult = false;
    assertThrows(IllegalStateException.class, () -> service.consume(order));
    verify(manager).rollback(status);
    verify(lock).unlock();
    reset(stock, manager);
    when(manager.getTransaction(any())).thenReturn(status);
    service.existing = order;
    service.consume(order);
    verifyNoInteractions(stock);
    verify(manager).commit(status);
  }

  @Test
  void lockContentionFailsConsumptionInsteadOfAcknowledgingSuccess() {
    Service service = new Service();
    RLock lock = mock(RLock.class);
    RedissonClient redis = mock(RedissonClient.class);
    when(redis.getLock(anyString())).thenReturn(lock);
    ReflectionTestUtils.setField(service, "redissonClient", redis);
    assertThrows(
        IllegalStateException.class,
        () ->
            service.consume(new ReservationOrder().setId(88L).setUserId(7L).setBookingPassId(9L)));
    verify(lock, never()).unlock();
  }

  @Test
  @SuppressWarnings("unchecked")
  void personalReadsAlwaysUseAuthenticatedOwnerAndHeldStatus() {
    Service service = new Service();
    UserDTO user = new UserDTO();
    user.setId(7L);
    UserHolder.saveUser(user);
    when(service.personal.page(any(Page.class))).thenReturn(new Page<ReservationOrder>(1, 20, 0));
    try {
      assertTrue(service.queryMyOrders(1, true).getSuccess());
      verify(service.personal).eq(any(), eq(7L));
      verify(service.personal).eq(any(), eq(2));
      assertFalse(service.queryMyOrders(0, false).getSuccess());
    } finally {
      UserHolder.removeUser();
    }
  }
}
