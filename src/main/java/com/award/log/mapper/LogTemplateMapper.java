package com.award.log.mapper;

import com.award.log.model.LogTemplateRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Drain 日志模板 Mapper，操作 log_template 表。
 */
@Mapper
public interface LogTemplateMapper {
    int insert(LogTemplateRecord record);

    int update(LogTemplateRecord record);

    int deleteByTemplateId(String templateId);

    LogTemplateRecord selectByTemplateId(String templateId);

    List<LogTemplateRecord> selectPage(@Param("offset") int offset, @Param("pageSize") int pageSize);
}
