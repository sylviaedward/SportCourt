package com.sportcourt.dto;

import lombok.Data;

@Data
public class VenueSearchDTO {
  private Long typeId;
  private String keyword;
  private String area;
  private Long maxPrice;
  /** 运动场馆数据库中的评分，4.5 分传 45。 */
  private Integer minScore;
  /** 只返回当前有可用订场券的场馆。 */
  private Boolean hasBookingPass;

  private Double x;
  private Double y;
  private Integer page = 1;
  private Integer size = 10;
  /** score、price 或 distance。 */
  private String sort = "score";
}
