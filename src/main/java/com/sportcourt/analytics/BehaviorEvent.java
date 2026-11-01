package com.sportcourt.analytics;

import lombok.Data;

@Data
public class BehaviorEvent {
  private int schemaVersion = 1;
  private String eventId;
  private long venueId;
  private Long userId;
  private String action;
  /** 事件发生时间，Unix 毫秒；保留 occurredAt 作为 JSON 协议字段。 */
  private long occurredAt;

  public BehaviorEvent() {}

  public BehaviorEvent(
      String eventId, long venueId, Long userId, String action, long occurredAtMillis) {
    this.eventId = eventId;
    this.venueId = venueId;
    this.userId = userId;
    this.action = action;
    this.occurredAt = occurredAtMillis;
  }

  public void validate() {
    if (schemaVersion != 1
        || eventId == null
        || eventId.isEmpty()
        || eventId.length() > 64
        || venueId <= 0
        || (userId != null && userId <= 0)
        || occurredAt <= 0
        || occurredAt > System.currentTimeMillis() + 60000
        || !("VISIT".equals(action) || "ORDER".equals(action) || "PAID".equals(action))) {
      throw new IllegalArgumentException("行为事件格式无效");
    }
  }
}
