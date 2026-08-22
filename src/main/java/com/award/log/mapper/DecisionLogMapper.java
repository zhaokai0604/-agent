package com.award.log.mapper;

import com.award.log.model.DecisionLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 决策日志 Mapper，操作 decision_log 表（写入、按决策 ID/条件查询及离线对照样本）。
 */
@Mapper
public interface DecisionLogMapper {
    int insert(DecisionLog log);

    DecisionLog selectByDecisionId(String decisionId);

    List<DecisionLog> selectByFilter(@Param("engineType") String engineType,
                                     @Param("startTime") String startTime,
                                     @Param("endTime") String endTime,
                                     @Param("result") Integer result,
                                     @Param("offset") int offset,
                                     @Param("pageSize") int pageSize);

    List<Map<String, Object>> selectOfflinePairs(@Param("startTime") String startTime,
                                                 @Param("endTime") String endTime);
}
