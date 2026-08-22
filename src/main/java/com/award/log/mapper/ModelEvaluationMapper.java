package com.award.log.mapper;

import com.award.log.model.ModelEvaluation;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 模型评估 Mapper，操作 model_evaluation 表（写入与读取最近评估结果）。
 */
@Mapper
public interface ModelEvaluationMapper {
    int insert(ModelEvaluation evaluation);

    List<ModelEvaluation> selectLatest(int limit);
}
