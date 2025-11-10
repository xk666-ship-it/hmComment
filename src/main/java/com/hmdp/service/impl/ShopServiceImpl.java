package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) throws JsonProcessingException {
        //先在Redis查询
        String s = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //存在，返回
        if(StrUtil.isNotBlank( s)){
            Shop shop = JSONUtil.toBean(s, Shop.class);
            return Result.ok(shop);
        }
        if(s!= null){//S==""
            return Result.fail("店铺不存在");
        }
        //不存在，MysQl查询
        Shop byId = getById(id);
        if (byId == null){
            //不存在将null缓存至Redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }

        //存在，写入Redis并返回
        String json = JSONUtil.toJsonStr(byId);
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,json,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(byId);
    }
}
