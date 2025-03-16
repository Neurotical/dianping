package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result queryWithRedis() {
        //查询redis中是否有shopType数据,  redis中使用List存储
        if (redisTemplate.hasKey("cache:shopType")) {
            List<String> shopTypeCache = redisTemplate.opsForList().range("cache:shopType", 0, -1);
            //存在则返回
            if (shopTypeCache != null) {
                List<ShopType> shopTypeList = new ArrayList<>();
                for (String s : shopTypeCache) {
                    ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                    shopTypeList.add(shopType);
                }
                return Result.ok(shopTypeList);
            }
        }
        //不存在时，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        //若无分类则报错
        if (typeList == null) {
            return Result.fail("分类为空");
        }

        //有分类则先清空，再向redis的list中添加
        redisTemplate.delete("cache:shopType");
        for (ShopType shopType : typeList) {
            redisTemplate.opsForList().rightPush("cache:shopType", JSONUtil.toJsonStr(shopType));
        }

        return Result.ok(typeList);
    }
}
