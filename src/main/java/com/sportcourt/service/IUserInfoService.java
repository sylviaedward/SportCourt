package com.sportcourt.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.sportcourt.dto.Result;
import com.sportcourt.entity.UserInfo;

/**
 * 服务类
 *
 * @author 虎哥
 * @since 2021-12-24
 */
public interface IUserInfoService extends IService<UserInfo> {

  Result queryUserInfo(Long userId);
}
