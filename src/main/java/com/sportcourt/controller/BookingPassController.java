package com.sportcourt.controller;

import com.sportcourt.dto.Result;
import com.sportcourt.entity.BookingPass;
import com.sportcourt.service.IBookingPassService;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端控制器
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/bookingPass")
public class BookingPassController {

  @Resource
  private IBookingPassService bookingPassService;

  @GetMapping("/manage/capabilities")
  public Result capabilities() {
    return bookingPassService.capabilities();
  }

  /**
   * 新增整点抢黄金时段券
   *
   * @param bookingPass 订场券信息，包含整点抢黄金时段信息
   * @return 订场券id
   */
  @PostMapping("goldenSlot")
  public Result addGoldenSlotPass(@RequestBody BookingPass bookingPass) {
    return bookingPassService.createGoldenSlotPass(bookingPass);
  }

  /**
   * 新增普通券
   *
   * @param bookingPass 订场券信息
   * @return 订场券id
   */
  @PostMapping
  public Result addBookingPass(@RequestBody BookingPass bookingPass) {
    return bookingPassService.createBookingPass(bookingPass);
  }

  /**
   * 查询场馆的订场券列表
   *
   * @param venueId 场馆id
   * @return 订场券列表
   */
  @GetMapping("/list/{venueId}")
  public Result queryBookingPassOfVenue(@PathVariable("venueId") Long venueId) {
    return bookingPassService.queryBookingPassOfVenue(venueId);
  }
}
