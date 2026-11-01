package com.sportcourt.entity;

import lombok.Data;

/** 整点抢黄金时段持久资格；订单、用户与券的身份必须在重放时保持一致。 */
@Data
public class GoldenSlotReservation {
  private Long orderId;
  private Long bookingPassId;
  private Long userId;
  private String state;
  private int attempts;
  private String reason;
}
