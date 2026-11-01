package com.sportcourt.service;

import com.sportcourt.dto.Result;
import java.util.List;

/** Bounded experience observations; no cache eviction or administrative operations. */
public interface IExperienceService {
  Result queryExperienceCapabilities();

  Result traceVenueCacheReads(Long venueId);

  Result submitDemoVisitEvents(Long venueId);

  Result queryVisitEventReceipts(List<String> eventIds);

  Result queryMyOrderProgress(Long orderId);
}
