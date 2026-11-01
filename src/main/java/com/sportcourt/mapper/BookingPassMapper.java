package com.sportcourt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportcourt.entity.BookingPass;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Mapper 接口
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface BookingPassMapper extends BaseMapper<BookingPass> {

  List<BookingPass> queryBookingPassOfVenue(@Param("venueId") Long venueId);

  List<Long> selectActiveBookingPassIds(@Param("venueId") Long venueId);
}
