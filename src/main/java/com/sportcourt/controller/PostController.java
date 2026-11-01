package com.sportcourt.controller;

import com.sportcourt.dto.Result;
import com.sportcourt.entity.Post;
import com.sportcourt.service.IPostService;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端控制器
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/post")
public class PostController {

  @Resource
  private IPostService postService;

  @PostMapping
  public Result savePost(@RequestBody Post post) {
    return postService.savePost(post);
  }

  @PutMapping("/like/{id}")
  public Result likePost(@PathVariable("id") Long id) {
    return postService.likePost(id);
  }

  @PutMapping("/like/{id}/state")
  public Result setLike(@PathVariable("id") Long id, @RequestParam boolean liked) {
    return postService.setPostLike(id, liked);
  }

  @GetMapping("/of/me")
  public Result queryMyPost(@RequestParam(value = "current", defaultValue = "1") Integer current) {
    return postService.queryMyPost(current);
  }

  @GetMapping("/hot")
  public Result queryHotPost(@RequestParam(value = "current", defaultValue = "1") Integer current) {
    return postService.queryHotPost(current);
  }

  /** SportCourt 使用的按场馆查询约球动态接口。 */
  @GetMapping("/of/venue")
  public Result queryPostByVenue(
      @RequestParam("venueId") Long venueId,
      @RequestParam(value = "current", defaultValue = "1") Integer current) {
    return postService.queryPostByVenue(venueId, current);
  }

  /** SportCourt 只读证据检索：限定场馆后按标题或正文关键词查约球动态。 */
  @GetMapping("/search")
  public Result searchPostEvidence(
      @RequestParam("venueId") Long venueId,
      @RequestParam("keyword") String keyword,
      @RequestParam(value = "current", defaultValue = "1") Integer current) {
    return postService.searchPostEvidence(venueId, keyword, current);
  }

  @GetMapping("/{id}")
  public Result queryPostById(@PathVariable("id") Long id) {
    return postService.queryPostById(id);
  }

  @GetMapping("/likes/{id}")
  public Result queryPostLikes(@PathVariable("id") Long id) {
    return postService.queryPostLikes(id);
  }

  @GetMapping("/of/user")
  public Result queryPostByUserId(
      @RequestParam(value = "current", defaultValue = "1") Integer current,
      @RequestParam("id") Long id) {
    return postService.queryPostByUserId(current, id);
  }

  @GetMapping("/of/follow")
  public Result queryPostOfFollow(
      @RequestParam("lastId") Long max,
      @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
    return postService.queryPostOfFollow(max, offset);
  }
}
