package com.sportcourt.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sportcourt.dto.Result;
import com.sportcourt.entity.SportType;
import com.sportcourt.mapper.SportTypeMapper;
import com.sportcourt.service.ISportTypeService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class SportTypeServiceImpl extends ServiceImpl<SportTypeMapper, SportType>
    implements ISportTypeService {

  @Override
  public Result queryTypeList() {
    List<SportType> typeList = query().orderByAsc("sort").list();
    return Result.ok(typeList);
  }
}
