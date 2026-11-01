package com.sportcourt.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sportcourt.entity.PostComments;
import com.sportcourt.mapper.PostCommentsMapper;
import com.sportcourt.service.IPostCommentsService;
import org.springframework.stereotype.Service;

/**
 * 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class PostCommentsServiceImpl extends ServiceImpl<PostCommentsMapper, PostComments>
    implements IPostCommentsService {}
