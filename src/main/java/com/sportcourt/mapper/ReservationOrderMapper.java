package com.sportcourt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportcourt.entity.ReservationOrder;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * Mapper 接口
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ReservationOrderMapper extends BaseMapper<ReservationOrder> {

  Long countByOrderId(@Param("orderId") Long orderId);

  Long countByBookingPassId(@Param("bookingPassId") Long bookingPassId);

  List<Long> selectExpiredOrdinaryOrders();

  int initializeDeadline(
      @Param("timeoutMinutes") Integer timeoutMinutes, @Param("orderId") Long orderId);

  Map<String, Object> selectDeadline(@Param("orderId") Long orderId);

  List<Map<String, Object>> selectExpiryStateForUpdate(@Param("orderId") Long orderId);

  int closeExpiredOrder(@Param("orderId") Long orderId);

  int insertOrdinarySlot(@Param("userId") Long userId, @Param("bookingPassId") Long bookingPassId);

  List<Map<String, Object>> lockOrdinarySlot(
      @Param("userId") Long userId, @Param("bookingPassId") Long bookingPassId);

  int insertEventHistory(
      @Param("eventId") Long eventId,
      @Param("orderId") Long orderId,
      @Param("eventType") String eventType,
      @Param("occurredAt") Timestamp occurredAt);

  List<Map<String, Object>> selectBehaviorSnapshot(@Param("orderId") Long orderId);
}
