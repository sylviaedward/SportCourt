package com.sportcourt.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.sportcourt.dto.Result;
import com.sportcourt.entity.BookingPass;

/**
 * 服务类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBookingPassService extends IService<BookingPass> {

  Result queryBookingPassOfVenue(Long venueId);

  void addGoldenSlotPass(BookingPass bookingPass);

  Result capabilities();

  Result createGoldenSlotPass(BookingPass bookingPass);

  Result createBookingPass(BookingPass bookingPass);
}
