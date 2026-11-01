package com.sportcourt.service.impl;

import com.sportcourt.dto.Result;
import com.sportcourt.entity.BusinessOutboxTask;
import com.sportcourt.enums.ReservationState;
import com.sportcourt.mapper.BehaviorMapper;
import com.sportcourt.mapper.BusinessOutboxMapper;
import com.sportcourt.mapper.GoldenSlotReservationMapper;
import com.sportcourt.mapper.MessageMapper;
import com.sportcourt.service.IGoldenSlotService;
import com.sportcourt.service.IOperationsService;
import javax.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class OperationsServiceImpl implements IOperationsService {

  @Resource
  private BehaviorMapper behaviorMapper;

  @Resource
  private BusinessOutboxMapper businessOutboxMapper;

  @Resource
  private MessageMapper messageMapper;

  @Resource
  private GoldenSlotReservationMapper goldenSlotReservationMapper;

  @Resource
  private IGoldenSlotService coordinator;

  @Override
  public Result queryReservationsForReview() {
    return coordinator.queryReservationsForReview();
  }

  @Override
  public Result queryGoldenSlotDeadLetters() {
    return Result.ok(goldenSlotReservationMapper.selectDeadLetters());
  }

  @Override
  public Result queryUnfinishedBusinessEvents() {
    return Result.ok(businessOutboxMapper.selectUnfinishedEvents());
  }

  @Override
  public Result retryBusinessEvent(long eventId) {
    BusinessOutboxTask task = businessOutboxMapper.selectReviewEvent(eventId);
    if (task != null && "GOLDEN_SLOT_FULFILL".equals(task.getKind())) {
      long orderId = task.getAggregateId();
      if (ReservationState.REVIEW.matches(coordinator.queryReservationState(orderId))) {
        return coordinator.retryReservation(orderId);
      }
    }
    return businessOutboxMapper.retryReviewEvent(eventId) > 0
        ? Result.ok()
        : Result.fail("任务不在待核对状态");
  }

  @Override
  public Result queryMessageFailures() {
    return Result.ok(messageMapper.selectFailures());
  }

  @Override
  public Result queryUnsentAnalyticsEvents() {
    return Result.ok(behaviorMapper.selectUnsentEvents());
  }

  @Override
  public Result queryAnalyticsFailures() {
    return Result.ok(behaviorMapper.selectFailures());
  }

  @Override
  public Result retryAnalyticsEvent(String eventId) {
    return behaviorMapper.retryReviewEvent(eventId) > 0 ? Result.ok() : Result.fail("事件不在待核对状态");
  }

  @Override
  public Result queryInventoryReconciliationReport(long bookingPassId) {
    return coordinator.queryInventoryReconciliationReport(bookingPassId);
  }

  @Override
  public Result publishActivity(long bookingPassId) {
    return coordinator.publishActivity(bookingPassId);
  }

  @Override
  public Result recoverActivity(long bookingPassId) {
    return coordinator.recoverActivity(bookingPassId);
  }

  @Override
  public Result retryReservation(long orderId) {
    return coordinator.retryReservation(orderId);
  }

  @Override
  public Result markReservationFailedAndCompensateRedis(long orderId) {
    return coordinator.markReservationFailedAndCompensateRedis(orderId);
  }
}
