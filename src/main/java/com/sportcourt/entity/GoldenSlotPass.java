package com.sportcourt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 整点抢黄金时段订场券表，与订场券是一对一关系
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_golden_slot_booking_pass")
public class GoldenSlotPass implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 关联的订场券的id */
  @TableId(value = "booking_pass_id", type = IdType.INPUT)
  private Long bookingPassId;

  /** 库存 */
  private Integer stock;

  /** 创建时间 */
  private LocalDateTime createTime;

  /** 生效时间 */
  private LocalDateTime beginTime;

  /** 失效时间 */
  private LocalDateTime endTime;

  /** 更新时间 */
  private LocalDateTime updateTime;
}
