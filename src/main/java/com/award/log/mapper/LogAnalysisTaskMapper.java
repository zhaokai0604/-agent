package com.award.log.mapper;

import com.award.log.model.LogAnalysisTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface LogAnalysisTaskMapper {
    /** 根据ID查询特定任务 */
    LogAnalysisTask selectById(String taskId);

    /** 查询所有任务 (不分页) */
    List<LogAnalysisTask> selectAll();

    /** 分页查询任务列表 */
    List<LogAnalysisTask> selectPage(@Param("offset") int offset, @Param("limit") int limit);
    List<LogAnalysisTask> selectPageWithFilter(@Param("offset") int offset,
                                               @Param("limit") int limit,
                                               @Param("fileName") String fileName,
                                               @Param("status") String status,
                                               @Param("startTime") String startTime,
                                               @Param("endTime") String endTime);

    /** 查询任务总数 */
    long countAll();
    long countWithFilter(@Param("fileName") String fileName,
                         @Param("status") String status,
                         @Param("startTime") String startTime,
                         @Param("endTime") String endTime);

    /** 插入任务记录 */
    int insert(LogAnalysisTask record);
    
    /** 更新任务记录 */
    int updateById(LogAnalysisTask task);
    
    /** 获取最近几天的任务趋势 */
    List<Map<String, Object>> getTaskTrend(@Param("days") int days);
    
    /** 获取各状态任务数量 */
    List<Map<String, Object>> getTaskStatusCount();
    
    /** 获取任务完成情况统计 */
    List<Map<String, Object>> getTaskCompletionStatistics();
    
    /** 获取平均处理时间 */
    Double getAverageProcessingTime();
    
    /** 根据任务ID删除任务 */
    int deleteById(String taskId);
}
