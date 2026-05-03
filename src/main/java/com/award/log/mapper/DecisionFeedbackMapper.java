package com.award.log.mapper;

import com.award.log.model.DecisionFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DecisionFeedbackMapper {
    int upsert(DecisionFeedback feedback);
    
    List<DecisionFeedback> selectUntrainedSamples(@Param("limit") int limit);
    
    int markAsTrained(@Param("ids") List<Long> ids);
    
    int countUntrained();
}
