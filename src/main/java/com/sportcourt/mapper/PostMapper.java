package com.sportcourt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportcourt.entity.Post;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * Mapper 接口
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface PostMapper extends BaseMapper<Post> {

  Long countLike(@Param("postId") Long postId, @Param("userId") Long userId);

  List<Map<String, Object>> selectIdForUpdate(@Param("postId") Long postId);

  int insertLike(@Param("postId") Long postId, @Param("userId") Long userId);

  int incrementLikes(@Param("postId") Long postId);

  int deleteLike(@Param("postId") Long postId, @Param("userId") Long userId);

  int decrementLikes(@Param("postId") Long postId);

  List<Long> selectFirstLikeUserIds(@Param("postId") Long postId);

  List<Long> selectLikedPostIds(@Param("userId") Long userId, @Param("postIds") List<Long> postIds);

  List<Map<String, Object>> selectLikeTimestamp(
      @Param("postId") Long postId, @Param("userId") Long userId);
}
