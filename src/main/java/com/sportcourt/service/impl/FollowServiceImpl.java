package com.sportcourt.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sportcourt.dto.Result;
import com.sportcourt.dto.UserDTO;
import com.sportcourt.entity.Follow;
import com.sportcourt.mapper.FollowMapper;
import com.sportcourt.service.IBusinessOutboxService;
import com.sportcourt.service.IFollowService;
import com.sportcourt.service.IUserService;
import com.sportcourt.utils.UserHolder;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

  @Resource
  private IUserService userService;

  @Resource
  private TransactionTemplate transactions;

  @Resource
  private IBusinessOutboxService outbox;

  @Override
  public Result follow(Long target, Boolean enabled) {
    long user = UserHolder.getUser().getId();
    if (target == null || target == user || userService.getById(target) == null) {
      return Result.fail("关注对象无效");
    }
    return transactions.execute(
        tx -> {
          int changed =
              Boolean.TRUE.equals(enabled)
                  ? baseMapper.insertEdge(user, target)
                  : baseMapper.deleteEdge(user, target);
          if (changed > 0) {
            outbox.appendBusinessEvent("FOLLOW", target, user);
          }
          return Result.ok();
        });
  }

  @Override
  public Result isFollow(Long target) {
    return Result.ok(baseMapper.countEdge(UserHolder.getUser().getId(), target) > 0);
  }

  @Override
  public Result followCommons(Long other) {
    List<Long> ids = baseMapper.selectCommonFollowIds(UserHolder.getUser().getId(), other);
    if (ids.isEmpty()) {
      return Result.ok(Collections.emptyList());
    }
    return Result.ok(
        userService.listByIds(ids).stream()
            .map(u -> BeanUtil.copyProperties(u, UserDTO.class))
            .collect(Collectors.toList()));
  }
}
