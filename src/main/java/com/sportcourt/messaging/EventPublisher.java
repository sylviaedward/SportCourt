package com.sportcourt.messaging;

public interface EventPublisher {
  void publish(BusinessEvent event);
}
