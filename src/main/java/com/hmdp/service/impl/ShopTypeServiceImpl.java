package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

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
    StringRedisTemplate stringRedisTemplate;

    /**
     * 实现店铺类型查询缓存
     * @return
     */
    @Override
    public Result queryTypeList() {
        // 思路一样，但是自己写的会出错？？？
        // 先从Redis中查，这里的常量值是固定前缀 + 店铺id
        List<String> shopTypes =
                stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOPTYPE_KEY, 0, -1);
        // 如果不为空（查询到了），则转为ShopType类型直接返回
        if (!shopTypes.isEmpty()) {
            List<ShopType> tmp = shopTypes.stream().map(type -> JSONUtil.toBean(type, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(tmp);
        }
        // 否则去数据库中查
        List<ShopType> tmp = query().orderByAsc("sort").list();
        if (tmp == null){
            return Result.fail("店铺类型不存在！！");
        }
        // 查到了转为json字符串，存入redis
        shopTypes = tmp.stream().map(type -> JSONUtil.toJsonStr(type))
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().leftPushAll(RedisConstants.CACHE_SHOPTYPE_KEY,shopTypes);
        // 最终把查询到的商户分类信息返回给前端
        return Result.ok(tmp);
    }
}
