package com.sportcourt.mapper;

import com.sportcourt.entity.GoldenSlotReservation;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 数据访问接口，SQL 统一维护在对应的 Mapper XML 中。 */
public interface GoldenSlotReservationMapper {

  List<Map<String, Object>> selectDeadLetters();

  Long countByBookingPassId(@Param("bookingPassId") Long bookingPassId);

  Long countReviewRequests();

  int recordAttempt(@Param("reason") String reason, @Param("orderId") Long orderId);

  GoldenSlotReservation selectAttemptState(@Param("orderId") Long orderId);

  GoldenSlotReservation selectStateForUpdate(@Param("orderId") Long orderId);

  int markProcessingForReview(@Param("reason") String reason, @Param("orderId") Long orderId);

  int insertDeadLetter(
      @Param("messageId") String messageId,
      @Param("orderId") Long orderId,
      @Param("payload") String payload,
      @Param("reason") String reason);

  GoldenSlotReservation selectFulfillment(@Param("orderId") Long orderId);

  int insertReservation(
      @Param("orderId") Long orderId,
      @Param("bookingPassId") Long bookingPassId,
      @Param("userId") Long userId);

  GoldenSlotReservation selectReservation(@Param("orderId") Long orderId);

  int markSuccess(@Param("orderId") Long orderId);

  String selectState(@Param("orderId") Long orderId);

  List<Map<String, Object>> selectOwnedRequest(
      @Param("orderId") Long orderId, @Param("userId") Long userId);

  Long countUnfinishedRequests(@Param("bookingPassId") Long bookingPassId);

  List<Map<String, Object>> selectSuccessfulBuyers(@Param("bookingPassId") Long bookingPassId);

  GoldenSlotReservation selectReservationForUpdate(@Param("orderId") Long orderId);

  int resetForRetry(@Param("orderId") Long orderId);

  int markFailed(@Param("orderId") Long orderId);

  List<Map<String, Object>> selectReviewRequests();
}
