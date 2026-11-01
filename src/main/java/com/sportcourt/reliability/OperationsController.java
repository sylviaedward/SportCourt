package com.sportcourt.reliability;

import com.sportcourt.dto.Result;
import com.sportcourt.service.IOperationsService;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ops")
public class OperationsController {
  @Resource
  private IOperationsService operationsService;

  @GetMapping("/goldenSlot/tasks")
  public Result queryReservationsForReview() {
    return operationsService.queryReservationsForReview();
  }

  @GetMapping("/goldenSlot/dead-letters")
  public Result queryGoldenSlotDeadLetters() {
    return operationsService.queryGoldenSlotDeadLetters();
  }

  @GetMapping("/outbox")
  public Result queryUnfinishedBusinessEvents() {
    return operationsService.queryUnfinishedBusinessEvents();
  }

  @PostMapping("/outbox/{id}/retry")
  public Result retryBusinessEvent(@PathVariable("id") long eventId) {
    return operationsService.retryBusinessEvent(eventId);
  }

  @GetMapping("/messages/failures")
  public Result queryMessageFailures() {
    return operationsService.queryMessageFailures();
  }

  @GetMapping("/analytics/outbox")
  public Result queryUnsentAnalyticsEvents() {
    return operationsService.queryUnsentAnalyticsEvents();
  }

  @GetMapping("/analytics/failures")
  public Result queryAnalyticsFailures() {
    return operationsService.queryAnalyticsFailures();
  }

  @PostMapping("/analytics/outbox/{eventId}/retry")
  public Result retryAnalyticsEvent(@PathVariable String eventId) {
    return operationsService.retryAnalyticsEvent(eventId);
  }

  @GetMapping("/goldenSlot/{id}/reconcile")
  public Result queryInventoryReconciliationReport(@PathVariable("id") long bookingPassId) {
    return operationsService.queryInventoryReconciliationReport(bookingPassId);
  }

  @PostMapping("/goldenSlot/{id}/publish")
  public Result publishActivity(@PathVariable("id") long bookingPassId) {
    return operationsService.publishActivity(bookingPassId);
  }

  @PostMapping("/goldenSlot/{id}/recover")
  public Result recoverActivity(@PathVariable("id") long bookingPassId) {
    return operationsService.recoverActivity(bookingPassId);
  }

  @PostMapping("/goldenSlot/requests/{id}/retry")
  public Result retryReservation(@PathVariable("id") long orderId) {
    return operationsService.retryReservation(orderId);
  }

  @PostMapping("/goldenSlot/requests/{id}/fail")
  public Result markReservationFailedAndCompensateRedis(@PathVariable("id") long orderId) {
    return operationsService.markReservationFailedAndCompensateRedis(orderId);
  }
}
