package com.sportcourt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportcourt.entity.GoldenSlotPass;
import com.sportcourt.entity.GoldenSlotPublication;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 整点抢黄金时段订场券表，与订场券是一对一关系 Mapper 接口
 *
 * @author 虎哥
 * @since 2022-01-04
 */
public interface GoldenSlotPassMapper extends BaseMapper<GoldenSlotPass> {

  List<Map<String, Object>> selectPublicationStates();

  int markPublished(@Param("bookingPassId") Long bookingPassId);

  GoldenSlotPublication selectPublicationDetails(@Param("bookingPassId") Long bookingPassId);

  Integer selectPublishedFlag(@Param("bookingPassId") Long bookingPassId);

  List<Map<String, Object>> selectInventory(@Param("bookingPassId") Long bookingPassId);

  List<Long> selectActivityBookingPassIds();

  int insertActivity(
      @Param("bookingPassId") Long bookingPassId, @Param("totalStock") Integer totalStock);
}
