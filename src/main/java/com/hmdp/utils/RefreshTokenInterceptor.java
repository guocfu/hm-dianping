package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    // 因为RefreshTokenInterceptor??不是由容器创建，而是由MvcConfig手动创建的，因此不能用@AutoWired自动注入变量
    // 可以由MvcConfig自动注入，在创建RefreshTokenInterceptor时使用构造函数
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从Redis中获取数据
        // 1.获取请求头中的token
        String token = request.getHeader(SystemConstants.TOKEN_HEADER);  // authorizaion单词写错了，导致一直无法获得token，一直跳转到登录界面
        // log.info("当前用户的token为：{}",token);
         if(StrUtil.isBlank(token)){  // String为空或者为""
            return true;
        }
        // 2.基于token获取redis中的用户
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        // entries会进行判断，如果查询结果为null则自动返回一个空Map
        //3.判断用户是否存在
        if(userMap.isEmpty()){
            return true;
        }
        // 5.将查询到的Hash数据转为UserDTO对象
        // 用Map填充Bean
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 6.保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);
        // 7.刷新token有效期
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 业务执行完毕销毁用户信息，防止内存泄漏
        UserHolder.removeUser();
    }
}
