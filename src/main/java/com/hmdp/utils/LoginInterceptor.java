package com.hmdp.utils;

import com.hmdp.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断是否需要拦截（Thread中是否有用户）
        // log.info("判断是否需要拦截（Thread）中是否有用户");
        if(UserHolder.getUser() == null){
            response.setStatus(401);
            // 拦截
            return false;
        }
        new HashSet<String>();
        return true;
    }
}
