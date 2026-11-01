package com.sportcourt.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.sportcourt.dto.Result;
import com.sportcourt.entity.Post;

/**
 * 服务类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IPostService extends IService<Post> {

  Result queryHotPost(Integer current);

  Result queryPostById(Long id);

  Result likePost(Long id);

  Result setPostLike(Long id, boolean liked);

  Result queryPostLikes(Long id);

  Result savePost(Post post);

  Result queryPostOfFollow(Long max, Integer offset);

  Result queryMyPost(Integer current);

  Result queryPostByVenue(Long venueId, Integer current);

  Result searchPostEvidence(Long venueId, String keyword, Integer current);

  Result queryPostByUserId(Integer current, Long id);
}
