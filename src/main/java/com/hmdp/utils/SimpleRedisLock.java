package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";

    /**
     * 利用setnx方法进行加锁，同时增加过期时间，防止死锁
     * @param timeoutSec
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取value（当前线程ID）
        long id = Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name, id+"", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);  // 避免自动拆箱出现空指针的风险
    }

    /**
     * delete方法释放锁
     */
    @Override
    public void ublock() {
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
