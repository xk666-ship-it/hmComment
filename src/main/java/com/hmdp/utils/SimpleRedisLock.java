package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;


import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;//锁的key
    private StringRedisTemplate  stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final String KEY_PREFIX = "lock:";
    //防止误释放其他线程的锁，使用UUID
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程 id
        String threadId = Thread.currentThread().getId() + "";
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, ID_PREFIX+threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(b);
    }

    @Override
    public void unLock() {
        //获取线程 id
        String value= stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        String id= ID_PREFIX+Thread.currentThread().getId();
        if(id.equals(value)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
