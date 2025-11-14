package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;


import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;//锁的key
    private StringRedisTemplate  stringRedisTemplate;

    private static final DefaultRedisScript< Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

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
        //调用lua脚本(保证原子性)
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX+Thread.currentThread().getId()
        );
    }
//    @Override
//    public void unLock() {
//        //获取线程 id
//        String value= stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        String id= ID_PREFIX+Thread.currentThread().getId();
//        if(id.equals(value)) {
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
