package com.award.log.mapper;

import com.award.log.model.AlarmRuleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 告警规则 Mapper，操作 alarm_rule 表（增删改查与启停）。
 */
@Mapper
public interface AlarmRuleMapper {

    int insert(AlarmRuleEntity entity);

    int update(AlarmRuleEntity entity);

    int deleteById(@Param("id") Long id);

    AlarmRuleEntity selectById(@Param("id") Long id);

    List<AlarmRuleEntity> selectAll();

    List<AlarmRuleEntity> selectEnabled();

    int updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
}
