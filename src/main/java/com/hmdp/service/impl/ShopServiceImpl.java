package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.javassist.compiler.ast.Variable;
import org.apache.tomcat.jni.Time;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate  stringRedisTemplate;

    // 缓存池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 查询店铺数据
     * 使用空对象缓存解决缓存穿透问题
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {

        // Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        Shop shop = querywithLogicalExpire(id);

        if(shop == null){
            return Result.fail("店铺不存在！");
        }
        // log.info("查询的店铺名为：{}",shop.getName());
        return Result.ok(shop);
    }

    /**
     * 封装使用id查询商铺信息，使用缓存空对象解决缓存穿透问题
     * @param id
     * @return
     */
    private Shop queryWithPassThrough(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否存在
        if(StrUtil.isNotBlank(shopJson)){
            // 3.缓存存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断命中的是否是空字符串
        if(shopJson != null){
            // 如果是空字符串，说明数据库中也是没有数据的
            return null;
        }
        // 4.不存在，根据id查询数据库
        Shop shop = getById(id);
        // 5.数据库不存在商铺信息，将空字符串写入redis中，返回错误
        if(shop==null){
            stringRedisTemplate.opsForValue().set(key, "",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，写入redis, 设置TTL
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7.返回商铺信息
        return shop;        // 缓存穿
    }

    private Shop queryWithMutex(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断缓存是否存在
        if(StrUtil.isNotBlank(shopJson)){
            // 3.缓存存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断命中的是否是空字符串
        if(shopJson != null){
            // 如果是空字符串，说明数据库中也是没有数据的
            return null;
        }
        String lockKey = null;
        Shop shop = null;
        try {
            // 4.实现缓存重建
            // 4.1获取互斥锁
            lockKey = "lock:shop:"+id;
            boolean isLock = tryLock(lockKey);
            // 4.2判断是否获取成功
            if(!isLock){
                // 4.3失败，则休眠并重试
                Thread.sleep(50);
                return queryWithPassThrough(id);
            }


            // 4.4成功，根据id查询数据库
            shop = getById(id);
            // 模拟重建的延时
            Thread.sleep(20);
            // 5.不存在，返回错误
            if(shop==null){
                stringRedisTemplate.opsForValue().set(key, "",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6.存在，写入redis, 设置TTL
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.不管前面是否有异常，最终都会释放互斥锁
            unlock(lockKey);
        }
        // 8.返回
        return shop;
    }
    /**
     * 使用互斥锁解决缓存击穿问题，加锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private boolean unlock(String key){
        return stringRedisTemplate.delete(key);
    }

    /**
     * 使用逻辑过期时间解决缓存击穿问题
     * @param id
     * @return
     */
    public Shop querywithLogicalExpire(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从redis中查询商品缓存
        String redis = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isBlank(redis)){
            // 3.不存在，直接返回空
            return null;  // 默认热点数据全部在缓存中
        }
        // 4.命中，现将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(redis, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getDateTime();  // 过期时间
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            // 5.1若未过期，直接返回店铺信息
            return shop;
        }
        // 5.2若已过期，需要缓存重建
        // 6.缓存重建
        // 6.1获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
        boolean flag = tryLock(lockKey);
        // 6.2判断是否获取锁成功
        if(!flag){
            // 6.3如果获取失败，直接返回旧数据
            return shop;
        }

        // 6.4如果成功，需要再次检查Redis缓存是否过期（二重验证，doubleCheck）
        String redis2 = stringRedisTemplate.opsForValue().get(key);
        RedisData redisData2 = JSONUtil.toBean(redis2, RedisData.class);
        Shop shop2 = JSONUtil.toBean((JSONObject) redisData2.getData(), Shop.class);
        LocalDateTime expireTime2 = redisData2.getDateTime();  // 过期时间
        if(expireTime2.isAfter(LocalDateTime.now())){
            // 6.5若缓存未过期，说明在第一次获取缓存到获取锁之间已经有线程重建缓存，不需要再重建缓存
            // 解锁，并返回新的店铺信息
            unlock(lockKey);
            return shop2;
        }

        // 6.6若缓存还是未命中，新开线程重建缓存并释放锁，原线程仍然返回旧数据
        CACHE_REBUILD_EXECUTOR.submit( ()->{
           try{
                // 重建缓存
               this.saveShop2Redis(id, 20L);  // 过期时间应射程30分钟，为了测试设成20s
           }catch (Exception e){
               throw new RuntimeException(e);
           }finally {
                unlock(lockKey);
           }
        });
        return shop;
    }

    @Override
    public Result update(Shop shop) {
        if(shop.getId()==null){
            return Result.fail("店铺id不能为空！！");
        }
        // 先修改数据库
        updateById(shop);
        // 再删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    public void saveShop2Redis(Long id, Long expireSeconds){
        // 1.查询店铺数据
        Shop shop = getById(id);

        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setDateTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(redisData));
    }
}
