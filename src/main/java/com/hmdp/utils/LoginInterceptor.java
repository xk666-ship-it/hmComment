package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {
    //构造器注入，因为在config里面是直接new的这个拦截器，不是有spring创建，所以不能用@Autowired
    private StringRedisTemplate stringRedisTemplate;
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取请求头的token
        String token = request.getHeader("authorization");

        //刷新有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+ token, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        //根据token获取Redis中的用户
        Map<Object,Object> map = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY+ token);
        if(map.isEmpty()){
            response.setStatus(401);
            return false;
        }
        UserDTO user =BeanUtil.fillBeanWithMap(map,new UserDTO(),false);
        UserHolder.saveUser(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
