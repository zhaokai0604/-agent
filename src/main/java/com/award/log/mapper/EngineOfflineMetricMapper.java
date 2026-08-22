package com.award.log.mapper;

import com.award.log.model.EngineOfflineMetric;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 引擎离线指标 Mapper，操作 engine_offline_metric 表（写入与读取最近汇总）。
 */
@Mapper
public interface EngineOfflineMetricMapper {
    int insert(EngineOfflineMetric metric);
    List<EngineOfflineMetric> selectLatest(int limit);
}
