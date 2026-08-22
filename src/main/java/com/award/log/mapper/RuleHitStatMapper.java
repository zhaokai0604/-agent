package com.award.log.mapper;

import com.award.log.model.RuleHitStat;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 规则命中统计 Mapper，操作 rule_hit_stat 表（写入与读取最近窗口）。
 */
@Mapper
public interface RuleHitStatMapper {
    int insert(RuleHitStat stat);
    List<RuleHitStat> selectLatest(int limit);
}
