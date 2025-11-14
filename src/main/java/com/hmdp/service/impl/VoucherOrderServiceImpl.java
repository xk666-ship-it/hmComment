package com.hmdp.service.impl;

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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Override

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
    }
    @Transactional
    public  Result createVoucherOrder(Long voucherId) {
        //一人一单
        Long id=UserHolder.getUser().getId();
        //这个锁用来一个用户一单
            int count = query().eq("user_id", id).eq("voucher_id", voucherId).count();
            //用户已经购买过
            if (count > 0) {
                return Result.fail("用户已经购买过");
            }
            //乐观锁，防止下单超库存
            boolean update = seckillVoucherService.update().setSql("stock=stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
            if (!update) {
                return Result.fail("库存不足");
            }
            VoucherOrder voucherOrder = new VoucherOrder();
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            long userId = UserHolder.getUser().getId();
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            return Result.ok(orderId);

    }
}
