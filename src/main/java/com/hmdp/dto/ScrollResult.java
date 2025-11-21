package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;  // 上次查询的博客的最小时间戳
    private Integer offset;
}
