package com.sportcourt.analytics;

import com.sportcourt.dto.Result;
import com.sportcourt.service.IVenueHeatService;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/venue/hot-ranking")
public class VenueHeatController {
  @Resource
  private IVenueHeatService venueHeatService;

  @GetMapping
  public Result ranking(
      @RequestParam(defaultValue = "60") int windowMinutes,
      @RequestParam(defaultValue = "10") int limit) {
    return venueHeatService.ranking(windowMinutes, limit);
  }
}
