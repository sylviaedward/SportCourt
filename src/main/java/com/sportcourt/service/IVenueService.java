package com.sportcourt.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.sportcourt.dto.Result;
import com.sportcourt.dto.VenueSearchDTO;
import com.sportcourt.entity.Venue;
import java.util.List;

/**
 * 服务类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVenueService extends IService<Venue> {

  Result queryById(Long id);

  Result update(Venue venue);

  Result queryVenueByType(Integer typeId, Integer current, Double x, Double y);

  Result searchVenues(VenueSearchDTO criteria);

  Result catalog(String keyword, Long typeId, Integer page, Integer size, String sort);

  Result queryVenuesByIds(List<Long> ids);

  Result listAreas();

  Result saveVenue(Venue venue);

  Result queryVenueByName(String name, Integer current);

  Result queryVenueDetail(Long id);
}
