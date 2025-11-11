package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //解决缓存穿透
    public <ID, T> T queryWithPassThrough(
            String keyPrefix, ID id, Class<T> clazz, Function<ID, T> dbFallback
            , Long time, TimeUnit unit) throws JsonProcessingException {

        String key = keyPrefix + id;

        //先在Redis查询
        String s = stringRedisTemplate.opsForValue().get(key);
        //存在，返回
        if (StrUtil.isNotBlank(s)) {
            return JSONUtil.toBean(s, clazz);
        }
        if (s != null) {//S==""
            return null;
        }
        //不存在，MysQl查询
        T byId = dbFallback.apply(id);
        if (byId == null) {
            //不存在将null缓存至Redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在，写入Redis并返回
        this.set(key, byId, time, unit);
        return byId;
    }
    public <ID, T> T queryWithMutexLock(
            String keyPrefix, ID id, Class<T> clazz, Function<ID, T> dbFallback
            , Long time, TimeUnit unit) throws JsonProcessingException {
                T byId = null;
        try {
            //先在Redis查询
            String s = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            //存在，返回
            if(StrUtil.isNotBlank( s)){
                return JSONUtil.toBean(s, clazz);
            }

            //设置互斥锁
            Boolean lock =stringRedisTemplate.opsForValue().setIfAbsent(RedisConstants.LOCK_SHOP_KEY+id, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
            if (BooleanUtil.isFalse(lock)){
                Thread.sleep(50);
                return queryWithMutexLock(keyPrefix, id, clazz, dbFallback, time, unit);
            }
            //不存在，MysQl查询
            byId = dbFallback.apply(id);
            if(byId == null)return null;
            //存在，写入Redis并返回
           this.set(keyPrefix,  byId, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            stringRedisTemplate.delete(RedisConstants.LOCK_SHOP_KEY+id);
        }
        return byId;
    }
}
