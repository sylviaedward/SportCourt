package com.sportcourt.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sportcourt.dto.Result;
import com.sportcourt.dto.ScrollResult;
import com.sportcourt.dto.UserDTO;
import com.sportcourt.entity.Post;
import com.sportcourt.entity.User;
import com.sportcourt.mapper.PostMapper;
import com.sportcourt.mapper.VenueMapper;
import com.sportcourt.service.IBusinessOutboxService;
import com.sportcourt.service.IFollowService;
import com.sportcourt.service.IMediaService;
import com.sportcourt.service.IPostService;
import com.sportcourt.service.IUserService;
import com.sportcourt.utils.RedisConstants;
import com.sportcourt.utils.SystemConstants;
import com.sportcourt.utils.UserHolder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class PostServiceImpl extends ServiceImpl<PostMapper, Post> implements IPostService {

  @Resource
  private VenueMapper venueMapper;

  @Resource
  private IUserService userService;

  @Resource
  private StringRedisTemplate stringRedisTemplate;

  @Resource
  private IFollowService followService;

  @Resource
  private TransactionTemplate transactions;

  @Resource
  private IBusinessOutboxService outbox;

  @Resource
  private IMediaService media;

  @Override
  public Result queryHotPost(Integer current) {
    // 根据用户查询
    Page<Post> page =
        query()
            .orderByDesc("liked")
            .orderByDesc("id")
            .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
    // 获取当前页数据
    List<Post> records = page.getRecords();
    enrich(records);
    return Result.ok(records);
  }

  @Override
  public Result queryPostById(Long id) {
    // 1.查询post
    Post post = getById(id);
    if (post == null) {
      return Result.fail("约球动态不存在！");
    }
    // 2.查询post有关的用户
    queryPostUser(post);
    // 3.查询post是否被点赞
    isPostLiked(post);
    return Result.ok(post);
  }

  private void isPostLiked(Post post) {
    // 1.获取登录用户
    UserDTO user = UserHolder.getUser();
    if (user == null) {
      // 用户未登录，无需查询是否点赞
      return;
    }
    post.setIsLike(baseMapper.countLike(post.getId(), user.getId()) > 0);
  }

  @Override
  public Result likePost(Long id) {
    return updateLike(id, null);
  }

  @Override
  public Result setPostLike(Long id, boolean liked) {
    return updateLike(id, liked);
  }

  private Result updateLike(Long id, Boolean requested) {
    long user = UserHolder.getUser().getId();
    return transactions.execute(
        tx -> {
          if (baseMapper.selectIdForUpdate(id).isEmpty()) {
            return Result.fail("约球动态不存在");
          }
          boolean current = baseMapper.countLike(id, user) > 0;
          boolean desired = requested == null ? !current : requested;
          if (current == desired) {
            return Result.ok();
          }
          if (desired) {
            baseMapper.insertLike(id, user);
            baseMapper.incrementLikes(id);
          } else {
            baseMapper.deleteLike(id, user);
            baseMapper.decrementLikes(id);
          }
          outbox.appendBusinessEvent("LIKE", id, user);
          return Result.ok();
        });
  }

  @Override
  public Result queryPostLikes(Long id) {
    List<Long> ids = baseMapper.selectFirstLikeUserIds(id);
    if (ids.isEmpty()) {
      return Result.ok(Collections.emptyList());
    }
    String idStr = StrUtil.join(",", ids);
    // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
    List<UserDTO> userDTOS =
        userService.query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list().stream()
            .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
            .collect(Collectors.toList());
    // 4.返回
    return Result.ok(userDTOS);
  }

  @Override
  @Transactional
  public Result savePost(Post post) {
    // 1.获取登录用户
    UserDTO user = UserHolder.getUser();
    post.setUserId(user.getId());
    if (post.getImages() == null) {
      post.setImages("");
    }
    if (post.getImages().length() > 2048
        || (!post.getImages().isEmpty() && post.getImages().split(",").length > 9)) {
      return Result.fail("图片数量或路径长度无效");
    }
    if (!media.lockOwnedImages(post.getImages(), user.getId())) {
      return Result.fail("图片不存在、不属于当前账号或正在删除");
    }
    post.setId(null);
    post.setLiked(0);
    post.setComments(0);
    post.setCreateTime(null);
    post.setUpdateTime(null);
    if (post.getTitle() == null
        || post.getTitle().trim().isEmpty()
        || post.getTitle().length() > 255
        || post.getContent() == null
        || post.getContent().length() > 10000
        || post.getVenueId() == null
        || venueMapper.countByVenueId(post.getVenueId()) == 0) {
      return Result.fail("约球动态内容或关联场馆无效");
    }
    // 2.保存约球动态
    boolean isSuccess = save(post);
    if (!isSuccess) {
      return Result.fail("新增约球动态失败!");
    }
    media.attach(post.getId(), post.getImages());
    outbox.appendBusinessEvent("FEED", post.getId(), user.getId());
    return Result.ok(post.getId());
  }

  @Override
  public Result queryPostOfFollow(Long max, Integer offset) {
    // 1.获取当前用户
    Long userId = UserHolder.getUser().getId();
    // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
    String key = RedisConstants.FEED_KEY + userId;
    Set<ZSetOperations.TypedTuple<String>> typedTuples =
        stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
    // 3.非空判断
    if (typedTuples == null || typedTuples.isEmpty()) {
      return Result.ok();
    }
    // 4.解析数据：postId、minTime（时间戳）、offset
    List<Long> ids = new ArrayList<>(typedTuples.size());
    long minTime = 0; // 2
    int os = 1; // 2
    for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
      // 4.1.获取id
      ids.add(Long.valueOf(tuple.getValue()));
      // 4.2.获取分数(时间戳）
      long time = tuple.getScore().longValue();
      if (time == minTime) {
        os++;
      } else {
        minTime = time;
        os = 1;
      }
    }

    // 5.根据id查询post
    String idStr = StrUtil.join(",", ids);
    List<Post> posts = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

    enrich(posts);
    // 6.封装并返回
    ScrollResult r = new ScrollResult();
    r.setList(posts);
    r.setOffset(minTime == max ? offset + os : os);
    r.setMinTime(minTime);

    return Result.ok(r);
  }

  private void enrich(List<Post> posts) {
    if (posts.isEmpty()) {
      return;
    }
    List<Long> authorIds =
        posts.stream().map(Post::getUserId).distinct().collect(Collectors.toList());
    Map<Long, User> authors =
        userService.listByIds(authorIds).stream().collect(Collectors.toMap(User::getId, u -> u));
    Set<Long> likedIds = new HashSet<>();
    if (UserHolder.getUser() != null) {
      likedIds.addAll(
          baseMapper.selectLikedPostIds(
              UserHolder.getUser().getId(),
              posts.stream().map(Post::getId).collect(Collectors.toList())));
    }
    for (Post post : posts) {
      User author = authors.get(post.getUserId());
      if (author != null) {
        post.setName(author.getNickName());
        post.setIcon(author.getIcon());
      }
      post.setIsLike(likedIds.contains(post.getId()));
    }
  }

  private void queryPostUser(Post post) {
    User user = userService.getById(post.getUserId());
    if (user != null) {
      post.setName(user.getNickName());
      post.setIcon(user.getIcon());
    }
  }

  @Override
  public Result queryMyPost(Integer current) {
    // 获取登录用户
    UserDTO user = UserHolder.getUser();
    // 根据用户查询
    Page<Post> page =
        query()
            .eq("user_id", user.getId())
            .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
    // 获取当前页数据
    List<Post> records = page.getRecords();
    return Result.ok(records);
  }

  @Override
  public Result queryPostByVenue(Long venueId, Integer current) {
    if (venueId == null || venueId <= 0 || current < 1 || current > 100000) {
      return Result.fail("venueId 和 current 必须是正整数");
    }
    Page<Post> page =
        query().eq("venue_id", venueId).orderByDesc("create_time").page(new Page<>(current, 5));
    return Result.ok(page.getRecords(), page.getTotal());
  }

  @Override
  public Result searchPostEvidence(Long venueId, String keyword, Integer current) {
    if (venueId == null
        || venueId <= 0
        || current == null
        || current < 1
        || current > 100000
        || keyword == null
        || keyword.trim().length() < 2
        || keyword.trim().length() > 40) {
      return Result.fail("venueId、keyword 或 current 不合法");
    }
    String query = keyword.trim();
    Page<Post> page =
        page(
            new Page<>(current, 5),
            new QueryWrapper<Post>()
                .eq("venue_id", venueId)
                .and(w -> w.like("title", query).or().like("content", query))
                .orderByDesc("create_time")
                .orderByDesc("id"));
    return Result.ok(page.getRecords(), page.getTotal());
  }

  @Override
  public Result queryPostByUserId(Integer current, Long id) {
    // 根据用户查询
    Page<Post> page =
        query().eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
    // 获取当前页数据
    List<Post> records = page.getRecords();
    return Result.ok(records);
  }
}
