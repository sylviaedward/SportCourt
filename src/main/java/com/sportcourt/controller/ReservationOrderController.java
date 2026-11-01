package com.sportcourt.controller;

import com.sportcourt.dto.Result;
import com.sportcourt.service.IGoldenSlotService;
import com.sportcourt.service.IReservationOrderService;
import com.sportcourt.utils.UserHolder;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端控制器
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/bookingPass-order")
public class ReservationOrderController {

  @Resource
  private IReservationOrderService reservationOrderService;

  @Resource
  private IGoldenSlotService coordinator;

  @GetMapping("requests/{id}")
  public Result queryReservationStatus(@PathVariable("id") Long orderId) {
    return coordinator.queryReservationStatus(orderId, UserHolder.getUser().getId());
  }

  @GetMapping("mine")
  public Result myOrders(@RequestParam(defaultValue = "1") Integer page) {
    return reservationOrderService.queryMyOrders(page, false);
  }

  @GetMapping("mine/bookingPasses")
  public Result myBookingPasses(@RequestParam(defaultValue = "1") Integer page) {
    return reservationOrderService.queryMyOrders(page, true);
  }

  @PostMapping("goldenSlot/{id}")
  public Result goldenSlotPass(@PathVariable("id") Long bookingPassId) {
    return reservationOrderService.goldenSlotPass(bookingPassId);
  }

  @PostMapping("ordinary/{id}")
  public Result orderOrdinaryBookingPass(@PathVariable("id") Long bookingPassId) {
    return reservationOrderService.orderOrdinaryBookingPass(bookingPassId);
  }

  @PostMapping("{id}/pay/mock")
  public Result payMockOrder(
      @PathVariable("id") Long orderId, @RequestParam Long expectedPayCents) {
    return reservationOrderService.payMockOrder(orderId, expectedPayCents);
  }

  @GetMapping("{id}")
  public Result queryMyOrder(@PathVariable("id") Long orderId) {
    return reservationOrderService.queryMyOrder(orderId);
  }

  @GetMapping("of/bookingPass/{id}")
  public Result queryMyReservationOrder(@PathVariable("id") Long bookingPassId) {
    return reservationOrderService.queryMyReservationOrder(bookingPassId);
  }

  @PostMapping("{id}/cancel")
  public Result cancelUnpaidOrdinaryOrder(@PathVariable("id") Long orderId) {
    return reservationOrderService.cancelUnpaidOrdinaryOrder(orderId);
  }
}
