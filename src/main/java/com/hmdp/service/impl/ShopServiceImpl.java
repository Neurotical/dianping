package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    
    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        Shop shop = cacheClient.queryWithPassThrough(
                RedisConstants.CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
//        Shop shop = cacheClient
//                .queryWithLogicalExpire(
//                        RedisConstants.CACHE_SHOP_KEY,
//                        id,
//                        Shop.class
//                        ,this::getById,
//                        RedisConstants.CACHE_SHOP_TTL,
//                        TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //返回
        return Result.ok(shop);
    }

//    public Shop queryWithLogicalExpire(Long id){
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        // 从redis查询缓冲
//        String cache = stringRedisTemplate.opsForValue().get(key);
//        //判断缓冲是否命中
//        if (StrUtil.isBlank(cache)){
//            //不存在则返回
//            return null;
//        }
//        //命中，则先将json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(cache, RedisData.class);
//        Shop shop = (Shop) redisData.getData();
//        LocalDateTime expireTime = redisData.getExpireTime();
//
//        //判断是否过期
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            //未过期，直接返回店铺信息
//            return shop;
//        }
//
//        //已过期，需要缓存重建
//
//        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
//        //获取互斥锁
//        boolean isLock = tryLock(lockKey);
//        //判断是否获取成功
//        if (isLock){
//            //成功则开启线程执行实现缓存重建
//
//            //再次判断是否过期
//            cache = stringRedisTemplate.opsForValue().get(key);
//            redisData = JSONUtil.toBean(cache, RedisData.class);
//            LocalDateTime expireTime2 = redisData.getExpireTime();
//            if (expireTime2.isAfter(LocalDateTime.now())) {
//                return (Shop) redisData.getData();
//            }
//
//            //创建新线程更新shop到redis
//            CACHE_REBUILD_EXECUTOR.submit(()->{
//                try {
//                    this.saveShop2Redis(id,30L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally {
//                    //释放锁
//                    unlock(lockKey);
//                }
//            });
//        }
//
//        //返回商铺信息
//        return shop;
//    }

    /*public Shop queryWithMutex(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 从redis查询缓冲
        String cache = stringRedisTemplate.opsForValue().get(key);
        //判断缓冲是否命中
        if (StrUtil.isNotBlank(cache)){
            //存在则返回
            Shop shop = JSONUtil.toBean(cache,Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (cache != null){
            return null;

        }

        //实现缓存重建
        //获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);

            //失败时休眠并重试
            if (!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //成功，根据id查询数据库
            shop = getById(id);

            //不存在则返回错误
            if (shop == null){
                //将空值写入redis 解决缓存穿透问题
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id," ",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }

            //存在则在redis中缓存,设置超时时间
            String shopStr = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,shopStr,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
            //释放互斥锁
            unlock(lockKey);
        }

        //返回
        return shop;
    }

    public Shop queryWithPassThrough(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 从redis查询缓冲
        String cache = stringRedisTemplate.opsForValue().get(key);
        //判断缓冲是否命中
        if (StrUtil.isNotBlank(cache)){
            //存在则返回
            Shop shop = JSONUtil.toBean(cache,Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (cache != null){
            return null;

        }

        //不存在则查询数据库
        Shop shop = getById(id);

        //不存在则返回错误
        if (shop == null){
            //将空值写入redis 解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id," ",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }

        //存在则在redis中缓存,设置超时时间
        String shopStr = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,shopStr,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return shop;
    }*/

    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }

        //更新数据库
        updateById(shop);

        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);

        return Result.ok();
    }

/*    private boolean tryLock(String key){
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }*/

//    private void saveShop2Redis(Long id,Long expireMinutes){
//        //从数据库中查询shop
//        Shop shop = getById(id);
//
//        //封装逻辑过期时间
//        RedisData rd = new RedisData();
//        rd.setData(shop);
//        rd.setExpireTime(LocalDateTime.now().plusMinutes(expireMinutes));
//        //写入redis
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(rd));
//    }
}
