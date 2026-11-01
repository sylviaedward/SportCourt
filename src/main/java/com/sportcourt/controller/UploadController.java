package com.sportcourt.controller;

import com.sportcourt.dto.Result;
import com.sportcourt.service.IMediaService;
import com.sportcourt.utils.UserHolder;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("upload")
public class UploadController {
  @Resource
  private IMediaService mediaService;

  @PostMapping("post")
  public Result uploadImage(@RequestParam("file") MultipartFile image) {
    return mediaService.upload(image, UserHolder.getUser().getId());
  }

  @GetMapping("/post/delete")
  public Result deletePostImgLegacy(@RequestParam("name") String filename) {
    return mediaService.delete(filename, UserHolder.getUser().getId());
  }

  @DeleteMapping("/post")
  public Result deletePostImg(@RequestParam("name") String filename) {
    return mediaService.delete(filename, UserHolder.getUser().getId());
  }
}
