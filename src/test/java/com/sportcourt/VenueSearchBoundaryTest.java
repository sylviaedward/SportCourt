package com.sportcourt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sportcourt.dto.Result;
import com.sportcourt.dto.VenueSearchDTO;
import com.sportcourt.service.impl.VenueServiceImpl;
import org.junit.jupiter.api.Test;

class VenueSearchBoundaryTest {
  private final VenueServiceImpl service = new VenueServiceImpl();

  private VenueSearchDTO valid() {
    VenueSearchDTO criteria = new VenueSearchDTO();
    criteria.setTypeId(1L);
    criteria.setPage(1);
    criteria.setSize(10);
    return criteria;
  }

  @Test
  void rejectsInvalidSearchBeforeDatabaseAccess() {
    VenueSearchDTO criteria = valid();
    criteria.setSize(21);
    assertFalse(service.searchVenues(criteria).getSuccess());

    criteria = valid();
    criteria.setMinScore(51);
    assertFalse(service.searchVenues(criteria).getSuccess());

    criteria = valid();
    criteria.setX(120.0);
    assertFalse(service.searchVenues(criteria).getSuccess());

    criteria = valid();
    criteria.setSort("distance");
    Result result = service.searchVenues(criteria);
    assertFalse(result.getSuccess());
    assertTrue(result.getErrorMsg().contains("x 和 y"));
  }
}
