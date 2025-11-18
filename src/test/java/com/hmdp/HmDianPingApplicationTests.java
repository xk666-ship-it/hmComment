package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private  ShopServiceImpl shopService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private ExecutorService executorService= Executors.newFixedThreadPool(500);
    @Test
    public void testSaveShop()  {
        List<Shop> list = shopService.list();
        Map<Long, List<Shop>> map=list.stream().
                collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            List<Shop> value = entry.getValue();
            for(Shop shop : value){
                stringRedisTemplate.opsForGeo().add(key,
                        new Point(shop.getX(), shop.getY()), shop.getId().toString());
            }
        }
    }
}
