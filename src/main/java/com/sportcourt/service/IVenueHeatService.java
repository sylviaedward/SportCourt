package com.sportcourt.service;

import com.sportcourt.dto.Result;

public interface IVenueHeatService {
  void refresh();

  Result ranking(int windowMinutes, int limit);
}
