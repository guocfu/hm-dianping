package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
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
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 实现优惠券下单业务
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }

        // 3.判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if(voucher.getStock()<=0){
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();
        // 实现单价状况下的锁（一人一单）
        // synchronized (userId.toString().intern()){
        //     /*
        //     由于toString的源码是new String，所以如果我们只用userId.toString()拿到的也不是同一个用户，
        //     需要使用intern()，如果字符串常量池中已经包含了一个等于这个string对象的字符串（由equals（object）方法确定），
        //     那么将返回池中的字符串, 否则，将此String对象添加到池中，并返回对此String对象的引用。
        //      */
        //     IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        //     return proxy.createVoucherOrder(voucherId);
        // }

        // 使用Redis锁解决集群并发安全问题
        SimpleRedisLock lock = new SimpleRedisLock("", stringRedisTemplate);
        boolean isLock = lock.tryLock(1200); // 一般设置成2s，为了方便调试设的长一点
        if(!isLock){
            return Result.fail("不允许重复下单！");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.ublock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单，不能用乐观锁（修改的情况），用悲观锁，适合判断是否存在
        // 5.1查询该用户是否有该优惠券订单
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.1判断订单是否存在
        if(count > 0){
            return Result.fail("用户已购买过一次！");
        }

        // 6.减去库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")  // 执行set sql语句
                .eq("voucher_id", voucherId)  // 设置更新条件
                // .eq("stock", voucher.getStock())
                .gt("stock", 0)  // 库存大于0条件，保证并发安全
                .update();  // 执行更新操作
        // 原子操作，保证并发安全
        if(!success){
            return Result.fail("库存不足");
        }
        // 7.创建并保存订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order"); // 生成全局唯一id
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
