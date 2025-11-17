package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final ExecutorService seckill_order_executor = Executors.newSingleThreadExecutor();

    //@Transactional生效的前提，必须是spring创建的代理对象调用才行
    private IVoucherOrderService proxy;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    public void init() {
        seckill_order_executor.submit(new VoucherOrderTask());
    }

    private class VoucherOrderTask implements Runnable {

        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 获取消息队列中的消息
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断信息是否获取成功
                    if (read == null || read.isEmpty()) {
                        continue;
                    }
                    //解析消息
                    MapRecord<String, Object, Object> record = read.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = new VoucherOrder();
                    BeanUtil.fillBeanWithMap(values, voucherOrder, true);
                    handleVoucherOrder(voucherOrder);

                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                    //消费过程已成，造成未确认
                } catch (Exception e) {
                    //已经消费未被确认（pending-list）
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }


        private void handlePendingList() {
            while (true) {
                try {
                    // 获取pending-list中的订单信息
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断信息是否获取成功
                    if (read == null || read.isEmpty()) {
                        //没有异常
                        break;
                    }

                    //解析消息
                    MapRecord<String, Object, Object> record = read.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = new VoucherOrder();
                    BeanUtil.fillBeanWithMap(values, voucherOrder, true);
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

            private void handleVoucherOrder(VoucherOrder voucherOrder){
                Long userId = voucherOrder.getUserId();
                //创建锁对象
                RLock lock = redissonClient.getLock("lock:order:" + userId);
                //只是保证线程安全,防止线程重复创建订单
                boolean isLock = lock.tryLock();//默认失败不等待
                if (!isLock) {
                    log.error("不允许重复下单");
                }
                try {

                    proxy.createVoucherOrder(voucherOrder);
                } finally {
                    lock.unlock();
                }
            }


            @Override

            public Result seckillVoucher (Long voucherId){
                SeckillVoucher byId = seckillVoucherService.getById(voucherId);
                Long userId = UserHolder.getUser().getId();
                Long orderId = redisIdWorker.nextId("order");
                if (byId.getBeginTime().isAfter(LocalDateTime.now())) {
                    return Result.fail("秒杀尚未开始");
                }
                if (byId.getEndTime().isBefore(LocalDateTime.now())) {
                    return Result.fail("秒杀已经结束");
                }
                Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                        voucherId.toString(), userId.toString(), orderId.toString());
                int r = result.intValue();
                if (r != 0) {
                    switch (r) {
                        case 1:
                            return Result.fail("库存不足");
                        case 2:
                            return Result.fail("不能重复下单");
                    }
                }
                //获取代理对象
                return Result.ok(orderId);
            }

//    @Override
//
//    public Result seckillVoucher(Long voucherId) {
//        SeckillVoucher byId = seckillVoucherService.getById(voucherId);
//        Long userId = UserHolder.getUser().getId();
//        if (byId.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        if (byId.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
//                voucherId.toString(), userId.toString());
//        int r = result.intValue();
//        if (r != 0){
//            switch (r) {
//                case 1:
//                    return Result.fail("库存不足");
//                case 2:
//                    return Result.fail("不能重复下单");
//            }
//        }
//        Long orderId = redisIdWorker.nextId("order");
//
//        // 有购买资格，保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        //@Transactional生效的前提，必须是spring创建的代理对象调用才行
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        orderTasks.add(voucherOrder);
//        //获取代理对象
//        return Result.ok(orderId);
//    }

    /*@Override

    public Result seckillVoucher(Long voucherId) {
        //查询优惠券信息
        SeckillVoucher byId = seckillVoucherService.getById(voucherId);
        if (byId.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        if (byId.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        if (byId.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long id=UserHolder.getUser().getId();
        //创建锁对象
        RLock lock =redissonClient.getLock("order:"+voucherId+":"+ id);
        //只是保证线程安全,防止线程重复创建订单
        boolean isLock = lock.tryLock();//默认失败不等待
        if (!isLock) {
            return Result.fail("用户已经购买过");
        }
        try {
            //@Transactional生效的前提，必须是spring创建的代理对象调用才行
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }*/
            @Transactional
            public void createVoucherOrder (VoucherOrder voucherOrder){
                Long voucherId = voucherOrder.getVoucherId();
                //乐观锁，防止下单超库存
                boolean update = seckillVoucherService.update().setSql("stock=stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
                save(voucherOrder);
            }
        }
