package com.sportcourt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.sportcourt.dto.UserDTO;
import com.sportcourt.entity.BookingPass;
import com.sportcourt.entity.ReservationOrder;
import com.sportcourt.service.IBookingPassService;
import com.sportcourt.service.impl.OrderLifecycleServiceImpl;
import com.sportcourt.service.impl.ReservationOrderServiceImpl;
import com.sportcourt.utils.UserHolder;
import java.io.Serializable;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class MockPaymentTest {
  static class Service extends ReservationOrderServiceImpl {
    ReservationOrder order =
        new ReservationOrder().setId(88L).setUserId(7L).setBookingPassId(2001L).setStatus(1);
    UpdateChainWrapper<ReservationOrder> chain = mock(UpdateChainWrapper.class, RETURNS_SELF);

    @Override
    public ReservationOrder getById(Serializable id) {
      return order;
    }

    @Override
    public UpdateChainWrapper<ReservationOrder> update() {
      return chain;
    }
  }

  @Test
  void validatesOwnerAmountStateAndIdempotence() {
    Service s = new Service();
    UserDTO user = new UserDTO();
    user.setId(7L);
    UserHolder.saveUser(user);
    IBookingPassService bookingPasses = mock(IBookingPassService.class);
    when(bookingPasses.getById(2001L))
        .thenReturn(new BookingPass().setId(2001L).setPayValue(8900L));
    ReflectionTestUtils.setField(s, "bookingPassService", bookingPasses);
    TransactionTemplate tx = mock(TransactionTemplate.class);
    when(tx.execute(any()))
        .thenAnswer(
            call ->
                ((TransactionCallback<?>) call.getArgument(0))
                    .doInTransaction(mock(TransactionStatus.class)));
    ReflectionTestUtils.setField(s, "transactionTemplate", tx);
    ReflectionTestUtils.setField(s, "lifecycle", mock(OrderLifecycleServiceImpl.class));
    try {
      assertFalse(s.payMockOrder(88L, 8900L).getSuccess());
      ReflectionTestUtils.setField(s, "mockPaymentEnabled", true);
      assertFalse(s.payMockOrder(88L, 1L).getSuccess());
      s.order.setUserId(8L);
      assertFalse(s.payMockOrder(88L, 8900L).getSuccess());
      s.order.setUserId(7L);
      s.order.setStatus(4);
      assertFalse(s.payMockOrder(88L, 8900L).getSuccess());
      s.order.setStatus(1);
      when(s.chain.update()).thenReturn(true);
      assertTrue(s.payMockOrder(88L, 8900L).getSuccess());
      verify(s.chain).eq("user_id", 7L);
      verify(s.chain).eq("status", 1);
      clearInvocations(s.chain);
      s.order.setStatus(2);
      assertTrue(s.payMockOrder(88L, 8900L).getSuccess());
      verifyNoInteractions(s.chain);
    } finally {
      UserHolder.removeUser();
    }
  }
}
