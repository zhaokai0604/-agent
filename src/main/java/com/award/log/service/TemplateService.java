package com.award.log.service;

import com.award.log.model.LogTemplateRecord;

import java.util.List;
import java.util.Map;

/**
 * Drain 日志模板 CRUD 与定期刷新。
 */
public interface TemplateService {
    List<LogTemplateRecord> page(int pageNum, int pageSize);

    LogTemplateRecord get(String id);

    boolean update(String id, LogTemplateRecord record);

    boolean delete(String id);

    boolean merge(String id, String targetId);

    Map<String, Object> detectTemplateChange();
}
