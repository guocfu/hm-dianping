package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

/**
 * 封装缓存工具类
 */
@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将任意对象序列化为json存储在redis中并设置TTL过期时间
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    /**
     * 将任意对象存储在redis中并设置逻辑过期时间
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback 传入的查询数据库函数
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
            Long time, TimeUnit unit
    ){
        String key = keyPrefix + "id";
        // 1.从redis查询商品缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }
        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);
        return r;
    }
    /**
     * 加锁
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
    // 缓存池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 使用逻辑过期时间解决缓存击穿问题
     * @param id
     * @return
     */
    public <R,ID> R querywithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
            Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis中查询商品缓存
        String redis = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isBlank(redis)){
            // 3.不存在，直接返回空
            return null;  // 默认热点数据全部在缓存中
        }
        // 4.命中，现将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(redis, RedisData.class);
        R shop = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();  // 过期时间
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
        R shop2 = JSONUtil.toBean((JSONObject) redisData2.getData(), type);
        LocalDateTime expireTime2 = redisData2.getExpireTime();  // 过期时间
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
                R newR = dbFallback.apply(id);
                this.setWithLogicalExpire(key,newR,time,unit);
            }catch (Exception e){
                throw new RuntimeException(e);
            }finally {
                unlock(lockKey);
            }
        });
        return shop;
    }

}
