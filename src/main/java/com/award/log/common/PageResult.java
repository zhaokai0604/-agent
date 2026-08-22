package com.award.log.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 分页查询通用包装：当前页列表与总记录数。
 * 不含页码/页大小，由调用方自行传递查询参数。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageResult<T> {
    private List<T> list;    // 当前页数据
    private long total;      // 总记录数
}
