package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 缓存工具类
 */
@Component
@Slf4j
public class CacheClient {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time, timeUnit);
    }

    /**
     * 写入redis并设置逻辑过期时间
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        //封装RedisData
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        // 从redis查询缓冲
        String cache = stringRedisTemplate.opsForValue().get(key);
        //判断缓冲是否命中
        if (StrUtil.isNotBlank(cache)){
            //存在则返回
            return JSONUtil.toBean(cache,type);
        }
        //判断命中的是否是空值
        if (cache != null){
            return null;

        }

        //不存在则查询数据库
        R r = dbFallback.apply(id);

        //不存在则返回错误
        if (r == null){
            //将空值写入redis 解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }

        //存在则在redis中缓存,设置超时时间
        this.set(key,r,time,timeUnit);
        //返回
        return r;
    }

    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        // 从redis查询缓冲
        String cache = stringRedisTemplate.opsForValue().get(key);
        //判断缓冲是否命中
        if (StrUtil.isBlank(cache)){
            //不存在则返回
            return null;
        }
        //命中，则先将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(cache, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回店铺信息
            return r;
        }

        //已过期，需要缓存重建

        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
        //获取互斥锁
        boolean isLock = tryLock(lockKey);
        //判断是否获取成功
        if (isLock){
            //成功则开启线程执行实现缓存重建

            //再次判断是否过期
            cache = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(cache, RedisData.class);
            LocalDateTime expireTime2 = redisData.getExpireTime();
            if (expireTime2.isAfter(LocalDateTime.now())) {
                return JSONUtil.toBean((JSONObject) redisData.getData(),type);
            }

            //创建新线程更新shop到redis
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r1 = dbFallback.apply(id);

                    this.setWithLogicalExpire(key,r1,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        //返回商铺信息
        return r;
    }

    private boolean tryLock(String key){
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
