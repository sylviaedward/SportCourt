package com.sportcourt.service;

import com.sportcourt.dto.Result;

public interface IOperationsService {
  Result queryReservationsForReview();

  Result queryGoldenSlotDeadLetters();

  Result queryUnfinishedBusinessEvents();

  Result retryBusinessEvent(long eventId);

  Result queryMessageFailures();

  Result queryUnsentAnalyticsEvents();

  Result queryAnalyticsFailures();

  Result retryAnalyticsEvent(String eventId);

  Result queryInventoryReconciliationReport(long bookingPassId);

  Result publishActivity(long bookingPassId);

  Result recoverActivity(long bookingPassId);

  Result retryReservation(long orderId);

  Result markReservationFailedAndCompensateRedis(long orderId);
}
