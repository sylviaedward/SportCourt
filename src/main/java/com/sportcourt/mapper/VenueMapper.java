package com.sportcourt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportcourt.entity.Venue;
import org.apache.ibatis.annotations.Param;

/**
 * Mapper 接口
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VenueMapper extends BaseMapper<Venue> {

  Long countByVenueId(@Param("venueId") Long venueId);
}
