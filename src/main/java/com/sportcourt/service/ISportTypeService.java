package com.sportcourt.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.sportcourt.dto.Result;
import com.sportcourt.entity.SportType;

/**
 * 服务类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ISportTypeService extends IService<SportType> {

  Result queryTypeList();
}
