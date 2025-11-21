package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;
    
    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("blog不存在");
        }
        // 2.查询blog相关用户
        queryBlogUser(blog);
        // 3.查询blog是否被当前用户点赞了
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            // 如果当前无用户登录，就不需要判断是否点赞
            return;
        }
        Long userId = user.getId();
        // 2.判断当前用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 实现用户点赞功能
     * @param id
     */
    @Override
    public void likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 3.如果未点赞，可以点赞
        if(score == null){
            // 3.1数据库点赞量+1
            boolean success = update().setSql("liked = liked+1").eq("id", id).update();
            if(success){
                // 3.2保存用户到Redis集合
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else{
            // 4.如果已经点赞，取消点赞
            // 4.1数据库点赞量-1
            boolean success = update().setSql("liked = liked-1").eq("id", id).update();
            if(success){
                // 4.2把用户从Redis集合中删除
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5点赞用户(最先点赞的五位用户)，升序查询 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());  // 避免空指针
        }
        // 2.解析用户id
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 3.根据用户id查询用户 where id in (5,1) order by field (id, 5,1)
        String idStr = StrUtil.join(",", userIds);
        List<UserDTO> userDTOS = userService.query().in("id", userIds).last("order by field (id, "+idStr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = blogService.save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败");
        }
        // 如果笔记发布成功，将笔记推送给关注登录用户的人
        // 查询所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        follows.forEach(follow -> {
            String key = "feed:" + follow.getUserId();  // 某用户的接收推文inbox
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        });
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱  zreverangebyscore key Max Min whithscores limit 2 3
        String key = "feed:" + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(key, 0, max, offset, 2); // 每页2个blog
        // 非空判断
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        // 3.解析数据：blogId, score(时间戳), offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 最小时间戳
        int offsetCount = 1; // 最小时间戳博客的数量
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long blogTime = tuple.getScore().longValue(); // 该博客的时间戳
            if(blogTime == minTime){
                offsetCount++;
            }else{
                minTime = blogTime;
                offsetCount = 1;
            }
        }
        // 4.根据id查询blog，返回id是有序的，使用listById()查询是无序的
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("order by field (id, " + idStr + ")").list();

        for (Blog blog : blogs) {
            // 4.1查询blog相关用户
            queryBlogUser(blog);
            // 4.2查询blog是否被当前用户点赞了
            isBlogLiked(blog);
        }
        // blogs.forEach(blog -> {
        //     // 4.1查询blog相关用户
        //     queryBlogUser(blog);
        //     // 4.2查询blog是否被当前用户点赞了
        //     isBlogLiked(blog);
        // });
        // 5.封装并返回
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setOffset(offsetCount);
        result.setMinTime(minTime);
        return Result.ok(result);
    }

}
