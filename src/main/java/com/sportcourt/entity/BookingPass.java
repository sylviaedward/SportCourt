package com.sportcourt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * @author 虎哥
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_booking_pass")
public class BookingPass implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 主键 */
  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 场馆id */
  private Long venueId;

  /** 订场券标题 */
  private String title;

  /** 副标题 */
  private String subTitle;

  /** 使用规则 */
  private String rules;

  /** 支付金额 */
  private Long payValue;

  /** 抵扣金额 */
  private Long actualValue;

  /** 订场券类型 */
  private Integer type;

  /** 订场券类型 */
  private Integer status;
  /** 库存 */
  @TableField(exist = false)
  private Integer stock;

  /** 生效时间 */
  @TableField(exist = false)
  private LocalDateTime beginTime;

  /** 失效时间 */
  @TableField(exist = false)
  private LocalDateTime endTime;

  /** 是否可下单，由运动场馆服务按自身时钟计算；仅用于响应，不入库。 */
  @TableField(exist = false)
  private Boolean orderable;

  /** 创建时间 */
  private LocalDateTime createTime;

  /** 更新时间 */
  private LocalDateTime updateTime;
}
