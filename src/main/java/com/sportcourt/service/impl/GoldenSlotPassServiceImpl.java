package com.sportcourt.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sportcourt.entity.GoldenSlotPass;
import com.sportcourt.mapper.GoldenSlotPassMapper;
import com.sportcourt.service.IGoldenSlotPassService;
import org.springframework.stereotype.Service;

/**
 * 整点抢黄金时段订场券表，与订场券是一对一关系 服务实现类
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Service
public class GoldenSlotPassServiceImpl extends ServiceImpl<GoldenSlotPassMapper, GoldenSlotPass>
    implements IGoldenSlotPassService {}
