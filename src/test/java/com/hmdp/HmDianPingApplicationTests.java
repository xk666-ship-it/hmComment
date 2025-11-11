package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private  ShopServiceImpl shopService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    private ExecutorService executorService= Executors.newFixedThreadPool(500);
    @Test
    public void testSaveShop() throws InterruptedException {
        Runnable task=()->{
            for (int i = 0; i < 1000; i++) {
                long order = redisIdWorker.nextId("order");
                System.out.println("order = " + order);
            }
        };
        for (int i = 0; i < 50; i++) {
            executorService.submit(task);
        }
    }
}
