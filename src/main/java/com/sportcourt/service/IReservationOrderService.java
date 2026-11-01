package com.sportcourt.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.sportcourt.dto.Result;
import com.sportcourt.entity.ReservationOrder;

/**
 * 服务类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IReservationOrderService extends IService<ReservationOrder> {

  /** 条件扣库存与保存订单在同一事务内完成；用户锁覆盖事务提交。 */
  void persistGoldenSlot(ReservationOrder order);

  Result goldenSlotPass(Long bookingPassId);

  Result orderOrdinaryBookingPass(Long bookingPassId);

  Result queryMyOrder(Long orderId);

  Result queryMyReservationOrder(Long bookingPassId);

  Result cancelUnpaidOrdinaryOrder(Long orderId);

  Result payMockOrder(Long orderId, Long expectedPayCents);

  Result queryMyOrders(Integer page, boolean heldOnly);
}
