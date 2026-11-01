package com.sportcourt.controller;

import com.sportcourt.dto.Result;
import com.sportcourt.dto.VenueSearchDTO;
import com.sportcourt.entity.Venue;
import com.sportcourt.service.IVenueService;
import java.util.List;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端控制器
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/venue")
public class VenueController {

  @Resource
  private IVenueService venueService;
  /**
   * 根据id查询场馆信息
   *
   * @param id 场馆id
   * @return 场馆详情数据
   */
  @GetMapping("/{id}")
  public Result queryVenueById(@PathVariable("id") Long id) {
    return venueService.queryVenueDetail(id);
  }

  /** 供 SportCourt 使用的只读组合搜索，评分参数按数据库的 10 倍整数传入。 */
  @GetMapping("/search")
  public Result searchVenues(VenueSearchDTO criteria) {
    return venueService.searchVenues(criteria);
  }

  /** 统一前端的跨分类场馆列表；Agent 的严格搜索接口保持原有条件。 */
  @GetMapping("/catalog")
  public Result catalog(
      @RequestParam(value = "keyword", defaultValue = "") String keyword,
      @RequestParam(value = "typeId", required = false) Long typeId,
      @RequestParam(value = "page", defaultValue = "1") Integer page,
      @RequestParam(value = "size", defaultValue = "12") Integer size,
      @RequestParam(value = "sort", defaultValue = "score") String sort) {
    return venueService.catalog(keyword, typeId, page, size, sort);
  }

  /** SportCourt 在组合两站前批量复核候选，直接读取当前场馆数据。 */
  @GetMapping("/batch")
  public Result queryVenuesByIds(@RequestParam("ids") List<Long> ids) {
    return venueService.queryVenuesByIds(ids);
  }

  /** 供 SportCourt 在用户原话中匹配真实商圈，避免猜测地名。 */
  @GetMapping("/areas")
  public Result listAreas() {
    return venueService.listAreas();
  }

  /**
   * 新增场馆信息
   *
   * @param venue 场馆数据
   * @return 场馆id
   */
  @PostMapping
  public Result saveVenue(@RequestBody Venue venue) {
    return venueService.saveVenue(venue);
  }

  /**
   * 更新场馆信息
   *
   * @param venue 场馆数据
   * @return 无
   */
  @PutMapping
  public Result updateVenue(@RequestBody Venue venue) {
    // 写入数据库
    return venueService.update(venue);
  }

  /**
   * 根据场馆类型分页查询场馆信息
   *
   * @param typeId 场馆类型
   * @param current 页码
   * @return 场馆列表
   */
  @GetMapping("/of/type")
  public Result queryVenueByType(
      @RequestParam("typeId") Integer typeId,
      @RequestParam(value = "current", defaultValue = "1") Integer current,
      @RequestParam(value = "x", required = false) Double x,
      @RequestParam(value = "y", required = false) Double y) {
    return venueService.queryVenueByType(typeId, current, x, y);
  }

  /**
   * 根据场馆名称关键字分页查询场馆信息
   *
   * @param name 场馆名称关键字
   * @param current 页码
   * @return 场馆列表
   */
  @GetMapping("/of/name")
  public Result queryVenueByName(
      @RequestParam(value = "name", required = false) String name,
      @RequestParam(value = "current", defaultValue = "1") Integer current) {
    return venueService.queryVenueByName(name, current);
  }
}
